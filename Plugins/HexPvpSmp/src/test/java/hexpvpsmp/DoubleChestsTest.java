package hexpvpsmp;

import hexpvpsmp.protection.DoubleChests;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Chest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DoubleChestsTest {

    @Test
    void singleChestHasNoPartner() {
        assertNull(DoubleChests.partnerDirection(BlockFace.NORTH, Chest.Type.SINGLE));
        assertNull(DoubleChests.partnerDirection(null, Chest.Type.LEFT));
        assertNull(DoubleChests.partnerDirection(BlockFace.NORTH, null));
    }

    @Test
    void leftHalfPairsClockwiseOfFacing() {
        assertEquals(BlockFace.EAST, DoubleChests.partnerDirection(BlockFace.NORTH, Chest.Type.LEFT));
        assertEquals(BlockFace.SOUTH, DoubleChests.partnerDirection(BlockFace.EAST, Chest.Type.LEFT));
        assertEquals(BlockFace.WEST, DoubleChests.partnerDirection(BlockFace.SOUTH, Chest.Type.LEFT));
        assertEquals(BlockFace.NORTH, DoubleChests.partnerDirection(BlockFace.WEST, Chest.Type.LEFT));
    }

    @Test
    void rightHalfPairsCounterClockwiseOfFacing() {
        assertEquals(BlockFace.WEST, DoubleChests.partnerDirection(BlockFace.NORTH, Chest.Type.RIGHT));
        assertEquals(BlockFace.SOUTH, DoubleChests.partnerDirection(BlockFace.WEST, Chest.Type.RIGHT));
        assertEquals(BlockFace.EAST, DoubleChests.partnerDirection(BlockFace.SOUTH, Chest.Type.RIGHT));
        assertEquals(BlockFace.NORTH, DoubleChests.partnerDirection(BlockFace.EAST, Chest.Type.RIGHT));
    }

    @Test
    void leftAndRightHalvesPointAtEachOther() {
        // A LEFT half facing NORTH pairs EAST; the EAST neighbour is the RIGHT
        // half (same facing) and must point back WEST.
        assertEquals(BlockFace.EAST, DoubleChests.partnerDirection(BlockFace.NORTH, Chest.Type.LEFT));
        assertEquals(BlockFace.WEST, DoubleChests.partnerDirection(BlockFace.NORTH, Chest.Type.RIGHT));
    }
}
