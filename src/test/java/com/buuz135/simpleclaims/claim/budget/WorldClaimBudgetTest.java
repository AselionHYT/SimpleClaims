package com.buuz135.simpleclaims.claim.budget;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorldClaimBudgetTest {

    private static final UUID PARTY = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final Map<String, Integer> FARM_FOUR = Map.of("farm", 4);

    private static Map<String, Integer> counts(int inDefault, int inFarm) {
        Map<String, Integer> counts = new HashMap<>();
        if (inDefault > 0) counts.put(WorldClaimBudget.key(PARTY, "default"), inDefault);
        if (inFarm > 0) counts.put(WorldClaimBudget.key(PARTY, "farm"), inFarm);
        counts.put(WorldClaimBudget.key(OTHER, "farm"), 3);
        return counts;
    }

    @Test
    void withoutLimitsEverythingIsOneBudget() {
        assertNull(WorldClaimBudget.limitFor("farm", Map.of()));
        assertEquals(12, WorldClaimBudget.used(counts(9, 3), PARTY, "farm", Map.of(), 12));
        assertEquals(30, WorldClaimBudget.max("farm", Map.of(), 30));
    }

    @Test
    void farmClaimsDoNotCountAgainstTheNormalBudget() {
        assertEquals(9, WorldClaimBudget.used(counts(9, 3), PARTY, "default", FARM_FOUR, 12));
        assertEquals(9, WorldClaimBudget.used(counts(9, 3), PARTY, null, FARM_FOUR, 12));
    }

    @Test
    void farmHasAFixedBudgetOfItsOwn() {
        assertEquals(3, WorldClaimBudget.used(counts(9, 3), PARTY, "farm", FARM_FOUR, 12));
        assertEquals(4, WorldClaimBudget.max("farm", FARM_FOUR, 300), "does not grow with the party budget");
        assertEquals(300, WorldClaimBudget.max("default", FARM_FOUR, 300));
    }

    @Test
    void otherPartiesDoNotCount() {
        assertEquals(0, WorldClaimBudget.used(counts(9, 0), PARTY, "farm", FARM_FOUR, 9));
    }

    @Test
    void negativeLimitMeansNoClaims() {
        assertEquals(0, WorldClaimBudget.max("farm", Map.of("farm", -1), 30));
    }
}
