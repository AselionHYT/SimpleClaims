package com.buuz135.simpleclaims.claim;

import com.buuz135.simpleclaims.Main;
import com.buuz135.simpleclaims.claim.party.PartyInvite;
import com.buuz135.simpleclaims.claim.party.PartyOverride;
import com.buuz135.simpleclaims.claim.party.PartyOverrides;
import com.buuz135.simpleclaims.commands.CommandMessages;
import com.buuz135.simpleclaims.files.*;
import com.buuz135.simpleclaims.util.FileUtils;
import com.buuz135.simpleclaims.claim.chunk.ChunkInfo;
import com.buuz135.simpleclaims.claim.chunk.ReservedChunk;
import com.buuz135.simpleclaims.claim.party.PartyInfo;
import com.buuz135.simpleclaims.claim.player_name.PlayerNameTracker;
import com.buuz135.simpleclaims.claim.tracking.ModifiedTracking;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import javax.annotation.Nullable;
import java.awt.*;
import java.io.File;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.logging.Level;

public class ClaimManager {

    private static final ClaimManager INSTANCE = new ClaimManager();

    private final Map<UUID, UUID> adminUsageParty;
    private final Map<UUID, PartyInvite> partyInvites;
    private final Map<UUID, UUID> playerToParty;
    private final Map<UUID, Integer> partyClaimCounts;
    private Set<String> worldsNeedingUpdates;
    private HytaleLogger logger = HytaleLogger.getLogger().getSubLogger("SimpleClaims");
    private PlayerNameTracker playerNameTracker;
    private HashMap<String, PartyInfo> parties;
    // Read from every world thread without a lock. Copy-on-write: the outer map is never
    // modified after it is published, a new dimension swaps in a new map (dimensionChunks).
    // A plain HashMap that world threads insert into concurrently loses entries during a
    // resize, which silently dropped every claim of a world from memory.
    private volatile HashMap<String, HashMap<String, ChunkInfo>> chunks;
    private HashMap<String, HashMap<String, ReservedChunk>> reservedChunks;
    private Set<UUID> adminOverrides;
    private DatabaseManager databaseManager;
    private HashMap<String, LongSet> mapUpdateQueue;
    private ExecutorService executorService;

    public static ClaimManager getInstance() {
        return INSTANCE;
    }

