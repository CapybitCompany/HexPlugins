package hex.minions.crafting;

import hex.minions.config.StorageChestDefinition;
import hex.minions.config.ResourceDefinition;
import hex.minions.config.Definitions;
import hex.minions.config.MinionTypeDefinition;
import hex.minions.config.StorageChestRegistry;
import hex.minions.service.MinionItemFactory;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class SpecialItemRegistry {
    public static final String SPECIAL_ITEM_KIND = "special_item";
    public static final String SPECIAL_BLOCK_KIND = "special_block";
    private static final String SPECIAL_BLOCK_METADATA = "hexminions_special_block_id";

    private final Plugin plugin;
    private final NamespacedKey kindKey;
    private final NamespacedKey specialItemIdKey;
    private final NamespacedKey specialBlockIdKey;
    private final NamespacedKey nonStackNonceKey;
    private final Map<String, SpecialItemDefinition> items;
    private final Map<String, SpecialRecipeDefinition> recipes;
    private final Map<String, CraftingStationDefinition> stations;
    private final Map<Integer, BoosterDefinition> boosters;
    private final Map<String, BoosterDefinition> boostersByItemId;
    private final Map<String, ProductionUpdateDefinition> productionUpdatesByItemId;
    private final Map<String, MachineUpgradeDefinition> machineUpgradesByItemId;
    private final Map<String, RobotUpgradeDefinition> robotUpgradesByItemId;
    private final int compressedUnitValue;
    private final int superCompressedUnitValue;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private SpecialItemRegistry(Plugin plugin, Map<String, SpecialItemDefinition> items, Map<String, SpecialRecipeDefinition> recipes, Map<String, CraftingStationDefinition> stations, Map<Integer, BoosterDefinition> boosters, Map<String, ProductionUpdateDefinition> productionUpdatesByItemId, Map<String, MachineUpgradeDefinition> machineUpgradesByItemId, Map<String, RobotUpgradeDefinition> robotUpgradesByItemId, int compressedUnitValue, int superCompressedUnitValue) {
        this.plugin = plugin;
        this.kindKey = new NamespacedKey(plugin, "item_kind");
        this.specialItemIdKey = new NamespacedKey(plugin, "special_item_id");
        this.specialBlockIdKey = new NamespacedKey(plugin, "special_block_id");
        this.nonStackNonceKey = new NamespacedKey(plugin, "non_stack_nonce");
        this.items = Map.copyOf(items);
        this.recipes = Map.copyOf(recipes);
        this.stations = Map.copyOf(stations);
        this.boosters = Map.copyOf(boosters);
        this.productionUpdatesByItemId = Map.copyOf(productionUpdatesByItemId);
        this.machineUpgradesByItemId = Map.copyOf(machineUpgradesByItemId);
        this.robotUpgradesByItemId = Map.copyOf(robotUpgradesByItemId);
        Map<String, BoosterDefinition> byItem = new LinkedHashMap<>();
        boosters.values().forEach(booster -> byItem.put(booster.specialItemId().toLowerCase(java.util.Locale.ROOT), booster));
        this.boostersByItemId = Map.copyOf(byItem);
        this.compressedUnitValue = Math.max(1, compressedUnitValue);
        this.superCompressedUnitValue = Math.max(this.compressedUnitValue, superCompressedUnitValue);
    }

    public static SpecialItemRegistry load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "special-items.yml");
        if (!file.exists()) plugin.saveResource("special-items.yml", false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Map<String, SpecialItemDefinition> items = new LinkedHashMap<>();
        ConfigurationSection itemRoot = yaml.getConfigurationSection("special-items");
        if (itemRoot != null) for (String id : itemRoot.getKeys(false)) {
            ConfigurationSection section = itemRoot.getConfigurationSection(id);
            if (section == null) continue;
            SpecialItemDefinition def = SpecialItemDefinition.fromConfig(id, section);
            if (def.enabled() && !isTemporarilyHiddenRobotContent(def.id())) items.put(def.id(), def);
        }
        applyResourceCanonicalMetadata(plugin, items);
        disableMachineItemGlint(plugin, items);
        Map<String, SpecialRecipeDefinition> recipes = new LinkedHashMap<>();
        ConfigurationSection recipeRoot = yaml.getConfigurationSection("recipes");
        if (recipeRoot != null) for (String id : recipeRoot.getKeys(false)) {
            ConfigurationSection section = recipeRoot.getConfigurationSection(id);
            if (section == null) continue;
            SpecialRecipeDefinition recipe = simplifyRecipe(SpecialRecipeDefinition.fromConfig(id, section));
            if (recipe.enabled()
                    && !isTemporarilyHiddenRobotContent(recipe.id())
                    && !isTemporarilyHiddenRobotContent(recipe.outputSpecialItem())) {
                recipes.put(recipe.id(), recipe);
            }
        }
        applySimplifiedMinionUnlockBalance(recipes);
        applySmeltingFurnaceUnlockOverride(recipes);
        addGeneratedCompression(plugin, yaml, items, recipes);
        enforceCompressionCraftDependencies(plugin, recipes);

        Map<String, CraftingStationDefinition> stations = new LinkedHashMap<>();
        ConfigurationSection stationRoot = yaml.getConfigurationSection("crafting-stations");
        if (stationRoot != null) for (String id : stationRoot.getKeys(false)) {
            ConfigurationSection section = stationRoot.getConfigurationSection(id);
            if (section == null) continue;
            CraftingStationDefinition station = CraftingStationDefinition.fromConfig(id, section);
            if (station.enabled()
                    && !isTemporarilyHiddenRobotContent(station.id())
                    && !isTemporarilyHiddenRobotContent(station.specialItemId())) {
                stations.put(station.id(), station);
            }
        }
        Map<Integer, BoosterDefinition> boosters = loadBoosters(yaml);
        Map<String, ProductionUpdateDefinition> productionUpdatesByItemId = loadProductionUpdates(yaml);
        Map<String, MachineUpgradeDefinition> machineUpgradesByItemId = loadMachineUpgrades(yaml);
        Map<String, RobotUpgradeDefinition> robotUpgradesByItemId = loadRobotUpgrades(yaml);
        int compressedUnitValue = Math.max(1, yaml.getInt("compression.defaults.compressed.value", 160));
        int superCompressedUnitValue = Math.max(compressedUnitValue, yaml.getInt("compression.defaults.super.value", compressedUnitValue * 32 * 5));
        return new SpecialItemRegistry(plugin, items, recipes, stations, boosters, productionUpdatesByItemId, machineUpgradesByItemId, robotUpgradesByItemId, compressedUnitValue, superCompressedUnitValue);
    }


    private static boolean isTemporarilyHiddenRobotContent(String id) {
        if (id == null || id.isBlank()) return false;
        String normalized = id.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("robot")) return true;
        return java.util.Set.of(
                "sugar_cube",
                "electric_mill", "biofuel", "hydro_update", "plant_fertilizer_booster", "animal_booster",
                "meat_refinery", "living_block", "plant_booster_tier_2", "wool_insulation_upgrade",
                "living_core_update", "silk_filter_update", "feather_drive_update", "root_nutrient_update",
                "compost", "refined_wheat", "refined_meat", "silverfish_armor",
                "gold_cable", "glass_cable", "advanced_accumulator", "super_capacitor",
                "energy_diamond", "advanced_chip", "bronze_wrench", "machine_energy_upgrade",
                "stone_frame", "iron_frame", "wooden_frame", "advanced_steel_frame",
                "technical_block", "bronze_ingot", "bronze_dust", "enchanted_crafting_table", "fertile_soil",
                "advanced_redstone", "graphene", "stone_dust", "golden_coil", "steel_block"
        ).contains(normalized);
    }

    /**
     * Uproszczony etap rozwoju serwera ma korzystać wyłącznie ze zwykłego craftingu.
     * Transformacja jest wykonywana również dla istniejących konfiguracji w katalogu pluginu,
     * dzięki czemu aktualizacja JAR-a nie wymaga ręcznego usuwania starego special-items.yml.
     */
    private static SpecialRecipeDefinition simplifyRecipe(SpecialRecipeDefinition recipe) {
        if (recipe == null) return null;
        Map<Character, SpecialIngredient> simplified = new LinkedHashMap<>();
        for (Map.Entry<Character, SpecialIngredient> entry : recipe.ingredients().entrySet()) {
            SpecialIngredient ingredient = entry.getValue();
            String special = ingredient.specialItemId() == null ? "" : ingredient.specialItemId().toLowerCase(java.util.Locale.ROOT);
            SpecialIngredient replacement = switch (special) {
                case "wooden_frame" -> new SpecialIngredient(Material.OAK_LOG, ingredient.amount(), 0, "compressed_oak_plank");
                case "stone_frame" -> new SpecialIngredient(Material.COBBLESTONE, ingredient.amount(), 0, "compressed_cobblestone");
                case "iron_frame", "advanced_steel_frame" -> new SpecialIngredient(Material.IRON_BLOCK, ingredient.amount(), 0, "compressed_iron");
                case "technical_block" -> new SpecialIngredient(Material.GOLD_BLOCK, ingredient.amount() * 16, 0, "");
                case "fertile_soil" -> new SpecialIngredient(Material.DIRT, ingredient.amount(), 0, "compressed_dirt");
                case "bronze_ingot" -> recipe.id().equalsIgnoreCase("musket_ammo")
                        ? new SpecialIngredient(Material.COPPER_INGOT, ingredient.amount(), 0, "")
                        : new SpecialIngredient(Material.IRON_INGOT, ingredient.amount(), 0, "steel_ingot");
                case "advanced_chip" -> new SpecialIngredient(Material.REDSTONE_BLOCK, ingredient.amount(), 0, "");
                case "advanced_redstone" -> new SpecialIngredient(Material.REDSTONE_BLOCK, ingredient.amount(), 0, "");
                case "graphene" -> new SpecialIngredient(Material.CHARCOAL, ingredient.amount() * 8, 12702, "graphite");
                case "golden_coil" -> new SpecialIngredient(Material.IRON_INGOT, ingredient.amount() * 32, 12101, "steel_ingot");
                default -> ingredient;
            };
            simplified.put(entry.getKey(), replacement);
        }
        return new SpecialRecipeDefinition(
                recipe.id(), recipe.enabled(), "VANILLA_CRAFTING_TABLE", recipe.shape(), Map.copyOf(simplified),
                recipe.outputSpecialItem(), recipe.outputMinionType(), recipe.outputMinionTier(), recipe.outputMaterial(),
                recipe.outputAmount(), recipe.outputCustomModelData(), recipe.unlock()
        );
    }

    private static Map<Integer, BoosterDefinition> loadBoosters(YamlConfiguration yaml) {
        Map<Integer, BoosterDefinition> result = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("boosters.tiers");
        if (root == null) return result;
        for (String rawTier : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(rawTier);
            if (s == null || !s.getBoolean("enabled", true)) continue;
            int tier;
            try { tier = Integer.parseInt(rawTier); } catch (NumberFormatException ignored) { continue; }
            String itemId = s.getString("special-item", "minion_booster_tier_" + tier);
            if (tier > 2 || isTemporarilyHiddenRobotContent(itemId)) continue;
            Particle particle;
            try { particle = Particle.valueOf(s.getString("particle", "FLAME").toUpperCase(java.util.Locale.ROOT)); }
            catch (Exception ignored) { particle = Particle.FLAME; }
            result.put(tier, new BoosterDefinition(
                    tier,
                    itemId,
                    Math.max(0.0D, s.getDouble("speed-boost-percent", 10.0D)),
                    Math.max(1, s.getInt("duration-seconds", 30)),
                    particle,
                    Math.max(1, s.getInt("particle-count", 8)),
                    Math.max(0.1D, s.getDouble("particle-radius", 0.65D)),
                    s.getDouble("particle-y-offset", 1.15D),
                    List.copyOf(s.getStringList("target-categories"))
            ));
        }
        return result;
    }

    private static Map<String, ProductionUpdateDefinition> loadProductionUpdates(YamlConfiguration yaml) {
        Map<String, ProductionUpdateDefinition> result = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("production-updates");
        if (root == null) return result;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null || !s.getBoolean("enabled", true)) continue;
            String itemId = s.getString("special-item", id);
            if (isTemporarilyHiddenRobotContent(id) || isTemporarilyHiddenRobotContent(itemId)) continue;
            ProductionUpdateDefinition update = new ProductionUpdateDefinition(
                    id,
                    itemId,
                    Math.max(0.0D, s.getDouble("speed-boost-percent", 10.0D)),
                    List.copyOf(s.getStringList("target-categories"))
            );
            if (!update.specialItemId().isBlank()) result.put(update.specialItemId(), update);
        }
        return result;
    }

    private static Map<String, MachineUpgradeDefinition> loadMachineUpgrades(YamlConfiguration yaml) {
        Map<String, MachineUpgradeDefinition> result = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("machine-upgrades");
        if (root == null) return result;
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null || !section.getBoolean("enabled", true)) continue;
            String specialItemId = section.getString("special-item", id).toLowerCase(java.util.Locale.ROOT);
            if (isTemporarilyHiddenRobotContent(id) || isTemporarilyHiddenRobotContent(specialItemId)) continue;
            MachineUpgradeDefinition upgrade = new MachineUpgradeDefinition(
                    id,
                    specialItemId,
                    Math.max(0, section.getInt("extra-buffer-capacity", 0)),
                    section.getDouble("buffer-capacity-multiplier", 1.0D),
                    section.getDouble("energy-consumption-multiplier", 1.0D),
                    section.getDouble("energy-generation-multiplier", 1.0D),
                    section.getDouble("energy-transfer-multiplier", 1.0D),
                    List.copyOf(section.getStringList("target-machine-types"))
            );
            if (!upgrade.specialItemId().isBlank()) result.put(upgrade.specialItemId(), upgrade);
        }
        return result;
    }

    private static Map<String, RobotUpgradeDefinition> loadRobotUpgrades(YamlConfiguration yaml) {
        Map<String, RobotUpgradeDefinition> result = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("robot-upgrades");
        if (root == null) return result;
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null || !section.getBoolean("enabled", true)) continue;
            String specialItemId = section.getString("special-item", id).toLowerCase(java.util.Locale.ROOT);
            if (isTemporarilyHiddenRobotContent(id) || isTemporarilyHiddenRobotContent(specialItemId)) continue;
            RobotUpgradeDefinition upgrade = new RobotUpgradeDefinition(
                    id,
                    specialItemId,
                    section.getDouble("work-interval-multiplier", 1.0D),
                    section.getDouble("fuel-duration-multiplier", 1.0D),
                    section.getDouble("pickaxe-damage-save-chance", 0.0D),
                    List.copyOf(section.getStringList("target-robot-types"))
            );
            if (!upgrade.specialItemId().isBlank()) result.put(upgrade.specialItemId(), upgrade);
        }
        return result;
    }

    /**
     * resources.yml is the canonical source for material/CMD/name of resource-backed custom items.
     * special-items.yml may still add lore and behaviour flags, but cannot silently diverge in identity.
     */
    private static void applyResourceCanonicalMetadata(Plugin plugin, Map<String, SpecialItemDefinition> items) {
        File resourcesFile = new File(plugin.getDataFolder(), "resources.yml");
        if (!resourcesFile.exists()) plugin.saveResource("resources.yml", false);
        YamlConfiguration resourcesYaml = YamlConfiguration.loadConfiguration(resourcesFile);
        ConfigurationSection root = resourcesYaml.getConfigurationSection("resources");
        if (root == null) return;

        File specialFile = new File(plugin.getDataFolder(), "special-items.yml");
        if (!specialFile.exists()) plugin.saveResource("special-items.yml", false);
        YamlConfiguration specialYaml = YamlConfiguration.loadConfiguration(specialFile);
        ConfigurationSection specialRoot = specialYaml.getConfigurationSection("special-items");

        for (Map.Entry<String, SpecialItemDefinition> entry : new ArrayList<>(items.entrySet())) {
            String id = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            SpecialItemDefinition current = entry.getValue();
            ConfigurationSection specialSection = specialRoot == null ? null : specialRoot.getConfigurationSection(id);
            String resourceRef = specialSection == null
                    ? id
                    : specialSection.getString("resource-ref", id).trim().toLowerCase(java.util.Locale.ROOT);
            java.util.Optional<String> compressionSource = SpecialItemCarrierResolver.compressionSourceResourceId(id, root);
            if (compressionSource.isPresent()) {
                ConfigurationSection rawResource = root.getConfigurationSection(compressionSource.get());
                Material material = SpecialItemCarrierResolver.resolveCarrierOrFallback(id, resourceRef, root, current.material());
                int rawCmd = rawResource == null ? 0 : Math.max(0, rawResource.getInt("custom-model-data", 0));
                ConfigurationSection legacyAlias = root.getConfigurationSection(resourceRef);
                int canonicalCmd = rawCmd <= 0 ? 0 : (legacyAlias == null
                        ? current.customModelData()
                        : Math.max(0, legacyAlias.getInt("custom-model-data", current.customModelData())));
                String displayName = legacyAlias == null ? current.displayName() : legacyAlias.getString("display-name", current.displayName());
                items.put(id, new SpecialItemDefinition(
                        current.id(), current.enabled(), material, canonicalCmd,
                        current.amount(), displayName, current.lore(), current.enchantGlint(),
                        current.placeable(), current.blockKind(), current.leatherColor()
                ));
                continue;
            }
            ConfigurationSection resource = root.getConfigurationSection(resourceRef);
            Material material = SpecialItemCarrierResolver.resolveCarrierOrFallback(id, resourceRef, root, current.material());
            if (resource == null) {
                // Compression carrier resolution must not depend on a legacy resources.compressed_* alias.
                if (material != current.material()) {
                    items.put(id, new SpecialItemDefinition(
                            current.id(), current.enabled(), material, current.customModelData(),
                            current.amount(), current.displayName(), current.lore(), current.enchantGlint(),
                            current.placeable(), current.blockKind(), current.leatherColor()
                    ));
                }
                continue;
            }
            items.put(id, new SpecialItemDefinition(
                    current.id(), current.enabled(), material,
                    Math.max(0, resource.getInt("custom-model-data", current.customModelData())),
                    current.amount(), resource.getString("display-name", current.displayName()),
                    current.lore(), current.enchantGlint(), current.placeable(), current.blockKind(), current.leatherColor()
            ));
        }
    }


    private static void disableMachineItemGlint(Plugin plugin, Map<String, SpecialItemDefinition> items) {
        File machinesFile = new File(plugin.getDataFolder(), "machines.yml");
        if (!machinesFile.exists()) plugin.saveResource("machines.yml", false);
        YamlConfiguration machinesYaml = YamlConfiguration.loadConfiguration(machinesFile);
        ConfigurationSection root = machinesYaml.getConfigurationSection("machines");
        if (root == null) return;
        for (String machineId : root.getKeys(false)) {
            ConfigurationSection machine = root.getConfigurationSection(machineId);
            if (machine == null) continue;
            String itemId = machine.getString("special-item", machineId).trim().toLowerCase(java.util.Locale.ROOT);
            SpecialItemDefinition current = items.get(itemId);
            if (current == null || !current.enchantGlint()) continue;
            items.put(itemId, new SpecialItemDefinition(
                    current.id(), current.enabled(), current.material(), current.customModelData(), current.amount(),
                    current.displayName(), current.lore(), false, current.placeable(), current.blockKind(), current.leatherColor()
            ));
        }
    }

    private static void addGeneratedCompression(Plugin plugin, YamlConfiguration specialYaml, Map<String, SpecialItemDefinition> items, Map<String, SpecialRecipeDefinition> recipes) {
        boolean defaultsEnabled = specialYaml.getBoolean("compression.defaults.enabled", true);
        if (!defaultsEnabled) return;
        File resourcesFile = new File(plugin.getDataFolder(), "resources.yml");
        if (!resourcesFile.exists()) plugin.saveResource("resources.yml", false);
        YamlConfiguration resourcesYaml = YamlConfiguration.loadConfiguration(resourcesFile);
        ConfigurationSection root = resourcesYaml.getConfigurationSection("resources");
        if (root == null) return;

        String station = "VANILLA_CRAFTING_TABLE";
        int compressedValue = Math.max(1, specialYaml.getInt("compression.defaults.compressed.value", 160));
        int superValue = Math.max(compressedValue, specialYaml.getInt("compression.defaults.super.value", compressedValue * 32 * 5));
        List<String> compressedShape = specialYaml.getStringList("compression.defaults.compressed.shape");
        if (compressedShape.isEmpty()) compressedShape = List.of(" C ", "CCC", " C ");
        List<String> superShape = specialYaml.getStringList("compression.defaults.super.shape");
        if (superShape.isEmpty()) superShape = List.of(" C ", "CCC", " C ");
        int compressedIngredientAmount = Math.max(1, specialYaml.getInt("compression.defaults.compressed.ingredient-amount", Math.max(1, compressedValue / countShape(compressedShape, 'C'))));
        int superIngredientAmount = Math.max(1, specialYaml.getInt("compression.defaults.super.ingredient-amount", 32));
        int customModelBase = Math.max(0, specialYaml.getInt("compression.defaults.custom-model-data-base", 11000));
        boolean glint = specialYaml.getBoolean("compression.defaults.enchant-glint", true);
        boolean placeable = specialYaml.getBoolean("compression.defaults.placeable", false);
        int legacyUnlockMinionLevel = Math.max(1, specialYaml.getInt("compression.defaults.unlock-minion-level", 3));
        int compressedUnlockMinionLevel = Math.max(1, specialYaml.getInt("compression.defaults.compressed-unlock-minion-level", legacyUnlockMinionLevel));
        int superUnlockMinionLevel = Math.max(compressedUnlockMinionLevel, specialYaml.getInt("compression.defaults.super-unlock-minion-level", 5));
        String compressedNameTemplate = specialYaml.getString("compression.defaults.compressed.display-name", "<aqua>Skompresowany <resource></aqua>");
        String superNameTemplate = specialYaml.getString("compression.defaults.super.display-name", "<gold>Superskompresowany <resource></gold>");

        Map<String, String> resourceUnlockMinions = loadCompressionUnlockMinions(plugin);
        int index = 0;
        for (String resourceId : root.getKeys(false)) {
            ConfigurationSection rs = root.getConfigurationSection(resourceId);
            if (rs == null || !SpecialItemCarrierResolver.compressionEnabled(resourceId, rs)
                    || !("emerald".equalsIgnoreCase(resourceId) || rs.getBoolean("compression.block-convertible", rs.getStringList("tags").contains("block")))) continue;
            Material rawMaterial = Material.matchMaterial(rs.getString("material", "STONE"));
            if (rawMaterial == null) rawMaterial = Material.STONE;
            Material outputMaterial = SpecialItemCarrierResolver.compressionCarrier(resourceId, rs, rawMaterial);
            String display = rs.getString("display-name", resourceId);
            String compressedId = "compressed_" + resourceId.toLowerCase(java.util.Locale.ROOT);
            String superId = "super_compressed_" + resourceId.toLowerCase(java.util.Locale.ROOT);
            int rawResourceCmd = Math.max(0, rs.getInt("custom-model-data", 0));
            int cmd = rawResourceCmd <= 0 ? 0 : rs.getInt("compression.compressed.custom-model-data", customModelBase + index * 2 + 1);
            int superCmd = rawResourceCmd <= 0 ? 0 : rs.getInt("compression.super.custom-model-data", customModelBase + index * 2 + 2);
            String compressedName = rs.getString("compression.compressed.display-name", compressedNameTemplate.replace("<resource>", stripMini(display)));
            String superName = rs.getString("compression.super.display-name", superNameTemplate.replace("<resource>", stripMini(display)));
            List<String> compressedLore = rs.getStringList("compression.compressed.lore");
            if (compressedLore.isEmpty()) compressedLore = List.of("<gray>Wartość: <white>" + compressedValue + "</white> szt. surowca.</gray>", "<dark_gray>Item generowany automatycznie z resources.yml.</dark_gray>");
            List<String> superLore = rs.getStringList("compression.super.lore");
            if (superLore.isEmpty()) superLore = List.of("<gray>Wartość: <white>" + superValue + "</white> szt. surowca.</gray>", "<dark_gray>Item generowany automatycznie z resources.yml.</dark_gray>");

            items.putIfAbsent(compressedId, new SpecialItemDefinition(compressedId, true, outputMaterial, cmd, 1,
                    compressedName, List.copyOf(compressedLore), glint, placeable, "", ""));
            items.putIfAbsent(superId, new SpecialItemDefinition(superId, true, outputMaterial, superCmd, 1,
                    superName, List.copyOf(superLore), glint, placeable, "", ""));

            SpecialItemDefinition compressedDef = items.get(compressedId);
            Material compressedMaterial = compressedDef == null ? outputMaterial : compressedDef.material();
            int compressedCustomModelData = compressedDef == null ? cmd : compressedDef.customModelData();
            int resourceLegacyUnlockLevel = Math.max(1, rs.getInt("compression.unlock-minion-level", compressedUnlockMinionLevel));
            int resourceCompressedUnlockLevel = Math.max(1, rs.getInt("compression.compressed-unlock-minion-level", resourceLegacyUnlockLevel));
            int resourceSuperUnlockLevel = Math.max(resourceCompressedUnlockLevel, rs.getInt("compression.super-unlock-minion-level", superUnlockMinionLevel));
            String configuredUnlockMinion = rs.getString("compression.unlock-minion-id", "").trim().toLowerCase(java.util.Locale.ROOT);
            String unlockMinionId = configuredUnlockMinion.isBlank()
                    ? resourceUnlockMinions.getOrDefault(resourceId.toLowerCase(java.util.Locale.ROOT), resourceId.toLowerCase(java.util.Locale.ROOT))
                    : configuredUnlockMinion;
            RecipeUnlockRequirement compressedUnlock = new RecipeUnlockRequirement(Map.of(unlockMinionId, resourceCompressedUnlockLevel), RecipeUnlockRequirement.MinionLevelMode.ALL, Map.of(), Map.of(), List.of(), List.of());
            RecipeUnlockRequirement superUnlock = new RecipeUnlockRequirement(Map.of(unlockMinionId, resourceSuperUnlockLevel), RecipeUnlockRequirement.MinionLevelMode.ALL, Map.of(), Map.of(), List.of(), List.of());
            recipes.putIfAbsent(compressedId, new SpecialRecipeDefinition(compressedId, true, station, normalizeShape(compressedShape),
                    Map.of('C', new SpecialIngredient(rawMaterial, compressedIngredientAmount, rs.getInt("custom-model-data", 0), "")),
                    compressedId, "", 1, Material.AIR, 1, 0, compressedUnlock));
            recipes.putIfAbsent(superId, new SpecialRecipeDefinition(superId, true, station, normalizeShape(superShape),
                    Map.of('C', new SpecialIngredient(compressedMaterial, superIngredientAmount, compressedCustomModelData, compressedId)),
                    superId, "", 1, Material.AIR, 1, 0, superUnlock));
            index++;
        }
    }


    /**
     * Finalna uproszczona progresja unlocków minionów. Nadpisanie działa również dla istniejącego
     * special-items.yml w katalogu serwera, dzięki czemu aktualizacja JAR-a nie zachowuje starych gate'ów.
     * Widoczny warunek pozostaje pojedynczy; prerequisiteCollectionTierCounts pilnuje sekwencji late game.
     */
    private static void applySimplifiedMinionUnlockBalance(Map<String, SpecialRecipeDefinition> recipes) {
        Map<String, UnlockGate> gates = new LinkedHashMap<>();
        addGate(gates, 2, 3, List.of(), "stone_minion", "spruce_wood_minion");
        addGate(gates, 5, 3, List.of(), "pig_minion", "chicken_minion", "wheat_minion");
        addGate(gates, 8, 4, List.of(), "iron_minion", "coal_minion", "cow_minion");
        addGate(gates, 9, 5, List.of(), "copper_minion", "tin_minion", "zombie_minion");
        addGate(gates, 13, 5, List.of(), "obsidian_minion", "skeleton_minion", "sugar_cane_minion");
        addGate(gates, 16, 5, List.of(), "gold_minion", "emerald_minion");
        List<RecipeUnlockRequirement.CollectionTierCount> afterStage6 = List.of(new RecipeUnlockRequirement.CollectionTierCount(16, 5, true));
        addGate(gates, 8, 6, afterStage6, "diamond_minion", "sheep_minion", "spider_minion", "redstone_minion", "uranium_minion", "cactus_minion", "beetroot_minion", "netherrack_minion");
        addGate(gates, 12, 6, afterStage6, "silverfish_minion");
        addGate(gates, 5, 7, List.of(
                new RecipeUnlockRequirement.CollectionTierCount(16, 5, true),
                new RecipeUnlockRequirement.CollectionTierCount(12, 6, true)
        ), "netherite_minion");

        for (Map.Entry<String, UnlockGate> entry : gates.entrySet()) {
            SpecialRecipeDefinition recipe = recipes.get(entry.getKey());
            if (recipe == null) continue;
            UnlockGate gate = entry.getValue();
            RecipeUnlockRequirement unlock = new RecipeUnlockRequirement(
                    Map.of(), RecipeUnlockRequirement.MinionLevelMode.ALL, Map.of(), Map.of(),
                    List.of(new RecipeUnlockRequirement.CollectionTierCount(gate.count(), gate.tier(), true)),
                    gate.prerequisites());
            recipes.put(entry.getKey(), new SpecialRecipeDefinition(
                    recipe.id(), recipe.enabled(), recipe.station(), recipe.shape(), recipe.ingredients(),
                    recipe.outputSpecialItem(), recipe.outputMinionType(), recipe.outputMinionTier(),
                    recipe.outputMaterial(), recipe.outputAmount(), recipe.outputCustomModelData(), unlock));
        }
    }

    private static void addGate(Map<String, UnlockGate> gates, int count, int tier, List<RecipeUnlockRequirement.CollectionTierCount> prerequisites, String... recipeIds) {
        UnlockGate gate = new UnlockGate(count, tier, List.copyOf(prerequisites));
        for (String recipeId : recipeIds) gates.put(recipeId, gate);
    }

    private record UnlockGate(int count, int tier, List<RecipeUnlockRequirement.CollectionTierCount> prerequisites) {}

    /**
     * A minion recipe that consumes a compressed resource must not become craftable before
     * the source minion has unlocked that compression tier. This is derived at runtime too,
     * so an old server-side special-items.yml cannot reintroduce an early-unlock window.
     */
    private static void enforceCompressionCraftDependencies(Plugin plugin, Map<String, SpecialRecipeDefinition> recipes) {
        File resourcesFile = new File(plugin.getDataFolder(), "resources.yml");
        if (!resourcesFile.exists()) plugin.saveResource("resources.yml", false);
        YamlConfiguration resourcesYaml = YamlConfiguration.loadConfiguration(resourcesFile);
        ConfigurationSection resources = resourcesYaml.getConfigurationSection("resources");
        if (resources == null) return;
        Map<String, String> inferredSources = loadCompressionUnlockMinions(plugin);

        for (Map.Entry<String, SpecialRecipeDefinition> entry : new ArrayList<>(recipes.entrySet())) {
            SpecialRecipeDefinition recipe = entry.getValue();
            if (recipe.outputMinionType() == null || recipe.outputMinionType().isBlank()) continue;
            if (!recipe.unlock().collectionTierCounts().isEmpty()) continue;
            Map<String, Integer> requiredMinions = new LinkedHashMap<>(recipe.unlock().townMinionLevels());
            boolean changed = false;
            for (SpecialIngredient ingredient : recipe.ingredients().values()) {
                String specialId = ingredient.specialItemId() == null ? "" : ingredient.specialItemId().trim().toLowerCase(java.util.Locale.ROOT);
                boolean superCompressed = specialId.startsWith("super_compressed_");
                boolean compressed = !superCompressed && specialId.startsWith("compressed_");
                if (!compressed && !superCompressed) continue;
                String resourceId = specialId.substring(superCompressed ? "super_compressed_".length() : "compressed_".length());
                ConfigurationSection resource = resources.getConfigurationSection(resourceId);
                if (resource == null) continue;
                ConfigurationSection compression = resource.getConfigurationSection("compression");
                if (compression == null || !compression.getBoolean("enabled", false)) continue;
                int compressedLevel = Math.max(1, compression.getInt("compressed-unlock-minion-level", compression.getInt("unlock-minion-level", 3)));
                int level = superCompressed
                        ? Math.max(compressedLevel, compression.getInt("super-unlock-minion-level", 5))
                        : compressedLevel;
                String source = compression.getString("unlock-minion-id", "").trim().toLowerCase(java.util.Locale.ROOT);
                if (source.isBlank()) source = inferredSources.getOrDefault(resourceId, resourceId);
                if (source.isBlank()) continue;
                int previous = requiredMinions.getOrDefault(source, 0);
                if (level > previous) {
                    requiredMinions.put(source, level);
                    changed = true;
                }
            }
            if (!changed) continue;
            RecipeUnlockRequirement old = recipe.unlock();
            RecipeUnlockRequirement unlock = new RecipeUnlockRequirement(
                    Map.copyOf(requiredMinions), RecipeUnlockRequirement.MinionLevelMode.ALL,
                    old.collections(), old.collectionLevels(), old.collectionTierCounts(), old.prerequisiteCollectionTierCounts());
            recipes.put(entry.getKey(), new SpecialRecipeDefinition(
                    recipe.id(), recipe.enabled(), recipe.station(), recipe.shape(), recipe.ingredients(),
                    recipe.outputSpecialItem(), recipe.outputMinionType(), recipe.outputMinionTier(),
                    recipe.outputMaterial(), recipe.outputAmount(), recipe.outputCustomModelData(), unlock));
        }
    }

    private static void applySmeltingFurnaceUnlockOverride(Map<String, SpecialRecipeDefinition> recipes) {
        SpecialRecipeDefinition recipe = recipes.get("smelting_furnace");
        if (recipe == null) return;
        RecipeUnlockRequirement old = recipe.unlock();
        RecipeUnlockRequirement unlock = new RecipeUnlockRequirement(
                Map.of("coal", 5), RecipeUnlockRequirement.MinionLevelMode.ALL,
                old.collections(), old.collectionLevels(), old.collectionTierCounts(), old.prerequisiteCollectionTierCounts());
        recipes.put(recipe.id(), new SpecialRecipeDefinition(
                recipe.id(), recipe.enabled(), recipe.station(), recipe.shape(), recipe.ingredients(),
                recipe.outputSpecialItem(), recipe.outputMinionType(), recipe.outputMinionTier(),
                recipe.outputMaterial(), recipe.outputAmount(), recipe.outputCustomModelData(), unlock));
    }

    private static Map<String, String> loadCompressionUnlockMinions(Plugin plugin) {
        File minionsFile = new File(plugin.getDataFolder(), "minion-types.yml");
        if (!minionsFile.exists()) plugin.saveResource("minion-types.yml", false);
        YamlConfiguration minionsYaml = YamlConfiguration.loadConfiguration(minionsFile);
        ConfigurationSection root = minionsYaml.getConfigurationSection("minion-types");
        if (root == null) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (String minionId : root.getKeys(false)) {
            ConfigurationSection minion = root.getConfigurationSection(minionId);
            if (minion == null || !minion.getBoolean("enabled", true)) continue;
            for (Map<?, ?> rawDrop : minion.getMapList("resource-table")) {
                Object value = rawDrop.get("resource");
                if (value == null) continue;
                String resourceId = String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
                if (!resourceId.isBlank()) result.putIfAbsent(resourceId, minionId.toLowerCase(java.util.Locale.ROOT));
            }
        }
        return Map.copyOf(result);
    }

    private static int countShape(List<String> shape, char needle) {
        int count = 0;
        for (String row : shape) for (int i = 0; i < row.length(); i++) if (row.charAt(i) == needle) count++;
        return Math.max(1, count);
    }

    private static List<String> normalizeShape(List<String> raw) {
        ArrayList<String> rows = new ArrayList<>(raw);
        while (rows.size() < 3) rows.add("   ");
        return rows.stream().limit(3).map(row -> (row + "   ").substring(0, 3)).toList();
    }

    private static String stripMini(String input) {
        return input == null ? "surowiec" : input.replaceAll("<[^>]+>", "").trim();
    }

    public Map<String, SpecialItemDefinition> items() { return items; }
    public int compressedUnitValue() { return compressedUnitValue; }
    public int superCompressedUnitValue() { return superCompressedUnitValue; }
    public Map<String, SpecialRecipeDefinition> recipes() { return recipes; }
    public Map<String, CraftingStationDefinition> stations() { return stations; }
    public Map<Integer, BoosterDefinition> boosters() { return boosters; }
    public Map<String, ProductionUpdateDefinition> productionUpdates() { return productionUpdatesByItemId; }
    public Map<String, MachineUpgradeDefinition> machineUpgrades() { return machineUpgradesByItemId; }
    public Map<String, RobotUpgradeDefinition> robotUpgrades() { return robotUpgradesByItemId; }
    public Optional<BoosterDefinition> booster(int tier) { return Optional.ofNullable(boosters.get(tier)); }
    public Optional<BoosterDefinition> boosterBySpecialItemId(String id) { return Optional.ofNullable(boostersByItemId.get(id == null ? "" : id.toLowerCase(java.util.Locale.ROOT))); }
    public Optional<BoosterDefinition> boosterByItem(ItemStack item) { return readSpecialItemId(item).flatMap(this::boosterBySpecialItemId); }
    public Optional<ProductionUpdateDefinition> productionUpdateBySpecialItemId(String id) { return Optional.ofNullable(productionUpdatesByItemId.get(id == null ? "" : id.toLowerCase(java.util.Locale.ROOT))); }
    public Optional<ProductionUpdateDefinition> productionUpdateByItem(ItemStack item) { return readSpecialItemId(item).flatMap(this::productionUpdateBySpecialItemId); }
    public Optional<MachineUpgradeDefinition> machineUpgradeBySpecialItemId(String id) { return Optional.ofNullable(machineUpgradesByItemId.get(id == null ? "" : id.toLowerCase(java.util.Locale.ROOT))); }
    public Optional<MachineUpgradeDefinition> machineUpgradeByItem(ItemStack item) { return readSpecialItemId(item).flatMap(this::machineUpgradeBySpecialItemId); }
    public Optional<RobotUpgradeDefinition> robotUpgradeBySpecialItemId(String id) { return Optional.ofNullable(robotUpgradesByItemId.get(id == null ? "" : id.toLowerCase(java.util.Locale.ROOT))); }
    public Optional<RobotUpgradeDefinition> robotUpgradeByItem(ItemStack item) { return readSpecialItemId(item).flatMap(this::robotUpgradeBySpecialItemId); }
    public Optional<SpecialItemDefinition> item(String id) { return Optional.ofNullable(items.get(id == null ? "" : id.toLowerCase(java.util.Locale.ROOT))); }
    public Optional<SpecialRecipeDefinition> recipe(String id) { return Optional.ofNullable(recipes.get(id == null ? "" : id.toLowerCase(java.util.Locale.ROOT))); }
    public Optional<CraftingStationDefinition> station(String id) { return Optional.ofNullable(stations.get(id == null ? "" : id)); }

    public ItemStack createItem(String id, int amount) {
        SpecialItemDefinition def = item(id).orElse(null);
        if (def == null) return new ItemStack(Material.AIR);
        ItemStack item = def.icon(miniMessage);
        boolean nonStackableUpdate = isNonStackableUpdate(def.id());
        item.setAmount(nonStackableUpdate ? 1 : Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if ("battery".equalsIgnoreCase(def.id())) meta.setMaxStackSize(1);
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(kindKey, PersistentDataType.STRING, SPECIAL_ITEM_KIND);
            pdc.set(specialItemIdKey, PersistentDataType.STRING, def.id());
            if (nonStackableUpdate) {
                pdc.set(nonStackNonceKey, PersistentDataType.STRING, UUID.randomUUID().toString());
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isNonStackableUpdate(String id) {
        if (id == null) return false;
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("booster")) return false;
        if (lower.equals("musket") || lower.equals("miner_robot")) return true;
        return lower.contains("update") || lower.equals("auto_smelter") || machineUpgradesByItemId.containsKey(lower) || robotUpgradesByItemId.containsKey(lower);
    }

    public Optional<String> readSpecialItemId(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return Optional.empty();
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!SPECIAL_ITEM_KIND.equals(pdc.get(kindKey, PersistentDataType.STRING))) return Optional.empty();
        String id = pdc.get(specialItemIdKey, PersistentDataType.STRING);
        return id == null || id.isBlank() ? Optional.empty() : Optional.of(id);
    }

    public ItemStack output(SpecialRecipeDefinition recipe) {
        return output(recipe, null, null);
    }

    public ItemStack output(SpecialRecipeDefinition recipe, MinionItemFactory itemFactory, Definitions definitions) {
        if (recipe.outputSpecialItem() != null && !recipe.outputSpecialItem().isBlank()) {
            return createItem(recipe.outputSpecialItem(), recipe.outputAmount());
        }
        if (recipe.outputMinionType() != null && !recipe.outputMinionType().isBlank() && itemFactory != null && definitions != null) {
            MinionTypeDefinition type = definitions.minionTypes().get(recipe.outputMinionType().toLowerCase(java.util.Locale.ROOT));
            if (type != null) return itemFactory.createMinionItem(type, recipe.outputMinionTier(), recipe.outputAmount());
        }
        ItemStack item = new ItemStack(recipe.outputMaterial() == Material.AIR ? Material.PAPER : recipe.outputMaterial(), recipe.outputAmount());
        ItemMeta meta = item.getItemMeta();
        if (meta != null && recipe.outputCustomModelData() > 0) {
            meta.setCustomModelData(recipe.outputCustomModelData());
            item.setItemMeta(meta);
        }
        return item;
    }

    public void markSpecialBlock(Block block, String stationId) {
        if (block == null || stationId == null || stationId.isBlank()) return;
        // Metadata is available immediately in the placement tick. PDC remains the
        // persistent source of truth after chunk reloads and server restarts.
        block.setMetadata(SPECIAL_BLOCK_METADATA, new FixedMetadataValue(plugin, stationId));
        if (block.getState() instanceof TileState state) {
            state.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, SPECIAL_BLOCK_KIND);
            state.getPersistentDataContainer().set(specialBlockIdKey, PersistentDataType.STRING, stationId);
            state.update(true, false);
        }
    }

    public void unmarkSpecialBlock(Block block) {
        if (block == null) return;
        block.removeMetadata(SPECIAL_BLOCK_METADATA, plugin);
        if (block.getState() instanceof TileState state) {
            state.getPersistentDataContainer().remove(kindKey);
            state.getPersistentDataContainer().remove(specialBlockIdKey);
            state.update(true, false);
        }
    }

    public Optional<String> readSpecialBlockId(Block block) {
        if (block == null) return Optional.empty();
        for (MetadataValue metadata : block.getMetadata(SPECIAL_BLOCK_METADATA)) {
            if (metadata.getOwningPlugin() != plugin) continue;
            String id = metadata.asString();
            if (id != null && !id.isBlank()) return Optional.of(id);
        }
        if (!(block.getState() instanceof TileState state)) return Optional.empty();
        PersistentDataContainer pdc = state.getPersistentDataContainer();
        if (!SPECIAL_BLOCK_KIND.equals(pdc.get(kindKey, PersistentDataType.STRING))) return Optional.empty();
        String id = pdc.get(specialBlockIdKey, PersistentDataType.STRING);
        if (id == null || id.isBlank()) return Optional.empty();
        block.setMetadata(SPECIAL_BLOCK_METADATA, new FixedMetadataValue(plugin, id));
        return Optional.of(id);
    }

    public void registerVanillaRecipes(MinionItemFactory itemFactory, StorageChestRegistry storageChests, Definitions definitions) {
        for (SpecialRecipeDefinition recipe : recipes.values()) {
            if (!"VANILLA_CRAFTING_TABLE".equalsIgnoreCase(recipe.station())) continue;
            try {
                NamespacedKey key = new NamespacedKey(plugin, "special_" + recipe.id());
                Bukkit.removeRecipe(key);
                ShapedRecipe shaped = new ShapedRecipe(key, output(recipe, itemFactory, definitions));
                shaped.shape(recipe.shape().toArray(String[]::new));
                for (Map.Entry<Character, SpecialIngredient> entry : recipe.ingredients().entrySet()) {
                    SpecialIngredient ingredient = entry.getValue();
                    if (ingredient.specialItemId() != null && !ingredient.specialItemId().isBlank()) {
                        ItemStack exactIngredient = createItem(ingredient.specialItemId(), 1);
                        if (exactIngredient.getType() != Material.AIR) {
                            // Critical for generated compression recipes whose raw/compressed/super items can share
                            // the same vanilla carrier (e.g. DIRT). ExactChoice keeps the PDC/special_item_id
                            // distinction, so five plain DIRT can never satisfy a super_compressed_dirt recipe.
                            shaped.setIngredient(entry.getKey(), new RecipeChoice.ExactChoice(exactIngredient));
                        }
                    } else if (ingredient.hasMaterialChoices()) {
                        shaped.setIngredient(entry.getKey(), new RecipeChoice.MaterialChoice(ingredient.materialChoices()));
                    } else if (ingredient.material() != Material.AIR) {
                        shaped.setIngredient(entry.getKey(), ingredient.material());
                    }
                }
                Bukkit.addRecipe(shaped);
            } catch (Throwable throwable) {
                plugin.getLogger().warning("Nie udało się zarejestrować specjalnej receptury '" + recipe.id() + "': " + throwable.getMessage());
            }
        }
    }

    public NamespacedKey specialItemIdKey() { return specialItemIdKey; }
}
