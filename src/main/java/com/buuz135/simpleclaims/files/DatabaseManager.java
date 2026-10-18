package com.buuz135.simpleclaims.files;

import com.buuz135.simpleclaims.claim.chunk.ChunkInfo;
import com.buuz135.simpleclaims.claim.chunk.ReservedChunk;
import com.buuz135.simpleclaims.claim.party.PartyInfo;
import com.buuz135.simpleclaims.claim.party.PartyOverride;
import com.buuz135.simpleclaims.claim.player_name.PlayerNameTracker;
import com.buuz135.simpleclaims.claim.tracking.ModifiedTracking;
import com.buuz135.simpleclaims.util.FileUtils;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;

public class DatabaseManager {

    private final HytaleLogger logger;
    private Connection connection;

    public DatabaseManager(HytaleLogger logger) {
        this.logger = logger;

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (Exception e) {
            logger.at(Level.SEVERE).log("Couldn't find relocated JDBC driver for SQLite");
        }
        FileUtils.ensureMainDirectory();
        try {
            var sqliteFile = new File(FileUtils.DATABASE_PATH);
            var parentDir = sqliteFile.getParentFile();

            // Ensure parent directory exists
            if (parentDir != null && !parentDir.exists()) {
                boolean dirsCreated = parentDir.mkdirs();
                if (!dirsCreated) {
                    logger.at(Level.SEVERE).log("Failed to create database directory: " + parentDir.getAbsolutePath());
                    throw new IOException("Cannot create database directory: " + parentDir.getAbsolutePath());
                }
            }

            // Check if parent directory is writable
            if (parentDir != null && !parentDir.canWrite()) {
                logger.at(Level.SEVERE).log("Database directory is not writable: " + parentDir.getAbsolutePath());
                throw new IOException("Database directory is not writable: " + parentDir.getAbsolutePath());
            }

            // Create database file if it doesn't exist
            if (!sqliteFile.exists()) {
                boolean fileCreated = sqliteFile.createNewFile();
                if (!fileCreated) {
                    logger.at(Level.SEVERE).log("Failed to create database file: " + sqliteFile.getAbsolutePath());
                    throw new IOException("Cannot create database file: " + sqliteFile.getAbsolutePath());
                }
            }

            // Check if file is writable
            if (!sqliteFile.canWrite()) {
                logger.at(Level.SEVERE).log("Database file is not writable: " + sqliteFile.getAbsolutePath());
                throw new IOException("Database file is not writable: " + sqliteFile.getAbsolutePath());
            }

            String dbPath = sqliteFile.getAbsolutePath();
            logger.at(Level.INFO).log("Connecting to database at: " + dbPath);
            // SQLite JDBC should handle paths with spaces, but we ensure the path is absolute
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON;");
            }
            createTables();
            logger.at(Level.INFO).log("Database initialized successfully");
        } catch (Exception e) {
            logger.at(Level.SEVERE).log("Error initializing database: " + e.getMessage());
            logger.at(Level.SEVERE).log("Database path: " + FileUtils.DATABASE_PATH);
            e.printStackTrace();
            this.connection = null;
        }
    }

    private void createTables() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS parties (" +
                    "id TEXT PRIMARY KEY," +
                    "owner TEXT," +
                    "name TEXT," +
                    "description TEXT," +
                    "color INTEGER," +
                    "created_user_uuid TEXT," +
                    "created_user_name TEXT," +
                    "created_date TEXT," +
                    "modified_user_uuid TEXT," +
                    "modified_user_name TEXT," +
                    "modified_date TEXT" +
                    ")");

            statement.execute("CREATE TABLE IF NOT EXISTS party_members (" +
                    "party_id TEXT," +
                    "member_uuid TEXT," +
                    "PRIMARY KEY (party_id, member_uuid)," +
                    "FOREIGN KEY (party_id) REFERENCES parties(id) ON DELETE CASCADE" +
                    ")");

            statement.execute("CREATE TABLE IF NOT EXISTS party_overrides (" +
                    "party_id TEXT," +
                    "type TEXT," +
                    "value_type TEXT," +
                    "value TEXT," +
                    "PRIMARY KEY (party_id, type)," +
                    "FOREIGN KEY (party_id) REFERENCES parties(id) ON DELETE CASCADE" +
                    ")");

            statement.execute("CREATE TABLE IF NOT EXISTS party_allies (" +
                    "party_id TEXT," +
                    "ally_party_id TEXT," +
                    "PRIMARY KEY (party_id, ally_party_id)," +
                    "FOREIGN KEY (party_id) REFERENCES parties(id) ON DELETE CASCADE" +
                    ")");

            statement.execute("CREATE TABLE IF NOT EXISTS player_allies (" +
                    "party_id TEXT," +
                    "player_uuid TEXT," +
                    "PRIMARY KEY (party_id, player_uuid)," +
                    "FOREIGN KEY (party_id) REFERENCES parties(id) ON DELETE CASCADE" +
                    ")");

            statement.execute("CREATE TABLE IF NOT EXISTS claims (" +
                    "dimension TEXT," +
                    "chunkX INTEGER," +
                    "chunkZ INTEGER," +
                    "party_owner TEXT," +
                    "created_user_uuid TEXT," +
                    "created_user_name TEXT," +
                    "created_date TEXT," +
                    "PRIMARY KEY (dimension, chunkX, chunkZ)" +
                    ")");

            statement.execute("CREATE TABLE IF NOT EXISTS name_cache (" +
                    "uuid TEXT PRIMARY KEY," +
                    "name TEXT," +
                    "last_seen INTEGER DEFAULT -1," +
                    "play_time REAL DEFAULT 0" +
                    ")");

            statement.execute("CREATE TABLE IF NOT EXISTS admin_overrides (" +
                    "uuid TEXT PRIMARY KEY" +
                    ")");

            statement.execute("CREATE TABLE IF NOT EXISTS party_permission_overrides (" +
                    "party_id TEXT," +
                    "target_uuid TEXT," +
                    "permission TEXT," +
                    "value INTEGER," +
                    "PRIMARY KEY (party_id, target_uuid, permission)," +
                    "FOREIGN KEY (party_id) REFERENCES parties(id) ON DELETE CASCADE" +
                    ")");

            statement.execute("CREATE TABLE IF NOT EXISTS reserved_chunks (" +
                    "dimension TEXT," +
                    "chunkX INTEGER," +
                    "chunkZ INTEGER," +
                    "reserved_by TEXT," +
                    "PRIMARY KEY (dimension, chunkX, chunkZ)" +
                    ")");

            addColumnIfNotExists("name_cache", "last_seen", "INTEGER DEFAULT " + System.currentTimeMillis());
            addColumnIfNotExists("name_cache", "play_time", "REAL DEFAULT 0");
        }
    }

    private void addColumnIfNotExists(String tableName, String columnName, String columnDefinition) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getColumns(null, null, tableName, columnName)) {
            if (!rs.next()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
                }
            }
        }
    }

    public boolean isMigrationNecessary() {
        if (!new File(FileUtils.MAIN_PATH + File.separator + ".migrated").exists()) {
            return hasAnyJsonFile();
        }
        return false;
    }

    private boolean hasAnyJsonFile() {
        return new File(FileUtils.PARTY_PATH).exists() ||
                new File(FileUtils.CLAIM_PATH).exists() ||
                new File(FileUtils.NAMES_CACHE_PATH).exists() ||
                new File(FileUtils.ADMIN_OVERRIDES_PATH).exists();
    }

    public void migrate(PartyBlockingFile partyFile, ClaimedChunkBlockingFile chunkFile, PlayerNameTrackerBlockingFile nameFile, AdminOverridesBlockingFile adminFile) {
        performMigration(partyFile, chunkFile, nameFile, adminFile);
    }

    private void performMigration(PartyBlockingFile partyFile, ClaimedChunkBlockingFile chunkFile, PlayerNameTrackerBlockingFile nameFile, AdminOverridesBlockingFile adminFile) {
        logger.at(Level.INFO).log("Starting migration to SQLite...");

        // Backup the entire folder
        try {
            Path source = Paths.get(FileUtils.MAIN_PATH);
            Path backup = Paths.get(FileUtils.MAIN_PATH + "_backup_" + System.currentTimeMillis());
            copyFolder(source, backup);
            logger.at(Level.INFO).log("Backup created at: " + backup.toAbsolutePath());
        } catch (IOException e) {
            logger.at(Level.SEVERE).log("Failed to create backup before migration: " + e.getMessage());
            return;
        }

        try {
            connection.setAutoCommit(false);

            // Migrate Parties
            for (PartyInfo party : partyFile.getParties().values()) {
                saveParty(party);
            }

            // Migrate Claims
            for (Map.Entry<String, HashMap<String, ChunkInfo>> dimEntry : chunkFile.getChunks().entrySet()) {
                String dimension = dimEntry.getKey();
                for (ChunkInfo chunk : dimEntry.getValue().values()) {
                    saveClaim(dimension, chunk);
                }
            }

            // Migrate Name Cache
            for (PlayerNameTracker.PlayerName name : nameFile.getTracker().getNames()) {
                saveNameCache(name.getUuid(), name.getName(), name.getLastSeen(), name.getPlayTime());
            }

            // Migrate Admin Overrides
            for (UUID uuid : adminFile.getAdminOverrides()) {
                saveAdminOverride(uuid);
            }

            connection.commit();
            connection.setAutoCommit(true);

            // Create migration marker
            new File(FileUtils.MAIN_PATH + File.separator + ".migrated").createNewFile();
            logger.at(Level.INFO).log("Migration to SQLite completed successfully.");
        } catch (Exception e) {
            try {
                connection.rollback();
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
            logger.at(Level.SEVERE).log("Migration failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void copyFolder(Path source, Path target) throws IOException {
        Files.walk(source).forEach(path -> {
            try {
                Path dest = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    if (!Files.exists(dest)) Files.createDirectories(dest);
                } else {
                    Files.copy(path, dest);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public void saveParty(PartyInfo party) {
        try {
            PreparedStatement ps = connection.prepareStatement("REPLACE INTO parties (id, owner, name, description, color, created_user_uuid, created_user_name, created_date, modified_user_uuid, modified_user_name, modified_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            ps.setString(1, party.getId().toString());
            ps.setString(2, party.getOwner().toString());
            ps.setString(3, party.getName());
            ps.setString(4, party.getDescription());
            ps.setInt(5, party.getColor());
            ps.setString(6, party.getCreatedTracked().getUserUUID().toString());
            ps.setString(7, party.getCreatedTracked().getUserName());
            ps.setString(8, party.getCreatedTracked().getDate());
            ps.setString(9, party.getModifiedTracked().getUserUUID().toString());
            ps.setString(10, party.getModifiedTracked().getUserName());
            ps.setString(11, party.getModifiedTracked().getDate());
            ps.executeUpdate();

            // Members
            try (PreparedStatement deleteMembers = connection.prepareStatement("DELETE FROM party_members WHERE party_id = ?")) {
                deleteMembers.setString(1, party.getId().toString());
                deleteMembers.executeUpdate();
            }
            try (PreparedStatement insertMember = connection.prepareStatement("INSERT INTO party_members (party_id, member_uuid) VALUES (?, ?)")) {
                for (UUID member : party.getMembers()) {
                    insertMember.setString(1, party.getId().toString());
                    insertMember.setString(2, member.toString());
                    insertMember.addBatch();
                }
                insertMember.executeBatch();
            }

            // Overrides
            try (PreparedStatement deleteOverrides = connection.prepareStatement("DELETE FROM party_overrides WHERE party_id = ?")) {
                deleteOverrides.setString(1, party.getId().toString());
                deleteOverrides.executeUpdate();
            }
            try (PreparedStatement insertOverride = connection.prepareStatement("INSERT INTO party_overrides (party_id, type, value_type, value) VALUES (?, ?, ?, ?)")) {
                for (PartyOverride override : party.getOverrides()) {
                    insertOverride.setString(1, party.getId().toString());
                    insertOverride.setString(2, override.getType());
                    insertOverride.setString(3, override.getValue().getType());
                    insertOverride.setString(4, override.getValue().getValue());
                    insertOverride.addBatch();
                }
                insertOverride.executeBatch();
            }

            // Party Allies
            try (PreparedStatement deleteAllies = connection.prepareStatement("DELETE FROM party_allies WHERE party_id = ?")) {
                deleteAllies.setString(1, party.getId().toString());
                deleteAllies.executeUpdate();
            }
            try (PreparedStatement insertAlly = connection.prepareStatement("INSERT INTO party_allies (party_id, ally_party_id) VALUES (?, ?)")) {
                for (UUID ally : party.getPartyAllies()) {
                    insertAlly.setString(1, party.getId().toString());
                    insertAlly.setString(2, ally.toString());
                    insertAlly.addBatch();
                }
                insertAlly.executeBatch();
            }

            // Player Allies
            try (PreparedStatement deletePAllies = connection.prepareStatement("DELETE FROM player_allies WHERE party_id = ?")) {
                deletePAllies.setString(1, party.getId().toString());
                deletePAllies.executeUpdate();
            }
            try (PreparedStatement insertPAlly = connection.prepareStatement("INSERT INTO player_allies (party_id, player_uuid) VALUES (?, ?)")) {
                for (UUID ally : party.getPlayerAllies()) {
                    insertPAlly.setString(1, party.getId().toString());
                    insertPAlly.setString(2, ally.toString());
                    insertPAlly.addBatch();
                }
                insertPAlly.executeBatch();
            }

            // Permission Overrides
            try (PreparedStatement deletePerms = connection.prepareStatement("DELETE FROM party_permission_overrides WHERE party_id = ?")) {
                deletePerms.setString(1, party.getId().toString());
                deletePerms.executeUpdate();
            }
            try (PreparedStatement insertPerm = connection.prepareStatement("INSERT INTO party_permission_overrides (party_id, target_uuid, permission, value) VALUES (?, ?, ?, ?)")) {
                for (Map.Entry<UUID, Map<String, Boolean>> entry : party.getPermissionOverrides().entrySet()) {
                    for (Map.Entry<String, Boolean> permEntry : entry.getValue().entrySet()) {
                        insertPerm.setString(1, party.getId().toString());
                        insertPerm.setString(2, entry.getKey().toString());
                        insertPerm.setString(3, permEntry.getKey());
                        insertPerm.setInt(4, permEntry.getValue() ? 1 : 0);
                        insertPerm.addBatch();
                    }
                }
                insertPerm.executeBatch();
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void deleteParty(UUID partyId) {
        try {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM parties WHERE id = ?")) {
                ps.setString(1, partyId.toString());
                ps.executeUpdate();
            }
            // Cascading deletes should handle others if foreign keys are working, 
            // but SQLite requires PRAGMA foreign_keys = ON;
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public Map<String, PartyInfo> loadParties() {
        Map<String, PartyInfo> parties = new HashMap<>();
        if (connection == null) {
            logger.at(Level.SEVERE).log("Cannot load parties: database connection is null");
            return parties;
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM parties")) {
            while (rs.next()) {
                UUID id = UUID.fromString(rs.getString("id"));
                PartyInfo party = new PartyInfo(
                        id,
                        UUID.fromString(rs.getString("owner")),
                        rs.getString("name"),
                        rs.getString("description"),
                        new UUID[0],
                        rs.getInt("color")
                );
                party.setCreatedTracked(new ModifiedTracking(
                        UUID.fromString(rs.getString("created_user_uuid")),
                        rs.getString("created_user_name"),
                        rs.getString("created_date")
                ));
                party.setModifiedTracked(new ModifiedTracking(
                        UUID.fromString(rs.getString("modified_user_uuid")),
                        rs.getString("modified_user_name"),
                        rs.getString("modified_date")
                ));

                // Load members
                try (PreparedStatement ps = connection.prepareStatement("SELECT member_uuid FROM party_members WHERE party_id = ?")) {
                    ps.setString(1, id.toString());
                    try (ResultSet rsMembers = ps.executeQuery()) {
                        List<UUID> members = new ArrayList<>();
                        while (rsMembers.next()) {
                            members.add(UUID.fromString(rsMembers.getString("member_uuid")));
                        }
                        party.setMembers(members.toArray(new UUID[0]));
                    }
                }

                // Load overrides
                try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM party_overrides WHERE party_id = ?")) {
                    ps.setString(1, id.toString());
                    try (ResultSet rsOverrides = ps.executeQuery()) {
                        while (rsOverrides.next()) {
                            party.setOverride(new PartyOverride(
                                    rsOverrides.getString("type"),
                                    new PartyOverride.PartyOverrideValue(rsOverrides.getString("value_type"), rsOverrides.getString("value"))
                            ));
                        }
                    }
                }

                // Load party allies
                try (PreparedStatement ps = connection.prepareStatement("SELECT ally_party_id FROM party_allies WHERE party_id = ?")) {
                    ps.setString(1, id.toString());
                    try (ResultSet rsAllies = ps.executeQuery()) {
                        while (rsAllies.next()) {
                            party.addPartyAllies(UUID.fromString(rsAllies.getString("ally_party_id")));
                        }
                    }
                }

                // Load player allies
                try (PreparedStatement ps = connection.prepareStatement("SELECT player_uuid FROM player_allies WHERE party_id = ?")) {
                    ps.setString(1, id.toString());
                    try (ResultSet rsAllies = ps.executeQuery()) {
                        while (rsAllies.next()) {
                            party.addPlayerAllies(UUID.fromString(rsAllies.getString("player_uuid")));
                        }
                    }
                }

                // Load permission overrides
                try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM party_permission_overrides WHERE party_id = ?")) {
                    ps.setString(1, id.toString());
                    try (ResultSet rsPerms = ps.executeQuery()) {
                        while (rsPerms.next()) {
                            party.setPermission(
                                    UUID.fromString(rsPerms.getString("target_uuid")),
                                    rsPerms.getString("permission"),
                                    rsPerms.getInt("value") == 1
                            );
                        }
                    }
                }

                parties.put(id.toString(), party);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return parties;
    }

    public void saveClaim(String dimension, ChunkInfo chunk) {
        try (PreparedStatement ps = connection.prepareStatement("REPLACE INTO claims (dimension, chunkX, chunkZ, party_owner, created_user_uuid, created_user_name, created_date) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, dimension);
            ps.setInt(2, chunk.getChunkX());
            ps.setInt(3, chunk.getChunkZ());
            ps.setString(4, chunk.getPartyOwner().toString());
            ps.setString(5, chunk.getCreatedTracked().getUserUUID().toString());
            ps.setString(6, chunk.getCreatedTracked().getUserName());
            ps.setString(7, chunk.getCreatedTracked().getDate());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void deleteClaim(String dimension, int chunkX, int chunkZ) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM claims WHERE dimension = ? AND chunkX = ? AND chunkZ = ?")) {
            ps.setString(1, dimension);
            ps.setInt(2, chunkX);
            ps.setInt(3, chunkZ);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public HashMap<String, HashMap<String, ChunkInfo>> loadClaims() {
        HashMap<String, HashMap<String, ChunkInfo>> claims = new HashMap<>();
        if (connection == null) {
            logger.at(Level.SEVERE).log("Cannot load claims: database connection is null");
            return claims;
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM claims")) {
            while (rs.next()) {
                String dimension = rs.getString("dimension");
                ChunkInfo chunk = new ChunkInfo(
                        UUID.fromString(rs.getString("party_owner")),
                        rs.getInt("chunkX"),
                        rs.getInt("chunkZ")
                );
                chunk.setCreatedTracked(new ModifiedTracking(
                        UUID.fromString(rs.getString("created_user_uuid")),
                        rs.getString("created_user_name"),
                        rs.getString("created_date")
                ));
                claims.computeIfAbsent(dimension, k -> new HashMap<>()).put(ChunkInfo.formatCoordinates(chunk.getChunkX(), chunk.getChunkZ()), chunk);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return claims;
    }

    public void saveNameCache(UUID uuid, String name, long lastSeen, float playTime) {
        try (PreparedStatement ps = connection.prepareStatement("REPLACE INTO name_cache (uuid, name, last_seen, play_time) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, lastSeen);
            ps.setFloat(4, playTime);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public PlayerNameTracker loadNameCache() {
        PlayerNameTracker tracker = new PlayerNameTracker();
        if (connection == null) {
            logger.at(Level.SEVERE).log("Cannot load name cache: database connection is null");
            return tracker;
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM name_cache")) {
            while (rs.next()) {
                tracker.setPlayerName(UUID.fromString(rs.getString("uuid")), rs.getString("name"), rs.getLong("last_seen"), rs.getFloat("play_time"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return tracker;
    }

    public void saveAdminOverride(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("REPLACE INTO admin_overrides (uuid) VALUES (?)")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void deleteAdminOverride(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM admin_overrides WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public Set<UUID> loadAdminOverrides() {
        Set<UUID> overrides = new HashSet<>();
        if (connection == null) {
            logger.at(Level.SEVERE).log("Cannot load admin overrides: database connection is null");
            return overrides;
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM admin_overrides")) {
            while (rs.next()) {
                overrides.add(UUID.fromString(rs.getString("uuid")));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return overrides;
    }

    public void saveReservedChunk(String dimension, ReservedChunk reservedChunk) {
        try (PreparedStatement ps = connection.prepareStatement("REPLACE INTO reserved_chunks (dimension, chunkX, chunkZ, reserved_by) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, dimension);
            ps.setInt(2, reservedChunk.getChunkX());
            ps.setInt(3, reservedChunk.getChunkZ());
            ps.setString(4, reservedChunk.getReservedBy().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void deleteReservedChunk(String dimension, int chunkX, int chunkZ) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM reserved_chunks WHERE dimension = ? AND chunkX = ? AND chunkZ = ?")) {
            ps.setString(1, dimension);
            ps.setInt(2, chunkX);
            ps.setInt(3, chunkZ);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void deleteReservedChunksByParty(String dimension, UUID partyId) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM reserved_chunks WHERE dimension = ? AND reserved_by = ?")) {
            ps.setString(1, dimension);
            ps.setString(2, partyId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public HashMap<String, HashMap<String, ReservedChunk>> loadReservedChunks() {
        HashMap<String, HashMap<String, ReservedChunk>> reservedChunks = new HashMap<>();
        if (connection == null) {
            logger.at(Level.SEVERE).log("Cannot load reserved chunks: database connection is null");
            return reservedChunks;
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM reserved_chunks")) {
            while (rs.next()) {
                String dimension = rs.getString("dimension");
                ReservedChunk reservedChunk = new ReservedChunk(
                        UUID.fromString(rs.getString("reserved_by")),
                        rs.getInt("chunkX"),
                        rs.getInt("chunkZ")
                );
                reservedChunks.computeIfAbsent(dimension, k -> new HashMap<>())
                        .put(ReservedChunk.formatCoordinates(reservedChunk.getChunkX(), reservedChunk.getChunkZ()), reservedChunk);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return reservedChunks;
    }

    // Aselion fork: chunk loading. The tables are only created once chunk loading is enabled in the config,
    // so a server with it off never writes them. No foreign key to parties on purpose: saveParty() uses
    // REPLACE INTO parties, which deletes and re-inserts the row and would cascade-delete these rows each time.
    // Rows of disbanded parties are removed by the chunk load manager.

    public void ensureChunkLoadingTables() {
        if (connection == null) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS chunk_loading_parties (" +
                    "party_id TEXT PRIMARY KEY," +
                    "enabled_by_name TEXT," +
                    "enabled_date TEXT" +
                    ")");
            statement.execute("CREATE TABLE IF NOT EXISTS chunk_loading_selection (" +
                    "party_id TEXT," +
                    "dimension TEXT," +
                    "chunkX INTEGER," +
                    "chunkZ INTEGER," +
                    "PRIMARY KEY (party_id, dimension, chunkX, chunkZ)" +
                    ")");
        } catch (SQLException e) {
            logger.at(Level.SEVERE).log("Could not create the chunk loading tables: " + e.getMessage());
        }
    }

    public Map<UUID, com.buuz135.simpleclaims.chunkload.ChunkLoadStore.Enabled> loadChunkLoadingParties() {
        Map<UUID, com.buuz135.simpleclaims.chunkload.ChunkLoadStore.Enabled> result = new HashMap<>();
        if (connection == null) return result;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM chunk_loading_parties")) {
            while (rs.next()) {
                result.put(UUID.fromString(rs.getString("party_id")),
                        new com.buuz135.simpleclaims.chunkload.ChunkLoadStore.Enabled(rs.getString("enabled_by_name"), rs.getString("enabled_date")));
            }
        } catch (SQLException e) {
            logger.at(Level.SEVERE).log("Could not load chunk loading parties: " + e.getMessage());
        }
        return result;
    }

    public Map<UUID, Set<String>> loadChunkLoadingSelections() {
        Map<UUID, Set<String>> result = new HashMap<>();
        if (connection == null) return result;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM chunk_loading_selection")) {
            while (rs.next()) {
                result.computeIfAbsent(UUID.fromString(rs.getString("party_id")), k -> new HashSet<>())
                        .add(com.buuz135.simpleclaims.chunkload.ChunkLoadPlan.chunkId(rs.getString("dimension"), rs.getInt("chunkX"), rs.getInt("chunkZ")));
            }
        } catch (SQLException e) {
            logger.at(Level.SEVERE).log("Could not load chunk loading selections: " + e.getMessage());
        }
        return result;
    }

    public void setChunkLoadingParty(UUID partyId, boolean enabled, String byName, String date) {
        if (connection == null) return;
        String sql = enabled
                ? "REPLACE INTO chunk_loading_parties (party_id, enabled_by_name, enabled_date) VALUES (?, ?, ?)"
                : "DELETE FROM chunk_loading_parties WHERE party_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, partyId.toString());
            if (enabled) {
                ps.setString(2, byName);
                ps.setString(3, date);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.at(Level.SEVERE).log("Could not save chunk loading for party " + partyId + ": " + e.getMessage());
        }
    }

    public void setChunkLoadingSelected(UUID partyId, String dimension, int chunkX, int chunkZ, boolean selected) {
        if (connection == null) return;
        String sql = selected
                ? "REPLACE INTO chunk_loading_selection (party_id, dimension, chunkX, chunkZ) VALUES (?, ?, ?, ?)"
                : "DELETE FROM chunk_loading_selection WHERE party_id = ? AND dimension = ? AND chunkX = ? AND chunkZ = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, partyId.toString());
            ps.setString(2, dimension);
            ps.setInt(3, chunkX);
            ps.setInt(4, chunkZ);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.at(Level.SEVERE).log("Could not save the chunk loading selection of party " + partyId + ": " + e.getMessage());
        }
    }

    public void clearChunkLoadingSelection(UUID partyId) {
        if (connection == null) return;
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM chunk_loading_selection WHERE party_id = ?")) {
            ps.setString(1, partyId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.at(Level.SEVERE).log("Could not clear the chunk loading selection of party " + partyId + ": " + e.getMessage());
        }
    }
}
