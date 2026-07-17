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
import java.util.Locale;

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


    private UpgradeRequirements loadUpgradeRequirements(ConfigurationSection tierSection) {
        Map<String, Long> collections = new LinkedHashMap<>();
        ConfigurationSection modernCollections = tierSection.getConfigurationSection("upgrade-requirements.collections");
        if (modernCollections != null) {
            for (Map.Entry<String, Object> entry : modernCollections.getValues(true).entrySet()) {
                if (entry.getValue() instanceof ConfigurationSection) continue;
                long amount = number(entry.getValue(), 0L).longValue();
                if (amount > 0L) collections.put(entry.getKey(), amount);
            }
        }

        // Backward compatibility: old upgrade-cost.resources entries are now treated as
        // town collection requirements. Resource ids are resolved through resources.yml
        // so e.g. cobblestone -> mining.cobblestone.
        ConfigurationSection legacyResources = tierSection.getConfigurationSection("upgrade-cost.resources");
        if (legacyResources != null) {
            Map<String, ResourceDefinition> resources = loadResources();
            for (Map.Entry<String, Object> entry : legacyResources.getValues(true).entrySet()) {
                if (entry.getValue() instanceof ConfigurationSection) continue;
                long amount = number(entry.getValue(), 0L).longValue();
                if (amount <= 0L) continue;
                ResourceDefinition resource = resources.get(entry.getKey());
                String collectionId = resource == null ? entry.getKey() : resource.collectionId();
                collections.merge(collectionId, amount, Long::sum);
            }
        }

        List<ItemRequirement> items = new ArrayList<>();
        ConfigurationSection itemSection = tierSection.getConfigurationSection("upgrade-requirements.items");
        if (itemSection == null) itemSection = tierSection.getConfigurationSection("upgrade-cost.items");
        if (itemSection != null) {
            for (String id : itemSection.getKeys(false)) {
                ConfigurationSection section = itemSection.getConfigurationSection(id);
                if (section != null) items.add(ItemRequirement.fromConfig(id.toLowerCase(Locale.ROOT), section));
            }
        }
        return new UpgradeRequirements(Map.copyOf(collections), List.copyOf(items));
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
                    List.copyOf(s.getStringList("tags")),
                    s.getBoolean("compression.enabled", s.getBoolean("compression", false)),
                    s.getBoolean("compression.block-convertible", s.getStringList("tags").contains("block")),
                    parseMaterial(s.getString("compression.compressed-material", material.name()), material)
            ));
        }
        for (ResourceDefinition resource : new ArrayList<>(result.values())) {
            if (!resource.compressionEnabled() || !resource.blockConvertible()) continue;
            String compressedId = "compressed_" + resource.id().toLowerCase(Locale.ROOT);
            if (result.containsKey(compressedId)) continue;
            result.put(compressedId, new ResourceDefinition(
                    compressedId,
                    "<aqua>Skompresowany " + resource.displayName() + "</aqua>",
                    resource.compressedMaterial(),
                    0,
                    resource.collectionId(),
                    resource.worth() * 160.0D,
                    64,
                    List.of("compressed", "special"),
                    false,
                    false,
                    resource.compressedMaterial()
            ));
        }
        return result;
    }

    private Map<String, MinionTypeDefinition> loadTypes() {
        YamlConfiguration yaml = loadYaml("minion-types.yml");
        ConfigurationSection root = yaml.getConfigurationSection("minion-types");
        Map<String, MinionTypeDefinition> result = new LinkedHashMap<>();
        List<Integer> defaultSupportedBoosters = yaml.getIntegerList("boosters.default-supported-tiers");
        if (defaultSupportedBoosters.isEmpty()) defaultSupportedBoosters = List.of(1);
        if (root == null) return result;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            ItemSpec itemSpec = ItemSpec.fromConfig(s.getConfigurationSection("item"), Material.PLAYER_HEAD);
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
                boolean specialDrop = booleanValue(map.get("special-drop"), booleanValue(map.get("special-item"), chance <= 0.01D));
                double perTierBonus = 0.0D;
                int scalingFromTier = 1;
                String upgradeItem = "";
                double upgradeBonus = 0.0D;
                Object scaling = map.get("special-drop-scaling");
                if (scaling instanceof Map<?, ?> scalingMap) {
                    perTierBonus = number(scalingMap.get("per-tier-bonus"), 0.0D).doubleValue();
                    scalingFromTier = number(scalingMap.get("from-tier"), 1).intValue();
                    Object rawUpgradeItem = scalingMap.get("upgrade-item");
                    upgradeItem = rawUpgradeItem == null ? "" : String.valueOf(rawUpgradeItem);
                    upgradeBonus = number(scalingMap.get("upgrade-bonus"), 0.0D).doubleValue();
                }
                drops.add(new ResourceDrop(resource, min, max, chance, specialDrop, perTierBonus, Math.max(1, scalingFromTier), upgradeItem, upgradeBonus));
            }
            Map<Integer, TierDefinition> tiers = new LinkedHashMap<>();
            ConfigurationSection tiersSection = s.getConfigurationSection("tiers");
            if (tiersSection != null) {
                for (String key : tiersSection.getKeys(false)) {
                    int tier = parseInt(key);
                    ConfigurationSection ts = tiersSection.getConfigurationSection(key);
                    if (ts == null) continue;
                    UpgradeRequirements requirements = loadUpgradeRequirements(ts);
                    tiers.put(tier, new TierDefinition(tier, ts.getDouble("action-time-seconds", 15.0D), ts.getInt("storage", 64), Math.max(1, Math.min(9, ts.getInt("storage-slots", Math.min(9, tier)))), requirements));
                }
            }
            if (!tiers.containsKey(1)) {
                tiers.put(1, new TierDefinition(1, 15.0D, 64, 1, UpgradeRequirements.empty()));
            }
            List<String> wikiSpecialItems = new ArrayList<>(s.getStringList("wiki.special-items"));
            if (s.getBoolean("wiki.auto-compressed-resources", true)) {
                Map<String, ResourceDefinition> resources = loadResources();
                for (ResourceDrop drop : drops) {
                    ResourceDefinition resource = resources.get(drop.resourceId());
                    if (resource != null && resource.compressionEnabled() && resource.blockConvertible()) {
                        String compressed = "compressed_" + resource.id();
                        String superCompressed = "super_compressed_" + resource.id();
                        if (!wikiSpecialItems.contains(compressed)) wikiSpecialItems.add(compressed);
                        if (!wikiSpecialItems.contains(superCompressed)) wikiSpecialItems.add(superCompressed);
                    }
                }
            }
            List<Integer> supportedBoosters = new ArrayList<>(s.getIntegerList("boosters.supported-tiers"));
            if (supportedBoosters.isEmpty()) {
                supportedBoosters = new ArrayList<>(defaultSupportedBoosters);
            } else {
                for (int boosterTier : defaultSupportedBoosters) {
                    if (!supportedBoosters.contains(boosterTier)) supportedBoosters.add(boosterTier);
                }
            }
            result.put(id, new MinionTypeDefinition(
                    id,
                    s.getBoolean("enabled", true),
                    s.getString("display-name", id),
                    s.getString("category", "special"),
                    itemSpec,
                    s.getString("item.display-name", id),
                    List.copyOf(s.getStringList("item.lore")),
                    Math.max(0, s.getInt("placement.footprint-radius-blocks", 1)),
                    s.getBoolean("placement.require-solid-ground", true),
                    List.copyOf(blocked),
                    s.getString("appearance", id + "_default"),
                    s.getString("menu", "default_minion"),
                    List.copyOf(drops),
                    s.getString("drop-selection-mode", "INDEPENDENT").toUpperCase(Locale.ROOT),
                    Map.copyOf(tiers),
                    Math.max(1, s.getInt("max-tier", tiers.keySet().stream().mapToInt(Integer::intValue).max().orElse(1))),
                    List.copyOf(wikiSpecialItems),
                    List.copyOf(supportedBoosters),
                    AutoSmelterDefinition.fromConfig(s.getConfigurationSection("auto-smelter"))
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

    private boolean booleanValue(Object value, boolean def) {
        if (value instanceof Boolean b) return b;
        if (value == null) return def;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private int parseInt(String raw) {
        try { return Integer.parseInt(raw); } catch (NumberFormatException ignored) { return 1; }
    }

    private Number number(Object value, Number def) {
        return value instanceof Number n ? n : def;
    }
}

