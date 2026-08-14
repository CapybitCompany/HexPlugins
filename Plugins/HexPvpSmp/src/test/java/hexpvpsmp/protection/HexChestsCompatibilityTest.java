package hexpvpsmp.protection;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HexChestsCompatibilityTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void onlyHexChestsRewardChestIdsAreSilent() {
        assertTrue(HexChestsCompatibility.isSilentChestId("afk"));
        assertTrue(HexChestsCompatibility.isSilentChestId("EPIC"));
        assertTrue(HexChestsCompatibility.isSilentChestId(" premium "));
        assertFalse(HexChestsCompatibility.isSilentChestId("daily"));
        assertFalse(HexChestsCompatibility.isSilentChestId(null));
    }

    @Test
    void requiresShulkerBlock() {
        Block block = blockAt(Material.RED_SHULKER_BOX);
        assertTrue(HexChestsCompatibility.isShulker(block));

        block.setType(Material.CHEST);
        assertFalse(HexChestsCompatibility.isShulker(block));
    }

    @Test
    void detectsSilentChestThroughHexChestsServiceShape() {
        Block block = blockAt(Material.PURPLE_SHULKER_BOX);
        assertTrue(HexChestsCompatibility.isHandledRewardShulker(
                new FakeChestService(Optional.of(new FakeChest("epic"))), block));
    }

    @Test
    void ignoresUnknownHexChestsChestIds() {
        Block block = blockAt(Material.YELLOW_SHULKER_BOX);
        assertFalse(HexChestsCompatibility.isHandledRewardShulker(
                new FakeChestService(Optional.of(new FakeChest("other"))), block));
    }

    @Test
    void ignoresEmptyHexChestsResult() {
        Block block = blockAt(Material.YELLOW_SHULKER_BOX);
        assertFalse(HexChestsCompatibility.isHandledRewardShulker(
                new FakeChestService(Optional.empty()), block));
    }

    private Block blockAt(Material material) {
        Block block = server.getWorld("world").getBlockAt(10, 64, 10);
        block.setType(material);
        return block;
    }

    public record FakeChest(String id) {
    }

    public static final class FakeChestService {
        private final Optional<?> result;

        FakeChestService(Optional<?> result) {
            this.result = result;
        }

        public Optional<?> chestAt(Block block) {
            return result;
        }
    }
}
