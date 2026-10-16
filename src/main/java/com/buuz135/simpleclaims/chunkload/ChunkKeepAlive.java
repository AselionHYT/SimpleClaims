package com.buuz135.simpleclaims.chunkload;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;

import java.util.Set;

/**
 * Keeps chunk columns of a world ticking with no player near. Same approach as Orbis Industries' demo/Chunks.java:
 * the server takes a chunk no player is near out of ticking once its active timer has run down, so the timer is what
 * is kept up. Since server 0.7.0 a column and each of its 32-block sections tick on their own (block entities and
 * entities live in the sections), so the column and every section are kept up.
 */
final class ChunkKeepAlive {

    private ChunkKeepAlive() {
    }

    /**
     * Resets the timers of the given columns and their sections, and asks for those that are not loaded or not
     * ticking. Runs on the world's thread.
     *
     * @param ticking filled with the keys of the columns found ticking with all their sections
     */
    static void keepUp(World world, Set<Long> keys, Set<Long> ticking) {
        var chunks = world.getChunkStore();
        var store = chunks.getStore();
        var nonTicking = ChunkStore.REGISTRY.getNonTickingComponentType();
        for (long key : keys) {
            var ref = chunks.getChunkReference(key);
            var chunk = ref == null || !ref.isValid() ? null : store.getComponent(ref, WorldChunk.getComponentType());
            if (chunk != null) {
                chunk.resetActiveTimer();
                chunk.resetKeepAlive();
            }
            if (chunk == null || store.getArchetype(ref).contains(nonTicking)) {
                // Not loaded (e.g. after a restart) or already asleep: load it and make it tick again.
                chunks.getChunkReferenceAsync(key, GetChunkFlags.SET_TICKING);
                continue;
            }
            var column = store.getComponent(ref, ChunkColumn.getComponentType());
            boolean asleep = column == null;
            for (int y = 0; !asleep && y < ChunkUtil.HEIGHT_SECTIONS; y++) {
                var sectionRef = column.getSection(y);
                var section = sectionRef == null || !sectionRef.isValid() ? null : store.getComponent(sectionRef, ChunkSection.getComponentType());
                if (section == null || store.getArchetype(sectionRef).contains(nonTicking)) {
                    asleep = true;
                } else {
                    section.resetActiveTimer();
                    section.resetKeepAlive();
                }
            }
            if (asleep) {
                for (int y = 0; y < ChunkUtil.HEIGHT_SECTIONS; y++) {
                    chunks.getChunkSectionReferenceAsync(ChunkUtil.xOfChunkIndex(key), y, ChunkUtil.zOfChunkIndex(key), GetChunkFlags.SET_TICKING);
                }
            } else {
                ticking.add(key);
            }
        }
    }
}
