package com.buuz135.simpleclaims.chunkload;

import com.buuz135.simpleclaims.config.SimpleClaimsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChunkLoadManagerTest {

    /** Records every call; a disabled feature must leave it untouched. */
    static class RecordingStore implements ChunkLoadStore {
        final List<String> calls = new ArrayList<>();

        @Override public void ensureSchema() { calls.add("ensureSchema"); }
        @Override public Map<UUID, Enabled> loadEnabledParties() { calls.add("loadEnabledParties"); return Map.of(); }
        @Override public Map<UUID, Set<String>> loadSelections() { calls.add("loadSelections"); return Map.of(); }
        @Override public void setEnabled(UUID partyId, boolean enabled, String byName, String date) { calls.add("setEnabled"); }
        @Override public void setSelected(UUID partyId, String dimension, int chunkX, int chunkZ, boolean selected) { calls.add("setSelected"); }
        @Override public void clearSelection(UUID partyId) { calls.add("clearSelection"); }
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

    @AfterEach
    void stop() {
        ChunkLoadManager.stop();
    }

    @Test
    void defaultConfigHasChunkLoadingOff() {
        var config = new SimpleClaimsConfig().getChunkLoading();
        assertFalse(config.isEnabled());
        assertTrue(config.isRequiresAdminApproval());
        assertEquals(9, config.getMaxChunksPerParty());
    }

    @Test
    void withDefaultConfigNothingIsScheduledOrWritten() {
        var store = new RecordingStore();
        var scheduler = new RecordingScheduler();
        List<String> log = new ArrayList<>();

        var manager = ChunkLoadManager.start(new SimpleClaimsConfig().getChunkLoading(), store, scheduler, log::add);

        assertNull(manager);
        assertNull(ChunkLoadManager.get(), "commands must see chunk loading as disabled");
        assertEquals(0, scheduler.scheduled, "no keep-alive loop may be scheduled");
        assertEquals(List.of(), store.calls, "the database must not be touched");
        assertEquals(List.of("chunk loading: off"), log);
    }

    @Test
    void enabledConfigSchedulesOneLoopAndStopCancelsIt() {
        var store = new RecordingStore();
        var scheduler = new RecordingScheduler();
        List<String> log = new ArrayList<>();

        var manager = ChunkLoadManager.start(new SimpleClaimsConfig.ChunkLoadingConfig(true, 9, 64, true), store, scheduler, log::add);

        assertNotNull(manager);
        assertSame(manager, ChunkLoadManager.get());
        assertEquals(1, scheduler.scheduled);
        assertTrue(store.calls.contains("ensureSchema"));
        assertEquals(1, log.size());
        assertTrue(log.get(0).startsWith("chunk loading: on"), log.get(0));

        ChunkLoadManager.stop();
        assertEquals(1, scheduler.cancelled);
        assertNull(ChunkLoadManager.get());
    }

    @Test
    void selectionStopsAtThePartyLimit() {
        var store = new RecordingStore();
        var manager = ChunkLoadManager.start(new SimpleClaimsConfig.ChunkLoadingConfig(true, 2, 64, true), store, new RecordingScheduler(), s -> {});
        var party = UUID.randomUUID();
        assertEquals(ChunkLoadManager.SelectResult.SELECTED, manager.toggleSelected(party, "default", 0, 0));
        assertEquals(ChunkLoadManager.SelectResult.SELECTED, manager.toggleSelected(party, "default", 0, 1));
        assertEquals(ChunkLoadManager.SelectResult.LIMIT_REACHED, manager.toggleSelected(party, "default", 0, 2));
        assertEquals(ChunkLoadManager.SelectResult.UNSELECTED, manager.toggleSelected(party, "default", 0, 0));
        assertEquals(Set.of(ChunkLoadPlan.chunkId("default", 0, 1)), manager.getSelection(party));
    }
}
