package hexcustomitems;

import hexcustomitems.config.HexCustomItemsConfigLoader;
import hexcustomitems.service.CooldownStore;
import hexcustomitems.service.CustomItemRegistryService;
import hexcustomitems.support.PluginTestBase;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReloadTest extends PluginTestBase {

    private File configFile() {
        return new File(plugin.getDataFolder(), "config.yml");
    }

    /** Ändert config.yml auf Platte, weil reloadConfig() neu von der Datei liest. */
    private void editConfig(java.util.function.Consumer<YamlConfiguration> mutation) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile());
        mutation.accept(yaml);
        try {
            yaml.save(configFile());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private int countManaged(PlayerMock player, String itemId) {
        CustomItemRegistryService registry = new CustomItemRegistryService(plugin, new HexCustomItemsConfigLoader(plugin).load());
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && itemId.equals(registry.resolveItemId(stack))) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    @Test
    void reloadSwitchesCooldownFileAndKeepsActiveCooldowns() {
        UUID id = UUID.randomUUID();
        plugin.cooldownService().apply(id, "hex:phoenix_heart", 300); // aktiver In-Memory-Cooldown

        editConfig(yaml -> yaml.set("cooldowns.file", "custom-cooldowns.yml"));
        PlayerMock admin = server.addPlayer();
        admin.setOp(true);
        admin.performCommand("hexcustomitem reload");

        // Cooldown darf durch Reload nicht verloren gehen.
        assertTrue(plugin.cooldownService().remainingSeconds(id, "hex:phoenix_heart") > 0,
                "Aktiver Cooldown sollte den Reload überleben");

        // onDisable muss die NEUE Datei nutzen.
        server.getPluginManager().disablePlugin(plugin);
        assertTrue(new File(plugin.getDataFolder(), "custom-cooldowns.yml").exists(),
                "onDisable sollte die geänderte cooldowns.file schreiben");

        Map<UUID, Map<String, Long>> saved = new CooldownStore(plugin, "custom-cooldowns.yml").read();
        assertTrue(saved.containsKey(id), "Cooldown sollte in der neuen Datei stehen");
    }

    @Test
    void giveCommandUsesCustomItemId() {
        PlayerMock player = server.addPlayer();
        player.setOp(true);

        player.performCommand("hexcustomitem hex:coin_3 " + player.getName() + " 1");
        assertEquals(1, countManaged(player, "hex:coin_3"));
    }
}
