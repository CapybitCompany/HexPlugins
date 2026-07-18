package hexpvpsmp;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicChestTest {

    private ServerMock server;
    private HexPvpSmpPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexPvpSmpPlugin.class);
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void configuredCoordinateIsRecognised() {
        assertTrue(plugin.publicChestRegistry().isPublicChest("world", 0, 65, 0));
        assertFalse(plugin.publicChestRegistry().isPublicChest("world", 1, 65, 0));
    }

    @Test
    void wrongWorldIsNotPublic() {
        assertFalse(plugin.publicChestRegistry().isPublicChest("nether", 0, 65, 0));
    }

    @Test
    void secondCoordinateCanBeAddedForDoubleChestHalves() {
        // A double chest can also be declared as two explicit halves.
        plugin.getConfig().set("worlds.world.public-chests.second.world", "world");
        plugin.getConfig().set("worlds.world.public-chests.second.x", 1);
        plugin.getConfig().set("worlds.world.public-chests.second.y", 65);
        plugin.getConfig().set("worlds.world.public-chests.second.z", 0);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());

        assertTrue(plugin.publicChestRegistry().isPublicChest("world", 0, 65, 0));
        assertTrue(plugin.publicChestRegistry().isPublicChest("world", 1, 65, 0));
    }

    @Test
    void doubleChestPartnerResolvesFromSingleConfiguredHalf() {
        // Configured half is (0,65,0). Its EAST neighbour (1,65,0) should count
        // as public when it is the RIGHT half of the same double chest (facing
        // NORTH -> partner is WEST -> back to the configured half).
        Block partner = server.getWorld("world").getBlockAt(1, 65, 0);
        partner.setType(Material.CHEST);
        BlockData data = partner.getBlockData();
        Assumptions.assumeTrue(data instanceof Chest,
                "MockBukkit must supply directional chest data for this check");
        Chest chest = (Chest) data;
        chest.setFacing(org.bukkit.block.BlockFace.NORTH);
        chest.setType(Chest.Type.RIGHT);
        partner.setBlockData(chest);

        assertTrue(plugin.publicChestRegistry().isPublicChest(partner),
                "the non-configured half of a double chest must resolve to the configured half");
    }
}
