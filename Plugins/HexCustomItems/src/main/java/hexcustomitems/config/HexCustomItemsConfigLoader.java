package hexcustomitems.config;

import hexcustomitems.model.CommandAction;
import hexcustomitems.model.CommandExecutorType;
import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.model.ItemAction;
import hexcustomitems.model.MessageAction;
import hexcustomitems.model.PotionEffectSpec;
import hexcustomitems.model.SelfPotionAction;
import hexcustomitems.model.SoundAction;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

public final class HexCustomItemsConfigLoader {

    private final JavaPlugin plugin;

    public HexCustomItemsConfigLoader(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public HexCustomItemsConfig load() {
        FileConfiguration config = plugin.getConfig();
        Logger logger = plugin.getLogger();

        String prefix = readString(config, "prefix", "<dark_gray>[<gold>HexItems<dark_gray>]<white> ", logger);
        String givePermission = readString(config, "permissions.give", "hex.items.give", logger);
        String reloadPermission = readString(config, "permissions.reload", "hex.items.reload", logger);
        String itemPermissionDefault = config.getString("permissions.item-default", "true");
        int maxGiveAmount = Math.max(1, config.getInt("settings.max-give-amount", 64));
        String menuTitle = readString(config, "settings.menu-title", "<gold>Custom Itemy", logger);
        int menuShiftGiveAmount = Math.max(1, config.getInt("settings.menu-shift-give-amount", 16));

        HexCustomItemsConfig.Messages messages = loadMessages(config, logger);
        HexCustomItemsConfig.Sounds sounds = new HexCustomItemsConfig.Sounds(
                readString(config, "sounds.consume", "item.book.page_turn", logger),
                readString(config, "sounds.drink", "entity.generic.drink", logger)
        );
        HexCustomItemsConfig.RegionAwareness regionAwareness = new HexCustomItemsConfig.RegionAwareness(
                config.getBoolean("region-awareness.enabled", true),
                config.getBoolean("region-awareness.fail-closed", false),
                config.getBoolean("region-awareness.respect-pvp", true),
                readString(config, "region-awareness.blocked-message", "<red>Nie możesz użyć tego przedmiotu tutaj.", logger)
        );
        HexCustomItemsConfig.Cooldowns cooldowns = new HexCustomItemsConfig.Cooldowns(
                config.getBoolean("cooldowns.persist", true),
                config.getString("cooldowns.file", "cooldowns.yml")
        );

        Map<String, CustomItemDefinition> items = loadItems(config, sounds, logger);
        Map<String, HexCustomItemsConfig.RecipeSpec> recipeSpecs = loadRecipes(config, logger);
        HexCustomItemsConfig.Recipes recipes = new HexCustomItemsConfig.Recipes(
                config.getBoolean("recipes.enabled", true),
                recipeSpecs
        );
        Map<String, String> legacyBindings = loadLegacyBindings(config, logger);

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
                items,
                legacyBindings
        );
    }

    private HexCustomItemsConfig.Messages loadMessages(FileConfiguration config, Logger logger) {
        return new HexCustomItemsConfig.Messages(
                readString(config, "messages.no-permission", "<red>Brak uprawnień.", logger),
                readString(config, "messages.use-no-permission", "<red>Nie możesz użyć tego przedmiotu.", logger),
                readString(config, "messages.player-not-found", "<red>Ten gracz nie jest online.", logger),
                readString(config, "messages.invalid-number", "<red>Niepoprawna liczba.", logger),
                readString(config, "messages.item-not-found", "<red>Nie znaleziono przedmiotu: <yellow><item_id></yellow>.", logger),
                readString(config, "messages.usage-main", "<gray>Użycie: <yellow>/hexcustomitems <give|reload|list|menu>", logger),
                readString(config, "messages.usage-give", "<gray>Użycie: <yellow>/hexcustomitems give <item_id> <player> [amount]", logger),
                readString(config, "messages.reloaded", "<green>Konfiguracja HexCustomItems została przeładowana.", logger),
                readString(config, "messages.given-sender", "<green>Dano <yellow><amount>x <item_name> <green>graczowi <yellow><target><green>.", logger),
                readString(config, "messages.given-target", "<gray>Otrzymałeś <yellow><amount>x <item_name><gray>.", logger),
                readString(config, "messages.list-header", "<gray>Dostępne przedmioty: <yellow><items>", logger),
                readString(config, "messages.cooldown-active", "<red>Musisz odczekać <yellow><time>s <red>zanim użyjesz ponownie.", logger),
                readString(config, "messages.drop-blocked", "<red>Nie możesz wyrzucić tego przedmiotu.", logger)
        );
    }

