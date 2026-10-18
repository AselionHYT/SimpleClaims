package com.buuz135.simpleclaims.chunkload;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Where the chunk loading state lives between restarts and rolling handoffs (the SimpleClaims database).
 * Only touched while chunk loading is enabled in the config.
 */
public interface ChunkLoadStore {

    /** Creates the tables if they are missing. */
    void ensureSchema();

    /** The parties chunk loading is switched on for, with who switched it on and when. */
    Map<UUID, Enabled> loadEnabledParties();

    /** The chunks each party chose to keep loaded, as {@link ChunkLoadPlan#chunkId} strings. */
    Map<UUID, Set<String>> loadSelections();

    void setEnabled(UUID partyId, boolean enabled, String byName, String date);

    void setSelected(UUID partyId, String dimension, int chunkX, int chunkZ, boolean selected);

    void clearSelection(UUID partyId);

    record Enabled(String byName, String date) {
    }
}
