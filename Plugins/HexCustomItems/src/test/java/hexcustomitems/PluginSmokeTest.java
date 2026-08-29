package hexcustomitems;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.config.HexCustomItemsConfigLoader;
import hexcustomitems.service.CooldownStore;
import hexcustomitems.service.CustomItemRegistryService;
import hexcustomitems.support.TestConfig;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginSmokeTest {

    private ServerMock server;
    private HexCustomItemsPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(HexCustomItemsPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void pluginLoads() {
        assertNotNull(plugin);
        assertNotNull(server.getPluginManager().getPlugin("HexCustomItems"));
    }

    @Test
    void adminPanelCommandOpensMenu() {
        PlayerMock player = server.addPlayer();
        player.setOp(true);
        player.performCommand("hexcustomitem adminpanel");
        assertTrue(player.getOpenInventory().getTopInventory().getSize() > 0);
    }

    @Test
    void giveCommandDeliversManagedItem() {
        PlayerMock player = server.addPlayer();
        player.setOp(true);
        player.performCommand("hexcustomitem hex:red_heart " + player.getName() + " 1");

        CustomItemRegistryService registry = registry();
        boolean hasManaged = false;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && "hex:red_heart".equals(registry.resolveItemId(stack))) {
                hasManaged = true;
            }
        }
        assertTrue(hasManaged, "Spieler sollte red_heart erhalten");
    }

    /**
     * Persistenz-Pfad wie in onDisable/onEnable, mit echten Klassen: Nutzung -> snapshot -> write -> read.
     * (MockBukkit liefert bei synthetisch konstruiertem PlayerInteractEvent den internen
     * Plugin-Cooldown nicht zurück, daher wird der Use-Flow hier direkt über den Service ausgelöst.)
     */
    @Test
    void cooldownPersistenceRoundTrips() {
        PlayerMock player = server.addPlayer();
        plugin.cooldownService().apply(player.getUniqueId(), "hex:phoenix_heart", 300);

        CooldownStore store = new CooldownStore(plugin, "cooldowns.yml"); // wie onDisable
        store.write(plugin.cooldownService().snapshot());

        Map<UUID, Map<String, Long>> reloaded = store.read();           // wie onEnable
        assertTrue(reloaded.containsKey(player.getUniqueId()), "Cooldown sollte gespeichert sein");
        assertTrue(reloaded.get(player.getUniqueId()).containsKey("hex:phoenix_heart"));
    }

    @Test
    void onDisableRunsCooldownPersistence() {
        server.getPluginManager().disablePlugin(plugin);
        assertTrue(new File(plugin.getDataFolder(), "cooldowns.yml").exists(),
                "onDisable sollte den Persistenz-Pfad ausführen (persist=true)");
    }

    private CustomItemRegistryService registry() {
        HexCustomItemsConfig config = new HexCustomItemsConfigLoader(plugin).load();
        return new CustomItemRegistryService(plugin, config);
    }
}
