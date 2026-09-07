package hex.parkour.config;

import hex.parkour.model.BlockVector;
import hex.parkour.model.CuboidRegion;
import hex.parkour.model.LocationSpec;
import hex.parkour.model.MoneyPoint;
import hex.parkour.model.ParkourArena;
import hex.parkour.model.ParkourCheckpoint;
import hex.parkour.util.DurationFormats;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ParkourConfigLoader {
    private final Plugin plugin;
    private final List<String> errors = new ArrayList<>();

    public ParkourConfigLoader(Plugin plugin) {
        this.plugin = plugin;
    }

    public ParkourConfig load() {
        errors.clear();
        ConfigurationSection root = plugin.getConfig();
        String worldName = root.getString("world.name", "Hex_Parkour");
        LocationSpec lobbySpawn = location(root.getConfigurationSection("lobby.spawn"), "lobby.spawn", true);
        BarConfig lobbyBossBar = bar(root.getConfigurationSection("lobby.bossbar"), "lobby.bossbar", "&a&lPARKOUR LOBBY");

        ItemConfig lobbyVisibility = item("items.lobby.visibility", Material.ENDER_EYE, 0);
        ItemConfig lobbyLeave = item("items.lobby.leave", Material.BARRIER, 8);
        ItemConfig arenaReset = item("items.arena.reset", Material.AMETHYST_SHARD, 0);
        ItemConfig arenaVisibility = item("items.arena.visibility", Material.ENDER_EYE, 4);
        ItemConfig arenaLobby = item("items.arena.lobby", Material.BARRIER, 8);

        Map<String, String> messages = stringMap(root.getConfigurationSection("messages"));
        SoundConfig checkpointSound = sound(root.getConfigurationSection("sounds.checkpoint"), "sounds.checkpoint", Sound.ENTITY_PLAYER_LEVELUP);
        SoundConfig moneySound = sound(root.getConfigurationSection("sounds.money"), "sounds.money", Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
        SoundConfig startSound = sound(root.getConfigurationSection("sounds.start"), "sounds.start", Sound.BLOCK_NOTE_BLOCK_PLING);
        SoundConfig finishSound = sound(root.getConfigurationSection("sounds.finish"), "sounds.finish", Sound.UI_TOAST_CHALLENGE_COMPLETE);
        SoundConfig resetSound = sound(root.getConfigurationSection("sounds.reset"), "sounds.reset", Sound.ENTITY_ENDERMAN_TELEPORT);
        ParticleConfig checkpointParticle = particle(root.getConfigurationSection("particles.checkpoint"), "particles.checkpoint", 170, 60, 255);
        ParticleConfig moneyParticle = particle(root.getConfigurationSection("particles.money"), "particles.money", 60, 220, 90);

        Map<String, ParkourArena> arenas = arenas(root.getConfigurationSection("arenas"));
        PlaceholderConfig placeholders = new PlaceholderConfig(
                root.getString("placeholders.empty-name", "-"),
                root.getString("placeholders.empty-time", "--:--.---")
        );
        FinishRewardConfig finishReward = finishReward(root.getConfigurationSection("finish-reward"), arenas);

        ParkourConfig config = new ParkourConfig(
                worldName,
                root.getBoolean("world.auto-join", true),
                root.getString("world.return-name", "world"),
                lobbySpawn,
                lobbyBossBar,
                lobbyVisibility,
                lobbyLeave,
                arenaReset,
                arenaVisibility,
                arenaLobby,
                messages,
                root.getBoolean("actionbar.enabled", true),
                root.getString("actionbar.format", "&fCzas: &a{time}"),
                root.getString("actionbar.not-started-format", "&fCzas: &7--:--.---"),
                placeholders,
                root.getString("timer.format", "mm:ss.SSS"),
                Math.max(1L, root.getLong("cooldowns.portal-trigger-ticks", 20L)),
                Math.max(1L, root.getLong("cooldowns.reset-ticks", 10L)),
                Math.max(1L, root.getLong("cooldowns.visibility-refresh-ticks", 20L)),
                checkpointSound,
                moneySound,
                startSound,
                finishSound,
                resetSound,
                checkpointParticle,
                moneyParticle,
                root.getString("economy.fallback-command", "hexeconomy give {player} {amount}"),
                root.getString("economy.currency", "MONEY"),
                root.getString("economy.reason", "HexParkour money point {point}"),
                finishReward,
                arenas,
                List.copyOf(errors)
        );
        for (String error : config.errors()) plugin.getLogger().severe("[config] " + error);
        return config;
    }

    private ItemConfig item(String path, Material fallbackMaterial, int fallbackSlot) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(path);
        Material material = parseMaterial(section == null ? null : section.getString("material"), path + ".material", fallbackMaterial);
        int slot = section == null ? fallbackSlot : section.getInt("slot", fallbackSlot);
        if (slot < 0 || slot > 8) {
            errors.add(path + ".slot must be between 0 and 8; using " + fallbackSlot);
            slot = fallbackSlot;
        }
        String name = section == null ? material.name() : section.getString("name", material.name());
        List<String> lore = section == null ? List.of() : section.getStringList("lore");
        return new ItemConfig(material, slot, name, lore);
    }

    private Map<String, ParkourArena> arenas(ConfigurationSection section) {
        Map<String, ParkourArena> out = new LinkedHashMap<>();
        if (section == null) {
            errors.add("arenas section is missing");
            return out;
        }
        for (String id : section.getKeys(false)) {
            String path = "arenas." + id;
            ConfigurationSection arenaSection = section.getConfigurationSection(id);
            if (arenaSection == null) continue;
            ParkourArena arena = arena(id, arenaSection, path);
            if (arena != null) out.put(id, arena);
        }
        if (out.isEmpty()) errors.add("arenas has no valid arena definitions");
        return out;
    }

    private ParkourArena arena(String id, ConfigurationSection section, String path) {
        CuboidRegion region = region(section.getConfigurationSection("region"), path + ".region", true);
        CuboidRegion portal = region(section.getConfigurationSection("portal"), path + ".portal", true);
        LocationSpec spawn = location(section.getConfigurationSection("spawn"), path + ".spawn", true);
        CuboidRegion start = region(section.getConfigurationSection("start-region"), path + ".start-region", true);
        CuboidRegion finish = region(section.getConfigurationSection("finish-region"), path + ".finish-region", true);
        if (region == null || portal == null || spawn == null || start == null || finish == null) return null;

        Set<Material> materials = new LinkedHashSet<>();
        for (String raw : section.getStringList("allowed-materials")) {
            Material material = parseMaterial(raw, path + ".allowed-materials", null);
            if (material != null) materials.add(material);
        }
        if (materials.isEmpty()) {
            errors.add(path + ".allowed-materials must contain at least one valid Material");
            return null;
        }

        Set<EntityType> vehicles = new LinkedHashSet<>();
        for (String raw : section.getStringList("allowed-vehicles")) {
            EntityType type = parseEnum(EntityType.class, raw, path + ".allowed-vehicles", null);
            if (type != null) vehicles.add(type);
        }

        List<ParkourCheckpoint> checkpoints = checkpoints(section.getConfigurationSection("checkpoints"), path + ".checkpoints");
        Map<String, MoneyPoint> moneyPoints = moneyPoints(section.getConfigurationSection("money-points"), path + ".money-points");
        return new ParkourArena(
                id,
                section.getString("display-name", id),
                bar(section.getConfigurationSection("bossbar"), path + ".bossbar", section.getString("display-name", id)),
                region,
                portal,
                spawn,
                start,
                finish,
                materials,
                vehicles,
                checkpoints,
                moneyPoints
        );
    }

    private FinishRewardConfig finishReward(ConfigurationSection section, Map<String, ParkourArena> arenas) {
        if (section == null) {
            return new FinishRewardConfig(false, "dajpunkt global {player} {amount}", Map.of());
        }
        boolean enabled = section.getBoolean("enabled", false);
        String command = section.getString("command", "dajpunkt global {player} {amount}");
        if (command == null || command.isBlank()) {
            if (enabled) errors.add("finish-reward.command must not be empty when finish rewards are enabled");
            command = "dajpunkt global {player} {amount}";
        }
        if (command.contains("{table}")) {
            errors.add("finish-reward.command must use literal 'global' instead of unsupported {table}");
        }
        if (enabled && (!command.contains("{player}") || !command.contains("{amount}"))) {
            errors.add("finish-reward.command must contain {player} and {amount} when finish rewards are enabled");
        }

        Map<String, FinishRewardConfig.ArenaReward> rewards = new LinkedHashMap<>();
        ConfigurationSection arenaSection = section.getConfigurationSection("arenas");
        if (arenaSection == null) {
            if (enabled) errors.add("finish-reward.arenas section is missing while finish rewards are enabled");
            return new FinishRewardConfig(enabled, command, rewards);
        }

        for (String arenaId : arenaSection.getKeys(false)) {
            String path = "finish-reward.arenas." + arenaId;
            if (!arenas.containsKey(arenaId)) {
                errors.add(path + " points to unknown arena");
                continue;
            }
            ConfigurationSection rewardSection = arenaSection.getConfigurationSection(arenaId);
            if (rewardSection == null) continue;
            boolean arenaEnabled = rewardSection.getBoolean("enabled", true);
            String maxTimeRaw = rewardSection.getString("max-time", "");
            long maxTimeMillis = DurationFormats.parseMillis(maxTimeRaw);
            int amount = rewardAmount(rewardSection.get("amount"), path + ".amount", enabled && arenaEnabled);
            if (enabled && arenaEnabled) {
                if (maxTimeMillis <= 0L) errors.add(path + ".max-time must be a positive time value");
                if (amount <= 0) errors.add(path + ".amount must be greater than 0 when reward is enabled");
            }
            rewards.put(arenaId, new FinishRewardConfig.ArenaReward(arenaEnabled, maxTimeMillis, amount));
        }
        return new FinishRewardConfig(enabled, command, rewards);
    }

    private int rewardAmount(Object raw, String path, boolean required) {
        if (raw == null) {
            if (required) errors.add(path + " is missing");
            return 0;
        }
        String value = String.valueOf(raw).trim();
        if (!value.matches("[0-9]+")) {
            if (required) errors.add(path + " must be a positive whole number");
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            if (required) errors.add(path + " is too large");
            return 0;
        }
    }

    private List<ParkourCheckpoint> checkpoints(ConfigurationSection section, String path) {
        if (section == null) return List.of();
        List<ParkourCheckpoint> out = new ArrayList<>();
        Set<Integer> orders = new LinkedHashSet<>();
        for (String id : section.getKeys(false)) {
            String cpPath = path + "." + id;
            ConfigurationSection cp = section.getConfigurationSection(id);
            if (cp == null) continue;
            int order = cp.getInt("order", -1);
            if (order <= 0) {
                errors.add(cpPath + ".order must be greater than 0");
                continue;
            }
            if (!orders.add(order)) {
                errors.add(cpPath + ".order duplicates another checkpoint order: " + order);
                continue;
            }
            CuboidRegion region = region(cp.getConfigurationSection("region"), cpPath + ".region", true);
            if (region == null) continue;
            LocationSpec respawn = location(cp.getConfigurationSection("respawn"), cpPath + ".respawn", false);
            if (respawn == null) {
                respawn = new LocationSpec(region.centerX(), region.safeRespawnY(), region.centerZ(), 0.0f, 0.0f);
            }
            out.add(new ParkourCheckpoint(id, order, region, respawn));
        }
        return out;
    }

    private Map<String, MoneyPoint> moneyPoints(ConfigurationSection section, String path) {
        if (section == null) return Map.of();
        Map<String, MoneyPoint> out = new LinkedHashMap<>();
        for (String id : section.getKeys(false)) {
            String moneyPath = path + "." + id;
            ConfigurationSection point = section.getConfigurationSection(id);
            if (point == null) continue;
            CuboidRegion region = region(point.getConfigurationSection("region"), moneyPath + ".region", true);
            if (region == null) continue;
            BigDecimal value;
            try {
                value = new BigDecimal(String.valueOf(point.get("value", "0")));
            } catch (NumberFormatException error) {
                errors.add(moneyPath + ".value is not a valid decimal");
                continue;
            }
            if (value.signum() <= 0) {
                errors.add(moneyPath + ".value must be greater than 0");
                continue;
            }
            out.put(id, new MoneyPoint(id, value, region, point.getString("subtitle", "&fOdebrano &a{amount}$")));
        }
        return out;
    }

    private CuboidRegion region(ConfigurationSection section, String path, boolean required) {
        if (section == null) {
            if (required) errors.add(path + " section is missing");
            return null;
        }
        BlockVector pos1 = block(section.getConfigurationSection("pos1"), path + ".pos1");
        BlockVector pos2 = block(section.getConfigurationSection("pos2"), path + ".pos2");
        return pos1 == null || pos2 == null ? null : new CuboidRegion(pos1, pos2);
    }

    private BlockVector block(ConfigurationSection section, String path) {
        if (section == null) {
            errors.add(path + " section is missing");
            return null;
        }
        return new BlockVector(section.getInt("x"), section.getInt("y"), section.getInt("z"));
    }

    private LocationSpec location(ConfigurationSection section, String path, boolean required) {
        if (section == null) {
            if (required) errors.add(path + " section is missing");
            return null;
        }
        return new LocationSpec(
                section.getDouble("x"),
                section.getDouble("y"),
                section.getDouble("z"),
                (float) section.getDouble("yaw", 0.0),
                (float) section.getDouble("pitch", 0.0)
        );
    }

    private BarConfig bar(ConfigurationSection section, String path, String fallbackTitle) {
        String title = section == null ? fallbackTitle : section.getString("title", fallbackTitle);
        BarColor color = parseEnum(BarColor.class, section == null ? null : section.getString("color"), path + ".color", BarColor.WHITE);
        BarStyle style = parseEnum(BarStyle.class, section == null ? null : section.getString("style"), path + ".style", BarStyle.SOLID);
        return new BarConfig(title, color, style);
    }

    private SoundConfig sound(ConfigurationSection section, String path, Sound fallback) {
        boolean enabled = section == null || section.getBoolean("enabled", true);
        Sound sound = parseSound(section == null ? null : section.getString("sound"), path + ".sound", fallback);
        float volume = section == null ? 1.0f : (float) section.getDouble("volume", 1.0);
        float pitch = section == null ? 1.0f : (float) section.getDouble("pitch", 1.0);
        return new SoundConfig(enabled, sound, volume, pitch);
    }

    private ParticleConfig particle(ConfigurationSection section, String path, int red, int green, int blue) {
        boolean enabled = section == null || section.getBoolean("enabled", true);
        Particle particle = parseEnum(Particle.class, section == null ? null : section.getString("particle"), path + ".particle", Particle.DUST);
        int r = section == null ? red : clampColor(section.getInt("rgb.red", red), path + ".rgb.red");
        int g = section == null ? green : clampColor(section.getInt("rgb.green", green), path + ".rgb.green");
        int b = section == null ? blue : clampColor(section.getInt("rgb.blue", blue), path + ".rgb.blue");
        float size = section == null ? 1.0f : (float) Math.max(0.1, section.getDouble("size", 1.0));
        int density = section == null ? 8 : Math.max(1, section.getInt("density", 8));
        long interval = section == null ? 10L : Math.max(1L, section.getLong("interval-ticks", 10L));
        return new ParticleConfig(enabled, particle, r, g, b, size, density, interval);
    }

    private int clampColor(int value, String path) {
        if (value < 0 || value > 255) {
            errors.add(path + " must be between 0 and 255; clamping value " + value);
        }
        return Math.max(0, Math.min(255, value));
    }

    private Map<String, String> stringMap(ConfigurationSection section) {
        if (section == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) out.put(key, section.getString(key, ""));
        return out;
    }

    private <T extends Enum<T>> T parseEnum(Class<T> type, String raw, String path, T fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            String message = path + " has invalid " + type.getSimpleName() + ": " + raw;
            if (fallback == null) errors.add(message);
            else errors.add(message + "; using " + fallback.name());
            return fallback;
        }
    }

    private Material parseMaterial(String raw, String path, Material fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if ("EYE_OF_ENDER".equals(normalized)) normalized = "ENDER_EYE";
        Material material = Material.matchMaterial(normalized);
        if (material == null) {
            String message = path + " has invalid Material: " + raw;
            if (fallback == null) errors.add(message);
            else errors.add(message + "; using " + fallback.name());
            return fallback;
        }
        return material;
    }

    private Sound parseSound(String raw, String path, Sound fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        try {
            Object value = Sound.class.getField(normalized).get(null);
            if (value instanceof Sound sound) return sound;
        } catch (ReflectiveOperationException ignored) {
        }
        errors.add(path + " has invalid Sound: " + raw + "; using fallback");
        return fallback;
    }
}
