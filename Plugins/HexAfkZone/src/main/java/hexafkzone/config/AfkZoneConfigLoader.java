package hexafkzone.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

public final class AfkZoneConfigLoader {

    public AfkZoneConfig load(FileConfiguration config, Logger logger) {
        AfkZoneConfig.Region region = region(config);
        AfkZoneConfig.Messages messages = new AfkZoneConfig.Messages(
                config.getString("messages.prefix", "&6&lAFK Zone &8> "),
                config.getString("messages.no-permission", "&cNie masz uprawnien."),
                config.getString("messages.usage", "&7Uzycie: &f/hexafkzone reload"),
                config.getString("messages.reload-success", "&aPrzeladowano konfiguracje."),
                config.getString("messages.reload-failed", "&cNie udalo sie przeladowac konfiguracji. Sprawdz konsole."),
                config.getString("messages.zone-subtitle", "{color}STREFA AFK"),
                config.getString("messages.timer-actionbar", "{color}Jestes AFK od: &f{time}"),
                config.getString("messages.reward-actionbar", "{color}Otrzymano: &a{base_reward}&7{bonus_rewards}")
        );
        int rewardMessageSeconds = Math.max(1, config.getInt("reward-message-seconds", 3));
        AfkZoneConfig.Sounds sounds = new AfkZoneConfig.Sounds(sound(config.getConfigurationSection("sounds.reward")));
        List<AfkZoneConfig.RankProfile> rankProfiles = rankProfiles(config, logger);
        AfkZoneConfig.Rewards rewards = rewards(config, logger);
        return new AfkZoneConfig(config.getBoolean("enabled", true), region, messages, rewardMessageSeconds,
                sounds, rankProfiles, rewards);
    }

    private AfkZoneConfig.Region region(FileConfiguration config) {
        int x1 = config.getInt("region.corner-1.x", 2786);
        int y1 = config.getInt("region.corner-1.y", 72);
        int z1 = config.getInt("region.corner-1.z", 952);
        int x2 = config.getInt("region.corner-2.x", 2782);
        int y2 = config.getInt("region.corner-2.y", 81);
        int z2 = config.getInt("region.corner-2.z", 965);
        return new AfkZoneConfig.Region(
                config.getString("region.world", "world"),
                Math.min(x1, x2),
                Math.max(x1, x2),
                Math.min(y1, y2),
                Math.max(y1, y2),
                Math.min(z1, z2),
                Math.max(z1, z2)
        );
    }

    private AfkZoneConfig.SoundSetting sound(ConfigurationSection section) {
        if (section == null) {
            return new AfkZoneConfig.SoundSetting(true, "minecraft:ui.toast.challenge_complete", 0.8F, 1.0F);
        }
        return new AfkZoneConfig.SoundSetting(
                section.getBoolean("enabled", true),
                section.getString("name", "minecraft:ui.toast.challenge_complete"),
                (float) section.getDouble("volume", 0.8D),
                (float) section.getDouble("pitch", 1.0D)
        );
    }

    private List<AfkZoneConfig.RankProfile> rankProfiles(FileConfiguration config, Logger logger) {
        List<AfkZoneConfig.RankProfile> profiles = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("rank-profiles");
        if (section == null) {
            profiles.add(defaultProfile());
            return List.copyOf(profiles);
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection profile = section.getConfigurationSection(key);
            if (profile == null) {
                continue;
            }
            String id = normalizeId(key);
            long intervalSeconds = durationSeconds(profile.getString("reward-interval", defaultInterval(id)));
            if (intervalSeconds <= 0L) {
                logger.warning("HexAfkZone: invalid reward interval in rank-profiles." + key + ". Using 10m.");
                intervalSeconds = 600L;
            }
            profiles.add(new AfkZoneConfig.RankProfile(
                    id,
                    profile.getString("display-name", key),
                    profile.getString("color", "&7"),
                    intervalSeconds,
                    profile.getInt("priority", 0),
                    profile.getBoolean("fallback-access", false),
                    profile.getBoolean("operator-access", false),
                    List.copyOf(profile.getStringList("permissions"))
            ));
        }

        if (profiles.isEmpty()) {
            logger.warning("HexAfkZone: no rank profiles configured. Using default profile.");
            profiles.add(defaultProfile());
        }
        profiles.sort(Comparator.comparingInt(AfkZoneConfig.RankProfile::priority).reversed());
        return List.copyOf(profiles);
    }

