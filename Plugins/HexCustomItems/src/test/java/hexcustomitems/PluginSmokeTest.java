package hexcustomitems;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.config.HexCustomItemsConfigLoader;
import hexcustomitems.service.ActionExecutor;
import hexcustomitems.service.CooldownService;
import hexcustomitems.service.CooldownStore;
import hexcustomitems.service.CustomItemRegistryService;
import hexcustomitems.service.CustomItemUseService;
import hexcustomitems.service.MessageService;
import hexcustomitems.service.UsePolicyService;
import hexcustomitems.support.TestConfig;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.util.Map;
import java.util.Optional;
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
    void listCommandNamesItems() {
        PlayerMock player = server.addPlayer();
        player.setOp(true);
        player.performCommand("hexcustomitems list");
        assertTrue(TestConfig.plain(player.nextComponentMessage()).contains("jump_potion"));
    }

    @Test
    void giveCommandDeliversManagedItem() {
        PlayerMock player = server.addPlayer();
        player.setOp(true);
        player.performCommand("hexcustomitems give jump_potion " + player.getName());

        CustomItemRegistryService registry = registry();
        boolean hasManaged = false;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && "jump_potion".equals(registry.resolveItemId(stack))) {
                hasManaged = true;
            }
        }
        assertTrue(hasManaged, "Spieler sollte jump_potion erhalten");
    }

    /**
     * Persistenz-Pfad wie in onDisable/onEnable, mit echten Klassen: Nutzung -> snapshot -> write -> read.
     * (MockBukkit liefert bei synthetisch konstruiertem PlayerInteractEvent den internen
     * Plugin-Cooldown nicht zurück, daher wird der Use-Flow hier direkt über den Service ausgelöst.)
     */
    @Test
    void cooldownPersistenceRoundTrips() {
        PlayerMock player = server.addPlayer();
        HexCustomItemsConfig config = new HexCustomItemsConfigLoader(plugin).load();
        CustomItemRegistryService registry = new CustomItemRegistryService(plugin, config);
        CooldownService cooldowns = new CooldownService();
        MessageService messages = new MessageService(() -> config);
        UsePolicyService policy = new UsePolicyService(() -> config, location -> Optional.empty());
        ActionExecutor executor = new ActionExecutor(plugin, messages);
        CustomItemUseService useService = new CustomItemUseService(registry, cooldowns, policy, executor, messages);

        ItemStack item = registry.createItem(registry.findById("jump_potion"), 1);
        player.getInventory().setItemInMainHand(item);
        useService.tryUseItem(player, EquipmentSlot.HAND, item); // jump_potion hat cooldown-seconds: 5

        CooldownStore store = new CooldownStore(plugin, "cooldowns.yml"); // wie onDisable
        store.write(cooldowns.snapshot());

        Map<UUID, Map<String, Long>> reloaded = store.read();           // wie onEnable
        assertTrue(reloaded.containsKey(player.getUniqueId()), "Cooldown sollte gespeichert sein");
        assertTrue(reloaded.get(player.getUniqueId()).containsKey("jump_potion"));
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