    private Map<String, CustomItemDefinition> loadItems(FileConfiguration config, HexCustomItemsConfig.Sounds sounds, Logger logger) {
        ConfigurationSection itemsSection = config.getConfigurationSection("items");
        if (itemsSection == null) {
            throw new IllegalStateException("Brak sekcji items w config.yml");
        }

        Map<String, CustomItemDefinition> items = new LinkedHashMap<>();
        for (String itemId : itemsSection.getKeys(false)) {
            String root = "items." + itemId;
            String id = itemId.toLowerCase(Locale.ROOT);

            Material material = parseMaterial(config.getString(root + ".material"), Material.PAPER, logger, root + ".material");
            String name = readString(config, root + ".name", "<white>" + itemId, logger);
            List<String> lore = config.getStringList(root + ".lore");
            boolean dropProtection = config.getBoolean(root + ".drop-protection", false);
            String permission = config.getString(root + ".permission");
            int cooldownSeconds = Math.max(0, config.getInt(root + ".cooldown-seconds", 0));
            int charges = Math.max(0, config.getInt(root + ".charges", 0));

            List<ItemAction> actions = loadActions(config, root, sounds, logger);
            if (actions.isEmpty()) {
                logger.warning("Przedmiot " + root + " nie ma żadnej poprawnej akcji - pomijam (nie da się go użyć).");
                continue;
            }

            items.put(id, new CustomItemDefinition(id, material, name, lore, dropProtection, permission, cooldownSeconds, charges, actions));
        }

        if (items.isEmpty()) {
            throw new IllegalStateException("Brak poprawnych przedmiotów w sekcji items");
        }
        return Map.copyOf(items);
    }

    /** Neues actions-System; fällt bei fehlender actions-Sektion auf die alte effect-Sektion zurück. */
    private List<ItemAction> loadActions(FileConfiguration config, String root, HexCustomItemsConfig.Sounds sounds, Logger logger) {
        List<Map<?, ?>> rawActions = config.getMapList(root + ".actions");
        if (!rawActions.isEmpty()) {
            List<ItemAction> actions = new ArrayList<>();
            for (Map<?, ?> raw : rawActions) {
                ItemAction action = parseAction(raw, root + ".actions", logger);
                if (action != null) {
                    actions.add(action);
                }
            }
            return actions;
        }

        ConfigurationSection legacyEffect = config.getConfigurationSection(root + ".effect");
        if (legacyEffect != null) {
            return translateLegacyEffect(legacyEffect, sounds, logger, root + ".effect");
        }

        logger.warning("Przedmiot " + root + " nie ma sekcji 'actions' ani 'effect' - brak akcji.");
        return List.of();
    }

    private ItemAction parseAction(Map<?, ?> raw, String path, Logger logger) {
        String type = asString(raw.get("type"), "").trim().toUpperCase(Locale.ROOT);
        boolean offensive = asBool(raw.get("offensive"), false);

        switch (type) {
            case "COMMAND", "HEX_COINS" -> {
                CommandExecutorType executor = parseExecutor(asString(raw.get("executor"), "CONSOLE"), logger, path);
                List<String> commands = asStringList(raw.get("commands"));
                if (commands.isEmpty() && raw.get("command") != null) {
                    commands = List.of(asString(raw.get("command"), ""));
                }
                if (commands.isEmpty()) {
                    logger.warning("Akcja COMMAND w " + path + " nie ma 'commands' - pomijam.");
                    return null;
                }
                return new CommandAction(executor, commands, offensive);
            }
            case "SELF_POTION" -> {
                PotionEffectSpec effect = parsePotionSpecFromMap(raw, logger, path);
                return new SelfPotionAction(effect, offensive);
            }
            case "MESSAGE" -> {
                String message = asString(raw.get("message"), "");
                if (message.isBlank()) {
                    logger.warning("Akcja MESSAGE w " + path + " nie ma 'message' - pomijam.");
                    return null;
                }
                return new MessageAction(message, offensive);
            }
            case "SOUND" -> {
                String sound = asString(raw.get("sound"), "");
                if (sound.isBlank()) {
                    logger.warning("Akcja SOUND w " + path + " nie ma 'sound' - pomijam.");
                    return null;
                }
                float volume = (float) asDouble(raw.get("volume"), 1.0D);
                float pitch = (float) asDouble(raw.get("pitch"), 1.0D);
                return new SoundAction(sound, volume, pitch, offensive);
            }
            default -> {
                logger.warning("Nieznany typ akcji '" + type + "' w " + path + " - pomijam.");
                return null;
            }
        }
    }

