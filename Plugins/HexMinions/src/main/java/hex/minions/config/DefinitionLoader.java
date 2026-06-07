package hex.minions.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DefinitionLoader {
    private final Plugin plugin;

    public DefinitionLoader(Plugin plugin) {
        this.plugin = plugin;
    }

    public Definitions load() {
        return new Definitions(loadTypes(), loadResources(), loadAppearances());
    }

    private Map<String, AppearanceDefinition> loadAppearances() {
        YamlConfiguration yaml = loadYaml("appearance.yml");
        ConfigurationSection root = yaml.getConfigurationSection("appearances");
        Map<String, AppearanceDefinition> result = new LinkedHashMap<>();
        if (root == null) return result;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            ConfigurationSection base = s.getConfigurationSection("base");
            ConfigurationSection equipment = base == null ? null : base.getConfigurationSection("equipment");
            ConfigurationSection label = s.getConfigurationSection("label");
            result.put(id, new AppearanceDefinition(
                    id,
                    base == null || base.getBoolean("small", true),
                    base != null && base.getBoolean("invisible", false),
                    base == null || base.getBoolean("invulnerable", true),
                    base == null || base.getBoolean("no-gravity", true),
                    base == null || base.getBoolean("arms", true),
                    base != null && base.getBoolean("marker", false),
                    base == null || base.getBoolean("equipment-locked", true),
                    ItemSpec.fromConfig(equipment == null ? null : equipment.getConfigurationSection("helmet"), null),
                    ItemSpec.fromConfig(equipment == null ? null : equipment.getConfigurationSection("chestplate"), null),
                    ItemSpec.fromConfig(equipment == null ? null : equipment.getConfigurationSection("leggings"), null),
                    ItemSpec.fromConfig(equipment == null ? null : equipment.getConfigurationSection("boots"), null),
                    ItemSpec.fromConfig(equipment == null ? null : equipment.getConfigurationSection("main-hand"), null),
                    ItemSpec.fromConfig(equipment == null ? null : equipment.getConfigurationSection("off-hand"), null),
                    labelOffsetY(label),
                    label == null ? "<yellow><name></yellow> <gray>Tier <tier></gray>\n<storage_bar>" : label.getString("text", "<yellow><name></yellow> <gray>Tier <tier></gray>\n<storage_bar>")
            ));
        }
        return result;
    }

    private double labelOffsetY(ConfigurationSection label) {
        if (label == null) return 1.65D;
        List<Double> offset = label.getDoubleList("offset");
        return offset.size() >= 2 ? offset.get(1) : 1.65D;
    }

    private Map<String, ResourceDefinition> loadResources() {
        YamlConfiguration yaml = loadYaml("resources.yml");
        ConfigurationSection root = yaml.getConfigurationSection("resources");
        Map<String, ResourceDefinition> result = new LinkedHashMap<>();
        if (root == null) return result;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Material material = parseMaterial(s.getString("material", "STONE"), Material.STONE);
            result.put(id, new ResourceDefinition(
                    id,
                    s.getString("display-name", id),
                    material,
                    s.getInt("custom-model-data", 0),
                    s.getString("collection-id", id),
                    s.getDouble("worth", 0.0),
                    Math.max(1, s.getInt("stack-size", material.getMaxStackSize())),
                    List.copyOf(s.getStringList("tags"))
            ));
        }
        return result;
    }

    private Map<String, MinionTypeDefinition> loadTypes() {
        YamlConfiguration yaml = loadYaml("minion-types.yml");
        ConfigurationSection root = yaml.getConfigurationSection("minion-types");
        Map<String, MinionTypeDefinition> result = new LinkedHashMap<>();
        if (root == null) return result;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Material itemMaterial = parseMaterial(s.getString("item.material", "PLAYER_HEAD"), Material.PLAYER_HEAD);
            List<Material> blocked = new ArrayList<>();
            for (String raw : s.getStringList("placement.blocked-materials")) {
                blocked.add(parseMaterial(raw, Material.AIR));
            }
            List<ResourceDrop> drops = new ArrayList<>();
            for (Map<?, ?> map : s.getMapList("resource-table")) {
                Object resourceValue = map.containsKey("resource") ? map.get("resource") : "";
                String resource = String.valueOf(resourceValue);
                int min = number(map.get("amount-min"), 1).intValue();
                int max = number(map.get("amount-max"), min).intValue();
                double chance = number(map.get("chance"), 1.0).doubleValue();
                drops.add(new ResourceDrop(resource, min, max, chance));
            }
            Map<Integer, TierDefinition> tiers = new LinkedHashMap<>();
            ConfigurationSection tiersSection = s.getConfigurationSection("tiers");
            if (tiersSection != null) {
                for (String key : tiersSection.getKeys(false)) {
                    int tier = parseInt(key);
                    ConfigurationSection ts = tiersSection.getConfigurationSection(key);
                    if (ts == null) continue;
                    Map<String, Long> cost = new LinkedHashMap<>();
                    ConfigurationSection resources = ts.getConfigurationSection("upgrade-cost.resources");
                    if (resources != null) {
                        for (String resource : resources.getKeys(false)) {
                            cost.put(resource, resources.getLong(resource));
                        }
                    }
                    tiers.put(tier, new TierDefinition(tier, ts.getInt("action-time-seconds", 15), ts.getInt("storage", 64), Math.max(1, Math.min(9, ts.getInt("storage-slots", Math.min(9, tier)))), Map.copyOf(cost)));
                }
            }
            if (!tiers.containsKey(1)) {
                tiers.put(1, new TierDefinition(1, 15, 64, 1, Map.of()));
            }
            result.put(id, new MinionTypeDefinition(
                    id,
                    s.getBoolean("enabled", true),
                    s.getString("display-name", id),
                    s.getString("category", "special"),
                    itemMaterial,
                    Math.max(0, s.getInt("item.custom-model-data", 0)),
                    s.getString("item.display-name", id),
                    List.copyOf(s.getStringList("item.lore")),
                    Math.max(0, s.getInt("placement.footprint-radius-blocks", 1)),
                    s.getBoolean("placement.require-solid-ground", true),
                    List.copyOf(blocked),
                    s.getString("appearance", id + "_default"),
                    s.getString("menu", "default_minion"),
                    List.copyOf(drops),
                    Map.copyOf(tiers),
                    Math.max(1, s.getInt("max-tier", tiers.keySet().stream().mapToInt(Integer::intValue).max().orElse(1)))
            ));
        }
        return result;
    }

    private YamlConfiguration loadYaml(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) plugin.saveResource(name, false);
        return YamlConfiguration.loadConfiguration(file);
    }

    private Material parseMaterial(String raw, Material def) {
        if (raw == null) return def;
        Material material = Material.matchMaterial(raw);
        if (material == null) {
            plugin.getLogger().warning("Unknown material '" + raw + "'" + (def == null ? ", skipping item." : ", using " + def.name()));
            return def;
        }
        return material;
    }

    private int parseInt(String raw) {
        try { return Integer.parseInt(raw); } catch (NumberFormatException ignored) { return 1; }
    }

    private Number number(Object value, Number def) {
        return value instanceof Number n ? n : def;
    }
}