    private ClaimManager() {
        this.adminUsageParty = new ConcurrentHashMap<>();
        this.worldsNeedingUpdates = new HashSet<>();
        this.partyInvites = new ConcurrentHashMap<>();
        this.playerToParty = new ConcurrentHashMap<>();
        this.partyClaimCounts = new ConcurrentHashMap<>();
        this.parties = new HashMap<>();
        this.chunks = new HashMap<>();
        this.reservedChunks = new HashMap<>();
        this.playerNameTracker = new PlayerNameTracker();
        this.adminOverrides = new HashSet<>();
        this.databaseManager = new DatabaseManager(logger);
        this.mapUpdateQueue = new HashMap<>();
        this.executorService = Executors.newFixedThreadPool(4);

        FileUtils.ensureMainDirectory();

        logger.at(Level.INFO).log("Loading simple claims data...");

        if (this.databaseManager.isMigrationNecessary()) {
            logger.at(Level.INFO).log("Migration needed, loading JSON files...");
            PartyBlockingFile partyBlockingFile = new PartyBlockingFile();
            ClaimedChunkBlockingFile claimedChunkBlockingFile = new ClaimedChunkBlockingFile();
            PlayerNameTrackerBlockingFile playerNameTrackerBlockingFile = new PlayerNameTrackerBlockingFile();
            AdminOverridesBlockingFile adminOverridesBlockingFile = new AdminOverridesBlockingFile();

            if (new File(FileUtils.PARTY_PATH).exists()) {
                FileUtils.loadWithBackup(partyBlockingFile::syncLoad, FileUtils.PARTY_PATH, logger);
            }
            if (new File(FileUtils.CLAIM_PATH).exists()) {
                FileUtils.loadWithBackup(claimedChunkBlockingFile::syncLoad, FileUtils.CLAIM_PATH, logger);
            }
            if (new File(FileUtils.NAMES_CACHE_PATH).exists()) {
                FileUtils.loadWithBackup(playerNameTrackerBlockingFile::syncLoad, FileUtils.NAMES_CACHE_PATH, logger);
            }
            if (new File(FileUtils.ADMIN_OVERRIDES_PATH).exists()) {
                FileUtils.loadWithBackup(adminOverridesBlockingFile::syncLoad, FileUtils.ADMIN_OVERRIDES_PATH, logger);
            }

            this.databaseManager.migrate(partyBlockingFile, claimedChunkBlockingFile, playerNameTrackerBlockingFile, adminOverridesBlockingFile);
        }

        logger.at(Level.INFO).log("Loading party data from DB...");
        this.parties.putAll(this.databaseManager.loadParties());
        for (PartyInfo party : this.parties.values()) {
            for (UUID member : party.getMembers()) {
                playerToParty.put(member, party.getId());
            }
        }

        logger.at(Level.INFO).log("Loading chunk data from DB...");
        this.chunks.putAll(this.databaseManager.loadClaims());
        for (HashMap<String, ChunkInfo> dimensionChunks : this.chunks.values()) {
            for (ChunkInfo chunk : dimensionChunks.values()) {
                partyClaimCounts.merge(chunk.getPartyOwner(), 1, Integer::sum);
            }
        }

        logger.at(Level.INFO).log("Loading name cache data from DB...");
        PlayerNameTracker tracker = this.databaseManager.loadNameCache();
        for (PlayerNameTracker.PlayerName name : tracker.getNames()) {
            this.playerNameTracker.setPlayerName(name.getUuid(), name.getName(), name.getLastSeen(), name.getPlayTime());
        }

        logger.at(Level.INFO).log("Loading admin overrides data from DB...");
        this.adminOverrides.addAll(this.databaseManager.loadAdminOverrides());

        logger.at(Level.INFO).log("Loading reserved chunks data from DB...");
        this.reservedChunks.putAll(this.databaseManager.loadReservedChunks());
      
        migrateOldClaimOverrides();
    }

    public void saveParty(PartyInfo partyInfo) {
        this.runAsync(() -> this.databaseManager.saveParty(partyInfo));
    }

    private void saveClaim(String dimension, ChunkInfo chunkInfo) {
        this.runAsync(() -> this.databaseManager.saveClaim(dimension, chunkInfo));
    }

    private void saveNameCache(UUID uuid, String name, long lastSeen, float playTime) {
        this.runAsync(() -> this.databaseManager.saveNameCache(uuid, name, lastSeen, playTime));
    }

    private void saveAdminOverride(UUID uuid) {
        this.runAsync(() -> this.databaseManager.saveAdminOverride(uuid));
    }

    public void addParty(PartyInfo partyInfo){
        this.parties.put(partyInfo.getId().toString(), partyInfo);
        this.saveParty(partyInfo);
    }

    public boolean isAllowedToInteract(UUID playerUUID, String dimension, int chunkX, int chunkZ, Predicate<PartyInfo> interactMethod, String permission) {
        if (playerUUID != null && adminOverrides.contains(playerUUID)) return true;

        var chunkInfo = getChunkRawCoords(dimension, chunkX, chunkZ);
        if (chunkInfo == null) return !Arrays.asList(Main.CONFIG.get().getFullWorldProtection()).contains(dimension);

        var chunkParty = getPartyById(chunkInfo.getPartyOwner());
        if (chunkParty == null) return true;
        
        // If playerUUID is null (e.g., explosion with unknown source), deny in claimed chunks
        if (playerUUID == null) return false;

        if (chunkParty.isOwnerOrMember(playerUUID) || chunkParty.isPlayerAllied(playerUUID)) {
            if (chunkParty.hasPermission(playerUUID, permission)) return true;
            return false;
        }

        var partyId = playerToParty.get(playerUUID);
        if (partyId != null && chunkParty.isPartyAllied(partyId)) {
            if (chunkParty.hasPartyPermission(partyId, permission)) return true;
            return false;
        }

        //Interact check for players that arent party members or allies
        return interactMethod.test(chunkParty);
    }

