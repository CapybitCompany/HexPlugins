package hexpvpsmp;

import org.bukkit.Location;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HungerTest {

    private ServerMock server;
    private HexPvpSmpPlugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexPvpSmpPlugin.class);
        player = server.addPlayer("Eater");
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private boolean fireHungerLoss(Location at, int newLevel) {
        player.teleport(at);
        player.setFoodLevel(20);
        FoodLevelChangeEvent event = new FoodLevelChangeEvent(player, newLevel);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    @Test
    void hungerLossBlockedInSpawn() {
        assertTrue(fireHungerLoss(new Location(server.getWorld("world"), 0, 64, 0), 15),
                "hunger loss in spawn must be blocked");
    }

    @Test
    void hungerLossAllowedInNoBuild() {
        assertFalse(fireHungerLoss(new Location(server.getWorld("world"), 0, 64, 150), 15),
                "hunger loss in no-build zone stays normal");
    }

    @Test
    void hungerLossAllowedInWilderness() {
        assertFalse(fireHungerLoss(new Location(server.getWorld("world"), 500, 64, 500), 15),
                "hunger loss in wilderness stays normal");
    }

    @Test
    void eatingIsNeverBlockedInSpawn() {
        player.teleport(new Location(server.getWorld("world"), 0, 64, 0));
        player.setFoodLevel(10);
        FoodLevelChangeEvent event = new FoodLevelChangeEvent(player, 18); // increase
        server.getPluginManager().callEvent(event);
        assertFalse(event.isCancelled(), "eating (food increase) must not be blocked");
    }

    @Test
    void hungerLossAllowedInSpawnWhenDisabledByConfig() {
        plugin.getConfig().set("worlds.world.spawn.disable-hunger-loss", false);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());
        assertFalse(fireHungerLoss(new Location(server.getWorld("world"), 0, 64, 0), 15),
                "with disable-hunger-loss=false hunger works normally in spawn");
    }
}
