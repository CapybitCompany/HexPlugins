package hexpvpsmp;

import hexpvpsmp.redline.BarrierService;
import hexpvpsmp.region.Cuboid;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure geometry of the client-side combat entry barrier. */
class BarrierGeometryTest {

    private final Cuboid region = new Cuboid(-100, -100, 100, 100);

    @Test
    void wallRunsAlongZWhenNearestEdgeIsX() {
        // Player just outside the +X wall (x=101), so the wall sits at x=100.
        List<int[]> blocks = BarrierService.computeBarrierBlocks(region, 101, 64, 0, 2, 3);
        // radius 2 -> 5 columns, height 3 -> 15 blocks
        assertEquals(15, blocks.size());
        for (int[] b : blocks) {
            assertEquals(100, b[0], "wall must be pinned to the maxX edge");
            assertTrue(b[2] >= -2 && b[2] <= 2, "z spans player +/- radius");
            assertTrue(b[1] >= 64 && b[1] < 67, "y spans feet..feet+height");
        }
    }

    @Test
    void wallRunsAlongXWhenNearestEdgeIsZ() {
        // Player just outside the -Z wall (z=-101), wall sits at z=-100.
        List<int[]> blocks = BarrierService.computeBarrierBlocks(region, 0, 70, -101, 1, 2);
        assertEquals(6, blocks.size()); // 3 columns * 2 height
        for (int[] b : blocks) {
            assertEquals(-100, b[2], "wall must be pinned to the minZ edge");
            assertTrue(b[0] >= -1 && b[0] <= 1, "x spans player +/- radius");
        }
    }

    @Test
    void nullRegionYieldsNoBlocks() {
        assertTrue(BarrierService.computeBarrierBlocks(null, 0, 0, 0, 4, 3).isEmpty());
    }
}