    @Nullable
    public PartyInfo getPartyFromPlayer(UUID player) {
        UUID partyId = playerToParty.get(player);
        if (partyId == null) return null;
        return getPartyById(partyId);
    }

    @Nullable
    public PartyInfo getPartyById(UUID partyId){
        return this.parties.get(partyId.toString());
    }

    public PartyInfo createParty(PlayerRef playerRef, boolean isAdmin) {
        var party = new PartyInfo(UUID.randomUUID(), playerRef.getUuid(), playerRef.getUsername() + "'s Party", playerRef.getUsername() + "'s Party Description", new UUID[0], Color.getHSBColor(new Random().nextFloat(), 1, 1).getRGB());
        party.addMember(playerRef.getUuid());
        party.setCreatedTracked(new ModifiedTracking(playerRef.getUuid(), playerRef.getUsername(), LocalDateTime.now().toString()));
        party.setModifiedTracked(new ModifiedTracking(playerRef.getUuid(), playerRef.getUsername(), LocalDateTime.now().toString()));
        this.parties.put(party.getId().toString(), party);
        if (!isAdmin) this.playerToParty.put(playerRef.getUuid(), party.getId());
        this.saveParty(party);
        return party;
    }

    public boolean canClaimInDimension(World world){
        if (world.getWorldConfig().isDeleteOnRemove()) return false;
        if (world.getName().contains("Gaia_Temple")) return false;
        if (Arrays.asList(Main.CONFIG.get().getWorldNameBlacklistForClaiming()).contains(world.getName())) return false;
        return true;
    }

    @Nullable
    public ChunkInfo getChunk(String dimension, int chunkX, int chunkZ){
        var chunkInfo = this.chunks.get(dimension);
        if (chunkInfo == null) return null;
        return chunkInfo.get(ChunkInfo.formatCoordinates(chunkX, chunkZ));
    }

    private synchronized HashMap<String, ChunkInfo> dimensionChunks(String dimension) {
        var existing = this.chunks.get(dimension);
        if (existing != null) return existing;
        var created = new HashMap<String, ChunkInfo>();
        var copy = new HashMap<>(this.chunks);
        copy.put(dimension, created);
        this.chunks = copy;
        return created;
    }

    @Nullable
    public ChunkInfo getChunkRawCoords(String dimension, int blockX, int blockZ){
        return this.getChunk(dimension, ChunkUtil.chunkCoordinate(blockX), ChunkUtil.chunkCoordinate(blockZ));
    }

    public ChunkInfo claimChunkBy(String dimension, int chunkX, int chunkZ, PartyInfo partyInfo, Player owner, PlayerRef playerRef) {
        var chunkInfo = new ChunkInfo(partyInfo.getId(), chunkX, chunkZ);
        var chunkDimension = this.dimensionChunks(dimension);
        chunkDimension.put(ChunkInfo.formatCoordinates(chunkX, chunkZ), chunkInfo);
        chunkInfo.setCreatedTracked(new ModifiedTracking(playerRef.getUuid(), owner.getPlayerRef().getUsername(), LocalDateTime.now().toString()));
        partyClaimCounts.merge(partyInfo.getId(), 1, Integer::sum);
        
        // Remove this chunk from reserved chunks if it was reserved by this party
        if (Main.CONFIG.get().isEnablePerimeterReservation()) {
            var reservedDimension = this.reservedChunks.get(dimension);
            if (reservedDimension != null) {
                ReservedChunk reserved = reservedDimension.get(ReservedChunk.formatCoordinates(chunkX, chunkZ));
                if (reserved != null && reserved.getReservedBy().equals(partyInfo.getId())) {
                    reservedDimension.remove(ReservedChunk.formatCoordinates(chunkX, chunkZ));
                    this.runAsync(() -> databaseManager.deleteReservedChunk(dimension, chunkX, chunkZ));
                }
            }
        }
        
        this.runAsync(() -> databaseManager.saveClaim(dimension, chunkInfo));
        
        // Calculate and reserve perimeter chunks if enabled
        if (Main.CONFIG.get().isEnablePerimeterReservation()) {
            updateReservedPerimeter(dimension, partyInfo.getId());
        }
        
        return chunkInfo;
    }

