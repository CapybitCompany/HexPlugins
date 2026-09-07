package hex.parkour.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuboidRegionTest {
    @Test
    void normalizesPositions() {
        CuboidRegion region = new CuboidRegion(new BlockVector(10, 5, -2), new BlockVector(-4, 12, 8));

        assertEquals(-4, region.minX());
        assertEquals(10, region.maxX());
        assertEquals(5, region.minY());
        assertEquals(12, region.maxY());
        assertEquals(-2, region.minZ());
        assertEquals(8, region.maxZ());
    }

    @Test
    void standingLocationMatchesTriggerBlockUnderFeet() {
        CuboidRegion region = new CuboidRegion(new BlockVector(-3, -45, 17), new BlockVector(-3, -45, 18));

        assertTrue(region.containsStandingBlock(-3, -44, 18));
    }

    @Test
    void extendedBelowCheckMatchesOneMoreBlockDown() {
        CuboidRegion region = new CuboidRegion(new BlockVector(22, -45, 17), new BlockVector(22, -45, 18));

        assertTrue(region.containsBlockBelow(22, -43, 18, 2));
    }

    @Test
    void nearCheckMatchesPointOneBlockAbovePlayerFeet() {
        CuboidRegion region = new CuboidRegion(new BlockVector(22, -45, 17), new BlockVector(22, -45, 18));

        assertTrue(region.containsBlockNear(22, -46, 18, 1, 3));
    }
}
