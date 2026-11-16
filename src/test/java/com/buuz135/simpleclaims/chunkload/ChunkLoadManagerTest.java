package com.buuz135.simpleclaims.chunkload;

import com.buuz135.simpleclaims.config.SimpleClaimsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChunkLoadManagerTest {

    private static final long HOUR = 3_600_000L;

    /** Records every call; a disabled feature must leave it untouched. */
    static class RecordingStore implements ChunkLoadStore {
        final List<String> calls = new ArrayList<>();
        final Map<UUID, Long> rows = new HashMap<>();

        @Override public void ensureSchema() { calls.add("ensureSchema"); }
        @Override public Map<UUID, Long> loadLastSeen() { calls.add("loadLastSeen"); return Map.copyOf(rows); }
        @Override public void saveLastSeen(UUID partyId, long lastSeen) { calls.add("saveLastSeen"); rows.put(partyId, lastSeen); }
        @Override public void forget(UUID partyId) { calls.add("forget"); rows.remove(partyId); }
    }

    static class RecordingScheduler implements ChunkLoadManager.Scheduler {
        int scheduled;
        int cancelled;

        @Override
        public Runnable scheduleAtFixedRate(Runnable task, long periodMillis) {
            scheduled++;
            return () -> cancelled++;
        }
    }

    static class RecordingLog implements ChunkLoadManager.Log {
        final List<String> info = new ArrayList<>();
        final List<String> warn = new ArrayList<>();

        @Override public void info(String line) { info.add(line); }
        @Override public void warn(String line) { warn.add(line); }
    }

    static class FakeParties implements ChunkLoadManager.Parties {
        final Map<UUID, List<UUID>> members = new HashMap<>();
        final Set<UUID> online = new HashSet<>();
        final Map<UUID, Long> nameCache = new HashMap<>();

        @Override public Map<UUID, List<UUID>> members() { return Map.copyOf(members); }
        @Override public boolean isOnline(UUID player) { return online.contains(player); }
        @Override public long playerLastSeen(UUID player) { return nameCache.getOrDefault(player, 0L); }

        @Nullable
        @Override
        public UUID partyOf(UUID player) {
            for (var e : members.entrySet()) if (e.getValue().contains(player)) return e.getKey();
            return null;
        }

        @Override public String name(UUID party) { return "p"; }
    }

    private final RecordingStore store = new RecordingStore();
    private final RecordingLog log = new RecordingLog();
    private final FakeParties parties = new FakeParties();
    private long now = 1_000 * HOUR;

    private ChunkLoadManager start(SimpleClaimsConfig.ChunkLoadingConfig config) {
        return ChunkLoadManager.start(config, store, new RecordingScheduler(), log, parties, () -> now);
    }

    @AfterEach
    void stop() {
        ChunkLoadManager.stop();
    }

    @Test
    void defaultConfigHasChunkLoadingOffWith48Hours() {
        var config = new SimpleClaimsConfig().getChunkLoading();
        assertFalse(config.isEnabled());
        assertEquals(48, config.getActiveHours());
        assertEquals(48 * HOUR, config.getActiveMillis());
        assertEquals(500, config.getWarnAboveLoadedChunks());
    }

    @Test
    void withDefaultConfigNothingIsScheduledOrWritten() {
        var scheduler = new RecordingScheduler();
        var manager = ChunkLoadManager.start(new SimpleClaimsConfig().getChunkLoading(), store, scheduler, log);

        assertNull(manager);
        assertNull(ChunkLoadManager.get(), "commands must see chunk loading as disabled");
        assertEquals(0, scheduler.scheduled, "no keep-alive loop may be scheduled");
        assertEquals(List.of(), store.calls, "the database must not be touched");
        assertEquals(List.of("chunk loading: off"), log.info);
    }

    @Test
    void enabledConfigSchedulesOneLoopAndStopCancelsIt() {
        var scheduler = new RecordingScheduler();
        var manager = ChunkLoadManager.start(new SimpleClaimsConfig.ChunkLoadingConfig(true, 48, 500), store, scheduler, log, parties, () -> now);

        assertNotNull(manager);
        assertSame(manager, ChunkLoadManager.get());
        assertEquals(1, scheduler.scheduled);
        assertEquals(List.of("ensureSchema", "loadLastSeen"), store.calls);
        assertTrue(log.info.get(0).startsWith("chunk loading: on"), log.info.get(0));

        ChunkLoadManager.stop();
        assertEquals(1, scheduler.cancelled);
        assertNull(ChunkLoadManager.get());
    }

    @Test
    void partiesAreActiveWithinTheWindowOnly() {
        var online = UUID.randomUUID();
        var recent = UUID.randomUUID();
        var stale = UUID.randomUUID();
        var unknown = UUID.randomUUID();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var c = UUID.randomUUID();
        parties.members.put(online, List.of(a));
        parties.members.put(recent, List.of(b));
        parties.members.put(stale, List.of(c));
        parties.members.put(unknown, List.of(UUID.randomUUID()));
        parties.online.add(a);
        parties.nameCache.put(b, now - 47 * HOUR);
        parties.nameCache.put(c, now - 49 * HOUR);

        var manager = start(new SimpleClaimsConfig.ChunkLoadingConfig(true, 48, 500));

        assertEquals(Set.of(online, recent), manager.evaluateActivity());
        assertEquals(now, store.rows.get(online), "an online party's last-seen is stored");
        assertEquals(now - 47 * HOUR, store.rows.get(recent), "the name cache seeds a party without a row");
    }

    @Test
    void storedLastSeenSurvivesARestartAndExpires() {
        var party = UUID.randomUUID();
        parties.members.put(party, List.of(UUID.randomUUID()));
        store.rows.put(party, now - 40 * HOUR);

        var manager = start(new SimpleClaimsConfig.ChunkLoadingConfig(true, 48, 500));
        assertEquals(Set.of(party), manager.evaluateActivity());

        now += 9 * HOUR;
        assertEquals(Set.of(), manager.evaluateActivity());
        assertTrue(log.info.stream().anyMatch(l -> l.contains("inactive")), log.info.toString());
    }

    @Test
    void activeHoursIsConfigurable() {
        var party = UUID.randomUUID();
        parties.members.put(party, List.of(UUID.randomUUID()));
        store.rows.put(party, now - 3 * HOUR);

        assertEquals(Set.of(), start(new SimpleClaimsConfig.ChunkLoadingConfig(true, 2, 500)).evaluateActivity());
        assertEquals(Set.of(party), start(new SimpleClaimsConfig.ChunkLoadingConfig(true, 4, 500)).evaluateActivity());
    }

    @Test
    void joinOrLeaveStoresNowAtOnce() {
        var party = UUID.randomUUID();
        var player = UUID.randomUUID();
        parties.members.put(party, List.of(player));
        start(new SimpleClaimsConfig.ChunkLoadingConfig(true, 48, 500));

        ChunkLoadManager.playerSeen(player);

        assertEquals(now, store.rows.get(party));
    }

    @Test
    void playerSeenNeverThrows() {
        start(new SimpleClaimsConfig.ChunkLoadingConfig(true, 48, 500));
        parties.members.put(UUID.randomUUID(), null); // a broken party list must not reach the event handler

        assertDoesNotThrow(() -> ChunkLoadManager.playerSeen(UUID.randomUUID()));
    }

    @Test
    void onlinePartyIsNotWrittenEveryEvaluation() {
        var party = UUID.randomUUID();
        var player = UUID.randomUUID();
        parties.members.put(party, List.of(player));
        parties.online.add(player);
        var manager = start(new SimpleClaimsConfig.ChunkLoadingConfig(true, 48, 500));

        manager.evaluateActivity();
        now += 60_000;
        manager.evaluateActivity();
        assertEquals(1, store.calls.stream().filter("saveLastSeen"::equals).count());

        now += ChunkLoadManager.PERSIST_MILLIS;
        manager.evaluateActivity();
        assertEquals(2, store.calls.stream().filter("saveLastSeen"::equals).count());
        assertEquals(now, store.rows.get(party));
    }

    @Test
    void disbandedPartyIsForgottenAfterTwoEvaluations() {
        var party = UUID.randomUUID();
        store.rows.put(party, now);
        var manager = start(new SimpleClaimsConfig.ChunkLoadingConfig(true, 48, 500));

        manager.evaluateActivity();
        assertTrue(store.rows.containsKey(party), "one miss could be a racy read of the party map");
        manager.evaluateActivity();
        assertFalse(store.rows.containsKey(party));
    }
}