    public ChunkInfo claimChunkByRawCoords(String dimension, int blockX, int blockZ, PartyInfo partyInfo, Player owner, PlayerRef playerRef) {
        return this.claimChunkBy(dimension, ChunkUtil.chunkCoordinate(blockX), ChunkUtil.chunkCoordinate(blockZ), partyInfo, owner, playerRef);
    }

    public boolean hasEnoughClaimsLeft(PartyInfo partyInfo) {
        int maxAmount = partyInfo.getMaxClaimAmount();
        int currentAmount = partyClaimCounts.getOrDefault(partyInfo.getId(), 0);
        return currentAmount < maxAmount;
    }

    public int getAmountOfClaims(PartyInfo partyInfo) {
        return partyClaimCounts.getOrDefault(partyInfo.getId(), 0);
    }

    public void unclaim(String dimension, int chunkX, int chunkZ) {
        var chunkMap = this.chunks.get(dimension);
        if (chunkMap != null) {
            ChunkInfo removed = chunkMap.remove(ChunkInfo.formatCoordinates(chunkX, chunkZ));
            if (removed != null) {
                UUID partyId = removed.getPartyOwner();
                partyClaimCounts.computeIfPresent(partyId, (k, v) -> v > 1 ? v - 1 : null);
                this.runAsync(() -> databaseManager.deleteClaim(dimension, chunkX, chunkZ));
                
                // Recalculate reserved perimeter after unclaiming if enabled
                if (Main.CONFIG.get().isEnablePerimeterReservation()) {
                    updateReservedPerimeter(dimension, partyId);
                }
            }
        }
    }

    public void unclaimRawCoords(String dimension, int blockX, int blockZ){
        this.unclaim(dimension, ChunkUtil.chunkCoordinate(blockX), ChunkUtil.chunkCoordinate(blockZ));
    }

    public Set<String> getWorldsNeedingUpdates() {
        return worldsNeedingUpdates;
    }

    public void setNeedsMapUpdate(String world) {
        this.worldsNeedingUpdates.add(world);
    }

    public void setPlayerName(UUID uuid, String name, long lastSeen) {
        var existing = this.playerNameTracker.getNamesMap().get(uuid);
        float playTime = existing != null ? existing.getPlayTime() : 0;
        this.playerNameTracker.setPlayerName(uuid, name, lastSeen, playTime);
        this.saveNameCache(uuid, name, lastSeen, playTime);
    }

    public void setPlayerPlayTime(UUID uuid, float playTime) {
        var existing = this.playerNameTracker.getNamesMap().get(uuid);
        if (existing != null) {
            if (Math.abs(existing.getPlayTime() - playTime) < 0.01) return;
            this.playerNameTracker.setPlayerName(uuid, existing.getName(), existing.getLastSeen(), playTime);
            this.saveNameCache(uuid, existing.getName(), existing.getLastSeen(), playTime);
        }
    }

    public PlayerNameTracker getPlayerNameTracker() {
        return playerNameTracker;
    }

    public HashMap<String, PartyInfo> getParties() {
        return parties;
    }

    public HashMap<String, HashMap<String, ChunkInfo>> getChunks() {
        return this.chunks;
    }

    public Map<UUID, UUID> getAdminUsageParty() {
        return adminUsageParty;
    }

    public void invitePlayerToParty(PlayerRef recipient, PartyInfo partyInfo, PlayerRef sender) {
        this.partyInvites.put(recipient.getUuid(), new PartyInvite(recipient.getUuid(), sender.getUuid(), partyInfo.getId()));
    }

    public PartyInvite acceptInvite(PlayerRef player) {
        var invite = this.partyInvites.get(player.getUuid());
        if (invite == null) return null;
        var party = this.getPartyById(invite.party());
        if (party == null) return null;
        if (Main.CONFIG.get().getMaxPartyMembers() != -1 && party.getMembers().length >= Main.CONFIG.get().getMaxPartyMembers()) {
            this.partyInvites.remove(player.getUuid());
            return null;
        }
        party.addMember(player.getUuid());
        this.playerToParty.put(player.getUuid(), party.getId());
        this.partyInvites.remove(player.getUuid());
        this.saveParty(party);
        return invite;
    }