    /** Übersetzt die alte effect-Sektion in Aktionen (Backward-Compatibility). */
    private List<ItemAction> translateLegacyEffect(ConfigurationSection effect, HexCustomItemsConfig.Sounds sounds, Logger logger, String path) {
        String type = effect.getString("type", "SELF_POTION").trim().toUpperCase(Locale.ROOT);
        List<ItemAction> actions = new ArrayList<>();

        switch (type) {
            case "HEX_COINS" -> {
                int coins = Math.max(0, effect.getInt("coins", 0));
                String template = effect.getString("command-template", "eco give %player% %coins%");
                String command = template.replace("%coins%", String.valueOf(coins));
                actions.add(new CommandAction(CommandExecutorType.CONSOLE, List.of(command), false));
                actions.add(new SoundAction(sounds.consume(), 1.0F, 1.0F, false));
            }
            case "SELF_POTION" -> {
                PotionEffectType potionType = parsePotionType(effect.getString("potion", "SPEED"), logger, path + ".potion");
                int duration = Math.max(1, effect.getInt("duration-seconds", 5));
                int amplifier = Math.max(0, effect.getInt("amplifier", 0));
                actions.add(new SelfPotionAction(new PotionEffectSpec(potionType, duration, amplifier), false));
                actions.add(new SoundAction(sounds.drink(), 1.0F, 1.0F, false));
            }
            default -> logger.warning("Stary effect.type '" + type + "' w " + path
                    + " nie jest wspierany w wersji SMP - przedmiot bez akcji. Użyj sekcji 'actions'.");
        }
        return actions;
    }

    private PotionEffectSpec parsePotionSpecFromMap(Map<?, ?> raw, Logger logger, String path) {
        PotionEffectType type = parsePotionType(asString(raw.get("potion"), "SPEED"), logger, path + ".potion");
        int duration = Math.max(1, asInt(raw.get("duration-seconds"), 5));
        int amplifier = Math.max(0, asInt(raw.get("amplifier"), 0));
        return new PotionEffectSpec(type, duration, amplifier);
    }

    private CommandExecutorType parseExecutor(String raw, Logger logger, String path) {
        try {
            return CommandExecutorType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            logger.warning("Niepoprawny executor '" + raw + "' w " + path + ". Używam CONSOLE.");
            return CommandExecutorType.CONSOLE;
        }
    }

    private Map<String, HexCustomItemsConfig.RecipeSpec> loadRecipes(FileConfiguration config, Logger logger) {
        ConfigurationSection recipesSection = config.getConfigurationSection("recipes.items");
        if (recipesSection == null) {
            return Map.of();
        }

        Map<String, HexCustomItemsConfig.RecipeSpec> result = new LinkedHashMap<>();
        for (String itemId : recipesSection.getKeys(false)) {
            String base = "recipes.items." + itemId;
            String type = config.getString(base + ".type", "shaped").toLowerCase(Locale.ROOT);
            int amount = Math.max(1, config.getInt(base + ".amount", 1));

            if ("shapeless".equals(type)) {
                List<Material> materials = new ArrayList<>();
                for (String rawMat : config.getStringList(base + ".ingredients")) {
                    Material mat = parseMaterial(rawMat, null, logger, base + ".ingredients");
                    if (mat != null) {
                        materials.add(mat);
                    }
                }
                result.put(itemId.toLowerCase(Locale.ROOT), new HexCustomItemsConfig.RecipeSpec(type, List.of(), Map.of(), materials, amount));
            } else {
                List<String> shape = config.getStringList(base + ".shape");
                Map<String, Material> ingredients = new LinkedHashMap<>();
                ConfigurationSection ingredientsSection = config.getConfigurationSection(base + ".ingredients");
                if (ingredientsSection != null) {
                    for (String symbol : ingredientsSection.getKeys(false)) {
                        Material mat = parseMaterial(ingredientsSection.getString(symbol), null, logger, base + ".ingredients." + symbol);
                        if (mat != null) {
                            ingredients.put(symbol, mat);
                        }
                    }
                }
                result.put(itemId.toLowerCase(Locale.ROOT), new HexCustomItemsConfig.RecipeSpec(type, shape, ingredients, List.of(), amount));
            }
        }
        return result;
    }

