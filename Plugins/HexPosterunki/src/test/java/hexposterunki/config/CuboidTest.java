package hexposterunki.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuboidTest {

    private final Cuboid region = Cuboid.of("world", new BlockVec(60, 10, -20), new BlockVec(10, 40, 20));

    @Test
    void cornersAreNormalised() {
        assertEquals(10, region.minX());
        assertEquals(60, region.maxX());
        assertEquals(10, region.minY());
        assertEquals(40, region.maxY());
        assertEquals(-20, region.minZ());
        assertEquals(20, region.maxZ());
    }

    @Test
    void containsIsInclusiveOnEveryBoundary() {
        assertTrue(region.contains("world", 10, 10, -20));
        assertTrue(region.contains("world", 60, 40, 20));
        assertFalse(region.contains("world", 9, 10, 0));
        assertFalse(region.contains("world", 10, 41, 0));
        assertFalse(region.contains("world", 10, 10, 21));
    }

    @Test
    void anotherWorldIsNeverInside() {
        assertFalse(region.contains("world_nether", 30, 20, 0));
    }

    @Test
    void volumeCountsBlocksInclusively() {
        Cuboid unit = Cuboid.of("world", new BlockVec(0, 0, 0), new BlockVec(0, 0, 0));
        assertEquals(1L, unit.volume());
        assertEquals(51L * 31L * 41L, region.volume());
    }

    @Test
    void chunkRangeCoversTheWholeRegion() {
        assertEquals(0, region.chunkMinX());
        assertEquals(3, region.chunkMaxX());
        assertEquals(-2, region.chunkMinZ());
        assertEquals(1, region.chunkMaxZ());
        assertEquals(16, region.chunkCount());
    }

    @Test
    void horizontalDistanceIsZeroInsideAndGrowsOutside() {
        assertEquals(0.0D, region.horizontalDistanceSquared(30.0D, 0.0D));
        assertEquals(100.0D, region.horizontalDistanceSquared(0.0D, 0.0D), 1e-9);
    }

    @Test
    void pointsAndVectorsUseTheSameContainment() {
        assertTrue(region.contains(new BlockVec(30, 20, 0)));
        assertTrue(region.contains(new PointDef(30.5D, 20.0D, 0.5D, 0F, 0F)));
        assertFalse(region.contains(new PointDef(-5.0D, 20.0D, 0.5D, 0F, 0F)));
    }

    @Test
    void coordinatesParseFromCsv() {
        assertEquals(new BlockVec(1, 2, 3), BlockVec.parse("1,2,3"));
        assertEquals(new BlockVec(-1, 2, -3), BlockVec.parse(" -1 , 2 , -3 "));

        PointDef withFacing = PointDef.parse("1.5,2.5,3.5,90,-10");
        assertEquals(1.5D, withFacing.x());
        assertEquals(90F, withFacing.yaw());
        assertEquals(-10F, withFacing.pitch());
    }
}
