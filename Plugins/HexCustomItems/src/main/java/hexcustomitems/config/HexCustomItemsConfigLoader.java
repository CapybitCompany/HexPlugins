package hexcustomitems.config;

import hexcustomitems.model.CommandAction;
import hexcustomitems.model.CommandExecutorType;
import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.model.ItemAction;
import hexcustomitems.model.MessageAction;
import hexcustomitems.model.PotionEffectSpec;
import hexcustomitems.model.SelfPotionAction;
import hexcustomitems.model.SoundAction;
import hexcustomitems.model.SpecialAction;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public final class HexCustomItemsConfigLoader {

    private static final Pattern CUSTOM_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");

    private final JavaPlugin plugin;

    public HexCustomItemsConfigLoader(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public HexCustomItemsConfig load() {
        FileConfiguration config = plugin.getConfig();
        Logger logger = plugin.getLogger();

        String prefix = readString(config, "prefix", "&8[&6HexItems&8]&f ", logger);
        String givePermission = readString(config, "permissions.give", "hex.items.give", logger);
        String reloadPermission = readString(config, "permissions.reload", "hex.items.reload", logger);
        String itemPermissionDefault = config.getString("permissions.item-default", "true");
        int maxGiveAmount = Math.max(1, config.getInt("settings.max-give-amount", 64));
        String menuTitle = readString(config, "settings.adminpanel-title", "&8Custom Itemy", logger);
        int menuShiftGiveAmount = Math.max(1, config.getInt("settings.menu-shift-give-amount", 16));

        HexCustomItemsConfig.Messages messages = loadMessages(config, logger);
        HexCustomItemsConfig.Sounds sounds = new HexCustomItemsConfig.Sounds(
                readString(config, "sounds.consume", "minecraft:item.book.page_turn", logger),
                readString(config, "sounds.drink", "minecraft:entity.generic.drink", logger)
        );
        HexCustomItemsConfig.RegionAwareness regionAwareness = new HexCustomItemsConfig.RegionAwareness(
                config.getBoolean("region-awareness.enabled", true),
                config.getBoolean("region-awareness.fail-closed", false),
                config.getBoolean("region-awareness.respect-pvp", true),
                readString(config, "region-awareness.blocked-message", "&cNie możesz użyć tego przedmiotu tutaj.", logger)
        );
        HexCustomItemsConfig.Cooldowns cooldowns = new HexCustomItemsConfig.Cooldowns(
                config.getBoolean("cooldowns.persist", true),
                config.getString("cooldowns.file", "cooldowns.yml")
        );

        Map<String, CustomItemDefinition> items = loadItems(config, logger);
        Map<String, String> itemIds = indexIds(items, logger);
        HexCustomItemsConfig.Recipes recipes = new HexCustomItemsConfig.Recipes(
                config.getBoolean("recipes.enabled", true),
                loadRecipes(config, logger)
        );
        HexCustomItemsConfig.MobDrops mobDrops = new HexCustomItemsConfig.MobDrops(
                config.getBoolean("mob-drops.enabled", true),
                loadMobDrops(config, logger)
        );

        return new HexCustomItemsConfig(
                prefix,
                givePermission,
                reloadPermission,
                itemPermissionDefault,
                maxGiveAmount,
                menuTitle,
                menuShiftGiveAmount,
                messages,
                sounds,
                regionAwareness,
                cooldowns,
                recipes,
                mobDrops,
                items,
                itemIds
        );
    }

    private HexCustomItemsConfig.Messages loadMessages(FileConfiguration config, Logger logger) {
        return new HexCustomItemsConfig.Messages(
                readString(config, "messages.no-permission", "&cBrak uprawnień.", logger),
                readString(config, "messages.use-no-permission", "&cNie możesz użyć tego przedmiotu.", logger),
                readString(config, "messages.player-not-found", "&cTen gracz nie jest online.", logger),
                readString(config, "messages.invalid-number", "&cNiepoprawna liczba.", logger),
                readString(config, "messages.item-not-found", "&cNie znaleziono przedmiotu: &e<item_id>&c.", logger),
                readString(config, "messages.usage-main", "&7Użycie: &e/hexcustomitem <reload|adminpanel|id player ilość>", logger),
                readString(config, "messages.usage-give", "&7Użycie: &e/hexcustomitem <item_id> <player> <ilość>", logger),
                readString(config, "messages.reloaded", "&aKonfiguracja HexCustomItems została przeładowana.", logger),
                readString(config, "messages.given-sender", "&aDano &e<amount>x <item_name> &agraczowi &e<target>&a.", logger),
                readString(config, "messages.given-target", "&7Otrzymałeś &e<amount>x <item_name>&7.", logger),
                readString(config, "messages.list-header", "&7Dostępne przedmioty: &e<items>", logger),
                readString(config, "messages.cooldown-active", "&cMusisz odczekać &e<time>s&c.", logger),
                readString(config, "messages.drop-blocked", "&cNie możesz wyrzucić tego przedmiotu.", logger),
                readString(config, "messages.combat-blocked", "&cNie możesz użyć tego przedmiotu podczas walki.", logger),
                readString(config, "messages.limit-reached", "&cOsiągnąłeś limit dla tego przedmiotu.", logger),
                readString(config, "messages.no-target", "&cNie znaleziono celu.", logger),
                readString(config, "messages.already-active", "&cTen efekt jest już aktywny.", logger),
                readString(config, "messages.anvil-blocked", "&cNie możesz użyć tego przedmiotu w kowadle.", logger)
        );
    }

    private Map<String, CustomItemDefinition> loadItems(FileConfiguration config, Logger logger) {
        ConfigurationSection section = config.getConfigurationSection("items");
        if (section == null) {
            throw new IllegalArgumentException("Brak sekcji items w config.yml");
        }
        Map<String, CustomItemDefinition> items = new LinkedHashMap<>();
        Map<Integer, String> modelData = new LinkedHashMap<>();
        for (String rawKey : section.getKeys(false)) {
            String key = rawKey.toLowerCase(Locale.ROOT);
            String root = "items." + rawKey;
            Material material = parseMaterial(config.getString(root + ".material"), Material.PAPER, logger, root + ".material");
            String id = firstString(config, root + ".ID", root + ".id");
            if (id == null || id.isBlank()) {
                id = "hex:" + key;
            }
            id = id.toLowerCase(Locale.ROOT);
            if (!CUSTOM_ID.matcher(id).matches()) {
                logger.warning("Niepoprawne ID custom itemu '" + id + "' w " + root + ". Pomijam item.");
                continue;
            }
            int cmd = Math.max(0, config.getInt(root + ".model-data", 0));
            if (cmd > 0 && modelData.containsKey(cmd)) {
                logger.warning("Duplikat model-data " + cmd + " dla " + rawKey + " i " + modelData.get(cmd) + ".");
            }
            modelData.put(cmd, rawKey);

            String name = readString(config, root + ".name", "&f" + rawKey, logger);
            List<String> lore = config.getStringList(root + ".lore");
            boolean canDrop = config.getBoolean(root + ".can-drop", !config.getBoolean(root + ".drop-protection", false));
            boolean canUseInAnvil = config.getBoolean(root + ".can-use-in-anvil", false);
            boolean glint = config.getBoolean(root + ".glint", false);
            String permission = config.getString(root + ".permission");
            int cooldownSeconds = Math.max(0, config.getInt(root + ".cooldown-seconds", 0));
            int adminPanelStack = Math.max(1, config.getInt(root + ".adminpanel-stack", config.getInt(root + ".admin-stack", 1)));
            int charges = Math.max(0, config.getInt(root + ".charges", 0));
            List<ItemAction> actions = loadActions(config, root, logger);

            items.put(key, new CustomItemDefinition(
                    key, id, cmd, material, name, lore, canDrop, canUseInAnvil, glint,
                    permission, cooldownSeconds, adminPanelStack, charges, actions
            ));
        }
        return items;
    }

    private Map<String, String> indexIds(Map<String, CustomItemDefinition> items, Logger logger) {
        Map<String, String> byId = new LinkedHashMap<>();
        for (CustomItemDefinition item : items.values()) {
            String previous = byId.put(item.id(), item.key());
            if (previous != null) {
                logger.warning("Duplikat custom ID '" + item.id() + "' w itemach " + previous + " i " + item.key() + ".");
            }
        }
        return byId;
    }

    private List<ItemAction> loadActions(FileConfiguration config, String root, Logger logger) {
        List<Map<?, ?>> rawActions = config.getMapList(root + ".actions");
        if (rawActions.isEmpty()) {
            return List.of();
        }
        List<ItemAction> actions = new ArrayList<>();
        for (Map<?, ?> raw : rawActions) {
            ItemAction action = parseAction(raw, root + ".actions", logger);
            if (action != null) {
                actions.add(action);
            }
        }
        return actions;
    }

    private ItemAction parseAction(Map<?, ?> raw, String path, Logger logger) {
        String type = asString(raw.get("type"), "").trim().toUpperCase(Locale.ROOT);
        boolean offensive = asBool(raw.get("offensive"), false);
        return switch (type) {
            case "COMMAND" -> {
                CommandExecutorType executor = parseExecutor(asString(raw.get("executor"), "CONSOLE"));
                List<String> commands = asStringList(raw.get("commands"));
                if (commands.isEmpty()) {
                    logger.warning(path + " COMMAND bez commands - pomijam.");
                    yield null;
                }
                yield new CommandAction(executor, commands, offensive);
            }
            case "SELF_POTION" -> {
                PotionEffectSpec effect = parsePotionSpec(raw, logger, path);
                yield new SelfPotionAction(effect, offensive);
            }
            case "MESSAGE" -> {
                String message = asString(raw.get("message"), "");
                if (message.isBlank()) {
                    logger.warning(path + " MESSAGE bez message - pomijam.");
                    yield null;
                }
                yield new MessageAction(message, offensive);
            }
            case "SOUND" -> {
                String sound = asString(raw.get("sound"), "");
                if (sound.isBlank()) {
                    logger.warning(path + " SOUND bez sound - pomijam.");
                    yield null;
                }
                yield new SoundAction(sound, (float) asDouble(raw.get("volume"), 1.0D),
                        (float) asDouble(raw.get("pitch"), 1.0D), asInt(raw.get("delay-ticks"), 0), offensive);
            }
            default -> {
                if (type.isBlank()) {
                    logger.warning(path + " akcja bez type - pomijam.");
                    yield null;
                }
                Map<String, String> params = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : raw.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    if ("type".equalsIgnoreCase(key) || "offensive".equalsIgnoreCase(key)) {
                        continue;
                    }
                    params.put(key, String.valueOf(entry.getValue()));
                }
                yield new SpecialAction(type, params, offensive);
            }
        };
    }

    private PotionEffectSpec parsePotionSpec(Map<?, ?> raw, Logger logger, String path) {
        PotionEffectType type = parsePotionType(asString(raw.get("potion"), "speed"), logger, path + ".potion");
        int duration = Math.max(1, asInt(raw.get("duration-seconds"), 5));
        int amplifier = Math.max(0, asInt(raw.get("amplifier"), 0));
        return new PotionEffectSpec(type, duration, amplifier);
    }

    private Map<String, HexCustomItemsConfig.RecipeSpec> loadRecipes(FileConfiguration config, Logger logger) {
        ConfigurationSection section = config.getConfigurationSection("recipes.items");
        if (section == null) {
            return Map.of();
        }
        Map<String, HexCustomItemsConfig.RecipeSpec> recipes = new LinkedHashMap<>();
        for (String recipeKey : section.getKeys(false)) {
            String base = "recipes.items." + recipeKey;
            String result = config.getString(base + ".result", recipeKey).toLowerCase(Locale.ROOT);
            int amount = Math.max(1, config.getInt(base + ".amount", 1));
            List<String> shape = config.getStringList(base + ".shape");
            if (shape.size() != 3 || shape.stream().anyMatch(row -> row.length() != 3)) {
                logger.warning("Receptura " + recipeKey + " musi mieć shape 3x3 - pomijam.");
                continue;
            }
            Map<String, HexCustomItemsConfig.IngredientSpec> ingredients = new LinkedHashMap<>();
            ConfigurationSection ingredientSection = config.getConfigurationSection(base + ".ingredients");
            if (ingredientSection != null) {
                for (String symbol : ingredientSection.getKeys(false)) {
                    ingredients.put(symbol, parseIngredient(ingredientSection, symbol, logger, base + ".ingredients." + symbol));
                }
            }
            recipes.put(recipeKey.toLowerCase(Locale.ROOT), new HexCustomItemsConfig.RecipeSpec(result, amount, shape, ingredients));
        }
        return recipes;
    }

    private HexCustomItemsConfig.IngredientSpec parseIngredient(ConfigurationSection section, String symbol, Logger logger, String path) {
        Object raw = section.get(symbol);
        if (raw instanceof String materialName) {
            return new HexCustomItemsConfig.IngredientSpec(parseMaterial(materialName, Material.AIR, logger, path),
                    null, null, 0, 1);
        }
        String base = section.getCurrentPath() + "." + symbol;
        Material material = parseMaterial(section.getString(symbol + ".material"), Material.AIR, logger, base + ".material");
        String customItem = section.getString(symbol + ".custom-item");
        String enchantment = section.getString(symbol + ".enchantment");
        int enchantmentLevel = Math.max(0, section.getInt(symbol + ".enchantment-level", 0));
        int amount = Math.max(1, section.getInt(symbol + ".amount", 1));
        return new HexCustomItemsConfig.IngredientSpec(material, customItem, enchantment, enchantmentLevel, amount);
    }

    private Map<EntityType, List<HexCustomItemsConfig.MobDropSpec>> loadMobDrops(FileConfiguration config, Logger logger) {
        ConfigurationSection section = config.getConfigurationSection("mob-drops.drops");
        if (section == null) {
            return Map.of();
        }
        Map<EntityType, List<HexCustomItemsConfig.MobDropSpec>> drops = new LinkedHashMap<>();
        for (String entityName : section.getKeys(false)) {
            EntityType type;
            try {
                type = EntityType.valueOf(entityName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                logger.warning("Nieznany mob w mob-drops: " + entityName);
                continue;
            }
            List<HexCustomItemsConfig.MobDropSpec> specs = new ArrayList<>();
            for (Map<?, ?> raw : config.getMapList("mob-drops.drops." + entityName)) {
                String item = asString(raw.get("item"), "");
                if (item.isBlank()) {
                    continue;
                }
                specs.add(new HexCustomItemsConfig.MobDropSpec(item, asDouble(raw.get("chance"), 0.0D), asInt(raw.get("amount"), 1)));
            }
            drops.put(type, List.copyOf(specs));
        }
        return drops;
    }

    private PotionEffectType parsePotionType(String raw, Logger logger, String path) {
        NamespacedKey key = NamespacedKey.fromString(raw.trim().toLowerCase(Locale.ROOT));
        if (key == null) {
            key = NamespacedKey.minecraft(raw.trim().toLowerCase(Locale.ROOT));
        }
        PotionEffectType type = Registry.EFFECT.get(key);
        if (type == null) {
            logger.warning("Niepoprawny potion type '" + raw + "' w " + path + ". Używam SPEED.");
            return Registry.EFFECT.get(NamespacedKey.minecraft("speed"));
        }
        return type;
    }

    private CommandExecutorType parseExecutor(String raw) {
        try {
            return CommandExecutorType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return CommandExecutorType.CONSOLE;
        }
    }

    private Material parseMaterial(String raw, Material fallback, Logger logger, String path) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        if (material == null) {
            logger.warning("Niepoprawny material '" + raw + "' w " + path + ". Używam " + fallback + ".");
            return fallback;
        }
        return material;
    }

    private String firstString(FileConfiguration config, String first, String second) {
        String value = config.getString(first);
        return value == null ? config.getString(second) : value;
    }

    private String readString(FileConfiguration config, String path, String fallback, Logger logger) {
        String value = config.getString(path);
        if (value == null) {
            logger.fine("Brak " + path + " w config.yml, używam domyślnej wartości.");
            return fallback;
        }
        return value;
    }

    private String asString(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private int asInt(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private double asDouble(Object value, double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private boolean asBool(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return fallback;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object entry : list) {
                result.add(String.valueOf(entry));
            }
            return result;
        }
        if (value instanceof String string && !string.isBlank()) {
            return List.of(string);
        }
        return List.of();
    }
}
