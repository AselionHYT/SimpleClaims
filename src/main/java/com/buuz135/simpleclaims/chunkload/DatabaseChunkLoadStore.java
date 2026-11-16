package com.buuz135.simpleclaims.chunkload;

import com.buuz135.simpleclaims.claim.ClaimManager;
import com.buuz135.simpleclaims.files.DatabaseManager;

import java.util.Map;
import java.util.UUID;

/** The chunk loading state in the SimpleClaims database. Writes go through the claim manager's database pool. */
public final class DatabaseChunkLoadStore implements ChunkLoadStore {

    private final ClaimManager claims;
    private final DatabaseManager database;

    public DatabaseChunkLoadStore(ClaimManager claims) {
        this.claims = claims;
        this.database = claims.getDatabaseManager();
    }

    @Override
    public void ensureSchema() {
        database.ensureChunkLoadingTables();
    }

    @Override
    public Map<UUID, Long> loadLastSeen() {
        return database.loadChunkLoadingLastSeen();
    }

    @Override
    public void saveLastSeen(UUID partyId, long lastSeen) {
        claims.runAsync(() -> database.saveChunkLoadingLastSeen(partyId, lastSeen));
    }

    @Override
    public void forget(UUID partyId) {
        claims.runAsync(() -> database.deleteChunkLoadingLastSeen(partyId));
    }
}