    /** Config-getriebene Legacy-Bindings; ohne Sektion greift die statische Migration. */
    private Map<String, String> loadLegacyBindings(FileConfiguration config, Logger logger) {
        ConfigurationSection section = config.getConfigurationSection("legacy-commands");
        if (section == null) {
            return defaultLegacyBindings();
        }
        Map<String, String> bindings = new LinkedHashMap<>();
        for (String command : section.getKeys(false)) {
            String itemId = section.getString(command);
            if (itemId == null || itemId.isBlank()) {
                logger.warning("Legacy-command '" + command + "' nie wskazuje na żaden przedmiot - pomijam.");
                continue;
            }
            bindings.put(command, itemId.toLowerCase(Locale.ROOT));
        }
        return Map.copyOf(bindings);
    }

    private Map<String, String> defaultLegacyBindings() {
        Map<String, String> bindings = new LinkedHashMap<>();
        bindings.put("hex_item_potkaskoku", "jump_potion");
        bindings.put("hex_item_ciastkoniewidka", "invisibility_cookie");
        bindings.put("hex_item_hexcoin1", "hex_coin_1");
        bindings.put("hex_item_hexcoins2", "hex_coin_2");
        bindings.put("hex_item_hexcoins3", "hex_coin_3");
        bindings.put("hex_item_hexcoins5", "hex_coin_5");
        bindings.put("hex_item_potkaspeedu", "speed_potion");
        return Map.copyOf(bindings);
    }

    private PotionEffectType parsePotionType(String raw, Logger logger, String path) {
        if (raw != null && !raw.isBlank()) {
            NamespacedKey key = NamespacedKey.fromString(raw.trim().toLowerCase(Locale.ROOT));
            if (key != null) {
                PotionEffectType type = Registry.EFFECT.get(key);
                if (type != null) {
                    return type;
                }
            }
        }
        logger.warning("Niepoprawny potion type '" + raw + "' w " + path + ". Używam SPEED.");
        return Registry.EFFECT.get(NamespacedKey.minecraft("speed"));
    }

    private Material parseMaterial(String raw, Material fallback, Logger logger, String path) {
        if (raw == null || raw.isBlank()) {
            if (fallback != null) {
                logger.warning("Brak materiału w " + path + ". Używam " + fallback + ".");
            }
            return fallback;
        }
        Material material = Material.matchMaterial(raw);
        if (material == null) {
            logger.warning("Niepoprawny materiał '" + raw + "' w " + path + (fallback != null ? ". Używam " + fallback + "." : " - pomijam."));
            return fallback;
        }
        return material;
    }

    private String readString(FileConfiguration config, String path, String fallback, Logger logger) {
        String value = config.getString(path);
        if (value == null || value.isBlank()) {
            logger.warning("Brak lub pusta wartość '" + path + "'. Używam domyślnej.");
            return fallback;
        }
        return value;
    }

    // ---- Hilfen zum Lesen aus getMapList-Einträgen ----

    private static String asString(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean asBool(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return fallback;
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static double asDouble(Object value, double fallback) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object element : list) {
                if (element != null) {
                    result.add(String.valueOf(element));
                }
            }
            return result;
        }
        if (value instanceof String s && !s.isBlank()) {
            return List.of(s);
        }
        return List.of();
    }
}
