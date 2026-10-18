package com.buuz135.simpleclaims.chunkload;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChunkLoadPlanTest {

    private static List<ChunkLoadPlan.Candidate> claims(UUID party, int count) {
        List<ChunkLoadPlan.Candidate> list = new ArrayList<>();
        for (int i = 0; i < count; i++) list.add(new ChunkLoadPlan.Candidate(party, "default", i, 0, "2026-10-0" + (9 - i % 9)));
        return list;
    }

    @Test
    void eachPartyGetsAtMostItsLimitOldestClaimsFirst() {
        var a = UUID.randomUUID();
        var plan = ChunkLoadPlan.plan(List.of(a), claims(a, 12), Map.of(), 9, 100);
        var result = plan.get(a);
        assertEquals(9, result.chunks().size());
        assertFalse(result.cappedByServer());
        // oldest date first: chunk 8 has date ...-01
        assertEquals(8, result.chunks().get(0).chunkX());
    }

    @Test
    void serverCapServesPartiesInTheOrderTheyWereSwitchedOn() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        List<ChunkLoadPlan.Candidate> all = new ArrayList<>(claims(first, 5));
        all.addAll(claims(second, 5));
        var plan = ChunkLoadPlan.plan(List.of(first, second), all, Map.of(), 9, 7);
        assertEquals(5, plan.get(first).chunks().size());
        assertEquals(2, plan.get(second).chunks().size());
        assertTrue(plan.get(second).cappedByServer());
        assertEquals(5, plan.get(second).wanted());
    }

    @Test
    void chosenChunksReplaceTheDefault() {
        var a = UUID.randomUUID();
        var plan = ChunkLoadPlan.plan(List.of(a), claims(a, 5), Map.of(a, Set.of(ChunkLoadPlan.chunkId("default", 3, 0), ChunkLoadPlan.chunkId("default", 99, 0))), 9, 100);
        assertEquals(1, plan.get(a).chunks().size());
        assertEquals(3, plan.get(a).chunks().get(0).chunkX());
    }

    @Test
    void claimsOfOtherPartiesAreIgnored() {
        var a = UUID.randomUUID();
        var stranger = UUID.randomUUID();
        var plan = ChunkLoadPlan.plan(List.of(a), claims(stranger, 3), Map.of(), 9, 100);
        assertEquals(0, plan.get(a).chunks().size());
        assertFalse(plan.containsKey(stranger));
    }
}
