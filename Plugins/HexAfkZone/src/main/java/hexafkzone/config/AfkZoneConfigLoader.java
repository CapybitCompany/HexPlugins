package hexafkzone.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
                config.getString("messages.timer-actionbar", "{color}Jesteś AFK od: &f{time}"),
                config.getString("messages.reward-actionbar", "{color}Otrzymano: &f{reward_name} x{amount}")
        );
        int rewardMessageSeconds = Math.max(1, config.getInt("reward-message-seconds", 3));
        AfkZoneConfig.Sounds sounds = new AfkZoneConfig.Sounds(sound(config.getConfigurationSection("sounds.reward")));
        List<AfkZoneConfig.RankProfile> rankProfiles = rankProfiles(config, logger);
        Map<String, AfkZoneConfig.RewardGroup> rewardGroups = rewardGroups(config, logger);
        return new AfkZoneConfig(config.getBoolean("enabled", true), region, messages, rewardMessageSeconds,
                sounds, rankProfiles, rewardGroups);
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
            profiles.add(new AfkZoneConfig.RankProfile(
                    id,
                    profile.getString("display-name", key),
                    profile.getString("color", "&7"),
                    normalizeId(profile.getString("reward-group", id)),
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
        return new AfkZoneConfig.RankProfile("default", "Default", "&7", "default", 0,
                true, false, List.of("hexafkzone.rank.default", "group.default"));
    }

    private Map<String, AfkZoneConfig.RewardGroup> rewardGroups(FileConfiguration config, Logger logger) {
        Map<String, AfkZoneConfig.RewardGroup> groups = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("reward-groups");
        if (section == null) {
            groups.put("default", new AfkZoneConfig.RewardGroup("default", List.of()));
            return Map.copyOf(groups);
        }

        for (String key : section.getKeys(false)) {
            String id = normalizeId(key);
            ConfigurationSection group = section.getConfigurationSection(key);
            groups.put(id, new AfkZoneConfig.RewardGroup(id, milestones(group, "reward-groups." + key, logger)));
        }
        return Map.copyOf(groups);
    }

    private List<AfkZoneConfig.Milestone> milestones(ConfigurationSection group, String label, Logger logger) {
        ConfigurationSection section = group == null ? null : group.getConfigurationSection("milestones");
        if (section == null) {
            return List.of();
        }

        List<AfkZoneConfig.Milestone> milestones = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            long seconds = durationSeconds(key);
            if (seconds <= 0L) {
                logger.warning("HexAfkZone: invalid milestone time '" + key + "' in " + label + ".");
                continue;
            }
            ConfigurationSection milestone = section.getConfigurationSection(key);
            if (milestone == null) {
                continue;
            }
            int amount = Math.max(1, milestone.getInt("amount", 1));
            milestones.add(new AfkZoneConfig.Milestone(
                    key,
                    seconds,
                    milestone.getString("display-name", key),
                    amount,
                    List.copyOf(milestone.getStringList("commands"))
            ));
        }
        milestones.sort(Comparator.comparingLong(AfkZoneConfig.Milestone::seconds));
        return List.copyOf(milestones);
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
