package com.buuz135.simpleclaims.chunkload;

import com.buuz135.simpleclaims.claim.ClaimManager;
import com.buuz135.simpleclaims.claim.chunk.ChunkInfo;
import com.buuz135.simpleclaims.claim.party.PartyInfo;
import com.buuz135.simpleclaims.config.SimpleClaimsConfig.ChunkLoadingConfig;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nullable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Aselion fork: claim chunk loading. Keeps the claimed chunks of parties an admin switched it on for ticking while
 * nobody is near, so machines (Orbis Industries) keep running while the owner is offline.
 *
 * <p>Gated by {@code ChunkLoading.Enabled} in the config. When it is off, {@link #start} schedules nothing, creates no
 * tables and writes nothing; {@link #get()} returns {@code null} and the commands answer that it is disabled.
 *
 * <p>Threads: the scheduler thread only works on this class' own concurrent state and on immutable snapshots. The
 * claims of a dimension are read on that world's thread, and the engine's chunk components are only touched there.
 * The outer claims map is read through the copy-on-write reference of {@link ClaimManager#getChunks()}.
 */
public final class ChunkLoadManager {

    public static final long PERIOD_MILLIS = 500;
    /** The plan is recomputed every this many periods, or at once after a change from a command. */
    private static final int REFRESH_EVERY = 10;

    /** Runs a task at a fixed rate; returns what cancels it. */
    public interface Scheduler {
        Runnable scheduleAtFixedRate(Runnable task, long periodMillis);
    }

    private static volatile ChunkLoadManager instance;

    private final ChunkLoadingConfig config;
    private final ChunkLoadStore store;
    private final Consumer<String> log;
    private final Map<UUID, ChunkLoadStore.Enabled> enabled = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> selections = new ConcurrentHashMap<>();
    /** Latest claimed chunks of enabled parties, per dimension, read on that world's thread. */
    private final Map<String, List<ChunkLoadPlan.Candidate>> candidates = new ConcurrentHashMap<>();
    /** Per dimension: the columns found ticking with all their sections at the last keep-up. */
    private final Map<String, Set<Long>> ticking = new ConcurrentHashMap<>();
    /** Per dimension: average nanoseconds the keep-up takes on the world thread. */
    private final Map<String, Long> keepUpNanos = new ConcurrentHashMap<>();
    private volatile Map<UUID, ChunkLoadPlan.PartyResult> plan = Map.of();
    private volatile Map<String, Set<Long>> keysByDimension = Map.of();
    private volatile boolean dirty = true;
    private int periods;
    private Set<UUID> missingBefore = Set.of();
    private Runnable cancel;

    private ChunkLoadManager(ChunkLoadingConfig config, ChunkLoadStore store, Consumer<String> log) {
        this.config = config;
        this.store = store;
        this.log = log;
    }

    /** The running manager, or {@code null} when chunk loading is disabled on this server. */
    @Nullable
    public static ChunkLoadManager get() {
        return instance;
    }

    /**
     * Starts chunk loading if the config enables it, and logs one line either way.
     *
     * @return the manager, or {@code null} when disabled (then nothing was scheduled and the store was not touched)
     */
    @Nullable
    public static synchronized ChunkLoadManager start(ChunkLoadingConfig config, ChunkLoadStore store, Scheduler scheduler, Consumer<String> log) {
        stop();
        if (config == null || !config.isEnabled()) {
            log.accept("chunk loading: off");
            return null;
        }
        var manager = new ChunkLoadManager(config, store, log);
        store.ensureSchema();
        manager.enabled.putAll(store.loadEnabledParties());
        store.loadSelections().forEach((party, chunks) -> {
            Set<String> set = ConcurrentHashMap.newKeySet();
            set.addAll(chunks);
            manager.selections.put(party, set);
        });
        log.accept("chunk loading: on (max " + config.getMaxChunksPerParty() + " chunks per party, " + config.getMaxChunksTotal()
                + " in total, admin approval " + (config.isRequiresAdminApproval() ? "required" : "not required") + "; "
                + manager.enabled.size() + " parties switched on)");
        manager.cancel = scheduler.scheduleAtFixedRate(manager::period, PERIOD_MILLIS);
        instance = manager;
        return manager;
    }

    public static synchronized void stop() {
        var running = instance;
        instance = null;
        if (running != null && running.cancel != null) running.cancel.run();
    }

    public ChunkLoadingConfig getConfig() {
        return config;
    }

    // ---- state changes, from commands -------------------------------------------------------------------------

    public boolean isEnabledFor(UUID partyId) {
        return enabled.containsKey(partyId);
    }

    /** @return false when it already was in that state */
    public boolean setEnabled(UUID partyId, boolean on, String byName) {
        boolean changed;
        if (on) {
            var entry = new ChunkLoadStore.Enabled(byName, LocalDateTime.now().toString());
            changed = enabled.putIfAbsent(partyId, entry) == null;
            if (changed) store.setEnabled(partyId, true, entry.byName(), entry.date());
        } else {
            changed = enabled.remove(partyId) != null;
            if (changed) store.setEnabled(partyId, false, null, null);
        }
        dirty = true;
        return changed;
    }

    public enum SelectResult { SELECTED, UNSELECTED, LIMIT_REACHED }

    /** Adds the chunk to the party's choice of chunks to keep loaded, or takes it out again. */
    public SelectResult toggleSelected(UUID partyId, String dimension, int chunkX, int chunkZ) {
        var chosen = selections.computeIfAbsent(partyId, k -> ConcurrentHashMap.newKeySet());
        var id = ChunkLoadPlan.chunkId(dimension, chunkX, chunkZ);
        SelectResult result;
        if (chosen.remove(id)) {
            store.setSelected(partyId, dimension, chunkX, chunkZ, false);
            result = SelectResult.UNSELECTED;
        } else if (chosen.size() >= config.getMaxChunksPerParty()) {
            return SelectResult.LIMIT_REACHED;
        } else {
            chosen.add(id);
            store.setSelected(partyId, dimension, chunkX, chunkZ, true);
            result = SelectResult.SELECTED;
        }
        dirty = true;
        return result;
    }

    public void clearSelection(UUID partyId) {
        selections.remove(partyId);
        store.clearSelection(partyId);
        dirty = true;
    }

    public Set<String> getSelection(UUID partyId) {
        return Set.copyOf(selections.getOrDefault(partyId, Set.of()));
    }

    // ---- the loop -----------------------------------------------------------------------------------------------

    private void period() {
        try {
            if (dirty || periods++ % REFRESH_EVERY == 0) {
                dirty = false;
                refresh();
            }
            keepUp();
        } catch (Throwable t) {
            // An exception would end the fixed-rate schedule for good; log and carry on next period.
            log.accept("chunk loading: period failed: " + t);
        }
    }

    /** Reads the claims of enabled parties on each world's thread, then plans from what has been read. */
    private void refresh() {
        var manager = ClaimManager.getInstance();
        // Parties that were disbanded since: forget them and remove their rows. The party map is a plain HashMap
        // read from this thread, so a party only counts as gone when it is missing on two refreshes in a row.
        Set<UUID> missing = new HashSet<>();
        for (var party : List.copyOf(enabled.keySet())) if (manager.getPartyById(party) == null) missing.add(party);
        for (var party : List.copyOf(selections.keySet())) if (manager.getPartyById(party) == null) missing.add(party);
        for (var party : missing) {
            if (!missingBefore.contains(party)) continue;
            if (enabled.remove(party) != null) store.setEnabled(party, false, null, null);
            if (selections.remove(party) != null) store.clearSelection(party);
            log.accept("chunk loading: party " + party + " is gone, forgot it");
        }
        missingBefore = missing;

        var partiesSnapshot = Set.copyOf(enabled.keySet());
        var claims = manager.getChunks(); // copy-on-write outer map: safe to iterate here
        candidates.keySet().removeIf(dimension -> !claims.containsKey(dimension));
        for (var dimension : claims.keySet()) {
            var world = world(dimension);
            if (world == null) {
                candidates.remove(dimension);
                continue;
            }
            try {
                world.execute(() -> {
                    // The inner map is written on this world's thread, so it is read here too.
                    var dimensionClaims = manager.getChunks().get(dimension);
                    List<ChunkLoadPlan.Candidate> found = new ArrayList<>();
                    if (dimensionClaims != null && !partiesSnapshot.isEmpty()) {
                        for (ChunkInfo info : dimensionClaims.values()) {
                            if (!partiesSnapshot.contains(info.getPartyOwner())) continue;
                            var created = info.getCreatedTracked();
                            found.add(new ChunkLoadPlan.Candidate(info.getPartyOwner(), dimension, info.getChunkX(), info.getChunkZ(),
                                    created == null ? null : created.getDate()));
                        }
                    }
                    var next = List.copyOf(found);
                    // The plan below was built from the previous candidates; plan again next period when they changed,
                    // so a restart or a new claim is picked up within 500 ms instead of the next periodic refresh.
                    if (!next.equals(candidates.put(dimension, next))) dirty = true;
                });
            } catch (RuntimeException e) {
                // The world takes no tasks any more: it is shutting down.
            }
        }

        List<UUID> order = new ArrayList<>(partiesSnapshot);
        order.sort(Comparator.comparing((UUID p) -> {
            var e = enabled.get(p);
            return e == null || e.date() == null ? "" : e.date();
        }).thenComparing(UUID::toString));
        List<ChunkLoadPlan.Candidate> all = new ArrayList<>();
        candidates.values().forEach(all::addAll);
        Map<UUID, Set<String>> chosen = new HashMap<>();
        selections.forEach((party, ids) -> chosen.put(party, Set.copyOf(ids)));
        var next = ChunkLoadPlan.plan(order, all, chosen, config.getMaxChunksPerParty(), config.getMaxChunksTotal());

        Map<String, Set<Long>> keys = new HashMap<>();
        for (var result : next.values()) {
            for (var c : result.chunks()) {
                keys.computeIfAbsent(c.dimension(), d -> new HashSet<>()).add(ChunkUtil.indexChunk(c.chunkX(), c.chunkZ()));
            }
        }
        logChanges(plan, next);
        plan = next;
        keysByDimension = keys;
        ticking.keySet().removeIf(dimension -> !keys.containsKey(dimension));
    }

    private void keepUp() {
        for (var entry : keysByDimension.entrySet()) {
            var dimension = entry.getKey();
            var keys = entry.getValue();
            var world = world(dimension);
            if (world == null) continue;
            try {
                world.execute(() -> {
                    long start = System.nanoTime();
                    Set<Long> found = new HashSet<>();
                    ChunkKeepAlive.keepUp(world, keys, found);
                    long took = System.nanoTime() - start;
                    keepUpNanos.merge(dimension, took, (old, now) -> (old * 7 + now) / 8);
                    ticking.put(dimension, found);
                });
            } catch (RuntimeException e) {
                // The world takes no tasks any more: it is shutting down.
            }
        }
    }

    private void logChanges(Map<UUID, ChunkLoadPlan.PartyResult> before, Map<UUID, ChunkLoadPlan.PartyResult> after) {
        for (var result : after.values()) {
            var old = before.get(result.partyId());
            int oldCount = old == null ? -1 : old.chunks().size();
            if (oldCount == result.chunks().size() && old.cappedByServer() == result.cappedByServer()) continue;
            log.accept("chunk loading: party " + partyLabel(result.partyId()) + " keeps " + result.chunks().size() + " chunk(s) loaded"
                    + dimensionsOf(result) + (result.cappedByServer() ? " (server cap " + config.getMaxChunksTotal() + " reached, wanted " + result.wanted() + ")" : ""));
        }
        for (var party : before.keySet()) {
            if (!after.containsKey(party)) log.accept("chunk loading: party " + partyLabel(party) + " stopped");
        }
    }

    private static String dimensionsOf(ChunkLoadPlan.PartyResult result) {
        Set<String> dims = new java.util.TreeSet<>();
        result.chunks().forEach(c -> dims.add(c.dimension()));
        return dims.isEmpty() ? "" : " in " + String.join(", ", dims);
    }

    @Nullable
    private static World world(String dimension) {
        try {
            var universe = Universe.get();
            return universe == null ? null : universe.getWorlds().get(dimension);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- reporting ----------------------------------------------------------------------------------------------

    public static String partyLabel(UUID partyId) {
        PartyInfo party = ClaimManager.getInstance().getPartyById(partyId);
        return party == null ? partyId.toString() : "'" + party.getName() + "' (" + partyId + ")";
    }

    /** Per party: chunks kept loaded / found ticking / wanted. */
    public List<String> describeParties() {
        List<String> lines = new ArrayList<>();
        var current = plan;
        for (var party : enabled.keySet()) {
            var result = current.get(party);
            lines.add(describe(party, result));
        }
        if (lines.isEmpty()) lines.add("No party has chunk loading switched on.");
        return lines;
    }

    public String describe(UUID party) {
        return describe(party, plan.get(party));
    }

    private String describe(UUID party, @Nullable ChunkLoadPlan.PartyResult result) {
        var by = enabled.get(party);
        if (by == null) return partyLabel(party) + ": off";
        if (result == null) return partyLabel(party) + ": on, waiting for the next refresh";
        int live = 0;
        List<String> coords = new ArrayList<>();
        for (var c : result.chunks()) {
            var found = ticking.getOrDefault(c.dimension(), Set.of());
            boolean isTicking = found.contains(ChunkUtil.indexChunk(c.chunkX(), c.chunkZ()));
            if (isTicking) live++;
            coords.add(c.dimension() + " " + c.chunkX() + "," + c.chunkZ() + (isTicking ? "" : " (loading)"));
        }
        var selected = selections.getOrDefault(party, Set.of()).size();
        return partyLabel(party) + ": " + result.chunks().size() + " chunk(s) kept, " + live + " ticking"
                + (result.cappedByServer() ? ", capped by the server limit (wanted " + result.wanted() + ")" : "")
                + (selected > 0 ? ", " + selected + " chosen by the owner" : ", oldest claims")
                + "; on since " + by.date() + " by " + by.byName()
                + (coords.isEmpty() ? "" : " [" + String.join("; ", coords) + "]");
    }

    /** Total kept, server cap, and the keep-up cost and average tick length per world. */
    public List<String> describeServer() {
        List<String> lines = new ArrayList<>();
        int total = plan.values().stream().mapToInt(r -> r.chunks().size()).sum();
        lines.add("Chunk loading: " + total + "/" + config.getMaxChunksTotal() + " chunks kept, max " + config.getMaxChunksPerParty()
                + " per party, admin approval " + (config.isRequiresAdminApproval() ? "required" : "not required"));
        for (var entry : keysByDimension.entrySet()) {
            var world = world(entry.getKey());
            String tick = "";
            if (world != null) {
                try {
                    var metric = world.getBufferedTickLengthMetricSet();
                    if (metric != null) tick = String.format(Locale.ROOT, ", world tick avg %.2f ms", metric.getAverage(0) / 1_000_000.0);
                } catch (RuntimeException ignored) {
                }
            }
            long nanos = keepUpNanos.getOrDefault(entry.getKey(), 0L);
            lines.add(String.format(Locale.ROOT, "  %s: %d chunks, %d ticking, keep-up %.3f ms per %d ms%s", entry.getKey(), entry.getValue().size(),
                    ticking.getOrDefault(entry.getKey(), Set.of()).size(), nanos / 1_000_000.0, PERIOD_MILLIS, tick));
        }
        return lines;
    }
}
