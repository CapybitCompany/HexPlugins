package hex.minions.listener;

import hex.minions.service.MinionService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class BioFuelListener implements Listener {
    private final MinionService minions;
    private final Map<String, Integer> furnaceBurnTicksBySpecialItemId;

    public BioFuelListener(Plugin plugin, MinionService minions) {
        this.minions = minions;
        this.furnaceBurnTicksBySpecialItemId = loadFurnaceFuels(plugin);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFurnaceBurn(FurnaceBurnEvent event) {
        ItemStack fuel = event.getFuel();
        if (fuel == null || fuel.getType().isAir()) return;
        String specialId = minions.specialItems().readSpecialItemId(fuel).orElse("").toLowerCase(Locale.ROOT);
        int burnTicks = furnaceBurnTicksBySpecialItemId.getOrDefault(specialId, 0);
        if (burnTicks <= 0) return;
        event.setBurnTime(burnTicks);
        event.setBurning(true);
    }

    private Map<String, Integer> loadFurnaceFuels(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "special-items.yml");
        if (!file.exists()) plugin.saveResource("special-items.yml", false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("custom-fuels");
        if (root == null) return Map.of();
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null || !section.getBoolean("enabled", true)) continue;
            String specialItemId = section.getString("special-item", id);
            int burnTicks = Math.max(0, section.getInt("furnace-burn-time-ticks", 0));
            if (specialItemId != null && !specialItemId.isBlank() && burnTicks > 0) {
                result.put(specialItemId.toLowerCase(Locale.ROOT), burnTicks);
            }
        }
        return Map.copyOf(result);
    }
}
