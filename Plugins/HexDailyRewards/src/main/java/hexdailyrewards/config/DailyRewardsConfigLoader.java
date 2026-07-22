package hexdailyrewards.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
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
                config.getString("time-format.minute", "min"),
                config.getString("time-format.second", "s"),
                validPattern(config.getString("time-format.reset-time-pattern", "HH:mm"), "HH:mm", logger),
                validPattern(config.getString("time-format.date-pattern", "dd.MM.yyyy"), "dd.MM.yyyy", logger)
        );

        DailyRewardsConfig.Messages messages = new DailyRewardsConfig.Messages(
                config.getString("messages.prefix", "&c&lDaily Rewards &8> "),
                config.getString("messages.no-permission", "&cNie masz uprawnien."),
                config.getString("messages.player-only", "&cTa komenda jest dostepna tylko dla gracza."),
                config.getString("messages.usage", "&7Uzycie: &f/hexdailyrewards reload"),
                config.getString("messages.reload-success", "&aPrzeladowano konfiguracje."),
                config.getString("messages.reload-failed", "&cNie udalo sie przeladowac konfiguracji. Sprawdz konsole."),
                config.getString("messages.disabled", "&cDaily Rewards sa obecnie wylaczone."),
                config.getString("messages.reward-claimed-chat", "&aOdebrales dzisiejsza nagrode."),
                config.getString("messages.reward-claimed-actionbar", "&aOdebrano codzienna nagrode!"),
                config.getString("messages.already-claimed-actionbar", "&eDzisiejsza nagroda zostala juz odebrana. Wroc za &6{time}&e."),
                config.getString("messages.reward-locked-actionbar", "&cTa skrzynka nie jest dostepna dla twojej rangi."),
                config.getString("messages.claim-error", "&cNie udalo sie zapisac odbioru nagrody. Zglos to administracji."),
                config.getString("messages.no-reward-configured", "&cBrak skonfigurowanej nagrody na dzisiaj.")
        );

        DailyRewardsConfig.PlaceholderTexts placeholderTexts = new DailyRewardsConfig.PlaceholderTexts(
                config.getString("placeholders.no-player", "-"),
                config.getString("placeholders.no-reward", "-"),
                config.getString("placeholders.available", "true"),
                config.getString("placeholders.unavailable", "false"),
                config.getString("placeholders.status-available", "Do odebrania"),
                config.getString("placeholders.status-claimed", "Odebrane"),
                config.getString("placeholders.player-status-available", "&aDo odebrania"),
                config.getString("placeholders.player-status-claimed", "&cOdebrano"),
                config.getString("placeholders.hologram-status-available", "&aDo odebrania"),
                config.getString("placeholders.hologram-status-claimed", "&cOdebrano")
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

        DailyRewardsConfig.RewardsCalendar legacyCalendar = calendar(
                config.getConfigurationSection("rewards-calendar"),
                null,
                "rewards-calendar",
                logger
        );
        Map<String, DailyRewardsConfig.RewardGroup> rewardGroups = rewardGroups(config, legacyCalendar, logger);
        DailyRewardsConfig.RewardsCalendar defaultCalendar = rewardGroups.getOrDefault("default",
                rewardGroups.values().iterator().next()).rewardsCalendar();
        DailyRewardsConfig.Gui gui = gui(config, logger);
        return new DailyRewardsConfig(enabled, zone, hexNpc, timeFormat, messages, placeholderTexts, sounds,
                reward, defaultCalendar, rewardGroups, gui);
    }

    private Map<String, DailyRewardsConfig.RewardGroup> rewardGroups(FileConfiguration config,
                                                                     DailyRewardsConfig.RewardsCalendar legacyCalendar,
                                                                     Logger logger) {
        Map<String, DailyRewardsConfig.RewardGroup> groups = new LinkedHashMap<>();
        ConfigurationSection groupsSection = config.getConfigurationSection("reward-groups");
        if (groupsSection == null) {
            DailyRewardsConfig.RewardGroup fallback = rewardGroup("default", null, legacyCalendar, 0, logger);
            groups.put(fallback.id(), fallback);
            return Collections.unmodifiableMap(groups);
        }

        int index = 0;
        for (String key : groupsSection.getKeys(false)) {
            DailyRewardsConfig.RewardGroup group = rewardGroup(
                    key,
                    groupsSection.getConfigurationSection(key),
                    legacyCalendar,
                    index++,
                    logger
            );
            if (groups.containsKey(group.id())) {
                logger.warning("HexDailyRewards: duplicate reward group id '" + group.id() + "'. Skipping " + key + ".");
                continue;
            }
            groups.put(group.id(), group);
        }

        if (groups.isEmpty()) {
            DailyRewardsConfig.RewardGroup fallback = rewardGroup("default", null, legacyCalendar, 0, logger);
            groups.put(fallback.id(), fallback);
        }
        return Collections.unmodifiableMap(groups);
    }

    private DailyRewardsConfig.RewardGroup rewardGroup(String key,
                                                       ConfigurationSection section,
                                                       DailyRewardsConfig.RewardsCalendar legacyCalendar,
                                                       int index,
                                                       Logger logger) {
        String id = normalizeGroupId(section == null ? key : section.getString("id", key), key);
        boolean enabled = section == null || section.getBoolean("enabled", true);
        String displayName = section == null
                ? defaultGroupDisplayName(id)
                : section.getString("display-name", defaultGroupDisplayName(id));
        List<String> ranks = section != null && section.contains("ranks")
                ? section.getStringList("ranks")
                : defaultRanks(id);
        List<String> permissions = section != null && section.contains("permissions")
                ? section.getStringList("permissions")
                : defaultPermissions(id);
        int priority = section == null
                ? defaultGroupPriority(id, index)
                : section.getInt("priority", defaultGroupPriority(id, index));
        boolean fallbackAccess = section == null
                ? "default".equals(id)
                : section.getBoolean("fallback-access", "default".equals(id));
        int slot = section == null
                ? defaultGroupSlot(id, index)
                : section.getInt("slot", defaultGroupSlot(id, index));
        Material frameMaterial = section == null
                ? defaultFrameMaterial(id)
                : material(section.getString("frame-material", defaultFrameMaterial(id).name()), defaultFrameMaterial(id), logger);
        List<Integer> frameColumns = section != null && section.contains("frame-columns")
                ? columns(section.getIntegerList("frame-columns"), defaultFrameColumns(id, index), logger)
                : defaultFrameColumns(id, index);
        String frameName = section == null ? "" : section.getString("frame-name", "");
        List<String> frameLore = section != null && section.contains("frame-lore")
                ? section.getStringList("frame-lore")
                : List.of();
        boolean frameHideTooltip = section == null || section.getBoolean("frame-hide-tooltip", true);
        ConfigurationSection calendarSection = section == null ? null : section.getConfigurationSection("rewards-calendar");
        DailyRewardsConfig.RewardsCalendar calendar = calendar(
                calendarSection,
                "default".equals(id) ? legacyCalendar : null,
                "reward-groups." + id + ".rewards-calendar",
                logger
        );
        return new DailyRewardsConfig.RewardGroup(id, enabled, displayName, List.copyOf(ranks),
                List.copyOf(permissions), priority, fallbackAccess, slot, frameMaterial,
                List.copyOf(frameColumns), frameName, List.copyOf(frameLore), frameHideTooltip, calendar);
    }

    private DailyRewardsConfig.RewardsCalendar calendar(ConfigurationSection section,
                                                       DailyRewardsConfig.RewardsCalendar fallback,
                                                       String label,
                                                       Logger logger) {
        if (section == null && fallback != null) {
            return fallback;
        }

        LocalDate fallbackStart = fallback == null ? LocalDate.of(2026, 7, 20) : fallback.startDate();
        int fallbackCycleDays = fallback == null ? 14 : fallback.cycleDays();
        LocalDate startDate = section == null
                ? fallbackStart
                : date(section.getString("start-date", fallbackStart.toString()), fallbackStart, logger);
        int cycleDays = section == null ? fallbackCycleDays : section.getInt("cycle-days", fallbackCycleDays);
        if (cycleDays < 1) {
            logger.warning("HexDailyRewards: " + label + ".cycle-days must be >= 1. Using " + fallbackCycleDays + ".");
            cycleDays = fallbackCycleDays;
        }

        List<String> defaultLore = section != null && section.contains("default-lore")
                ? section.getStringList("default-lore")
                : List.of("&7Nagroda dnia: &f{reward_name}");

        Map<Integer, DailyRewardsConfig.RewardDefinition> days = new LinkedHashMap<>();
        ConfigurationSection daysSection = section == null ? null : section.getConfigurationSection("days");
        if (daysSection != null) {
            for (String key : daysSection.getKeys(false)) {
                int day = parseDayKey(key, logger);
                if (day < 1) {
                    continue;
                }
                days.put(day, rewardDefinition(key, daysSection.getConfigurationSection(key), defaultLore, logger));
            }
        }

        Map<LocalDate, DailyRewardsConfig.RewardDefinition> overrides = new LinkedHashMap<>();
        ConfigurationSection overridesSection = section == null ? null : section.getConfigurationSection("date-overrides");
        if (overridesSection != null) {
            for (String key : overridesSection.getKeys(false)) {
                LocalDate date = date(key, null, logger);
                if (date == null) {
                    continue;
                }
                overrides.put(date, rewardDefinition(key, overridesSection.getConfigurationSection(key), defaultLore, logger));
            }
        }

        return new DailyRewardsConfig.RewardsCalendar(startDate, cycleDays,
                Collections.unmodifiableMap(days), Collections.unmodifiableMap(overrides));
    }

    private DailyRewardsConfig.RewardDefinition rewardDefinition(String id,
                                                                ConfigurationSection section,
                                                                List<String> defaultLore,
                                                                Logger logger) {
        if (section == null) {
            return new DailyRewardsConfig.RewardDefinition(id, "&cBrak nagrody", Material.BARRIER, defaultLore, List.of());
        }
        Material material = material(section.getString("material", "CHEST"), Material.CHEST, logger);
        List<String> commands = section.contains("commands") ? section.getStringList("commands") : List.of();
        List<String> lore = section.contains("lore") ? section.getStringList("lore") : defaultLore;
        if (isBlank(lore)) {
            lore = defaultLore;
        }
        return new DailyRewardsConfig.RewardDefinition(
                id,
                section.getString("display-name", "&6Daily Reward"),
                material,
                List.copyOf(lore),
                List.copyOf(commands)
        );
    }

    private DailyRewardsConfig.Gui gui(FileConfiguration config, Logger logger) {
        int size = config.getInt("gui.size", 45);
        if (size < 9 || size > 54 || size % 9 != 0) {
            logger.warning("HexDailyRewards: gui.size must be a multiple of 9 between 9 and 54. Using 45.");
            size = 45;
        }
        DailyRewardsConfig.GuiItem filler = item(config.getConfigurationSection("gui.filler"),
                true, 0, Material.BLACK_STAINED_GLASS_PANE, false, "", List.of(), true, logger);
        DailyRewardsConfig.GuiItems items = new DailyRewardsConfig.GuiItems(
                item(config.getConfigurationSection("gui.items.available"), true, 13, Material.CHEST, true,
                        "{group_name}",
                        List.of("&fNagroda: {reward_name}", "", "&fStatus: {player_status}",
                                "&fNastepna nagroda za: {time}"), false, logger),
                item(config.getConfigurationSection("gui.items.claimed"), true, 13, Material.MINECART, true,
                        "{group_name}",
                        List.of("&fNagroda: {reward_name}", "", "&fStatus: {player_status}",
                                "&fNastepna nagroda za: {time}"), false, logger),
                item(config.getConfigurationSection("gui.items.locked"), true, 13, Material.GRAY_DYE, true,
                        "{group_name}",
                        List.of("&fNagroda: {reward_name}", "", "&fStatus: &cNiedostepna",
                                "&fNastepna nagroda za: -"), false, logger),
                item(config.getConfigurationSection("gui.items.status-available"), false, 31, Material.LIME_DYE, false,
                        "&fStatus: &aDo odebrania", List.of("&7Do nastepnego resetu: &f{time}"), false, logger),
                item(config.getConfigurationSection("gui.items.status-claimed"), false, 31, Material.RED_DYE, false,
                        "&fStatus: &cOdebrane", List.of("&7Do nastepnej nagrody: &f{time}"), false, logger),
                item(config.getConfigurationSection("gui.items.info"), false, 4, Material.CLOCK, false,
                        "&6Daily Rewards", List.of("&7Wybierz skrzynke dostepna", "&7dla swojej rangi."), false, logger),
                item(config.getConfigurationSection("gui.items.close"), false, 40, Material.BARRIER, false,
                        "&cZamknij", List.of(), false, logger)
        );
        return new DailyRewardsConfig.Gui(size, config.getString("gui.title", "&cDaily Rewards"), filler, items);
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
                section.getBoolean("hide_tooltip", section.getBoolean("hide-tooltip", hideTooltip))
        );
    }

    private String normalizeGroupId(String raw, String fallback) {
        String normalized = raw == null ? "" : raw.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9_-]", "_");
        if (!normalized.isBlank()) {
            return normalized;
        }
        return fallback == null || fallback.isBlank() ? "default" : fallback.toLowerCase(Locale.ROOT);
    }

    private String defaultGroupDisplayName(String id) {
        return switch (id) {
            case "vip" -> "&6VIP / SVIP";
            case "elite", "elita" -> "&dElita";
            default -> "&aGracze / Media";
        };
    }

    private List<String> defaultRanks(String id) {
        return switch (id) {
            case "vip" -> List.of("vip", "svip");
            case "elite", "elita" -> List.of("elita");
            default -> List.of("default", "media");
        };
    }

    private List<String> defaultPermissions(String id) {
        return switch (id) {
            case "vip" -> List.of("hexdailyrewards.rank.vip", "hexdailyrewards.rank.svip", "group.vip", "group.svip");
            case "elite", "elita" -> List.of("hexdailyrewards.rank.elita", "hexdailyrewards.rank.elite", "group.elita", "group.elite");
            default -> List.of("hexdailyrewards.rank.default", "hexdailyrewards.rank.media", "group.default", "group.media");
        };
    }

    private int defaultGroupPriority(String id, int index) {
        return switch (id) {
            case "vip" -> 20;
            case "elite", "elita" -> 30;
            default -> index <= 0 ? 10 : 10 + (index * 10);
        };
    }

    private int defaultGroupSlot(String id, int index) {
        return switch (id) {
            case "vip" -> 22;
            case "elite", "elita" -> 25;
            default -> index <= 0 ? 19 : 19 + (index * 3);
        };
    }

    private Material defaultFrameMaterial(String id) {
        return switch (id) {
            case "vip" -> Material.YELLOW_STAINED_GLASS_PANE;
            case "elite", "elita" -> Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            default -> Material.BLACK_STAINED_GLASS_PANE;
        };
    }

    private List<Integer> defaultFrameColumns(String id, int index) {
        return switch (id) {
            case "vip" -> List.of(4, 5, 6);
            case "elite", "elita" -> List.of(7, 8, 9);
            default -> index <= 0 ? List.of(1, 2, 3) : List.of(1, 2, 3);
        };
    }

    private List<Integer> columns(List<Integer> configured, List<Integer> fallback, Logger logger) {
        if (configured == null || configured.isEmpty()) {
            return fallback;
        }
        List<Integer> out = configured.stream()
                .filter(column -> column != null && column >= 1 && column <= 9)
                .distinct()
                .toList();
        if (out.isEmpty()) {
            logger.warning("HexDailyRewards: frame-columns must contain column numbers from 1 to 9. Using defaults.");
            return fallback;
        }
        return out;
    }

    private boolean isBlank(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return true;
        }
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                return false;
            }
        }
        return true;
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
