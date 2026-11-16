package com.buuz135.simpleclaims.chunkload;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * The activity rule of claim chunk loading. Pure: no engine calls, so it is tested on its own.
 *
 * <p>Every claimed chunk of a party stays loaded while at least one member was online within the configured window
 * (default 48 hours). There is no per-party or server-wide budget: the claim limits per world are the budget.
 */
public final class ChunkLoadPlan {

    /** A claimed chunk of an active party. */
    public record Candidate(UUID partyId, String dimension, int chunkX, int chunkZ) {
    }

    private ChunkLoadPlan() {
    }

    /**
     * When the party was last seen: {@code now} if a member is online, otherwise the latest of what was stored for the
     * party and the members' own last-seen times. {@code 0} when nothing is known.
     */
    public static long lastSeen(Collection<UUID> members, Predicate<UUID> online, ToLongFunction<UUID> memberLastSeen, long stored, long now) {
        long latest = Math.max(0, stored);
        for (UUID member : members) {
            if (member == null) continue;
            if (online.test(member)) return now;
            latest = Math.max(latest, memberLastSeen.applyAsLong(member));
        }
        return latest;
    }

    /** Whether a party last seen at {@code lastSeen} still keeps its claims loaded. */
    public static boolean isActive(long lastSeen, long now, long windowMillis) {
        return lastSeen > 0 && now - lastSeen < windowMillis;
    }

    /** Kept chunks per dimension, sorted by name, for log lines. */
    public static Map<String, Integer> countByDimension(Collection<Candidate> chunks) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Candidate c : chunks) counts.merge(c.dimension(), 1, Integer::sum);
        return counts;
    }
}
