package hex.minigames.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** One-time migration of previous stock settings; custom values and original file backups survive. */
final class BalanceConfigMigration {
    private static final String REVISION = "balance-revision";

    static void apply(Plugin plugin, String name) {
        if (!java.util.Set.of("jump_rope.yml","breeze_tower.yml","disco_floor.yml","monkey_run.yml","elytra.yml").contains(name)) return;
        File file = new File(plugin.getDataFolder(), "games/" + name);
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        int targetRevision = name.equals("breeze_tower.yml") ? 5 : name.equals("elytra.yml") ? 9 : 3;
        if (yaml.getInt(REVISION, 0) >= targetRevision) return;
        if (java.util.Set.of("disco_floor.yml","monkey_run.yml","elytra.yml").contains(name) && yaml.getConfigurationSection("region") != null
                && yaml.getConfigurationSection("region").getKeys(false).isEmpty()
                && yaml.getMapList("participant-spawns").isEmpty()) {
            try (var input = plugin.getResource("games/" + name)) {
                if (input == null) throw new IOException("Missing bundled disco_floor.yml");
                var defaults = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
                for (String key : defaults.getKeys(false)) yaml.set(key, defaults.get(key));
            } catch (IOException error) { throw new IllegalStateException("Cannot initialize Disco Floor", error); }
        }
        if (name.equals("elytra.yml")) {
            try (var input = plugin.getResource("games/elytra.yml")) {
                if (input == null) throw new IOException("Missing bundled elytra.yml");
                var defaults = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
                if (yaml.getMapList("settings.ring-order").isEmpty()) yaml.set("settings.ring-order", defaults.getMapList("settings.ring-order"));
                correctElytraMarkers(yaml);
                var spawns = yaml.getMapList("participant-spawns");
                if (spawns.size() == 1 && oldElytraSpawn(spawns.getFirst())) yaml.set("participant-spawns", defaults.getMapList("participant-spawns"));
                if (yaml.getInt("spectator-spawn.x") == 22 && yaml.getInt("spectator-spawn.y") == 72 && yaml.getInt("spectator-spawn.z") == 180)
                    yaml.set("spectator-spawn", defaults.get("spectator-spawn"));
            } catch (IOException error) { throw new IllegalStateException("Cannot migrate Elytra", error); }
        }
        update(name, yaml);
        yaml.set(REVISION, targetRevision);
        try {
            File backup = new File(file.getParentFile(), name + ".before-balance-" + targetRevision + ".bak");
            if (!backup.exists()) Files.copy(file.toPath(), backup.toPath());
            yaml.save(file);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot migrate minigame config " + file, error);
        }
    }

    /** Change only old defaults, then mark the file so later administrator choices remain intact. */
    static void update(String name, YamlConfiguration yaml) {
        if (name.equals("breeze_tower.yml") && yaml.getInt(REVISION, 0) < 5) {
            replace(yaml, "settings.shots.interval-ticks", 30, 6);
            replace(yaml, "settings.shots.interval-ticks", 12, 6);
            replace(yaml, "settings.shots.first-delay-ticks", 40, 20);
            replaceDecimal(yaml, "settings.shots.speed", 0.85, 1.35);
            replaceDecimal(yaml, "settings.shots.knockback-multiplier", 1.8, 2.6);
            replaceDecimal(yaml, "settings.shots.minimum-upward-velocity", 0.95, 1.15);
            yaml.set(REVISION, 5);
            return;
        }
        if (yaml.getInt(REVISION, 0) >= 3) return;
        if (name.equals("jump_rope.yml")) {
            if (yaml.getInt(REVISION, 0) < 1) {
                replace(yaml, "settings.rope.bottom-y", 13, 12);
                replace(yaml, "settings.rope.pivot-y", 18, 17);
                replace(yaml, "settings.rope.rotation-ticks", 100, 80);
            }
            if (yaml.getInt(REVISION, 0) < 2) replace(yaml, "settings.rope.rotation-ticks", 80, 60);
        } else if (name.equals("breeze_tower.yml")) {
            if (yaml.getInt(REVISION, 0) < 1) replace(yaml, "settings.shots.interval-ticks", 30, 12);
            if (!yaml.contains("settings.shots.knockback-multiplier")) yaml.set("settings.shots.knockback-multiplier", 1.8);
            if (!yaml.contains("settings.shots.minimum-upward-velocity")) yaml.set("settings.shots.minimum-upward-velocity", 0.95);
        } else if (!java.util.Set.of("disco_floor.yml","monkey_run.yml","elytra.yml").contains(name)) return;
        yaml.set(REVISION, 3);
    }

    private static void replace(YamlConfiguration yaml, String path, int before, int after) {
        if (!yaml.contains(path) || yaml.getInt(path) == before) yaml.set(path, after);
    }

    private static void replaceDecimal(YamlConfiguration yaml, String path, double before, double after) {
        if (!yaml.contains(path) || Double.compare(yaml.getDouble(path), before) == 0) yaml.set(path, after);
    }

    /** Correct three coordinates confirmed by the arena markers without reordering the route. */
    static void correctElytraMarkers(YamlConfiguration yaml) {
        var corrected = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (var entry : yaml.getMapList("settings.ring-order")) {
            var row = new java.util.LinkedHashMap<String, Object>();
            entry.forEach((key, value) -> row.put(String.valueOf(key), value));
            if (entry.get("x") instanceof Number x && entry.get("y") instanceof Number y
                    && entry.get("z") instanceof Number z) {
                if (x.intValue() == 76 && y.intValue() == -18 && (z.intValue() == 138 || z.intValue() == 139)) row.put("z", 133);
                if (x.intValue() == 268 && y.intValue() == 24 && z.intValue() == 168) row.put("z", 108);
                if (x.intValue() == 245 && y.intValue() == 7 && z.intValue() == 170) row.put("z", 172);
            }
            corrected.add(row);
        }
        yaml.set("settings.ring-order", corrected);
    }

    private static boolean oldElytraSpawn(java.util.Map<?, ?> spawn) {
        return spawn.get("x") instanceof Number x && x.intValue() == 22
                && spawn.get("y") instanceof Number y && y.intValue() == 72
                && spawn.get("z") instanceof Number z && z.intValue() == 180;
    }
}