    public Map<UUID, PartyInvite> getPartyInvites() {
        return partyInvites;
    }

    public void leaveParty(PlayerRef player, PartyInfo partyInfo) {
        this.playerToParty.remove(player.getUuid());

        if (partyInfo.isOwner(player.getUuid())) {
            disbandParty(partyInfo);
            player.sendMessage(CommandMessages.PARTY_DISBANDED);
            return;
        } else {
            partyInfo.removeMember(player.getUuid());
            playerToParty.remove(player.getUuid());
            player.sendMessage(CommandMessages.PARTY_LEFT);
        }
        this.saveParty(partyInfo);
    }

    public void disbandParty(PartyInfo partyInfo) {
        for (UUID member : partyInfo.getMembers()) {
            playerToParty.remove(member);
        }
        queueMapUpdateForParty(partyInfo);
        this.chunks.forEach((dimension, chunkInfos) -> chunkInfos.values().removeIf(chunkInfo -> {
            boolean matches = chunkInfo.getPartyOwner().equals(partyInfo.getId());
            if (matches) {
                this.runAsync(() -> databaseManager.deleteClaim(dimension, chunkInfo.getChunkX(), chunkInfo.getChunkZ()));
            }
            return matches;
        }));
        
        // Remove all reserved chunks for this party
        this.reservedChunks.forEach((dimension, reservedMap) -> {
            reservedMap.values().removeIf(reserved -> reserved.getReservedBy().equals(partyInfo.getId()));
        });
        this.runAsync(() -> {
            this.chunks.forEach((dimension, chunkInfos) -> {
                databaseManager.deleteReservedChunksByParty(dimension, partyInfo.getId());
            });
        });
        
        partyClaimCounts.remove(partyInfo.getId());

        this.parties.remove(partyInfo.getId().toString());
        this.runAsync(() -> databaseManager.deleteParty(partyInfo.getId()));
    }

    public void removeAdminOverride(UUID uuid) {
        if (this.adminOverrides.remove(uuid)) {
            this.runAsync(() -> databaseManager.deleteAdminOverride(uuid));
        }
    }

    public void addAdminOverride(UUID uuid) {
        if (this.adminOverrides.add(uuid)) {
            this.saveAdminOverride(uuid);
        }
    }

    public Set<UUID> getAdminClaimOverrides() {
        return adminOverrides;
    }

    public void queueMapUpdateForParty(PartyInfo partyInfo) {
        this.getChunks().forEach((dimension, chunkInfos) -> {
            var world = Universe.get().getWorlds().get(dimension);
            if (world != null) {
                for (ChunkInfo value : chunkInfos.values()) {
                    if (value.getPartyOwner().equals(partyInfo.getId())) {
                        queueMapUpdate(world, value.getChunkX(), value.getChunkZ());
                    }
                }
            }
        });
    }

    public void queueMapUpdate(World world, int chunkX, int chunkZ) {
        if (!mapUpdateQueue.containsKey(world.getName())) {
            mapUpdateQueue.put(world.getName(), new LongOpenHashSet());
        }
        int[] dx = {-1, 0, 1, -1, 1, -1, 0, 1};
        int[] dz = {-1, -1, -1, 0, 0, 1, 1, 1};

        for (int i = 0; i < dx.length; i++) {
            int adjX = chunkX + dx[i];
            int adjZ = chunkZ + dz[i];
            mapUpdateQueue.get(world.getName()).add(ChunkUtil.indexChunk(adjX, adjZ));
        }
        mapUpdateQueue.get(world.getName()).add(ChunkUtil.indexChunk(chunkX, chunkZ));
        this.setNeedsMapUpdate(world.getName());
    }

    public HashMap<String, LongSet> getMapUpdateQueue() {
        return mapUpdateQueue;
    }

    public Map<UUID, UUID> getPlayerToParty() {
        return playerToParty;
    }