    private AfkZoneConfig.RankProfile defaultProfile() {
        return new AfkZoneConfig.RankProfile("default", "Default", "&7", 600L, 0,
                true, false, List.of("hexafkzone.rank.default", "group.default"));
    }

    private AfkZoneConfig.Rewards rewards(FileConfiguration config, Logger logger) {
        ConfigurationSection base = config.getConfigurationSection("rewards.base");
        int baseAmount = Math.max(0, base == null ? 20 : base.getInt("amount", 20));
        List<String> baseCommands = base == null ? List.of() : base.getStringList("commands");
        if (baseCommands.isEmpty()) {
            baseCommands = List.of("hexeconomy add {player} " + baseAmount);
        }
        AfkZoneConfig.BaseReward baseReward = new AfkZoneConfig.BaseReward(
                base == null ? baseAmount + "$" : base.getString("display-name", baseAmount + "$"),
                baseAmount,
                List.copyOf(baseCommands)
        );

        ConfigurationSection section = config.getConfigurationSection("rewards.chance");
        List<AfkZoneConfig.ChanceReward> chanceRewards = new ArrayList<>();
        if (section == null) {
            chanceRewards.add(defaultChanceReward("afk_key", "Klucz AFK", 50.0D, "hexcustomitem afk_key {player} 1"));
            chanceRewards.add(defaultChanceReward("epic_key", "Epicki klucz", 2.0D, "hexcustomitem epic_key {player} 1"));
            chanceRewards.add(defaultChanceReward("premium_key", "Klucz Premium", 0.1D, "hexcustomitem premium_key {player} 1"));
            return new AfkZoneConfig.Rewards(baseReward, List.copyOf(chanceRewards));
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection reward = section.getConfigurationSection(key);
            if (reward == null) {
                continue;
            }
            String id = normalizeId(key);
            int amount = Math.max(1, reward.getInt("amount", 1));
            List<String> commands = reward.getStringList("commands");
            if (commands.isEmpty()) {
                logger.warning("HexAfkZone: chance reward '" + key + "' has no commands.");
            }
            chanceRewards.add(new AfkZoneConfig.ChanceReward(
                    id,
                    reward.getString("display-name", key),
                    Math.max(0.0D, Math.min(100.0D, reward.getDouble("chance-percent", 0.0D))),
                    amount,
                    List.copyOf(commands)
            ));
        }
        return new AfkZoneConfig.Rewards(baseReward, List.copyOf(chanceRewards));
    }

    private AfkZoneConfig.ChanceReward defaultChanceReward(String id, String displayName, double chancePercent, String command) {
        return new AfkZoneConfig.ChanceReward(id, displayName, chancePercent, 1, List.of(command));
    }

    private String defaultInterval(String profileId) {
        return switch (profileId) {
            case "vip" -> "9m";
            case "svip" -> "8m";
            case "elite", "media", "admin" -> "6m";
            default -> "10m";
        };
    }

    private long durationSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1L;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains(":")) {
            String[] parts = normalized.split(":");
            try {
                if (parts.length == 3) {
                    return (Long.parseLong(parts[0]) * 3600L)
                            + (Long.parseLong(parts[1]) * 60L)
                            + Long.parseLong(parts[2]);
                }
                if (parts.length == 2) {
                    return (Long.parseLong(parts[0]) * 60L) + Long.parseLong(parts[1]);
                }
            } catch (NumberFormatException ignored) {
                return -1L;
            }
            return -1L;
        }

        long multiplier = 1L;
        if (normalized.endsWith("s")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        } else if (normalized.endsWith("m")) {
            normalized = normalized.substring(0, normalized.length() - 1);
            multiplier = 60L;
        } else if (normalized.endsWith("h")) {
            normalized = normalized.substring(0, normalized.length() - 1);
            multiplier = 3600L;
        } else if (normalized.endsWith("d")) {
            normalized = normalized.substring(0, normalized.length() - 1);
            multiplier = 86400L;
        }
        try {
            return Long.parseLong(normalized) * multiplier;
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private String normalizeId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "default";
        }
        return raw.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9_-]", "_");
    }
}
