package hex.minions.diagnostics;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.Optional;

/** Static future-safety policy for placeable custom items, including disabled content. */
public final class PlaceableItemPolicy {
    public enum Strategy { CABLE, URANIUM_CHEST, ROBOT, MACHINE_OR_STATION, NONE }

    public record Resolution(Strategy strategy, Material physicalBlock) {
        public boolean supported() {
            return strategy == Strategy.CABLE
                    || (strategy != Strategy.NONE && physicalBlock != null && physicalBlock.isBlock());
        }
    }

    private PlaceableItemPolicy() { }

    public static Strategy classify(String blockKind) {
        String kind = normalize(blockKind);
        if (kind.startsWith("CABLE_")) return Strategy.CABLE;
        if ("IRON_URANIUM_CHEST".equals(kind)) return Strategy.URANIUM_CHEST;
        if (kind.startsWith("ROBOT_")) return Strategy.ROBOT;
        if (!kind.isBlank()) return Strategy.MACHINE_OR_STATION;
        return Strategy.NONE;
    }

    public static Resolution resolve(String blockKind, ConfigurationSection stationRoot, ConfigurationSection machineRoot) {
        Strategy strategy = classify(blockKind);
        String kind = normalize(blockKind);
        if (strategy == Strategy.CABLE) return new Resolution(strategy, Material.AIR); // cable has virtual physical segments
        if (strategy == Strategy.URANIUM_CHEST) return new Resolution(strategy, Material.CHEST);

        Material stationBlock = configuredBlock(stationRoot == null ? null : stationRoot.getConfigurationSection(kind), "block").orElse(null);
        if (strategy == Strategy.ROBOT) return new Resolution(strategy, stationBlock);

        if (stationBlock != null) return new Resolution(strategy, stationBlock);
        if (machineRoot != null) {
            for (String machineId : machineRoot.getKeys(false)) {
                ConfigurationSection machine = machineRoot.getConfigurationSection(machineId);
                if (machine == null) continue;
                String station = normalize(machine.getString("station", machine.getString("station-id", "")));
                String type = normalize(machine.getString("type", ""));
                String id = normalize(machineId);
                if (!kind.equals(station) && !kind.equals(type) && !kind.equals(id)) continue;
                Material block = configuredBlock(machine, "base-block").orElse(null);
                if (block != null) return new Resolution(strategy, block);
            }
        }
        return new Resolution(Strategy.NONE, null);
    }

    public static Optional<Material> configuredStationBlock(String stationId, ConfigurationSection stationRoot) {
        if (stationRoot == null) return Optional.empty();
        return configuredBlock(stationRoot.getConfigurationSection(normalize(stationId)), "block");
    }

    private static Optional<Material> configuredBlock(ConfigurationSection section, String key) {
        if (section == null) return Optional.empty();
        Material material = Material.matchMaterial(section.getString(key, ""));
        return material != null && material.isBlock() ? Optional.of(material) : Optional.empty();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
