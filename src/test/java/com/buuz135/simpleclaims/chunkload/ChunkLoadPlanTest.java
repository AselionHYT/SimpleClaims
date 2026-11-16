package com.buuz135.simpleclaims.chunkload;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChunkLoadPlanTest {

    private static final long HOUR = 3_600_000L;
    private static final long NOW = 1_000 * HOUR;
    private final UUID owner = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();

    @Test
    void anOnlineMemberMeansSeenNow() {
        long seen = ChunkLoadPlan.lastSeen(List.of(owner, member), Set.of(member)::contains, p -> NOW - 100 * HOUR, 0, NOW);
        assertEquals(NOW, seen);
    }

    @Test
    void offlineTakesTheLatestOfStoredAndMembers() {
        Map<UUID, Long> names = Map.of(owner, NOW - 30 * HOUR, member, NOW - 10 * HOUR);
        assertEquals(NOW - 10 * HOUR, ChunkLoadPlan.lastSeen(List.of(owner, member), p -> false, names::get, NOW - 20 * HOUR, NOW));
        assertEquals(NOW - 5 * HOUR, ChunkLoadPlan.lastSeen(List.of(owner, member), p -> false, names::get, NOW - 5 * HOUR, NOW));
    }

    @Test
    void nothingKnownIsZeroAndInactive() {
        long seen = ChunkLoadPlan.lastSeen(List.of(owner), p -> false, p -> 0, 0, NOW);
        assertEquals(0, seen);
        assertFalse(ChunkLoadPlan.isActive(seen, NOW, 48 * HOUR));
    }

    @Test
    void activeStrictlyWithinTheWindow() {
        assertTrue(ChunkLoadPlan.isActive(NOW, NOW, 48 * HOUR));
        assertTrue(ChunkLoadPlan.isActive(NOW - 48 * HOUR + 1, NOW, 48 * HOUR));
        assertFalse(ChunkLoadPlan.isActive(NOW - 48 * HOUR, NOW, 48 * HOUR));
    }

    @Test
    void countsPerDimensionAreSorted() {
        var p = UUID.randomUUID();
        var counts = ChunkLoadPlan.countByDimension(List.of(
                new ChunkLoadPlan.Candidate(p, "farm", 0, 0),
                new ChunkLoadPlan.Candidate(p, "default", 0, 0),
                new ChunkLoadPlan.Candidate(p, "default", 1, 0)));
        assertEquals(List.of("default", "farm"), List.copyOf(counts.keySet()));
        assertEquals(2, counts.get("default"));
    }
}
