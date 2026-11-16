package com.buuz135.simpleclaims.chunkload;

import com.buuz135.simpleclaims.claim.ClaimManager;
import com.buuz135.simpleclaims.claim.chunk.ChunkInfo;
import com.buuz135.simpleclaims.claim.party.PartyInfo;
import com.buuz135.simpleclaims.config.SimpleClaimsConfig.ChunkLoadingConfig;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Aselion fork: claim chunk loading. Every claimed chunk of a party stays ticking while at least one party member was
 * online within {@code ChunkLoading.ActiveHours} (default 48), so machines (Orbis Industries) keep running while the
 * owners are offline. No per-party budget and no admin switch: the claim limits per world are the budget. Above
 * {@code ChunkLoading.WarnAboveLoadedChunks} kept chunks a WARN is logged; nothing is cut off.
 *
 * <p>Gated by {@code ChunkLoading.Enabled} in the config. When it is off, {@link #start} schedules nothing, creates no
 * tables and writes nothing; {@link #get()} returns {@code null} and the commands answer that it is disabled.
 *
 * <p>Threads: the scheduler thread only works on this class' own concurrent state and on immutable snapshots. The
 * claims of a dimension are read on that world's thread, and the engine's chunk components are only touched there.
 * Every task handed to a world catches everything it throws: an exception escaping a world task unloads the world.
 */
public final class ChunkLoadManager {

    public static final long PERIOD_MILLIS = 500;
    /** Activity and claims are re-evaluated every this many periods (5 s), or at once after join, leave, claim or unclaim. */
    private static final int REFRESH_EVERY = 10;
    /** The last-seen time of a party with a member online is written to the database at most this often. */
    static final long PERSIST_MILLIS = 5 * 60_000L;
    /** While above the warning threshold, the WARN is repeated at most this often. */
    static final long WARN_REPEAT_MILLIS = 60 * 60_000L;

    /** Where log lines go. */
    public interface Log {
        void info(String line);

        void warn(String line);
    }

    /** Runs a task at a fixed rate; returns what cancels it. */
    public interface Scheduler {
        Runnable scheduleAtFixedRate(Runnable task, long periodMillis);
    }

    /** What the manager needs from the parties and players; {@link #engine()} reads the live server. */
    interface Parties {
        /** Party id to all members including the owner. Called on the scheduler thread. */
        Map<UUID, List<UUID>> members();

        boolean isOnline(UUID player);

        /** Last-seen millis of a player from the name cache, {@code 0} when unknown. */
        long playerLastSeen(UUID player);

        @Nullable
        UUID partyOf(UUID player);

        /** The party's name, {@code null} when unknown. */
        @Nullable
        String name(UUID party);
    }

    private static volatile ChunkLoadManager instance;

    private final ChunkLoadingConfig config;
    private final ChunkLoadStore store;
    private final Log log;
    private final Parties parties;
    private final LongSupplier clock;
    /** Party id to the epoch millis a member was last seen online. */
    private final Map<UUID, Long> lastSeen = new ConcurrentHashMap<>();
    private final Map<UUID, Long> persistedAt = new ConcurrentHashMap<>();
    /** Latest claimed chunks of active parties, per dimension, read on that world's thread. */
    private final Map<String, List<ChunkLoadPlan.Candidate>> candidates = new ConcurrentHashMap<>();
    /** Per dimension: the columns found ticking with all their sections at the last keep-up. */
    private final Map<String, Set<Long>> ticking = new ConcurrentHashMap<>();
    /** Per dimension: average nanoseconds the keep-up takes on the world thread. */
    private final Map<String, Long> keepUpNanos = new ConcurrentHashMap<>();
    private volatile Set<UUID> active = Set.of();
    private volatile Map<UUID, List<ChunkLoadPlan.Candidate>> keptByParty = Map.of();
    private volatile Map<String, Set<Long>> keysByDimension = Map.of();
    private volatile boolean dirty = true;
    private int periods;
    private Set<UUID> missingBefore = Set.of();
    private Map<String, Integer> loggedCounts = null;
    private long lastWarnAt;
    private boolean above;
    private Runnable cancel;

    ChunkLoadManager(ChunkLoadingConfig config, ChunkLoadStore store, Log log, Parties parties, LongSupplier clock) {
        this.config = config;
        this.store = store;
        this.log = log;
        this.parties = parties;
        this.clock = clock;
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
    public static ChunkLoadManager start(ChunkLoadingConfig config, ChunkLoadStore store, Scheduler scheduler, Log log) {
        return start(config, store, scheduler, log, engine(), System::currentTimeMillis);
    }

    @Nullable
    static synchronized ChunkLoadManager start(ChunkLoadingConfig config, ChunkLoadStore store, Scheduler scheduler, Log log,
                                               Parties parties, LongSupplier clock) {
        stop();
        if (config == null || !config.isEnabled()) {
            log.info("chunk loading: off");
            return null;
        }
        var manager = new ChunkLoadManager(config, store, log, parties, clock);
        store.ensureSchema();
        manager.lastSeen.putAll(store.loadLastSeen());
        log.info("chunk loading: on (claims of parties with a member online in the last " + config.getActiveHours()
                + " h stay loaded; warn above " + config.getWarnAboveLoadedChunks() + " chunks"
                + (config.getExcludedWorlds().length == 0 ? "" : "; excluded worlds " + String.join(", ", config.getExcludedWorlds()))
                + "; " + manager.lastSeen.size() + " parties with a stored last-seen time)");
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

    // ---- triggers -----------------------------------------------------------------------------------------------

    /** A claim was added or removed: re-read the claims within the next period. Safe from any thread. */
    public static void claimsChanged() {
        var running = instance;
        if (running != null) running.dirty = true;
    }

    /**
     * A player joined or left: their party counts as seen now (stored at once) and activity is re-evaluated within the
     * next period. Never throws, so it is safe in event handlers.
     */
    public static void playerSeen(UUID player) {
        var running = instance;
        if (running == null) return;
        try {
            running.onPlayerSeen(player);
        } catch (Throwable t) {
            running.log.warn("chunk loading: recording player " + player + " failed: " + t);
        }
    }

    void onPlayerSeen(UUID player) {
        var party = parties.partyOf(player);
        if (party != null) {
            long now = clock.getAsLong();
            lastSeen.merge(party, now, Math::max);
            persist(party, now);
        }
        dirty = true;
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
            log.warn("chunk loading: period failed: " + t);
        }
    }

    /**
     * Updates the last-seen time of every party and returns the parties whose claims stay loaded. Package-private so
     * the activity rule is tested without a server.
     */
    Set<UUID> evaluateActivity() {
        long now = clock.getAsLong();
        var members = parties.members();

        // Parties that were disbanded since: forget them and remove their rows. The party map is a plain HashMap read
        // from this thread, so a party only counts as gone when it is missing on two evaluations in a row.
        Set<UUID> missing = new HashSet<>();
        for (var party : List.copyOf(lastSeen.keySet())) if (!members.containsKey(party)) missing.add(party);
        for (var party : missing) {
            if (!missingBefore.contains(party)) continue;
            lastSeen.remove(party);
            persistedAt.remove(party);
            store.forget(party);
        }
        missingBefore = missing;

        Set<UUID> next = new HashSet<>();
        for (var entry : members.entrySet()) {
            var party = entry.getKey();
            long stored = lastSeen.getOrDefault(party, 0L);
            long seen = ChunkLoadPlan.lastSeen(entry.getValue(), parties::isOnline, parties::playerLastSeen, stored, now);
            if (seen > stored) {
                lastSeen.put(party, seen);
                if (now - persistedAt.getOrDefault(party, 0L) >= PERSIST_MILLIS) persist(party, seen);
            }
            if (ChunkLoadPlan.isActive(seen, now, config.getActiveMillis())) next.add(party);
        }
        for (var party : active) {
            if (!next.contains(party)) {
                log.info("chunk loading: party " + partyLabel(party) + " inactive (last member online "
                        + Instant.ofEpochMilli(lastSeen.getOrDefault(party, 0L)) + "), its claims are released");
            }
        }
        active = Set.copyOf(next);
        return active;
    }

    private void persist(UUID party, long seen) {
        persistedAt.put(party, clock.getAsLong());
        store.saveLastSeen(party, seen);
    }

    /** Reads the claims of active parties on each world's thread, then keeps what has been read. */
    private void refresh() {
        var activeSnapshot = evaluateActivity();
        var manager = ClaimManager.getInstance();
        var claims = manager.getChunks(); // copy-on-write outer map: safe to iterate here
        candidates.keySet().removeIf(dimension -> !claims.containsKey(dimension));
        for (var dimension : claims.keySet()) {
            var world = world(dimension);
            if (world == null || config.isExcludedWorld(dimension)) {
                // Not loaded (yet), removed (farm reset) or excluded: nothing to keep there.
                candidates.remove(dimension);
                continue;
            }
            submit(world, dimension, "reading claims", () -> {
                // The inner map is written on this world's thread, so it is read here too.
                var dimensionClaims = manager.getChunks().get(dimension);
                List<ChunkLoadPlan.Candidate> found = new ArrayList<>();
                if (dimensionClaims != null && !activeSnapshot.isEmpty()) {
                    for (ChunkInfo info : dimensionClaims.values()) {
                        if (!activeSnapshot.contains(info.getPartyOwner())) continue;
                        found.add(new ChunkLoadPlan.Candidate(info.getPartyOwner(), dimension, info.getChunkX(), info.getChunkZ()));
                    }
                }
                var next = List.copyOf(found);
                // What is kept below was built from the previous candidates; refresh again next period when they
                // changed, so a restart or a new claim is picked up within 500 ms.
                if (!next.equals(candidates.put(dimension, next))) dirty = true;
            });
        }

        Map<UUID, List<ChunkLoadPlan.Candidate>> byParty = new HashMap<>();
        Map<String, Set<Long>> keys = new HashMap<>();
        List<ChunkLoadPlan.Candidate> all = new ArrayList<>();
        for (var list : candidates.values()) {
            for (var c : list) {
                // A party that went inactive since the candidates were read is dropped here already.
                if (!activeSnapshot.contains(c.partyId())) continue;
                all.add(c);
                byParty.computeIfAbsent(c.partyId(), p -> new ArrayList<>()).add(c);
                keys.computeIfAbsent(c.dimension(), d -> new HashSet<>()).add(ChunkUtil.indexChunk(c.chunkX(), c.chunkZ()));
            }
        }
        logChanges(keptByParty, byParty);
        keptByParty = byParty;
        keysByDimension = keys;
        ticking.keySet().removeIf(dimension -> !keys.containsKey(dimension));
        report(all, byParty.size(), activeSnapshot.size());
    }

    private void report(List<ChunkLoadPlan.Candidate> all, int partyCount, int activeCount) {
        var counts = ChunkLoadPlan.countByDimension(all);
        if (!counts.equals(loggedCounts)) {
            loggedCounts = counts;
            log.info("chunk loading: " + all.size() + " claim chunk(s) kept loaded for " + partyCount + " part"
                    + (partyCount == 1 ? "y" : "ies") + " (" + activeCount + " active)" + counts(counts));
        }
        int threshold = config.getWarnAboveLoadedChunks();
        long now = clock.getAsLong();
        if (threshold >= 0 && all.size() > threshold) {
            if (!above || now - lastWarnAt >= WARN_REPEAT_MILLIS) {
                log.warn("chunk loading: " + all.size() + " claim chunks kept loaded, above the warning threshold of " + threshold
                        + " (" + partyCount + " parties" + counts(counts) + "). Nothing is cut off.");
                lastWarnAt = now;
            }
            above = true;
        } else if (above) {
            above = false;
            log.info("chunk loading: " + all.size() + " claim chunks kept loaded, back under the warning threshold of " + threshold);
        }
    }

    private static String counts(Map<String, Integer> counts) {
        if (counts.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        counts.forEach((dimension, count) -> parts.add(dimension + " " + count));
        return " (" + String.join(", ", parts) + ")";
    }

    private void keepUp() {
        for (var entry : keysByDimension.entrySet()) {
            var dimension = entry.getKey();
            var keys = entry.getValue();
            var world = world(dimension);
            if (world == null) continue;
            submit(world, dimension, "keep-up", () -> {
                long start = System.nanoTime();
                Set<Long> found = new HashSet<>();
                ChunkKeepAlive.keepUp(world, keys, found);
                long took = System.nanoTime() - start;
                keepUpNanos.merge(dimension, took, (old, now) -> (old * 7 + now) / 8);
                ticking.put(dimension, found);
            });
        }
    }

    /**
     * Hands a task to a world's thread. Whatever the task throws is logged here and never reaches the world: an
     * exception escaping a world task unloads the world.
     */
    private void submit(World world, String dimension, String what, Runnable task) {
        try {
            world.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    log.warn("chunk loading: " + what + " in " + dimension + " failed: " + t);
                }
            });
        } catch (RuntimeException e) {
            // The world takes no tasks any more: it is shutting down or was removed.
        }
    }

    private void logChanges(Map<UUID, List<ChunkLoadPlan.Candidate>> before, Map<UUID, List<ChunkLoadPlan.Candidate>> after) {
        for (var entry : after.entrySet()) {
            var old = before.get(entry.getKey());
            if (old != null && old.size() == entry.getValue().size()) continue;
            log.info("chunk loading: party " + partyLabel(entry.getKey()) + " keeps " + entry.getValue().size() + " chunk(s) loaded"
                    + dimensionsOf(entry.getValue()));
        }
        for (var party : before.keySet()) {
            if (!after.containsKey(party)) log.info("chunk loading: party " + partyLabel(party) + " keeps no chunks loaded any more");
        }
    }

    private static String dimensionsOf(List<ChunkLoadPlan.Candidate> chunks) {
        Set<String> dims = new TreeSet<>();
        chunks.forEach(c -> dims.add(c.dimension()));
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

    /** Reads the live server: parties from SimpleClaims, online players from the universe. */
    private static Parties engine() {
        return new Parties() {
            @Override
            public Map<UUID, List<UUID>> members() {
                Map<UUID, List<UUID>> result = new HashMap<>();
                for (PartyInfo party : List.copyOf(ClaimManager.getInstance().getParties().values())) {
                    result.put(party.getId(), party.getAllMembers());
                }
                return result;
            }

            @Override
            public boolean isOnline(UUID player) {
                try {
                    var universe = Universe.get();
                    return universe != null && universe.getPlayer(player) != null;
                } catch (RuntimeException e) {
                    return false;
                }
            }

            @Override
            public long playerLastSeen(UUID player) {
                var name = ClaimManager.getInstance().getPlayerNameTracker().getNamesMap().get(player);
                return name == null ? 0 : Math.max(0, name.getLastSeen());
            }

            @Nullable
            @Override
            public UUID partyOf(UUID player) {
                var party = ClaimManager.getInstance().getPartyFromPlayer(player);
                return party == null ? null : party.getId();
            }

            @Nullable
            @Override
            public String name(UUID party) {
                var info = ClaimManager.getInstance().getPartyById(party);
                return info == null ? null : info.getName();
            }
        };
    }

    // ---- reporting ----------------------------------------------------------------------------------------------

    private String partyLabel(UUID partyId) {
        try {
            var name = parties.name(partyId);
            return name == null ? partyId.toString() : "'" + name + "' (" + partyId + ")";
        } catch (RuntimeException e) {
            return partyId.toString();
        }
    }

    /** Whether the party's claims are kept loaded, until when, and how many of them tick right now. */
    public String describe(UUID party) {
        long seen = lastSeen.getOrDefault(party, 0L);
        if (!active.contains(party)) {
            return partyLabel(party) + ": claims not kept loaded (no member online in the last " + config.getActiveHours() + " h)";
        }
        var kept = keptByParty.getOrDefault(party, List.of());
        int live = 0;
        for (var c : kept) {
            if (ticking.getOrDefault(c.dimension(), Set.of()).contains(ChunkUtil.indexChunk(c.chunkX(), c.chunkZ()))) live++;
        }
        return partyLabel(party) + ": " + kept.size() + " claimed chunk(s) kept loaded, " + live + " ticking"
                + dimensionsOf(kept) + "; kept until " + Instant.ofEpochMilli(seen + config.getActiveMillis())
                + " unless a member comes online before";
    }

    /** One line per active party. */
    public List<String> describeParties() {
        List<String> lines = new ArrayList<>();
        for (var party : active) lines.add(describe(party));
        if (lines.isEmpty()) lines.add("No party had a member online in the last " + config.getActiveHours() + " h.");
        return lines;
    }

    /** Total kept, and the keep-up cost and average tick length per world. */
    public List<String> describeServer() {
        List<String> lines = new ArrayList<>();
        int total = keptByParty.values().stream().mapToInt(List::size).sum();
        lines.add("Chunk loading: " + total + " claim chunks kept loaded for " + active.size() + " active parties (window "
                + config.getActiveHours() + " h, warn above " + config.getWarnAboveLoadedChunks() + ")");
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
