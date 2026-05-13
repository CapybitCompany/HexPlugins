package mysterybox.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

public final class MysteryBoxConfigLoader {

    private static final String DEFAULT_PREFIX = "&8[&cMysteryBox&8]&f ";
    private static final String DEFAULT_GIVE_PERMISSION = "hex.mysterybox.give";
    private static final int DEFAULT_MAX_GIVE_AMOUNT = 64;
    private static final String DEFAULT_BOX_NAME = "&4&lMYSTERY BOX";
    private static final String DEFAULT_VOUCHER_NAME = "&eVIP &fna obecną edycję!";
    private static final List<Integer> DEFAULT_ROW_SLOTS = List.of(9, 10, 11, 12, 13, 14, 15, 16, 17);

    private final JavaPlugin plugin;

    public MysteryBoxConfigLoader(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public MysteryBoxConfig load() {
        FileConfiguration config = plugin.getConfig();
        Logger logger = plugin.getLogger();

        String prefix = readString(config, "prefix", DEFAULT_PREFIX, logger);
        MysteryBoxConfig.CommandSettings commandSettings = loadCommandSettings(config, logger);
        MysteryBoxConfig.BoxSettings boxSettings = loadBoxSettings(config, logger);
        MysteryBoxConfig.VoucherSettings voucherSettings = loadVoucherSettings(config, logger);
        MysteryBoxConfig.AuditSettings auditSettings = loadAuditSettings(config, logger);
        MysteryBoxConfig.DropProtectionSettings dropProtectionSettings = loadDropProtectionSettings(config);
        MysteryBoxConfig.OpeningSettings openingSettings = loadOpeningSettings(config, logger);
        MysteryBoxConfig.MessageSettings messageSettings = loadMessageSettings(config, logger);
        List<MysteryBoxConfig.RewardSettings> rewards = loadRewards(config, logger);

        int totalChance = rewards.stream().mapToInt(MysteryBoxConfig.RewardSettings::chance).sum();
        if (totalChance != 100) {
            logger.warning("Suma szans nagród wynosi " + totalChance + ", zalecana wartość to 100.");
        }

        return new MysteryBoxConfig(
                prefix,
                commandSettings,
                boxSettings,
                voucherSettings,
                auditSettings,
                dropProtectionSettings,
                openingSettings,
                messageSettings,
                rewards
        );
    }

    private MysteryBoxConfig.CommandSettings loadCommandSettings(FileConfiguration config, Logger logger) {
        String permission = readString(config, "commands.give-permission", DEFAULT_GIVE_PERMISSION, logger);
        int maxAmount = Math.max(1, config.getInt("commands.max-give-amount", DEFAULT_MAX_GIVE_AMOUNT));
        return new MysteryBoxConfig.CommandSettings(permission, maxAmount);
    }

    private MysteryBoxConfig.BoxSettings loadBoxSettings(FileConfiguration config, Logger logger) {
        Material material = readMaterial(
                config.getString("box.material"),
                Material.SHULKER_BOX,
                logger,
                "box.material"
        );
        String name = readString(config, "box.name", DEFAULT_BOX_NAME, logger);
        List<String> lore = readStringList(config, "box.lore", List.of("&7Kliknij PPM, aby otworzyć."));
        return new MysteryBoxConfig.BoxSettings(material, name, lore);
    }

    private MysteryBoxConfig.VoucherSettings loadVoucherSettings(FileConfiguration config, Logger logger) {
        Material material = readMaterial(
                config.getString("voucher.material"),
                Material.PAPER,
                logger,
                "voucher.material"
        );
        String name = readString(config, "voucher.name", DEFAULT_VOUCHER_NAME, logger);
        List<String> lore = readStringList(config, "voucher.lore", List.of("&7Kliknij PPM, aby aktywować."));
        List<String> vipChecks = readStringList(
                config,
                "voucher.already-vip-check-permissions",
                List.of("group.vip")
        );
        boolean treatOpAsVip = config.getBoolean("voucher.treat-op-as-already-vip", true);

        MysteryBoxConfig.VoucherAction alreadyVip = new MysteryBoxConfig.VoucherAction(
                config.getString("voucher.already-vip.message", ""),
                config.getString("voucher.already-vip.actionbar", ""),
                readStringList(config, "voucher.already-vip.commands", List.of())
        );
        MysteryBoxConfig.VoucherAction activate = new MysteryBoxConfig.VoucherAction(
                config.getString("voucher.activate.message", "&eAktywowano VIP!"),
                config.getString("voucher.activate.actionbar", ""),
                readStringList(config, "voucher.activate.commands", List.of("lp user %player% parent settemp vip 1d"))
        );

        return new MysteryBoxConfig.VoucherSettings(
                material,
                name,
                lore,
                vipChecks,
                treatOpAsVip,
                alreadyVip,
                activate
        );
    }

    private MysteryBoxConfig.AuditSettings loadAuditSettings(FileConfiguration config, Logger logger) {
        boolean enabled = config.getBoolean("audit.enabled", true);
        String fileName = readString(config, "audit.file-name", "opening-audit.log", logger);
        return new MysteryBoxConfig.AuditSettings(enabled, fileName);
    }

    private MysteryBoxConfig.DropProtectionSettings loadDropProtectionSettings(FileConfiguration config) {
        boolean blockBox = config.getBoolean("drop-protection.block-mystery-box-drop", true);
        boolean blockVoucher = config.getBoolean("drop-protection.block-voucher-drop", true);
        return new MysteryBoxConfig.DropProtectionSettings(blockBox, blockVoucher);
    }

    private MysteryBoxConfig.OpeningSettings loadOpeningSettings(FileConfiguration config, Logger logger) {
        String title = readString(config, "opening.gui-title", "&4&lMYSTERY BOX", logger);
        int guiSize = config.getInt("opening.gui-size", 27);
        List<Integer> rowSlots = readIntegerList(config, "opening.row-slots", DEFAULT_ROW_SLOTS);
        int spinSteps = Math.max(10, config.getInt("opening.spin-steps", 45));
        int updateTicks = Math.max(1, config.getInt("opening.update-period-ticks", 2));
        int rewardDelayTicks = Math.max(0, config.getInt("opening.reward-delay-ticks", 16));

        MysteryBoxConfig.SoundSettings tickSound = new MysteryBoxConfig.SoundSettings(
                readString(config, "opening.sounds.tick.name", "block.note_block.pling", logger),
                (float) config.getDouble("opening.sounds.tick.volume", 1.0D),
                (float) config.getDouble("opening.sounds.tick.pitch", 1.0D)
        );
        MysteryBoxConfig.SoundSettings finalSound = new MysteryBoxConfig.SoundSettings(
                readString(config, "opening.sounds.final.name", "entity.player.levelup", logger),
                (float) config.getDouble("opening.sounds.final.volume", 1.0D),
                (float) config.getDouble("opening.sounds.final.pitch", 1.0D)
        );

        return new MysteryBoxConfig.OpeningSettings(
                title,
                guiSize,
                rowSlots,
                spinSteps,
                updateTicks,
                rewardDelayTicks,
                tickSound,
                finalSound
        );
    }

    private MysteryBoxConfig.MessageSettings loadMessageSettings(FileConfiguration config, Logger logger) {
        return new MysteryBoxConfig.MessageSettings(
                readString(config, "messages.no-permission", "&cNie masz uprawnień.", logger),
                readString(config, "messages.reloaded", "&aKonfiguracja MysteryBox została przeładowana.", logger),
                readString(config, "messages.player-not-found", "&cNie znaleziono gracza.", logger),
                readString(config, "messages.usage-mysterybox", "&7Użycie: /mysterybox [player] [amount]", logger),
                readString(config, "messages.only-player", "&cTa komenda jest tylko dla gracza.", logger),
                readString(config, "messages.give-success-sender", "&7Dałeś boxa.", logger),
                readString(config, "messages.give-success-target", "&7Dostałeś boxa.", logger),
                readString(config, "messages.voucher-give-success", "&7Dostałeś voucher VIP.", logger),
                readString(config, "messages.already-opening", "&cJuż otwierasz mysterybox.", logger),
                readString(config, "messages.open-started", "&7Trwa otwieranie...", logger),
                readString(config, "messages.open-won", "&7Wylosowałeś nagrodę.", logger),
                readString(config, "messages.inventory-full", "&cInventory pełne.", logger)
        );
    }

    private List<MysteryBoxConfig.RewardSettings> loadRewards(FileConfiguration config, Logger logger) {
        List<Map<?, ?>> rewardMaps = config.getMapList("rewards");
        if (rewardMaps.isEmpty()) {
            logger.warning("Brak sekcji rewards. Tworzę awaryjną nagrodę.");
            return List.of(fallbackReward());
        }

        List<MysteryBoxConfig.RewardSettings> rewards = new ArrayList<>();
        for (int index = 0; index < rewardMaps.size(); index++) {
            Map<?, ?> rewardMap = rewardMaps.get(index);
            String rootPath = "rewards[" + index + "]";

            String id = readString(rewardMap, "id", "reward_" + index, logger, rootPath + ".id");
            int chance = Math.max(0, readInt(rewardMap, "chance", 0));
            String winMessage = readString(rewardMap, "win-message", id, logger, rootPath + ".win-message");

            Map<?, ?> previewMap = readMap(rewardMap, "preview");
            Material previewMaterial = readMaterial(
                    readString(previewMap, "material", "PAPER", logger, rootPath + ".preview.material"),
                    Material.PAPER,
                    logger,
                    rootPath + ".preview.material"
            );
            String previewName = readString(previewMap, "name", "&f" + id, logger, rootPath + ".preview.name");
            List<String> previewLore = readStringList(previewMap, "lore");
            MysteryBoxConfig.RewardPreviewSettings preview = new MysteryBoxConfig.RewardPreviewSettings(
                    previewMaterial,
                    previewName,
                    previewLore
            );

            Map<?, ?> grantMap = readMap(rewardMap, "grant");
            Map<?, ?> itemMap = readMap(grantMap, "item");
            boolean itemEnabled = readBoolean(itemMap, "enabled", true);
            MysteryBoxConfig.RewardItemPreset preset = readPreset(
                    readString(itemMap, "preset", "CUSTOM", logger, rootPath + ".grant.item.preset"),
                    logger,
                    rootPath + ".grant.item.preset"
            );
            Material itemMaterial = readMaterial(
                    readString(itemMap, "material", "PAPER", logger, rootPath + ".grant.item.material"),
                    Material.PAPER,
                    logger,
                    rootPath + ".grant.item.material"
            );
            int amount = Math.max(1, readInt(itemMap, "amount", 1));
            String itemName = readString(itemMap, "name", previewName, logger, rootPath + ".grant.item.name");
            List<String> itemLore = readStringList(itemMap, "lore");

            MysteryBoxConfig.RewardGrantItemSettings grantItem = new MysteryBoxConfig.RewardGrantItemSettings(
                    itemEnabled,
                    preset,
                    itemMaterial,
                    amount,
                    itemName,
                    itemLore
            );

            List<String> commands = readStringList(grantMap, "commands");
            MysteryBoxConfig.RewardGrantSettings grant = new MysteryBoxConfig.RewardGrantSettings(grantItem, commands);

            rewards.add(new MysteryBoxConfig.RewardSettings(id, chance, winMessage, preview, grant));
        }

        if (rewards.stream().noneMatch(reward -> reward.chance() > 0)) {
            logger.warning("Wszystkie nagrody mają szansę 0. Używam awaryjnej nagrody.");
            return List.of(fallbackReward());
        }

        return List.copyOf(rewards);
    }

    private MysteryBoxConfig.RewardSettings fallbackReward() {
        MysteryBoxConfig.RewardPreviewSettings preview = new MysteryBoxConfig.RewardPreviewSettings(
                Material.COOKIE,
                "&6Awaryjna nagroda",
                List.of("&7Sprawdź konfigurację rewards.")
        );
        MysteryBoxConfig.RewardGrantItemSettings grantItem = new MysteryBoxConfig.RewardGrantItemSettings(
                true,
                MysteryBoxConfig.RewardItemPreset.CUSTOM,
                Material.COOKIE,
                1,
                "&6Awaryjna nagroda",
                List.of("&7Sprawdź konfigurację rewards.")
        );
        MysteryBoxConfig.RewardGrantSettings grant = new MysteryBoxConfig.RewardGrantSettings(grantItem, List.of());
        return new MysteryBoxConfig.RewardSettings("fallback", 100, "&6Awaryjna nagroda", preview, grant);
    }

    private Material readMaterial(String rawValue, Material fallback, Logger logger, String path) {
        if (rawValue == null || rawValue.isBlank()) {
            logger.warning("Brak wartości '" + path + "'. Używam " + fallback + ".");
            return fallback;
        }

        Material material = Material.matchMaterial(rawValue);
        if (material == null) {
            logger.warning("Niepoprawny materiał '" + rawValue + "' w '" + path + "'. Używam " + fallback + ".");
            return fallback;
        }
        return material;
    }

    private String readString(FileConfiguration config, String path, String fallback, Logger logger) {
        String value = config.getString(path);
        if (value == null || value.isBlank()) {
            logger.warning("Brak lub pusta wartość '" + path + "'. Używam wartości domyślnej.");
            return fallback;
        }
        return value;
    }

    private List<String> readStringList(FileConfiguration config, String path, List<String> fallback) {
        List<String> values = config.getStringList(path);
        if (values.isEmpty()) {
            return fallback;
        }

        List<String> parsed = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                parsed.add(value);
            }
        }
        return parsed.isEmpty() ? fallback : List.copyOf(parsed);
    }

    private List<Integer> readIntegerList(FileConfiguration config, String path, List<Integer> fallback) {
        List<Integer> parsed = new ArrayList<>();
        for (Object raw : config.getList(path, fallback)) {
            if (raw instanceof Number number) {
                parsed.add(number.intValue());
            }
        }
        return parsed.isEmpty() ? fallback : List.copyOf(parsed);
    }

    private Map<?, ?> readMap(Map<?, ?> parent, String key) {
        Object raw = parent.get(key);
        if (raw instanceof Map<?, ?> map) {
            return map;
        }
        return Map.of();
    }

    private String readString(Map<?, ?> map, String key, String fallback, Logger logger, String debugPath) {
        Object raw = map.get(key);
        if (raw instanceof String value && !value.isBlank()) {
            return value;
        }
        logger.warning("Brak lub pusta wartość '" + debugPath + "'. Używam wartości domyślnej.");
        return fallback;
    }

    private List<String> readStringList(Map<?, ?> map, String key) {
        Object raw = map.get(key);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }

        List<String> parsed = new ArrayList<>();
        for (Object element : list) {
            if (element instanceof String text && !text.isBlank()) {
                parsed.add(text);
            }
        }
        return List.copyOf(parsed);
    }

    private int readInt(Map<?, ?> map, String key, int fallback) {
        Object raw = map.get(key);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return fallback;
    }

    private boolean readBoolean(Map<?, ?> map, String key, boolean fallback) {
        Object raw = map.get(key);
        if (raw instanceof Boolean bool) {
            return bool;
        }
        return fallback;
    }

    private MysteryBoxConfig.RewardItemPreset readPreset(String rawPreset, Logger logger, String path) {
        try {
            return MysteryBoxConfig.RewardItemPreset.valueOf(rawPreset.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            logger.warning("Niepoprawny preset '" + rawPreset + "' w '" + path + "'. Używam CUSTOM.");
            return MysteryBoxConfig.RewardItemPreset.CUSTOM;
        }
    }
}
