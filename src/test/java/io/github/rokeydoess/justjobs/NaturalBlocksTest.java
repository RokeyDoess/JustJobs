package io.github.rokeydoess.justjobs;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NaturalBlocksTest {
    @Test void localPositionsIncludeNegativeHeightsWithoutCollisions() {
        var positions = new java.util.HashSet<Long>();
        for (int y = -64; y < 320; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
            assertTrue(positions.add(NaturalBlocks.position(x, y, z)));
        assertEquals(NaturalBlocks.position(15, -64, 15), NaturalBlocks.position(-1, -64, -1));
    }
}