    public void disbandInactiveParties() {
        int inactivityHours = Main.CONFIG.get().getPartyInactivityHours();
        if (inactivityHours < 0) return;
        long inactivityMillis = inactivityHours * 60L * 60L * 1000L;
        long currentTime = System.currentTimeMillis();
        List<PartyInfo> toDisband = new ArrayList<>();
        for (PartyInfo party : parties.values()) {
            if (party.getOwner() == null && (party.getMembers() == null || party.getMembers().length == 0)) {
                continue; // Ignore if no owner and no members
            }
            boolean allInactive = true;
            if (party.getOwner() != null) {
                var playerName = playerNameTracker.getNamesMap().get(party.getOwner());
                if (playerName == null || playerName.getLastSeen() <= 0 || Universe.get().getPlayer(party.getOwner()) != null) { //Check if online also
                    allInactive = false; // Ignore if lastSeen is missing
                } else if (currentTime - playerName.getLastSeen() < inactivityMillis) {
                    allInactive = false;
                }
            }
            if (allInactive && party.getMembers() != null) {
                for (UUID member : party.getMembers()) {
                    var playerName = playerNameTracker.getNamesMap().get(member);
                    if (playerName == null || playerName.getLastSeen() <= 0 || Universe.get().getPlayer(member) != null) { //Check if online also
                        allInactive = false; // Ignore if lastSeen is missing
                        break;
                    } else if (currentTime - playerName.getLastSeen() < inactivityMillis) {
                        allInactive = false;
                        break;
                    }
                }
            }
            if (allInactive) {
                toDisband.add(party);
            }
        }
        for (PartyInfo party : toDisband) {
            logger.at(Level.INFO).log("Disbanding inactive party: " + party.getName() + " (" + party.getId() + ")");
            disbandParty(party);
        }
    }

    public void migrateOldClaimOverrides() {
        if (!Main.CONFIG.get().isMigrateOldClaimOverrides()) {
            return;
        }
        
        logger.at(Level.INFO).log("Checking for old claim overrides to migrate...");
        int migratedCount = 0;
        
        for (PartyInfo party : this.parties.values()) {
            var oldOverride = party.getOverride(PartyOverrides.CLAIM_CHUNK_AMOUNT);
            if (oldOverride != null) {
                int oldValue = (Integer) oldOverride.getValue().getTypedValue();
                int configDefault = Main.CONFIG.get().getDefaultPartyClaimsAmount();
                
                if (oldValue != configDefault) {
                    // Split into base + bonus
                    if (oldValue > configDefault) {
                        // Assume extra is earned bonuses
                        int bonus = oldValue - configDefault;
                        party.setOverride(new PartyOverride(PartyOverrides.BONUS_CLAIM_CHUNKS, 
                            new PartyOverride.PartyOverrideValue("integer", bonus)));
                        logger.at(Level.INFO).log("Migrated party " + party.getName() + ": base=" + configDefault + ", bonus=" + bonus);
                    } else {
                        // Admin had lowered it - treat as base override
                        party.setOverride(new PartyOverride(PartyOverrides.CLAIM_CHUNK_BASE, 
                            new PartyOverride.PartyOverrideValue("integer", oldValue)));
                        logger.at(Level.INFO).log("Migrated party " + party.getName() + ": custom base=" + oldValue);
                    }
                }
                
                // Remove old override
                party.removeOverride(PartyOverrides.CLAIM_CHUNK_AMOUNT);
                saveParty(party);
                migratedCount++;
            }
        }
        
        if (migratedCount > 0) {
            logger.at(Level.INFO).log("Successfully migrated " + migratedCount + " parties to new claim system");
        } else {
            logger.at(Level.INFO).log("No parties needed migration");
        }
    }

    public void runAsync(Runnable runnable) {
        this.executorService.submit(runnable);
    }

