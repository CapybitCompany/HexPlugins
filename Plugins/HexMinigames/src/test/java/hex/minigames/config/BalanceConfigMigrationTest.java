package hex.minigames.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BalanceConfigMigrationTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;
    @Test
    void migratesOldDefaultsOnlyOnceAndPreservesCustomValues() {
        var yaml = new YamlConfiguration();
        yaml.set("settings.rope.bottom-y", 13);
        yaml.set("settings.rope.pivot-y", 18);
        yaml.set("settings.rope.rotation-ticks", 100);
        yaml.set("round-time-seconds", 95);
        BalanceConfigMigration.update("jump_rope.yml", yaml);
        assertEquals(12, yaml.getInt("settings.rope.bottom-y"));
        assertEquals(17, yaml.getInt("settings.rope.pivot-y"));
        assertEquals(60, yaml.getInt("settings.rope.rotation-ticks"));
        assertEquals(95, yaml.getInt("round-time-seconds"));
        yaml.set("settings.rope.rotation-ticks", 100);
        BalanceConfigMigration.update("jump_rope.yml", yaml);
        assertEquals(100, yaml.getInt("settings.rope.rotation-ticks"));
        var custom = new YamlConfiguration();
        custom.set("settings.shots.interval-ticks", 20);
        BalanceConfigMigration.update("breeze_tower.yml", custom);
        assertEquals(20, custom.getInt("settings.shots.interval-ticks"));
        var old = new YamlConfiguration();
        old.set("settings.shots.interval-ticks", 30);
        BalanceConfigMigration.update("breeze_tower.yml", old);
        assertEquals(6, old.getInt("settings.shots.interval-ticks"));
    }

    @Test void upgradesCurrentBreezeDefaultsOnce() {
        var yaml = new YamlConfiguration();
        yaml.set("balance-revision", 3);
        yaml.set("settings.shots.interval-ticks", 12);
        yaml.set("settings.shots.speed", 0.85);
        yaml.set("settings.shots.knockback-multiplier", 1.8);
        yaml.set("settings.shots.minimum-upward-velocity", 0.95);
        BalanceConfigMigration.update("breeze_tower.yml", yaml);
        assertEquals(6, yaml.getInt("settings.shots.interval-ticks"));
        assertEquals(1.35, yaml.getDouble("settings.shots.speed"));
        assertEquals(2.6, yaml.getDouble("settings.shots.knockback-multiplier"));
        assertEquals(1.15, yaml.getDouble("settings.shots.minimum-upward-velocity"));
        yaml.set("settings.shots.interval-ticks", 12);
        BalanceConfigMigration.update("breeze_tower.yml", yaml);
        assertEquals(12, yaml.getInt("settings.shots.interval-ticks"));
    }

    @Test void initializesExistingDiscoPlaceholderAndBacksItUp() throws Exception {
        var folder = java.nio.file.Files.createDirectories(temp.resolve("games"));
        var file = folder.resolve("disco_floor.yml");
        java.nio.file.Files.writeString(file, "id: disco_floor\nenabled: false\nregion: {}\nparticipant-spawns: []\n");
        var plugin = org.mockito.Mockito.mock(org.bukkit.plugin.Plugin.class);
        org.mockito.Mockito.when(plugin.getDataFolder()).thenReturn(temp.toFile());
        org.mockito.Mockito.when(plugin.getResource("games/disco_floor.yml"))
                .thenAnswer(call -> getClass().getResourceAsStream("/games/disco_floor.yml"));
        BalanceConfigMigration.apply(plugin, "disco_floor.yml");
        var yaml = YamlConfiguration.loadConfiguration(file.toFile());
        assertTrue(yaml.getBoolean("enabled"));
        assertEquals(77, yaml.getInt("round-time-seconds"));
        assertEquals(549, yaml.getInt("region.pos1.x"));
        assertTrue(java.nio.file.Files.exists(folder.resolve("disco_floor.yml.before-balance-3.bak")));
    }

    @Test void upgradesPreviousRopeSpeedWithoutRepeatingOlderGeometryChanges() {
        var yaml = new YamlConfiguration();
        yaml.set("balance-revision", 1);
        yaml.set("settings.rope.rotation-ticks", 80);
        yaml.set("settings.rope.bottom-y", 11);
        BalanceConfigMigration.update("jump_rope.yml", yaml);
        assertEquals(60, yaml.getInt("settings.rope.rotation-ticks"));
        assertEquals(11, yaml.getInt("settings.rope.bottom-y"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"7,139", "8,138", "8,133"})
    void repairsPreviousCoordinatesAndKeepsCustomRouteOrder(int revision, int oldZ) throws Exception {
        var folder = java.nio.file.Files.createDirectories(temp.resolve("games"));
        var file = folder.resolve("elytra.yml");
        String original = "balance-revision: " + revision + "\nsettings:\n  ring-order:\n"
                + "    - {x: 245, y: 7, z: 170}\n    - {x: 76, y: -18, z: " + oldZ + "}\n"
                + "    - {x: 268, y: 24, z: 168}\n    - {x: 10, y: 20, z: 30}\n";
        java.nio.file.Files.writeString(file, original);
        var plugin = org.mockito.Mockito.mock(org.bukkit.plugin.Plugin.class);
        org.mockito.Mockito.when(plugin.getDataFolder()).thenReturn(temp.toFile());
        org.mockito.Mockito.when(plugin.getResource("games/elytra.yml"))
                .thenAnswer(call -> getClass().getResourceAsStream("/games/elytra.yml"));
        BalanceConfigMigration.apply(plugin, "elytra.yml");
        var yaml = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(java.util.List.of(
                java.util.Map.of("x",245,"y",7,"z",172), java.util.Map.of("x",76,"y",-18,"z",133),
                java.util.Map.of("x",268,"y",24,"z",108), java.util.Map.of("x",10,"y",20,"z",30)), yaml.getMapList("settings.ring-order"));
        assertEquals(9, yaml.getInt("balance-revision"));
        assertEquals(original, java.nio.file.Files.readString(folder.resolve("elytra.yml.before-balance-9.bak")));
        String migrated = java.nio.file.Files.readString(file);
        BalanceConfigMigration.apply(plugin, "elytra.yml");
        assertEquals(migrated, java.nio.file.Files.readString(file));
    }

    @Test void populatesElytraRouteAndCorrectsOldSpawnOnce() throws Exception {
        var folder = java.nio.file.Files.createDirectories(temp.resolve("games"));
        var file = folder.resolve("elytra.yml");
        java.nio.file.Files.writeString(file, "balance-revision: 3\nparticipant-spawns:\n  - {x: 22, y: 72, z: 180}\nspectator-spawn: {x: 22, y: 72, z: 180}\nsettings:\n  ring-order: []\n");
        var plugin = org.mockito.Mockito.mock(org.bukkit.plugin.Plugin.class);
        org.mockito.Mockito.when(plugin.getDataFolder()).thenReturn(temp.toFile());
        org.mockito.Mockito.when(plugin.getResource("games/elytra.yml"))
                .thenAnswer(call -> getClass().getResourceAsStream("/games/elytra.yml"));
        BalanceConfigMigration.apply(plugin, "elytra.yml");
        var yaml = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(21, yaml.getMapList("settings.ring-order").size());
        assertEquals(21, new java.util.HashSet<>(yaml.getMapList("settings.ring-order")).size());
        assertEquals(276, yaml.getMapList("participant-spawns").getFirst().get("x"));
        assertEquals(-21, yaml.getInt("spectator-spawn.y"));
        assertTrue(java.nio.file.Files.exists(folder.resolve("elytra.yml.before-balance-9.bak")));
        yaml.set("settings.ring-order", java.util.List.of(java.util.Map.of("x", 1, "y", 2, "z", 3)));
        yaml.save(file.toFile());
        BalanceConfigMigration.apply(plugin, "elytra.yml");
        assertEquals(1, YamlConfiguration.loadConfiguration(file.toFile()).getMapList("settings.ring-order").size());
    }
}
