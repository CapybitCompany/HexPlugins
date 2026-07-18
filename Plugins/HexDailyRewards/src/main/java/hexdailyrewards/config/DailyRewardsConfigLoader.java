package hexdailyrewards.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

public final class DailyRewardsConfigLoader {

    public DailyRewardsConfig load(FileConfiguration config, Logger logger) {
        boolean enabled = config.getBoolean("enabled", true);
        ZoneId zone = zone(config.getString("daily-reset.time-zone", "Europe/Warsaw"), logger);

        DailyRewardsConfig.HexNpc hexNpc = new DailyRewardsConfig.HexNpc(
                config.getBoolean("hexnpc.enabled", true),
                config.getString("hexnpc.action-id", "hexdailyrewards")
        );

        DailyRewardsConfig.TimeFormat timeFormat = new DailyRewardsConfig.TimeFormat(
                config.getString("time-format.now", "teraz"),
                config.getString("time-format.hour", "h"),
                config.getString("time-format.minute", "m"),
                config.getString("time-format.second", "s"),
                validPattern(config.getString("time-format.reset-time-pattern", "HH:mm"), "HH:mm", logger),
                validPattern(config.getString("time-format.date-pattern", "dd.MM.yyyy"), "dd.MM.yyyy", logger)
        );

        DailyRewardsConfig.Messages messages = new DailyRewardsConfig.Messages(
                config.getString("messages.prefix", "&6&lDaily Rewards &8> "),
                config.getString("messages.no-permission", "&cNie masz permisji."),
                config.getString("messages.player-only", "&cTa komenda jest tylko dla gracza."),
                config.getString("messages.usage", "&7Uzycie: &f/hexdailyrewards reload"),
                config.getString("messages.reload-success", "&aPrzeladowano konfiguracje."),
                config.getString("messages.reload-failed", "&cNie udalo sie przeladowac konfiguracji. Sprawdz konsole."),
                config.getString("messages.disabled", "&cDaily Rewards sa obecnie wylaczone."),
                config.getString("messages.reward-claimed-chat", "&aOdebrales dzisiejsza nagrode."),
                config.getString("messages.reward-claimed-actionbar", "&aOdebrano codzienna nagrode!"),
                config.getString("messages.already-claimed-actionbar", "&eDzisiejsza nagroda zostala juz odebrana. Wroc za &6{time}&e."),
                config.getString("messages.claim-error", "&cNie udalo sie zapisac odbioru nagrody. Zglos to administracji."),
                config.getString("messages.no-reward-configured", "&cBrak skonfigurowanej nagrody na dzisiaj.")
        );

        DailyRewardsConfig.Sounds sounds = new DailyRewardsConfig.Sounds(
                sound(config.getConfigurationSection("sounds.open"), "minecraft:ui.button.click"),
                sound(config.getConfigurationSection("sounds.claim"), "minecraft:entity.player.levelup"),
                sound(config.getConfigurationSection("sounds.unavailable"), "minecraft:block.note_block.bass")
        );

        DailyRewardsConfig.Reward reward = new DailyRewardsConfig.Reward(
                config.getBoolean("reward.close-gui-after-claim", false),
                config.getBoolean("reward.refresh-gui-after-claim", true)
        );

        DailyRewardsConfig.RewardsCalendar rewardsCalendar = calendar(config, logger);
        DailyRewardsConfig.Gui gui = gui(config, logger);
        return new DailyRewardsConfig(enabled, zone, hexNpc, timeFormat, messages, sounds, reward, rewardsCalendar, gui);
    }

    private DailyRewardsConfig.RewardsCalendar calendar(FileConfiguration config, Logger logger) {
        LocalDate fallbackStart = LocalDate.of(2026, 7, 20);
        LocalDate startDate = date(config.getString("rewards-calendar.start-date", fallbackStart.toString()),
                fallbackStart, logger);
        int cycleDays = config.getInt("rewards-calendar.cycle-days", 14);
        if (cycleDays < 1) {
            logger.warning("HexDailyRewards: rewards-calendar.cycle-days must be >= 1. Using 14.");
            cycleDays = 14;
        }

        Map<Integer, DailyRewardsConfig.RewardDefinition> days = new LinkedHashMap<>();
        ConfigurationSection daysSection = config.getConfigurationSection("rewards-calendar.days");
        if (daysSection != null) {
            for (String key : daysSection.getKeys(false)) {
                int day = parseDayKey(key, logger);
                if (day < 1) {
                    continue;
                }
                days.put(day, rewardDefinition(key, daysSection.getConfigurationSection(key), logger));
            }
        }

        Map<LocalDate, DailyRewardsConfig.RewardDefinition> overrides = new LinkedHashMap<>();
        ConfigurationSection overridesSection = config.getConfigurationSection("rewards-calendar.date-overrides");
        if (overridesSection != null) {
            for (String key : overridesSection.getKeys(false)) {
                LocalDate date = date(key, null, logger);
                if (date == null) {
                    continue;
                }
                overrides.put(date, rewardDefinition(key, overridesSection.getConfigurationSection(key), logger));
            }
        }

        return new DailyRewardsConfig.RewardsCalendar(startDate, cycleDays, Map.copyOf(days), Map.copyOf(overrides));
    }

