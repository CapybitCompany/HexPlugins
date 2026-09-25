package hexposterunki.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped defaults must parse and make sense. This catches a broken YAML file or a renamed
 * config key before it ever reaches a server.
 */
class DefaultResourcesTest {

    private static final Logger QUIET = quietLogger();

    private static Logger quietLogger() {
        Logger logger = Logger.getLogger(DefaultResourcesTest.class.getName());
        logger.setLevel(Level.OFF);
        return logger;
    }

    private static YamlConfiguration load(String resource) {
        InputStream stream = DefaultResourcesTest.class.getResourceAsStream(resource);
        assertNotNull(stream, "brak zasobu " + resource);
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (Exception exception) {
            throw new AssertionError("nie udało się wczytać " + resource, exception);
        }
    }

    @Test
    void defaultConfigParsesIntoASensibleModel() {
        PosterunkiConfig config = new ConfigLoader().load(load("/config.yml"), QUIET);

        assertTrue(config.enabled());
        assertTrue(config.requiredKills() > 0);
        assertTrue(config.timing().cooldownSeconds() > 0);
        assertTrue(config.timing().gracePeriodSeconds() > 0);
        assertEquals(20_000.0D, config.ui().bossbarMaxDistance(),
                "domyślny zasięg bossbara to 20.000 bloków");
        assertFalse(config.rewards().enabled(), "nagrody TOP 5 są domyślnie wyłączone");
        assertFalse(config.protection().snapshotMaterials().isEmpty());
        assertEquals("*", config.towns().requiredVersion());
    }

    @Test
    void theDefaultLootWindowGivesWinnersRealTimeToCollect() {
        PosterunkiConfig config = new ConfigLoader().load(load("/config.yml"), QUIET);
        assertEquals(120L, config.timing().lootSeconds(),
                "domyślne okno zbierania łupów to 120 sekund");
        assertEquals(120_000L, config.timing().lootMillis());
    }

    @Test
    void resetScansAreBoundedAndSpreadOverTicks() {
        PosterunkiConfig.Protection protection =
                new ConfigLoader().load(load("/config.yml"), QUIET).protection();
        assertTrue(protection.maxResetScanVolume() > 0L,
                "skan resetu musi mieć limit, tak jak snapshot");
        assertTrue(protection.scanBlocksPerTick() >= 256L);
        assertTrue(protection.scanBlocksPerTick() < protection.maxResetScanVolume(),
                "skan musi rozłożyć się na więcej niż jeden tick");
        assertFalse(protection.removeGroundItems(),
                "domyślnie nie usuwamy dropów graczy przy resecie");
    }

    @Test
    void rewardDeliveryRetriesAreBounded() {
        assertTrue(new ConfigLoader().load(load("/config.yml"), QUIET)
                .rewards().maxDeliveryAttempts() >= 1);
    }

    @Test
    void defaultWavesAreOrderedAndNonEmpty() {
        PosterunkiConfig config = new ConfigLoader().load(load("/config.yml"), QUIET);
        List<WaveDefinition> waves = config.waves();

        assertEquals(3, waves.size());
        for (int i = 0; i < waves.size(); i++) {
            WaveDefinition wave = waves.get(i);
            assertEquals(i + 1, wave.id(), "id fali musi odpowiadać kolejności");
            assertFalse(wave.groups().isEmpty());
            assertTrue(wave.totalMobs() > 0);
        }
        assertNotNull(config.wave(1));
        assertNotNull(config.wave(3));
        assertEquals(null, config.wave(4), "poza zakresem nie ma fali");
    }

    @Test
    void defaultBossConfigIsWeightedAndPinnedToStormbossyOne() {
        BossConfig boss = new ConfigLoader().load(load("/config.yml"), QUIET).boss();

        assertTrue(boss.enabled());
        assertTrue(boss.usable());
        assertEquals("stormbossy", boss.provider());
        assertEquals("1.0", boss.requiredVersion());
        assertTrue(boss.requireNativeSchedulesOff(),
                "natywny harmonogram STORMBOSSY musi być domyślnie wymagany jako wyłączony");
        assertTrue(boss.spawnChance() > 0.0D && boss.spawnChance() < 1.0D);
        assertTrue(boss.bosses().size() >= 2);
        boss.bosses().forEach(entry -> assertTrue(entry.weight() > 0));
    }

    @Test
    void defaultOutpostsAreAllValid() {
        OutpostsLoader loader = new OutpostsLoader(name -> true, world -> new int[]{-64, 319});
        OutpostCatalog catalog = loader.load(load("/outposts.yml"));

        assertTrue(catalog.report().skipped().isEmpty(),
                "domyślne posterunki muszą przechodzić walidację: " + catalog.report().skipped());
        assertEquals(2, catalog.all().size());

        OutpostDefinition fort = catalog.find("fort_polnocny").orElseThrow();
        assertEquals("Fort Północny", fort.displayName());
        assertFalse(fort.spawnPoints().isEmpty());
        assertFalse(fort.lootContainers().isEmpty());
    }

    @Test
    void defaultLootBindingsPointAtExistingTables() {
        LootConfig loot = new LootLoader().load(load("/loot.yml"), QUIET);

        assertTrue(loot.enabled());
        assertEquals(LootConfig.FillPhase.WAVES_CLEARED, loot.fillPhase());
        assertEquals(LootConfig.ResetMode.CLEAR, loot.resetMode());
        assertTrue(loot.tables().containsKey(loot.defaultTable()));

        loot.bindings().forEach((outpostId, containers) -> containers.forEach((container, table) ->
                assertTrue(loot.tables().containsKey(table),
                        "kontener " + outpostId + "/" + container + " wskazuje nieistniejącą tabelę " + table)));

        loot.tables().values().forEach(table -> {
            assertTrue(table.rolls() > 0);
            assertFalse(table.entries().isEmpty());
            table.entries().forEach(entry -> assertTrue(entry.weight() > 0));
        });
    }

    @Test
    void wavesAndLootReferenceTheSpawnPointsAndContainersOfTheDefaultOutposts() {
        PosterunkiConfig config = new ConfigLoader().load(load("/config.yml"), QUIET);
        OutpostCatalog catalog = new OutpostsLoader(name -> true, world -> new int[]{-64, 319})
                .load(load("/outposts.yml"));
        LootConfig loot = new LootLoader().load(load("/loot.yml"), QUIET);

        OutpostDefinition fort = catalog.find("fort_polnocny").orElseThrow();
        for (WaveDefinition wave : config.waves()) {
            for (WaveDefinition.Group group : wave.groups()) {
                for (String point : group.spawnPoints()) {
                    assertTrue(fort.spawnPoints().containsKey(point),
                            "fala " + wave.id() + " używa nieistniejącego punktu '" + point + "'");
                }
            }
        }
        loot.bindings().getOrDefault("fort_polnocny", java.util.Map.of()).keySet()
                .forEach(container -> assertTrue(fort.lootContainers().containsKey(container),
                        "loot.yml wskazuje nieistniejący kontener '" + container + "'"));
    }
}
