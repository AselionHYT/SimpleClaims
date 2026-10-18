package com.buuz135.simpleclaims.config;

import dev.unnm3d.codeclib.config.CodecFactory;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Aselion config keys survive the codec the plugin loads SimpleClaims.json with. */
class SimpleClaimsConfigCodecTest {

    private final com.hypixel.hytale.codec.Codec<SimpleClaimsConfig> codec = CodecFactory.createClassCodec(SimpleClaimsConfig.class);

    @Test
    void worldClaimLimitsAndExcludedWorldsAreRead() {
        var json = BsonDocument.parse("{\"WorldClaimLimits\": {\"farm\": 4}, "
                + "\"ChunkLoading\": {\"Enabled\": true, \"ExcludedWorlds\": [\"farm\"]}}");
        SimpleClaimsConfig config = codec.decode(json);
        assertEquals(Map.of("farm", 4), Map.copyOf(config.getWorldClaimLimits()));
        assertTrue(config.getChunkLoading().isExcludedWorld("farm"));
        assertFalse(config.getChunkLoading().isExcludedWorld("default"));
    }

    @Test
    void defaultsAreEmptyAndSurviveARoundTrip() {
        SimpleClaimsConfig config = codec.decode(BsonDocument.parse("{}"));
        assertTrue(config.getWorldClaimLimits().isEmpty());
        assertFalse(config.getChunkLoading().isExcludedWorld("farm"));
        var again = codec.decode(codec.encode(config));
        assertTrue(again.getWorldClaimLimits().isEmpty());
    }
}
