package com.buuz135.simpleclaims.chunkload;

import com.buuz135.simpleclaims.claim.ClaimManager;
import com.buuz135.simpleclaims.files.DatabaseManager;

import java.util.Map;
import java.util.Set;
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
    public Map<UUID, Enabled> loadEnabledParties() {
        return database.loadChunkLoadingParties();
    }

    @Override
    public Map<UUID, Set<String>> loadSelections() {
        return database.loadChunkLoadingSelections();
    }

    @Override
    public void setEnabled(UUID partyId, boolean enabled, String byName, String date) {
        claims.runAsync(() -> database.setChunkLoadingParty(partyId, enabled, byName, date));
    }

    @Override
    public void setSelected(UUID partyId, String dimension, int chunkX, int chunkZ, boolean selected) {
        claims.runAsync(() -> database.setChunkLoadingSelected(partyId, dimension, chunkX, chunkZ, selected));
    }

    @Override
    public void clearSelection(UUID partyId) {
        claims.runAsync(() -> database.clearChunkLoadingSelection(partyId));
    }
}
