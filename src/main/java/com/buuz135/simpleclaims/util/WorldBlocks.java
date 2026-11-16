package com.buuz135.simpleclaims.util;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.SectionReader;

import javax.annotation.Nullable;

/**
 * Block lookups for Hytale 0.7, which removed {@code World.getBlockType} and the block getters of
 * {@code WorldChunk}: blocks are read from the section that holds them. Nothing is loaded; a
 * position whose section is not in memory reads as the empty block. World thread only.
 */
public final class WorldBlocks {

    private WorldBlocks() {
    }

    @Nullable
    public static BlockType blockType(World world, int x, int y, int z) {
        return BlockType.getAssetMap().getAsset(new SectionReader(world.getChunkStore()).getBlock(x, y, z));
    }
}
