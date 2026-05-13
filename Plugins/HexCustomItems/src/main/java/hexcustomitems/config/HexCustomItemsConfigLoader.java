package hexcustomitems.config;

import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.model.CustomItemEffectType;
import hexcustomitems.model.ItemEffectSettings;
import hexcustomitems.model.PotionEffectSpec;
import org.bukkit.Material;
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

        String prefix = readString(config, "prefix", "&8[&6HexItems&8]&f ", logger);
        String givePermission = readString(config, "permissions.give", "hex.items.give", logger);
        String reloadPermission = readString(config, "permissions.reload", "hex.items.reload", logger);
        int maxGiveAmount = Math.max(1, config.getInt("settings.max-give-amount", 64));
        boolean protectOps = config.getBoolean("settings.protect-ops-from-negative-effects", true);

        HexCustomItemsConfig.Messages messages = new HexCustomItemsConfig.Messages(
                readString(config, "messages.no-permission", "&cBrak uprawnień.", logger),
                readString(config, "messages.player-not-found", "&cTen gracz nie jest online.", logger),
                readString(config, "messages.invalid-number", "&cNiepoprawna liczba.", logger),
                readString(config, "messages.item-not-found", "&cNie znaleziono itemu: &e%item_id%&c.", logger),
                readString(config, "messages.usage-main", "&7Użycie: /hexcustomitems <give|reload|list>", logger),
                readString(config, "messages.usage-give", "&7Użycie: /hexcustomitems give <item_id> <player> [amount]", logger),
                readString(config, "messages.reloaded", "&aKonfiguracja HexCustomItems została przeładowana.", logger),
                readString(config, "messages.given-sender", "&aDano item.", logger),
                readString(config, "messages.given-target", "&7Otrzymałeś item.", logger),
                readString(config, "messages.list-header", "&7Dostępne itemy: &e%items%", logger),
                readString(config, "messages.target-player-required", "&cMusisz celować w gracza.", logger),
                readString(config, "messages.target-too-far", "&cGracz jest za daleko.", logger),
                readString(config, "messages.target-op-protected", "&cNie możesz użyć tego itemu na operatorze.", logger),
                readString(config, "messages.drop-blocked", "&cNie możesz wyrzucić tego itemu.", logger)
        );

        HexCustomItemsConfig.Sounds sounds = new HexCustomItemsConfig.Sounds(
                readString(config, "sounds.consume", "item.book.page_turn", logger),
                readString(config, "sounds.drink", "entity.generic.drink", logger),
                readString(config, "sounds.dark", "entity.vex.charge", logger),
                readString(config, "sounds.fire", "item.flintandsteel.use", logger),
                readString(config, "sounds.ice", "block.glass.break", logger),
                readString(config, "sounds.throw", "entity.witch.throw", logger),
                readString(config, "sounds.wind-launch", "entity.wither.shoot", logger),
                readString(config, "sounds.wind-hit", "entity.generic.explode", logger)
        );

        HexCustomItemsConfig.WindSettings windSettings = new HexCustomItemsConfig.WindSettings(
                config.getDouble("wind-charge.radius", 2.8D),
                config.getDouble("wind-charge.power", 1.55D),
                config.getDouble("wind-charge.power-owner", 0.65D),
                config.getDouble("wind-charge.up", 0.36D),
                config.getDouble("wind-charge.up-owner", 0.14D),
                config.getDouble("wind-charge.recoil", 0.28D),
                config.getDouble("wind-charge.recoil-up", 0.08D),
                config.getDouble("wind-charge.projectile-speed", 1.7D),
                config.getInt("wind-charge.particle-explosion-count", 3),
                config.getInt("wind-charge.particle-range", 32)
        );

        Map<String, CustomItemDefinition> items = loadItems(config, logger);
        Map<String, String> legacyBindings = loadLegacyBindings();

        return new HexCustomItemsConfig(
                prefix,
                givePermission,
                reloadPermission,
                maxGiveAmount,
                protectOps,
                messages,
                sounds,
                windSettings,
                items,
                legacyBindings
        );
    }

    private Map<String, CustomItemDefinition> loadItems(FileConfiguration config, Logger logger) {
        ConfigurationSection itemsSection = config.getConfigurationSection("items");
        if (itemsSection == null) {
            throw new IllegalStateException("Brak sekcji items w config.yml");
        }

        Map<String, CustomItemDefinition> items = new LinkedHashMap<>();
        for (String itemId : itemsSection.getKeys(false)) {
            String root = "items." + itemId;
            Material material = parseMaterial(
                    config.getString(root + ".material"),
                    Material.PAPER,
                    logger,
                    root + ".material"
            );
            String name = readString(config, root + ".name", "&f" + itemId, logger);
            List<String> lore = config.getStringList(root + ".lore");
            boolean dropProtection = config.getBoolean(root + ".drop-protection", false);

            ConfigurationSection effectSection = config.getConfigurationSection(root + ".effect");
            if (effectSection == null) {
                logger.warning("Brak sekcji " + root + ".effect. Pomijam item.");
                continue;
            }

            ItemEffectSettings effectSettings = parseEffectSettings(effectSection, logger, root + ".effect");
            items.put(
                    itemId.toLowerCase(Locale.ROOT),
                    new CustomItemDefinition(itemId.toLowerCase(Locale.ROOT), material, name, lore, dropProtection, effectSettings)
            );
        }

        if (items.isEmpty()) {
            throw new IllegalStateException("Brak poprawnych itemów w sekcji items");
        }
        return Map.copyOf(items);
    }

    private ItemEffectSettings parseEffectSettings(ConfigurationSection effectSection, Logger logger, String rootPath) {
        String typeRaw = effectSection.getString("type", "HEX_COINS");
        CustomItemEffectType type;
        try {
            type = CustomItemEffectType.valueOf(typeRaw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            logger.warning("Niepoprawny effect.type '" + typeRaw + "' w " + rootPath + ". Używam HEX_COINS.");
            type = CustomItemEffectType.HEX_COINS;
        }

        int coins = Math.max(0, effectSection.getInt("coins", 0));
        String commandTemplate = effectSection.getString("command-template", "eco give %player% %coins%");
        PotionEffectSpec potionEffect = parsePotionEffectSpec(effectSection, logger, rootPath);
        double radius = effectSection.getDouble("radius", 4.0D);
        boolean affectSelf = effectSection.getBoolean("affect-self", false);
        double maxDistance = effectSection.getDouble("max-distance", 5.0D);
        int fireSeconds = Math.max(1, effectSection.getInt("fire-seconds", 5));
        List<PotionEffectSpec> areaEffects = parseAreaEffects(effectSection.getMapList("effects"), logger, rootPath + ".effects");

        return new ItemEffectSettings(
                type,
                coins,
                commandTemplate,
                potionEffect,
                radius,
                affectSelf,
                maxDistance,
                fireSeconds,
                areaEffects
        );
    }

    private PotionEffectSpec parsePotionEffectSpec(ConfigurationSection section, Logger logger, String path) {
        String potionRaw = section.getString("potion", "SPEED");
        PotionEffectType type = parsePotionType(potionRaw, logger, path + ".potion");
        int durationSeconds = Math.max(1, section.getInt("duration-seconds", 5));
        int amplifier = Math.max(0, section.getInt("amplifier", 0));
        return new PotionEffectSpec(type, durationSeconds, amplifier);
    }

    private List<PotionEffectSpec> parseAreaEffects(List<Map<?, ?>> maps, Logger logger, String path) {
        if (maps.isEmpty()) {
            return List.of();
        }

        List<PotionEffectSpec> result = new ArrayList<>();
        for (Map<?, ?> map : maps) {
            Object potionRaw = map.get("potion");
            if (!(potionRaw instanceof String potionName) || potionName.isBlank()) {
                logger.warning("Brak pola potion w " + path + ". Pomijam wpis.");
                continue;
            }
            PotionEffectType type = parsePotionType(potionName, logger, path + ".potion");
            int duration = map.get("duration-seconds") instanceof Number n ? Math.max(1, n.intValue()) : 5;
            int amplifier = map.get("amplifier") instanceof Number n2 ? Math.max(0, n2.intValue()) : 0;
            result.add(new PotionEffectSpec(type, duration, amplifier));
        }
        return List.copyOf(result);
    }

    private PotionEffectType parsePotionType(String raw, Logger logger, String path) {
        PotionEffectType type = PotionEffectType.getByName(raw);
        if (type != null) {
            return type;
        }
        logger.warning("Niepoprawny potion type '" + raw + "' w " + path + ". Używam SPEED.");
        return PotionEffectType.SPEED;
    }

    private Material parseMaterial(String raw, Material fallback, Logger logger, String path) {
        if (raw == null || raw.isBlank()) {
            logger.warning("Brak materiału w " + path + ". Używam " + fallback + ".");
            return fallback;
        }
        Material material = Material.matchMaterial(raw);
        if (material == null) {
            logger.warning("Niepoprawny materiał '" + raw + "' w " + path + ". Używam " + fallback + ".");
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

    private Map<String, String> loadLegacyBindings() {
        Map<String, String> bindings = new LinkedHashMap<>();
        bindings.put("hex_item_potkaskoku", "jump_potion");
        bindings.put("hex_item_potkaspowolnienia", "slowness_splash");
        bindings.put("hex_item_ciastkoniewidka", "invisibility_cookie");
        bindings.put("hex_item_proszekciemnosci", "dark_powder");
        bindings.put("hex_item_windcharge", "wind_charge");
        bindings.put("hex_item_hexcoin1", "hex_coin_1");
        bindings.put("hex_item_hexcoins2", "hex_coin_2");
        bindings.put("hex_item_hexcoins3", "hex_coin_3");
        bindings.put("hex_item_hexcoins5", "hex_coin_5");
        bindings.put("hex_item_fireballitem", "fireball_item");
        bindings.put("hex_item_iceballitem", "iceball_item");
        bindings.put("hex_item_potkazawrotow", "nausea_splash");
        bindings.put("hex_item_potkaspeedu", "speed_potion");
        return Map.copyOf(bindings);
    }
}