    private DailyRewardsConfig.RewardDefinition rewardDefinition(String id,
                                                                ConfigurationSection section,
                                                                Logger logger) {
        if (section == null) {
            return new DailyRewardsConfig.RewardDefinition(id, "&cBrak nagrody", Material.BARRIER, List.of(), List.of());
        }
        Material material = material(section.getString("material", "CHEST"), Material.CHEST, logger);
        List<String> commands = section.contains("commands") ? section.getStringList("commands") : List.of();
        return new DailyRewardsConfig.RewardDefinition(
                id,
                section.getString("display-name", "&6Daily Reward"),
                material,
                section.contains("lore") ? section.getStringList("lore") : List.of(),
                List.copyOf(commands)
        );
    }

    private DailyRewardsConfig.Gui gui(FileConfiguration config, Logger logger) {
        int size = config.getInt("gui.size", 27);
        if (size < 9 || size > 54 || size % 9 != 0) {
            logger.warning("HexDailyRewards: gui.size must be a multiple of 9 between 9 and 54. Using 27.");
            size = 27;
        }
        DailyRewardsConfig.GuiItem filler = item(config.getConfigurationSection("gui.filler"),
                true, 0, Material.BLACK_STAINED_GLASS_PANE, false, "", List.of(), true, logger);
        DailyRewardsConfig.GuiItems items = new DailyRewardsConfig.GuiItems(
                item(config.getConfigurationSection("gui.items.available"), true, 13, Material.CHEST, true,
                        "&a&l{reward_name}", List.of("&7Kliknij, aby odebrac."), false, logger),
                item(config.getConfigurationSection("gui.items.claimed"), true, 13, Material.MINECART, true,
                        "&c&l{reward_name}", List.of("&7Nastepna za: &f{time}"), false, logger),
                item(config.getConfigurationSection("gui.items.status-available"), true, 11, Material.LIME_DYE, false,
                        "&aStatus: Dostepna", List.of(), false, logger),
                item(config.getConfigurationSection("gui.items.status-claimed"), true, 11, Material.RED_DYE, false,
                        "&cStatus: Odebrana", List.of("&7Pozostalo: &f{time}"), false, logger),
                item(config.getConfigurationSection("gui.items.info"), true, 15, Material.CLOCK, false,
                        "&6Dzisiejsza nagroda", List.of("&7Reset: &f{reset_time}"), false, logger),
                item(config.getConfigurationSection("gui.items.close"), true, 22, Material.BARRIER, false,
                        "&cZamknij", List.of(), false, logger)
        );
        return new DailyRewardsConfig.Gui(size, config.getString("gui.title", "&6Daily Rewards"), filler, items);
    }

    private DailyRewardsConfig.GuiItem item(ConfigurationSection section,
                                           boolean enabled,
                                           int slot,
                                           Material material,
                                           boolean useRewardMaterial,
                                           String name,
                                           List<String> lore,
                                           boolean hideTooltip,
                                           Logger logger) {
        if (section == null) {
            return new DailyRewardsConfig.GuiItem(enabled, slot, material, useRewardMaterial, name, lore, hideTooltip);
        }
        return new DailyRewardsConfig.GuiItem(
                section.getBoolean("enabled", enabled),
                section.getInt("slot", slot),
                material(section.getString("material", material.name()), material, logger),
                section.getBoolean("use-reward-material", useRewardMaterial),
                section.getString("name", name),
                section.contains("lore") ? section.getStringList("lore") : lore,
                section.getBoolean("hide-tooltip", hideTooltip)
        );
    }

    private DailyRewardsConfig.SoundSetting sound(ConfigurationSection section, String fallback) {
        if (section == null) {
            return new DailyRewardsConfig.SoundSetting(true, fallback, 0.8F, 1.0F);
        }
        return new DailyRewardsConfig.SoundSetting(
                section.getBoolean("enabled", true),
                section.getString("name", fallback),
                (float) section.getDouble("volume", 0.8D),
                (float) section.getDouble("pitch", 1.0D)
        );
    }

    private Material material(String raw, Material fallback, Logger logger) {
        Material found = Material.matchMaterial(raw == null ? "" : raw);
        if (found == null || !found.isItem()) {
            logger.warning("HexDailyRewards: unknown item material '" + raw + "'. Using " + fallback + ".");
            return fallback;
        }
        return found;
    }

    private ZoneId zone(String raw, Logger logger) {
        try {
            return ZoneId.of(raw == null || raw.isBlank() ? "Europe/Warsaw" : raw);
        } catch (Exception ex) {
            logger.warning("HexDailyRewards: invalid time zone '" + raw + "'. Using Europe/Warsaw.");
            return ZoneId.of("Europe/Warsaw");
        }
    }

    private LocalDate date(String raw, LocalDate fallback, Logger logger) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException | NullPointerException ex) {
            logger.warning("HexDailyRewards: invalid date '" + raw + "'. Expected yyyy-MM-dd.");
            return fallback;
        }
    }

    private int parseDayKey(String key, Logger logger) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT).trim();
        if (normalized.startsWith("day-")) {
            normalized = normalized.substring(4);
        }
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException ex) {
            logger.warning("HexDailyRewards: invalid reward day key '" + key + "'. Expected day-1.");
            return -1;
        }
    }

    private String validPattern(String raw, String fallback, Logger logger) {
        try {
            DateTimeFormatter.ofPattern(raw);
            return raw;
        } catch (IllegalArgumentException ex) {
            logger.warning("HexDailyRewards: invalid time format pattern '" + raw + "'. Using " + fallback + ".");
            return fallback;
        }
    }
}

