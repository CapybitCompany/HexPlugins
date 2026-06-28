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
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class SpecialItemRegistry {
    public static final String SPECIAL_ITEM_KIND = "special_item";
    public static final String SPECIAL_BLOCK_KIND = "special_block";

    private final Plugin plugin;
    private final NamespacedKey kindKey;
    private final NamespacedKey specialItemIdKey;
    private final NamespacedKey specialBlockIdKey;
    private final Map<String, SpecialItemDefinition> items;
    private final Map<String, SpecialRecipeDefinition> recipes;
    private final Map<String, CraftingStationDefinition> stations;
    private final Map<Integer, BoosterDefinition> boosters;
    private final Map<String, BoosterDefinition> boostersByItemId;
    private final int compressedUnitValue;
    private final int superCompressedUnitValue;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private SpecialItemRegistry(Plugin plugin, Map<String, SpecialItemDefinition> items, Map<String, SpecialRecipeDefinition> recipes, Map<String, CraftingStationDefinition> stations, Map<Integer, BoosterDefinition> boosters, int compressedUnitValue, int superCompressedUnitValue) {
        this.plugin = plugin;
        this.kindKey = new NamespacedKey(plugin, "item_kind");
        this.specialItemIdKey = new NamespacedKey(plugin, "special_item_id");
        this.specialBlockIdKey = new NamespacedKey(plugin, "special_block_id");
        this.items = Map.copyOf(items);
        this.recipes = Map.copyOf(recipes);
        this.stations = Map.copyOf(stations);
        this.boosters = Map.copyOf(boosters);
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
            if (def.enabled()) items.put(def.id(), def);
        }
        Map<String, SpecialRecipeDefinition> recipes = new LinkedHashMap<>();
        ConfigurationSection recipeRoot = yaml.getConfigurationSection("recipes");
        if (recipeRoot != null) for (String id : recipeRoot.getKeys(false)) {
            ConfigurationSection section = recipeRoot.getConfigurationSection(id);
            if (section == null) continue;
            SpecialRecipeDefinition recipe = SpecialRecipeDefinition.fromConfig(id, section);
            if (recipe.enabled()) recipes.put(recipe.id(), recipe);
        }
        addGeneratedCompression(plugin, yaml, items, recipes);

        Map<String, CraftingStationDefinition> stations = new LinkedHashMap<>();
        ConfigurationSection stationRoot = yaml.getConfigurationSection("crafting-stations");
        if (stationRoot != null) for (String id : stationRoot.getKeys(false)) {
            ConfigurationSection section = stationRoot.getConfigurationSection(id);
            if (section == null) continue;
            CraftingStationDefinition station = CraftingStationDefinition.fromConfig(id, section);
            if (station.enabled()) stations.put(station.id(), station);
        }
        Map<Integer, BoosterDefinition> boosters = loadBoosters(yaml);
        int compressedUnitValue = Math.max(1, yaml.getInt("compression.defaults.compressed.value", 128));
        int superCompressedUnitValue = Math.max(compressedUnitValue, yaml.getInt("compression.defaults.super.value", compressedUnitValue * 32 * 5));
        return new SpecialItemRegistry(plugin, items, recipes, stations, boosters, compressedUnitValue, superCompressedUnitValue);
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
                    s.getDouble("particle-y-offset", 1.15D)
            ));
        }
        return result;
    }

    private static void addGeneratedCompression(Plugin plugin, YamlConfiguration specialYaml, Map<String, SpecialItemDefinition> items, Map<String, SpecialRecipeDefinition> recipes) {
        boolean defaultsEnabled = specialYaml.getBoolean("compression.defaults.enabled", true);
        if (!defaultsEnabled) return;
        File resourcesFile = new File(plugin.getDataFolder(), "resources.yml");
        if (!resourcesFile.exists()) plugin.saveResource("resources.yml", false);
        YamlConfiguration resourcesYaml = YamlConfiguration.loadConfiguration(resourcesFile);
        ConfigurationSection root = resourcesYaml.getConfigurationSection("resources");
        if (root == null) return;

        String station = specialYaml.getString("compression.defaults.station", "ENCHANTED_CRAFTING_TABLE");
        int compressedValue = Math.max(1, specialYaml.getInt("compression.defaults.compressed.value", 128));
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
        int unlockMinionLevel = Math.max(1, specialYaml.getInt("compression.defaults.unlock-minion-level", 5));
        String compressedNameTemplate = specialYaml.getString("compression.defaults.compressed.display-name", "<aqua>Skompresowany <resource></aqua>");
        String superNameTemplate = specialYaml.getString("compression.defaults.super.display-name", "<gold>Superskompresowany <resource></gold>");

        int index = 0;
        for (String resourceId : root.getKeys(false)) {
            ConfigurationSection rs = root.getConfigurationSection(resourceId);
            if (rs == null || !rs.getBoolean("compression.enabled", false) || !rs.getBoolean("compression.block-convertible", rs.getStringList("tags").contains("block"))) continue;
            Material rawMaterial = Material.matchMaterial(rs.getString("material", "STONE"));
            if (rawMaterial == null) rawMaterial = Material.STONE;
            Material outputMaterial = Material.matchMaterial(rs.getString("compression.compressed-material", rawMaterial.name()));
            if (outputMaterial == null) outputMaterial = rawMaterial;
            String display = rs.getString("display-name", resourceId);
            String compressedId = "compressed_" + resourceId.toLowerCase(java.util.Locale.ROOT);
            String superId = "super_compressed_" + resourceId.toLowerCase(java.util.Locale.ROOT);
            int cmd = rs.getInt("compression.compressed.custom-model-data", customModelBase + index * 2 + 1);
            int superCmd = rs.getInt("compression.super.custom-model-data", customModelBase + index * 2 + 2);

            items.putIfAbsent(compressedId, new SpecialItemDefinition(compressedId, true, outputMaterial, cmd, 1,
                    compressedNameTemplate.replace("<resource>", stripMini(display)),
                    List.of("<gray>Wartość: <white>" + compressedValue + "</white> szt. surowca.</gray>", "<dark_gray>Item generowany automatycznie z resources.yml.</dark_gray>"),
                    glint, placeable, "", ""));
            items.putIfAbsent(superId, new SpecialItemDefinition(superId, true, outputMaterial, superCmd, 1,
                    superNameTemplate.replace("<resource>", stripMini(display)),
                    List.of("<gray>Wartość: <white>" + superValue + "</white> szt. surowca.</gray>", "<dark_gray>Item generowany automatycznie z resources.yml.</dark_gray>"),
                    glint, placeable, "", ""));

            SpecialItemDefinition compressedDef = items.get(compressedId);
            Material compressedMaterial = compressedDef == null ? outputMaterial : compressedDef.material();
            int compressedCustomModelData = compressedDef == null ? cmd : compressedDef.customModelData();
            int resourceUnlockLevel = Math.max(1, rs.getInt("compression.unlock-minion-level", unlockMinionLevel));
            RecipeUnlockRequirement unlock = new RecipeUnlockRequirement(Map.of(resourceId.toLowerCase(java.util.Locale.ROOT), resourceUnlockLevel), Map.of(), Map.of(), List.of());
            recipes.putIfAbsent(compressedId, new SpecialRecipeDefinition(compressedId, true, station, normalizeShape(compressedShape),
                    Map.of('C', new SpecialIngredient(rawMaterial, compressedIngredientAmount, rs.getInt("custom-model-data", 0), "")),
                    compressedId, "", 1, Material.AIR, 1, 0, unlock));
            recipes.putIfAbsent(superId, new SpecialRecipeDefinition(superId, true, station, normalizeShape(superShape),
                    Map.of('C', new SpecialIngredient(compressedMaterial, superIngredientAmount, compressedCustomModelData, compressedId)),
                    superId, "", 1, Material.AIR, 1, 0, unlock));
            index++;
        }
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
    public Optional<BoosterDefinition> booster(int tier) { return Optional.ofNullable(boosters.get(tier)); }
    public Optional<BoosterDefinition> boosterBySpecialItemId(String id) { return Optional.ofNullable(boostersByItemId.get(id == null ? "" : id.toLowerCase(java.util.Locale.ROOT))); }
    public Optional<BoosterDefinition> boosterByItem(ItemStack item) { return readSpecialItemId(item).flatMap(this::boosterBySpecialItemId); }
    public Optional<SpecialItemDefinition> item(String id) { return Optional.ofNullable(items.get(id == null ? "" : id.toLowerCase(java.util.Locale.ROOT))); }
    public Optional<SpecialRecipeDefinition> recipe(String id) { return Optional.ofNullable(recipes.get(id == null ? "" : id.toLowerCase(java.util.Locale.ROOT))); }
    public Optional<CraftingStationDefinition> station(String id) { return Optional.ofNullable(stations.get(id == null ? "" : id)); }

    public ItemStack createItem(String id, int amount) {
        SpecialItemDefinition def = item(id).orElse(null);
        if (def == null) return new ItemStack(Material.AIR);
        ItemStack item = def.icon(miniMessage);
        item.setAmount(Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(kindKey, PersistentDataType.STRING, SPECIAL_ITEM_KIND);
            pdc.set(specialItemIdKey, PersistentDataType.STRING, def.id());
            item.setItemMeta(meta);
        }
        return item;
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
        if (block.getState() instanceof TileState state) {
            state.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, SPECIAL_BLOCK_KIND);
            state.getPersistentDataContainer().set(specialBlockIdKey, PersistentDataType.STRING, stationId);
            state.update(true, false);
        }
    }

    public Optional<String> readSpecialBlockId(Block block) {
        if (block == null || !(block.getState() instanceof TileState state)) return Optional.empty();
        PersistentDataContainer pdc = state.getPersistentDataContainer();
        if (!SPECIAL_BLOCK_KIND.equals(pdc.get(kindKey, PersistentDataType.STRING))) return Optional.empty();
        String id = pdc.get(specialBlockIdKey, PersistentDataType.STRING);
        return id == null || id.isBlank() ? Optional.empty() : Optional.of(id);
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
                    if (entry.getValue().material() != Material.AIR) shaped.setIngredient(entry.getKey(), entry.getValue().material());
                }
                Bukkit.addRecipe(shaped);
            } catch (Throwable throwable) {
                plugin.getLogger().warning("Nie udało się zarejestrować specjalnej receptury '" + recipe.id() + "': " + throwable.getMessage());
            }
        }
    }

    public NamespacedKey specialItemIdKey() { return specialItemIdKey; }
}
