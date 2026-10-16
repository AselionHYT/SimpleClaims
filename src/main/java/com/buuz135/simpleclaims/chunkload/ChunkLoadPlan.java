package com.buuz135.simpleclaims.chunkload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Decides which claimed chunks are kept loaded. Pure: no engine calls, so it is tested on its own.
 *
 * <p>Parties are served in the order chunk loading was switched on for them (first come, first served under the
 * server-wide cap). A party that chose chunks gets only those; otherwise its oldest claims come first.
 */
public final class ChunkLoadPlan {

    /** A claimed chunk of a party that has chunk loading switched on. */
    public record Candidate(UUID partyId, String dimension, int chunkX, int chunkZ, String claimedDate) {
        public String id() {
            return chunkId(dimension, chunkX, chunkZ);
        }
    }

    /** What one party gets: the chunks kept loaded, and how many it would have wanted. */
    public record PartyResult(UUID partyId, List<Candidate> chunks, int wanted, boolean cappedByServer) {
    }

    private ChunkLoadPlan() {
    }

    public static String chunkId(String dimension, int chunkX, int chunkZ) {
        return dimension + "|" + chunkX + "|" + chunkZ;
    }

    /**
     * @param partiesInOrder parties with chunk loading on, in the order they were switched on
     * @param candidates     every claimed chunk of those parties
     * @param selections     the chunks each party chose; a party without a choice keeps its oldest claims
     */
    public static Map<UUID, PartyResult> plan(List<UUID> partiesInOrder, List<Candidate> candidates, Map<UUID, Set<String>> selections,
                                              int maxPerParty, int maxTotal) {
        Map<UUID, List<Candidate>> byParty = new LinkedHashMap<>();
        for (UUID party : partiesInOrder) byParty.put(party, new ArrayList<>());
        for (Candidate candidate : candidates) {
            var list = byParty.get(candidate.partyId());
            if (list != null) list.add(candidate);
        }
        Comparator<Candidate> oldestFirst = Comparator
                .comparing((Candidate c) -> c.claimedDate() == null ? "" : c.claimedDate())
                .thenComparing(Candidate::dimension)
                .thenComparingInt(Candidate::chunkX)
                .thenComparingInt(Candidate::chunkZ);

        Map<UUID, PartyResult> result = new LinkedHashMap<>();
        int left = Math.max(0, maxTotal);
        for (var entry : byParty.entrySet()) {
            List<Candidate> list = entry.getValue();
            Set<String> chosen = selections.getOrDefault(entry.getKey(), Set.of());
            if (!chosen.isEmpty()) {
                Set<String> chosenIds = new LinkedHashSet<>(chosen);
                list.removeIf(c -> !chosenIds.contains(c.id()));
            }
            list.sort(oldestFirst);
            int wanted = Math.min(list.size(), Math.max(0, maxPerParty));
            int granted = Math.min(wanted, left);
            left -= granted;
            result.put(entry.getKey(), new PartyResult(entry.getKey(), List.copyOf(list.subList(0, granted)), wanted, granted < wanted));
        }
        return result;
    }
}
