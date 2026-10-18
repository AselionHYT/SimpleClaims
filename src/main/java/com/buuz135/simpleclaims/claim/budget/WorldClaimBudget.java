package com.buuz135.simpleclaims.claim.budget;

import java.util.Map;
import java.util.UUID;

/**
 * Aselion fork: per-world claim budgets ({@code WorldClaimLimits}). Pure, so it is tested on its own.
 *
 * <p>A world listed in {@code WorldClaimLimits} has a fixed budget per party: claims there count only against that
 * number, which does not grow with play time ({@code ClaimChunkGainInMinutes}) or bonus chunks. Claims in every other
 * world share the party's normal budget ({@code PartyInfo.getMaxClaimAmount()}), which ignores claims in listed worlds.
 */
public final class WorldClaimBudget {

    private WorldClaimBudget() {
    }

    /** The fixed limit of {@code dimension}, or {@code null} when it uses the party's normal budget. */
    public static Integer limitFor(String dimension, Map<String, Integer> worldLimits) {
        if (dimension == null || worldLimits == null) return null;
        Integer limit = worldLimits.get(dimension);
        return limit == null ? null : Math.max(0, limit);
    }

    /** Key of the per-party, per-dimension claim count. */
    public static String key(UUID partyId, String dimension) {
        return partyId + "|" + dimension;
    }

    /**
     * Claims that count against the budget of {@code dimension}: the claims in that dimension if it is listed,
     * otherwise all claims of the party ({@code totalClaims}) minus those in listed worlds.
     *
     * @param dimensionCounts claims per {@link #key(UUID, String)}
     */
    public static int used(Map<String, Integer> dimensionCounts, UUID partyId, String dimension,
                           Map<String, Integer> worldLimits, int totalClaims) {
        if (limitFor(dimension, worldLimits) != null) return dimensionCounts.getOrDefault(key(partyId, dimension), 0);
        int shared = totalClaims;
        if (worldLimits != null) {
            for (String listed : worldLimits.keySet()) shared -= dimensionCounts.getOrDefault(key(partyId, listed), 0);
        }
        return Math.max(0, shared);
    }

    /** The budget of {@code dimension}: its fixed limit if listed, else {@code partyMax}. */
    public static int max(String dimension, Map<String, Integer> worldLimits, int partyMax) {
        Integer limit = limitFor(dimension, worldLimits);
        return limit != null ? limit : partyMax;
    }
}
