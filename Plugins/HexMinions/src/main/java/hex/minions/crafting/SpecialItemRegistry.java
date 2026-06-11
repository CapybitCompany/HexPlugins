package hex.minions.crafting;

import hex.minions.config.StorageChestDefinition;
import hex.minions.config.StorageChestRegistry;
import hex.minions.service.MinionItemFactory;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private SpecialItemRegistry(Plugin plugin, Map<String, SpecialItemDefinition> items, Map<String, SpecialRecipeDefinition> recipes, Map<String, CraftingStationDefinition> stations) {
        this.plugin = plugin;
        this.kindKey = new NamespacedKey(plugin, "item_kind");
        this.specialItemIdKey = new NamespacedKey(plugin, "special_item_id");
        this.specialBlockIdKey = new NamespacedKey(plugin, "special_block_id");
        this.items = Map.copyOf(items);
        this.recipes = Map.copyOf(recipes);
        this.stations = Map.copyOf(stations);
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
        Map<String, CraftingStationDefinition> stations = new LinkedHashMap<>();
        ConfigurationSection stationRoot = yaml.getConfigurationSection("crafting-stations");
        if (stationRoot != null) for (String id : stationRoot.getKeys(false)) {
            ConfigurationSection section = stationRoot.getConfigurationSection(id);
            if (section == null) continue;
            CraftingStationDefinition station = CraftingStationDefinition.fromConfig(id, section);
            if (station.enabled()) stations.put(station.id(), station);
        }
        return new SpecialItemRegistry(plugin, items, recipes, stations);
    }

    public Map<String, SpecialItemDefinition> items() { return items; }
    public Map<String, SpecialRecipeDefinition> recipes() { return recipes; }
    public Map<String, CraftingStationDefinition> stations() { return stations; }
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
        if (recipe.outputSpecialItem() != null && !recipe.outputSpecialItem().isBlank()) {
            return createItem(recipe.outputSpecialItem(), recipe.outputAmount());
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

    public void registerVanillaRecipes(MinionItemFactory itemFactory, StorageChestRegistry storageChests) {
        for (SpecialRecipeDefinition recipe : recipes.values()) {
            if (!"VANILLA_CRAFTING_TABLE".equalsIgnoreCase(recipe.station())) continue;
            try {
                NamespacedKey key = new NamespacedKey(plugin, "special_" + recipe.id());
                Bukkit.removeRecipe(key);
                ShapedRecipe shaped = new ShapedRecipe(key, output(recipe));
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