    /**
     * Checks if a chunk is adjacent (shares at least one side) to any chunk claimed by the party
     */
    public boolean isAdjacentToPartyClaims(String dimension, int chunkX, int chunkZ, UUID partyId) {
        // Check all 4 directions (north, south, east, west)
        ChunkInfo[] adjacentChunks = {
            getChunk(dimension, chunkX, chunkZ + 1),     // North
            getChunk(dimension, chunkX, chunkZ - 1),     // South
            getChunk(dimension, chunkX + 1, chunkZ),    // East
            getChunk(dimension, chunkX - 1, chunkZ)     // West
        };
        
        for (ChunkInfo adjacent : adjacentChunks) {
            if (adjacent != null && adjacent.getPartyOwner().equals(partyId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a chunk is reserved (perimeter chunk) by another party
     */
    public boolean isReservedByOtherParty(String dimension, int chunkX, int chunkZ, UUID excludePartyId) {
        var reservedDimension = this.reservedChunks.get(dimension);
        if (reservedDimension == null) return false;
        
        ReservedChunk reserved = reservedDimension.get(ReservedChunk.formatCoordinates(chunkX, chunkZ));
        return reserved != null && !reserved.getReservedBy().equals(excludePartyId);
    }

    /**
     * Checks if a chunk is reserved (perimeter chunk) by the same party
     */
    public boolean isReservedByOwnParty(String dimension, int chunkX, int chunkZ, UUID partyId) {
        var reservedDimension = this.reservedChunks.get(dimension);
        if (reservedDimension == null) return false;
        
        ReservedChunk reserved = reservedDimension.get(ReservedChunk.formatCoordinates(chunkX, chunkZ));
        return reserved != null && reserved.getReservedBy().equals(partyId);
    }

    /**
     * Checks if claiming a chunk would create a perimeter that overlaps with chunks reserved by other parties
     */
    public boolean wouldPerimeterOverlapOtherReserved(String dimension, int chunkX, int chunkZ, UUID partyId) {
        if (!Main.CONFIG.get().isEnablePerimeterReservation()) {
            return false;
        }
        
        var reservedDimension = this.reservedChunks.get(dimension);
        if (reservedDimension == null) return false;
        
        // Calculate what the perimeter would be for this new chunk
        // Check all 8 directions (including diagonals for corners)
        int[] dx = {-1, 0, 1, -1, 1, -1, 0, 1};
        int[] dz = {-1, -1, -1, 0, 0, 1, 1, 1};
        
        for (int i = 0; i < dx.length; i++) {
            int adjX = chunkX + dx[i];
            int adjZ = chunkZ + dz[i];
            // Check if this adjacent chunk is already claimed by this party
            ChunkInfo existingChunk = getChunk(dimension, adjX, adjZ);
            if (existingChunk != null && existingChunk.getPartyOwner().equals(partyId)) {
                continue; // Skip chunks already claimed by this party
            }
            
            // Check if this adjacent chunk is reserved by another party
            ReservedChunk reserved = reservedDimension.get(ReservedChunk.formatCoordinates(adjX, adjZ));
            if (reserved != null && !reserved.getReservedBy().equals(partyId)) {
                // This chunk would be in the perimeter and is already reserved by another party
                return true;
            }
        }
        
        return false;
    }

    /**
     * Gets the reserved chunk if it exists
     */
    @Nullable
    public ReservedChunk getReservedChunk(String dimension, int chunkX, int chunkZ) {
        var reservedDimension = this.reservedChunks.get(dimension);
        if (reservedDimension == null) return null;
        return reservedDimension.get(ReservedChunk.formatCoordinates(chunkX, chunkZ));
    }

    /**
     * Calculates and updates the reserved perimeter chunks for a party
     * This creates a protective border around all claimed chunks
     */
    private void updateReservedPerimeter(String dimension, UUID partyId) {
        var chunkDimension = this.chunks.get(dimension);
        if (chunkDimension == null) return;
        
        // Get all chunks claimed by this party in this dimension
        Set<String> partyChunkCoords = new HashSet<>();
        for (ChunkInfo chunk : chunkDimension.values()) {
            if (chunk.getPartyOwner().equals(partyId)) {
                partyChunkCoords.add(ChunkInfo.formatCoordinates(chunk.getChunkX(), chunk.getChunkZ()));
            }
        }
        
        // If no chunks claimed, remove all reserved chunks for this party
        if (partyChunkCoords.isEmpty()) {
            var reservedDimension = this.reservedChunks.get(dimension);
            if (reservedDimension != null) {
                List<ReservedChunk> toRemove = new ArrayList<>();
                for (ReservedChunk reserved : reservedDimension.values()) {
                    if (reserved.getReservedBy().equals(partyId)) {
                        toRemove.add(reserved);
                    }
                }
                for (ReservedChunk reserved : toRemove) {
                    reservedDimension.remove(ReservedChunk.formatCoordinates(reserved.getChunkX(), reserved.getChunkZ()));
                    this.runAsync(() -> databaseManager.deleteReservedChunk(dimension, reserved.getChunkX(), reserved.getChunkZ()));
                }
            }
            return;
        }
        
        // Calculate perimeter: all chunks adjacent to claimed chunks that are not themselves claimed
        Set<String> perimeterCoords = new HashSet<>();
        for (String coord : partyChunkCoords) {
            String[] parts = coord.split(":");
            int chunkX = Integer.parseInt(parts[0]);
            int chunkZ = Integer.parseInt(parts[1]);
            
            // Check all 8 directions (including diagonals for corners)
            int[] dx = {-1, 0, 1, -1, 1, -1, 0, 1};
            int[] dz = {-1, -1, -1, 0, 0, 1, 1, 1};
            
            for (int i = 0; i < dx.length; i++) {
                int adjX = chunkX + dx[i];
                int adjZ = chunkZ + dz[i];
                String adjCoord = ChunkInfo.formatCoordinates(adjX, adjZ);
                
                // Only add if not already claimed by this party
                if (!partyChunkCoords.contains(adjCoord)) {
                    // Check if it's already claimed by another party - if so, don't reserve it
                    ChunkInfo existingChunk = getChunk(dimension, adjX, adjZ);
                    if (existingChunk == null || !existingChunk.getPartyOwner().equals(partyId)) {
                        perimeterCoords.add(ReservedChunk.formatCoordinates(adjX, adjZ));
                    }
                }
            }
        }
        
        // Remove old reserved chunks that are no longer in the perimeter
        var reservedDimension = this.reservedChunks.computeIfAbsent(dimension, k -> new HashMap<>());
        List<ReservedChunk> toRemove = new ArrayList<>();
        for (ReservedChunk reserved : reservedDimension.values()) {
            if (reserved.getReservedBy().equals(partyId) && !perimeterCoords.contains(ReservedChunk.formatCoordinates(reserved.getChunkX(), reserved.getChunkZ()))) {
                toRemove.add(reserved);
            }
        }
        for (ReservedChunk reserved : toRemove) {
            reservedDimension.remove(ReservedChunk.formatCoordinates(reserved.getChunkX(), reserved.getChunkZ()));
            this.runAsync(() -> databaseManager.deleteReservedChunk(dimension, reserved.getChunkX(), reserved.getChunkZ()));
        }
        
        // Add new reserved chunks
        for (String coord : perimeterCoords) {
            String[] parts = coord.split(":");
            int chunkX = Integer.parseInt(parts[0]);
            int chunkZ = Integer.parseInt(parts[1]);
            
            String formattedCoord = ReservedChunk.formatCoordinates(chunkX, chunkZ);
            if (!reservedDimension.containsKey(formattedCoord)) {
                ReservedChunk reserved = new ReservedChunk(partyId, chunkX, chunkZ);
                reservedDimension.put(formattedCoord, reserved);
                this.runAsync(() -> databaseManager.saveReservedChunk(dimension, reserved));
            } else {
                // Update if reserved by different party (shouldn't happen, but just in case)
                ReservedChunk existing = reservedDimension.get(formattedCoord);
                if (!existing.getReservedBy().equals(partyId)) {
                    existing.setReservedBy(partyId);
                    this.runAsync(() -> databaseManager.saveReservedChunk(dimension, existing));
                }
            }
        }
    }

    public HashMap<String, HashMap<String, ReservedChunk>> getReservedChunks() {
        return reservedChunks;
    }
}
