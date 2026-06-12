package hex.minions.machine;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;

public record MachineEnergyDefinition(
        boolean enabled,
        boolean generator,
        int bufferCapacity,
        int euPerSecond,
        int transferPerSecond,
        Map<String, Integer> fuelEu,
        Map<String, Integer> fuelBurnSeconds,
        Map<String, Integer> fallbackFuelEu,
        Map<String, Integer> fallbackFuelBurnSeconds,
        int batterySlot,
        int batteryExtraCapacity
) {
    public static MachineEnergyDefinition disabled() {
        return new MachineEnergyDefinition(false, false, 0, 0, 0, Map.of(), Map.of(), Map.of(), Map.of(), 40, 0);
    }

    public static MachineEnergyDefinition fromConfig(ConfigurationSection section) {
        if (section == null || !section.getBoolean("enabled", false)) return disabled();
        return new MachineEnergyDefinition(
                true,
                section.getBoolean("generator", false),
                Math.max(0, section.getInt("buffer-capacity", 500)),
                Math.max(0, section.getInt("eu-per-second", 0)),
                Math.max(0, section.getInt("transfer-per-second", 250)),
                loadIntMap(section.getConfigurationSection("fuel-eu")),
                loadBurnMap(section.getConfigurationSection("fuel-burn-seconds")),
                loadIntMap(section.getConfigurationSection("fallback-fuel-eu")),
                loadBurnMap(section.getConfigurationSection("fallback-fuel-burn-seconds")),
                Math.max(0, section.getInt("battery-slot", 40)),
                Math.max(0, section.getInt("battery-extra-capacity", 0))
        );
    }

    public int fuelEu(String idOrMaterial) {
        if (idOrMaterial == null) return 0;
        return fuelEu.getOrDefault(idOrMaterial.toLowerCase(java.util.Locale.ROOT), 0);
    }

    public int fuelBurnSeconds(String idOrMaterial, int fallback) {
        if (idOrMaterial == null) return fallback;
        return fuelBurnSeconds.getOrDefault(idOrMaterial.toLowerCase(java.util.Locale.ROOT), fallback);
    }

    public int fallbackFuelEu(String idOrMaterial) {
        if (idOrMaterial == null) return 0;
        return fallbackFuelEu.getOrDefault(idOrMaterial.toLowerCase(java.util.Locale.ROOT), 0);
    }

    public int fallbackFuelBurnSeconds(String idOrMaterial, int fallback) {
        if (idOrMaterial == null) return fallback;
        return fallbackFuelBurnSeconds.getOrDefault(idOrMaterial.toLowerCase(java.util.Locale.ROOT), fallback);
    }

    private static Map<String, Integer> loadIntMap(ConfigurationSection section) {
        if (section == null) return Map.of();
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            result.put(key.toLowerCase(java.util.Locale.ROOT), Math.max(0, section.getInt(key, 0)));
            Material material = Material.matchMaterial(key);
            if (material != null) result.put(material.name().toLowerCase(java.util.Locale.ROOT), Math.max(0, section.getInt(key, 0)));
        }
        return Map.copyOf(result);
    }

    private static Map<String, Integer> loadBurnMap(ConfigurationSection section) {
        if (section == null) return Map.of();
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) result.put(key.toLowerCase(java.util.Locale.ROOT), Math.max(1, section.getInt(key, 8)));
        return Map.copyOf(result);
    }
}
