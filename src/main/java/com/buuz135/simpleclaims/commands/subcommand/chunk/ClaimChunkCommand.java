package com.buuz135.simpleclaims.commands.subcommand.chunk;

import com.buuz135.simpleclaims.Main;
import com.buuz135.simpleclaims.claim.ClaimManager;
import com.buuz135.simpleclaims.claim.party.PartyOverrides;
import com.buuz135.simpleclaims.commands.CommandMessages;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import java.util.concurrent.CompletableFuture;

import static com.hypixel.hytale.server.core.command.commands.player.inventory.InventorySeeCommand.MESSAGE_COMMANDS_ERRORS_PLAYER_NOT_IN_WORLD;

public class ClaimChunkCommand extends AbstractAsyncCommand {

    public ClaimChunkCommand() {
        super("claim", "Claims the chunk where you are");
        this.requirePermission(CommandMessages.BASE_PERM + "claim");
    }

    @NonNullDecl
    @Override
    protected CompletableFuture<Void> executeAsync(CommandContext commandContext) {
        CommandSender sender = commandContext.sender();
        if (sender instanceof PlayerRef playerRef) {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref != null && ref.isValid()) {
                Store<EntityStore> store = ref.getStore();
                World world = store.getExternalData().getWorld();
                return CompletableFuture.runAsync(() -> {
                    Player player = ref.getStore().getComponent(ref, Player.getComponentType());
                    if (player == null) return;
                    if (!ClaimManager.getInstance().canClaimInDimension(world)) {
                        playerRef.sendMessage(CommandMessages.CANT_CLAIM_IN_THIS_DIMENSION);
                        return;
                    }
                    var party = ClaimManager.getInstance().getPartyFromPlayer(playerRef.getUuid());
                    if (party == null) {
                        party = ClaimManager.getInstance().createParty(playerRef, false);
                        playerRef.sendMessage(CommandMessages.PARTY_CREATED);
                    }
                    if (!party.hasPermission(playerRef.getUuid(), PartyOverrides.PARTY_PROTECTION_CLAIM_UNCLAIM)) {
                        playerRef.sendMessage(CommandMessages.NO_PERMISSION);
                        return;
                    }
                    var chunk = ClaimManager.getInstance().getChunkRawCoords(player.getWorld().getName(), (int) playerRef.getTransform().getPosition().x(), (int) playerRef.getTransform().getPosition().z());
                    if (chunk != null && ClaimManager.getInstance().getPartyById(chunk.getPartyOwner()) != null) {
                        playerRef.sendMessage(chunk.getPartyOwner().equals(party.getId()) ? CommandMessages.ALREADY_CLAIMED_BY_YOU : CommandMessages.ALREADY_CLAIMED_BY_ANOTHER_PLAYER);
                        return;
                    }

                    int chunkX = ChunkUtil.chunkCoordinate((int) playerRef.getTransform().getPosition().x());
                    int chunkZ = ChunkUtil.chunkCoordinate((int) playerRef.getTransform().getPosition().z());

                    
                    // Check if chunk is reserved by another party (only if perimeter reservation is enabled)
                    if (Main.CONFIG.get().isEnablePerimeterReservation() &&
                        ClaimManager.getInstance().isReservedByOtherParty(player.getWorld().getName(), chunkX, chunkZ, party.getId())) {
                        playerRef.sendMessage(CommandMessages.CHUNK_RESERVED_BY_OTHER_PARTY);
                        return;
                    }
                    
                    // Check if claiming this chunk would create a perimeter that overlaps with chunks reserved by other parties
                    // Skip this check if the chunk itself is reserved by our party (we can claim our own reserved chunks)
                    if (Main.CONFIG.get().isEnablePerimeterReservation() &&
                        ClaimManager.getInstance().wouldPerimeterOverlapOtherReserved(player.getWorld().getName(), chunkX, chunkZ, party.getId())) {
                        playerRef.sendMessage(CommandMessages.CHUNK_RESERVED_BY_OTHER_PARTY);
                        return;
                    }
                    
                    // Check if player has any claims - if yes, new chunk must be adjacent OR be a reserved chunk by the same party (only if restriction is enabled)
                    if (Main.CONFIG.get().isEnableAdjacentChunkRestriction() && 
                        ClaimManager.getInstance().getAmountOfClaims(party) > 0) {
                        boolean isAdjacent = ClaimManager.getInstance().isAdjacentToPartyClaims(player.getWorld().getName(), chunkX, chunkZ, party.getId());
                        if (!isAdjacent) {
                            playerRef.sendMessage(CommandMessages.CHUNK_NOT_ADJACENT);
                            return;
                        }
                    }
                    
                    if (!ClaimManager.getInstance().hasEnoughClaimsLeft(party, player.getWorld().getName())) {
                        playerRef.sendMessage(CommandMessages.NOT_ENOUGH_CHUNKS);
                        return;
                    }
                    var chunkInfo = ClaimManager.getInstance().claimChunkByRawCoords(player.getWorld().getName(), (int) playerRef.getTransform().getPosition().x(), (int) playerRef.getTransform().getPosition().z(), party, player, playerRef);
                    ClaimManager.getInstance().queueMapUpdate(player.getWorld(), chunkInfo.getChunkX(), chunkInfo.getChunkZ());
                    playerRef.sendMessage(CommandMessages.CLAIMED);
                }, world);
            } else {
                commandContext.sendMessage(MESSAGE_COMMANDS_ERRORS_PLAYER_NOT_IN_WORLD);
                return CompletableFuture.completedFuture(null);
            }
        } else {
            return CompletableFuture.completedFuture(null);
        }
    }
}
