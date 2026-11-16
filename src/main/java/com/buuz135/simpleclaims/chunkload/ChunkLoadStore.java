package com.buuz135.simpleclaims.chunkload;

import java.util.Map;
import java.util.UUID;

/**
 * Where the last-seen time of each party lives between restarts and rolling handoffs (the SimpleClaims database).
 * Only touched while chunk loading is enabled in the config.
 */
public interface ChunkLoadStore {

    /** Creates the table if it is missing. */
    void ensureSchema();

    /** Epoch millis each party was last seen with a member online. */
    Map<UUID, Long> loadLastSeen();

    void saveLastSeen(UUID partyId, long lastSeen);

    /** Removes the row of a disbanded party. */
    void forget(UUID partyId);
}
