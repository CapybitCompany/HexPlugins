package hexposterunki;

import hex.core.api.HexApi;
import hexcustommobs.api.CustomMobsApi;
import hexposterunki.config.OutpostDefinition;
import hexposterunki.config.PosterunkiConfig;
import hexposterunki.domain.RunPhase;
import hexposterunki.engine.EngineMode;
import hexposterunki.persistence.SqlTestSupport;
import hexposterunki.support.FakeCustomMobsApi;
import hexposterunki.support.FakeTownsAdapter;
import hexposterunki.support.TestHexApi;
import hexposterunki.support.TestUiService;
import hexposterunki.support.TicketTrackingWorld;
import hexposterunki.towns.TownsAdapterFactory;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review finding 7 (and the reload half of finding 5) on the real plugin lifecycle.
 *
 * <p>MockBukkit loads and enables {@link HexPosterunkiPlugin} itself: configuration files on disk,
 * {@code onEnable}, bootstrap, recovery, scheduler, listeners, {@code reloadRuntime} and
 * {@code onDisable}. Test doubles sit only at named external boundaries: HexCore's {@link HexApi}
 * (real UI service, real repository SQL on H2), HexCustomMobs' {@link CustomMobsApi} and HexTowns
 * through {@link TownsAdapterFactory}. STORMBOSSY is disabled in the test configuration.
 */
class HexPosterunkiPluginLifecycleTest {

    private static final String WORLD = "posterunki_test";
    private static final UUID TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private ServerMock server;
    private World world;
    private Connection connection;
    private TestHexApi api;
    private FakeTownsAdapter towns;
    private File dataFolder;
    private HexPosterunkiPlugin plugin;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        TicketTrackingWorld ticketWorld = new TicketTrackingWorld(WORLD);
        server.addWorld(ticketWorld);
        world = ticketWorld;
        connection = SqlTestSupport.open();
        api = new TestHexApi(new TestUiService().service(), SqlTestSupport.db(connection));
        Plugin core = MockBukkit.createMockPlugin("HexCoreTest");
        server.getServicesManager().register(HexApi.class, api, core, ServicePriority.Normal);
        server.getServicesManager().register(CustomMobsApi.class, new FakeCustomMobsApi(core), core,
                ServicePriority.Normal);
        towns = new FakeTownsAdapter();
        dataFolder = new File(server.getPluginManager().getParentTemporaryDirectory(), "HexPosterunki-1.0.0");
        assertTrue(dataFolder.isDirectory() || dataFolder.mkdirs());
        write("loot.yml", "enabled: false\n");
        writeOutposts(0);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
        api.shutdown();
        connection.close();
    }

    // ---------------------------------------------------------------- files

    private void write(String name, String content) throws Exception {
        Files.writeString(new File(dataFolder, name).toPath(), content, StandardCharsets.UTF_8);
    }

    private void writeConfig(boolean enabled, String mobId) throws Exception {
        write("config.yml", """
                enabled: %s
                timing:
                  active-window-seconds: 3600
                  cooldown-seconds: 3600
                  preparation-seconds: 0
                  wave-delay-seconds: 0
                  grace-period-seconds: 120
                  loot-seconds: 120
                  tick-interval-ticks: 1
                  snapshot-interval-seconds: 30
                kills:
                  required: 1
                waves:
                  - id: 1
                    groups:
                      - mob-id: %s
                        count: 2
                        spawn-points: [ brama ]
                boss:
                  enabled: false
                protection:
                  snapshot-materials: [ OAK_DOOR ]
                ui:
                  bossbar:
                    enabled: false
                  hologram:
                    enabled: false
                  actionbar:
                    enabled: false
                rewards:
                  enabled: false
                """.formatted(enabled, mobId));
    }

    private void writeOutposts(int minX) throws Exception {
        write("outposts.yml", """
                outposts:
                  fort:
                    display-name: "Fort Północny"
                    world: %s
                    weight: 10
                    region:
                      min: "%d,60,0"
                      max: "%d,80,31"
                    center: "%d,64,16"
                    boss-spawn: "%d,64,16"
                    spawn-points:
                      brama: "%d,64,4"
                    loot-containers:
                      skrzynia: "%d,64,10"
                """.formatted(WORLD, minX, minX + 31, minX + 16, minX + 16, minX + 4, minX + 10));
    }

    // ---------------------------------------------------------------- helpers

    private Player fighterInsideTheFort() {
        Player player = server.addPlayer("Obronca");
        towns.assign(player.getUniqueId(), TOWN, "Rycerze");
        player.teleport(world.getBlockAt(8, 64, 8).getLocation());
        return player;
    }

    private void load() {
        plugin = MockBukkit.load(HexPosterunkiPlugin.class, (TownsAdapterFactory) (logger, version) -> towns);
    }

    private void waitUntil(BooleanSupplier condition, String what) throws Exception {
        for (int i = 0; i < 400 && !condition.getAsBoolean(); i++) {
            server.getScheduler().performOneTick();
            Thread.sleep(5L);
        }
        assertTrue(condition.getAsBoolean(), "nie osiągnięto: " + what);
    }

    private void startRunningFight() throws Exception {
        writeConfig(true, "forest_zombie");
        fighterInsideTheFort();
        load();
        waitUntil(() -> plugin.runtimeState() == RuntimeState.RUNNING, "RUNNING");
        waitUntil(() -> plugin.engine().phase() == RunPhase.WAVES, "fala");
        assertEquals(2, plugin.engine().liveMobs().size());
    }

    private boolean breakIsCancelled(int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.STONE);
        BlockBreakEvent event = new BlockBreakEvent(block, server.addPlayer());
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private boolean stateTableExists() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables"
                     + " WHERE UPPER(table_name) = 'POSTERUNKI_STATE'")) {
            rows.next();
            return rows.getInt(1) > 0;
        }
    }

    private void assertOperationUntouched(PosterunkiConfig config, Object catalog) {
        assertSame(config, plugin.config(), "dotychczasowa konfiguracja pozostaje");
        assertSame(catalog, plugin.catalog(), "dotychczasowy katalog pozostaje");
        assertSame(config, plugin.engine().context().config().get(), "silnik używa dotychczasowej konfiguracji");
        assertEquals(RuntimeState.RUNNING, plugin.runtimeState());
        assertTrue(plugin.schedulerRunning(), "harmonogram nie może zostać zatrzymany");
        assertTrue(plugin.engine().operational());
        assertEquals(EngineMode.RUNNING, plugin.engine().mode());
    }

    // ---------------------------------------------------------------- tests

    @Test
    void aReloadWithAnUnknownMobIsRejectedAndTheFightGoesOn() throws Exception {
        startRunningFight();
        PosterunkiConfig config = plugin.config();
        Object catalog = plugin.catalog();

        writeConfig(true, "nieznany_mob");
        String problem = plugin.reloadRuntime();

        assertNotNull(problem);
        assertTrue(problem.contains("odrzucone") && problem.contains("nieznany_mob"), problem);
        assertOperationUntouched(config, catalog);

        // The running fight really continues on the old configuration and the running scheduler.
        Player fighter = server.getPlayer("Obronca");
        List<LivingEntity> mobs = new ArrayList<>();
        for (Entity entity : world.getEntities()) {
            if (plugin.engine().liveMobs().containsKey(entity.getUniqueId())) {
                mobs.add((LivingEntity) entity);
            }
        }
        for (LivingEntity mob : mobs) {
            plugin.engine().onRunMobDeath(mob, fighter);
            mob.remove();
        }
        waitUntil(() -> plugin.engine().phase().isWon(), "zwycięstwo po odrzuconym przeładowaniu");
    }

    @Test
    void brokenYamlIsRejectedExplicitlyInsteadOfFallingBackToAnEmptyConfiguration() throws Exception {
        startRunningFight();
        PosterunkiConfig config = plugin.config();
        Object catalog = plugin.catalog();

        write("outposts.yml", "outposts:\n  fort: [ niezamknięta lista\n");
        String outpostsProblem = plugin.reloadRuntime();
        assertNotNull(outpostsProblem);
        assertTrue(outpostsProblem.contains("outposts.yml") && outpostsProblem.contains("YAML"), outpostsProblem);
        assertOperationUntouched(config, catalog);
        assertEquals(1, plugin.catalog().all().size());
        assertTrue(breakIsCancelled(8, 64, 9), "ochrona regionu działa dalej");

        write("outposts.yml", "outposts: {}\n");
        String emptyProblem = plugin.reloadRuntime();
        assertNotNull(emptyProblem, "pusty katalog nie może zastąpić działającego");
        assertOperationUntouched(config, catalog);

        writeOutposts(0);
        write("config.yml", "enabled: true\nwaves: [ - id: 1\n");
        String configProblem = plugin.reloadRuntime();
        assertNotNull(configProblem);
        assertTrue(configProblem.contains("config.yml"), configProblem);
        assertOperationUntouched(config, catalog);
    }

    /** Engine ticks plus scheduler ticks, as many as a running event would get. */
    private void tickEverything(int ticks) throws Exception {
        for (int i = 0; i < ticks; i++) {
            plugin.engine().tick();
            server.getScheduler().performOneTick();
            Thread.sleep(2L);
        }
    }

    private long zombies() {
        return world.getEntities().stream().filter(entity -> entity instanceof org.bukkit.entity.Zombie
                && entity.isValid()).count();
    }

    @Test
    void aBlockedFirstStartLoadsTheStoredStateAndAValidReloadReleasesOperation() throws Exception {
        writeConfig(false, "forest_zombie");
        fighterInsideTheFort();
        load();

        // Changed bootstrap contract: blocked events still load the stored state (here: none).
        waitUntil(() -> plugin.runtimeState() == RuntimeState.PAUSED, "wczytany stan przy zablokowanych eventach");
        assertTrue(stateTableExists(), "zapisany stan jest wczytywany niezależnie od blokady eventów");
        assertEquals(RunPhase.COOLDOWN, plugin.engine().phase());
        assertEquals("ok", plugin.databaseStatus());
        assertOperatingState(false, RuntimeState.PAUSED, false, EngineMode.STOPPED, false);
        assertTrue(plugin.engine().stoppedReason().contains("enabled: false"), plugin.engine().stoppedReason());
        assertTrue(breakIsCancelled(8, 64, 9), "ochrona regionów działa mimo zablokowanego startu");

        tickEverything(20);
        assertEquals(RunPhase.COOLDOWN, plugin.engine().phase(), "zablokowane eventy nie startują rundy");
        assertTrue(plugin.engine().state().runId().isEmpty());
        assertEquals(0L, zombies());

        writeConfig(true, "forest_zombie");
        assertNull(plugin.reloadRuntime());

        // Released synchronously - no second bootstrap that would pass through INITIALIZING.
        assertOperatingState(true, RuntimeState.RUNNING, true, EngineMode.RUNNING, true);
        waitUntil(() -> plugin.engine().phase() == RunPhase.WAVES, "pierwsza walka po odblokowaniu");
    }

    @Test
    void anInvalidReloadAfterABlockedStartChangesNothing() throws Exception {
        writeConfig(false, "forest_zombie");
        load();
        waitUntil(() -> plugin.runtimeState() == RuntimeState.PAUSED, "wczytany stan");
        PosterunkiConfig config = plugin.config();
        long revision = plugin.engine().state().revision();

        writeConfig(true, "nieznany_mob");
        String problem = plugin.reloadRuntime();

        assertNotNull(problem);
        assertTrue(problem.contains("odrzucone"), problem);
        assertSame(config, plugin.config());
        assertFalse(plugin.config().enabled());
        assertOperatingState(false, RuntimeState.PAUSED, false, EngineMode.STOPPED, false);
        assertEquals(RunPhase.COOLDOWN, plugin.engine().phase());
        assertEquals(revision, plugin.engine().state().revision(), "odrzucony kandydat niczego nie wczytuje ponownie");
        assertTrue(plugin.engine().state().runId().isEmpty());
    }

    @Test
    void movingTheRunningOutpostByReloadKeepsItsFightingGroundProtected() throws Exception {
        startRunningFight();

        writeOutposts(300);
        assertNull(plugin.reloadRuntime(), "przeniesiony posterunek jest poprawną konfiguracją");

        OutpostDefinition active = plugin.engine().activeOutpost().orElseThrow();
        assertTrue(active.region().contains(WORLD, 8, 64, 8), "walka trwa na zamrożonej geometrii");
        assertEquals(300, plugin.catalog().find("fort").orElseThrow().region().minX());
        assertTrue(breakIsCancelled(8, 64, 9), "stary teren walki pozostaje chroniony");
        assertTrue(breakIsCancelled(310, 64, 9), "nowa lokalizacja również jest chroniona");
        assertEquals(RunPhase.WAVES, plugin.engine().phase());
        assertEquals(EngineMode.RUNNING, plugin.engine().mode());
        assertTrue(plugin.schedulerRunning());
    }

    // ---------------------------------------------------------------- operating state ownership

    /** Configuration, runtime state, engine flag, operating mode and scheduler must agree. */
    private void assertOperatingState(boolean enabled, RuntimeState runtime, boolean operational, EngineMode mode,
                                      boolean scheduler) {
        assertEquals(enabled, plugin.config().enabled(), "config.enabled");
        assertEquals(runtime, plugin.runtimeState(), "RuntimeState");
        assertEquals(operational, plugin.engine().operational(), "engine.operational");
        assertEquals(mode, plugin.engine().mode(), "EngineMode");
        assertEquals(scheduler, plugin.schedulerRunning(), "scheduler");
    }

    @Test
    void anAdminResetUnderEnabledFalseCleansUpButOperationStaysStoppedUntilAValidReload() throws Exception {
        startRunningFight();
        String previousRun = plugin.engine().state().runId().orElseThrow();

        writeConfig(false, "forest_zombie");
        String disabled = plugin.reloadRuntime();
        assertTrue(disabled != null && disabled.contains("enabled: false"), disabled);
        assertOperatingState(false, RuntimeState.PAUSED, false, EngineMode.STOPPED, false);

        assertTrue(server.executeConsole("posterunki", "reset").hasSucceeded());
        assertEquals(EngineMode.RESETTING, plugin.engine().mode());
        waitUntil(() -> plugin.engine().phase() == RunPhase.COOLDOWN, "koniec resetu przy enabled: false");

        assertTrue(plugin.engine().liveMobs().isEmpty(), "sprzątanie zostało wykonane");
        assertOperatingState(false, RuntimeState.PAUSED, false, EngineMode.STOPPED, false);

        server.executeConsole("posterunki", "start");
        for (int i = 0; i < 20; i++) {
            plugin.engine().tick();
            server.getScheduler().performOneTick();
        }
        assertEquals(RunPhase.COOLDOWN, plugin.engine().phase(), "/posterunki start nie uruchamia wyłączonego eventu");
        assertEquals(previousRun, plugin.engine().state().runId().orElseThrow(), "nie powstał nowy run");
        assertOperatingState(false, RuntimeState.PAUSED, false, EngineMode.STOPPED, false);

        writeConfig(true, "forest_zombie");
        assertNull(plugin.reloadRuntime());
        assertOperatingState(true, RuntimeState.RUNNING, true, EngineMode.RUNNING, true);
        waitUntil(() -> plugin.engine().phase() == RunPhase.WAVES, "nowa walka po ponownym włączeniu");
        assertFalse(previousRun.equals(plugin.engine().state().runId().orElseThrow()));
    }

    @Test
    void aFailedRunIsReleasedOnlyByItsResetAndTheOperatingStateFollows() throws Exception {
        startRunningFight();
        String failedRun = plugin.engine().state().runId().orElseThrow();

        plugin.engine().failRun("symulowany błąd techniczny");
        assertOperatingState(true, RuntimeState.PAUSED, false, EngineMode.STOPPED, true);

        server.executeConsole("posterunki", "start");
        for (int i = 0; i < 10; i++) {
            server.getScheduler().performOneTick();
        }
        assertEquals(RunPhase.FAILED, plugin.engine().phase(), "nieudany run nie startuje ponownie sam z siebie");

        assertTrue(server.executeConsole("posterunki", "reset").hasSucceeded());
        waitUntil(() -> plugin.runtimeState() == RuntimeState.RUNNING, "zwolnienie pracy po zakończonym resecie");
        assertTrue(plugin.engine().operational());
        assertTrue(plugin.schedulerRunning());
        waitUntil(() -> plugin.engine().phase() == RunPhase.WAVES, "nowa walka po resecie");
        assertFalse(failedRun.equals(plugin.engine().state().runId().orElseThrow()));
        assertEquals(EngineMode.RUNNING, plugin.engine().mode());
    }

    // ---------------------------------------------------------------- startup with a stored run

    private static final UUID STORED_TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final String STORED_RUN = "zapisany-run";

    /** The geometry the stored run was started with (x 0..31) - independent of outposts.yml. */
    private static hexposterunki.config.OutpostDefinition storedGeometry() {
        return new hexposterunki.config.OutpostDefinition("fort", "Fort Północny", WORLD,
                hexposterunki.config.Cuboid.of(WORLD, new hexposterunki.config.BlockVec(0, 60, 0),
                        new hexposterunki.config.BlockVec(31, 80, 31)),
                new hexposterunki.config.PointDef(16, 64, 16, 0, 0), new hexposterunki.config.PointDef(16, 64, 16, 0, 0),
                java.util.Map.of("brama", new hexposterunki.config.PointDef(4, 64, 4, 0, 0)),
                java.util.Map.of("skrzynia", new hexposterunki.config.BlockVec(10, 64, 10)), 10);
    }

    /** Writes a run as a previous server session would have left it, geometry included. */
    private void storeRun(boolean resetting) {
        hexposterunki.domain.OutpostStateMachine state = new hexposterunki.domain.OutpostStateMachine();
        state.restoreEmpty();
        state.beginRun(STORED_RUN, "fort", System.currentTimeMillis(), 3_600_000L);
        state.applyBossRoll(null);
        state.claim(STORED_TOWN, "Obrońcy", 0L);
        state.startWaves();
        state.setMobsRemaining(2);
        if (resetting) {
            state.beginReset(0L);
        }
        hexposterunki.persistence.PosterunkiRepository repository = SqlTestSupport.freshRepository(connection);
        repository.saveConsistent(new hexposterunki.persistence.RunPersistenceSnapshot(state.snapshot(), "fort",
                List.of(), List.of(), null,
                hexposterunki.persistence.OutpostDefinitionCodec.encode(storedGeometry())), System.currentTimeMillis());
    }

    private enum Blocker { EMPTY_CATALOG, MOVED_AND_DISABLED, CUSTOM_MOBS_UNAVAILABLE }

    private long recoveryAudits(String marker) {
        return new hexposterunki.persistence.PosterunkiRepository(SqlTestSupport.db(connection)).recentAudit(100)
                .stream().filter(line -> line.contains("RECOVERY") && line.contains(marker)).count();
    }

    private void storedFightSurvivesABlockedStart(Blocker blocker) throws Exception {
        writeConfig(blocker != Blocker.MOVED_AND_DISABLED, "forest_zombie");
        switch (blocker) {
            case EMPTY_CATALOG -> write("outposts.yml", "outposts: {}\n");
            case MOVED_AND_DISABLED -> writeOutposts(300);
            case CUSTOM_MOBS_UNAVAILABLE -> server.getServicesManager().unregisterAll(
                    server.getServicesManager().getRegistration(CustomMobsApi.class).getPlugin());
        }
        if (blocker == Blocker.CUSTOM_MOBS_UNAVAILABLE) {
            // unregisterAll also dropped HexCore's API of the same stand-in owner; publish it again.
            Plugin core = MockBukkit.createMockPlugin("HexCoreTest2");
            server.getServicesManager().register(HexApi.class, api, core, ServicePriority.Normal);
        }
        storeRun(false);
        Player fighter = fighterInsideTheFort();
        load();

        waitUntil(() -> plugin.runtimeState() == RuntimeState.PAUSED, "wczytany zapisany run");

        // Loaded and protected ...
        assertEquals(RunPhase.WAVES, plugin.engine().phase());
        assertEquals(STORED_RUN, plugin.engine().state().runId().orElseThrow());
        assertEquals(storedGeometry(), plugin.engine().activeOutpost().orElseThrow(), "zapisana geometria jest aktywna");
        assertEquals(storedGeometry(), plugin.engine().context().regions().active().orElseThrow());
        assertTrue(breakIsCancelled(8, 64, 9), "zapisany teren walki jest chroniony przy zablokowanym starcie");
        if (blocker == Blocker.MOVED_AND_DISABLED) {
            assertTrue(breakIsCancelled(310, 64, 9), "aktualny region z katalogu też jest chroniony");
        }
        assertEquals("ok", plugin.databaseStatus(), "baza działa - blokada dotyczy tylko eventów");

        // ... but frozen: no opponents, no control change, no new run.
        assertOperatingState(blocker != Blocker.MOVED_AND_DISABLED, RuntimeState.PAUSED, false, EngineMode.STOPPED,
                false);
        String expectedReason = switch (blocker) {
            case EMPTY_CATALOG -> "brak poprawnych posterunków";
            case MOVED_AND_DISABLED -> "enabled: false";
            case CUSTOM_MOBS_UNAVAILABLE -> "HexCustomMobs";
        };
        assertTrue(plugin.engine().stoppedReason().contains(expectedReason), plugin.engine().stoppedReason());
        tickEverything(30);
        server.executeConsole("posterunki", "start");
        tickEverything(10);
        assertEquals(RunPhase.WAVES, plugin.engine().phase());
        assertEquals(STORED_RUN, plugin.engine().state().runId().orElseThrow(), "nie powstał nowy run");
        assertEquals(0L, zombies(), "zablokowany start nie tworzy przeciwników");
        assertTrue(plugin.engine().liveMobs().isEmpty());
        assertEquals(STORED_TOWN, plugin.engine().state().controllingTown().orElseThrow(), "brak zmiany kontroli");
        assertEquals(0, ((TicketTrackingWorld) world).ticketCount(), "walka nie jest uzbrajana przed odblokowaniem");

        // A valid reload releases operation and reconciles the stored fight exactly once.
        switch (blocker) {
            case EMPTY_CATALOG -> writeOutposts(300);
            case MOVED_AND_DISABLED -> writeConfig(true, "forest_zombie");
            case CUSTOM_MOBS_UNAVAILABLE -> server.getServicesManager().register(CustomMobsApi.class,
                    new FakeCustomMobsApi(MockBukkit.createMockPlugin("HexCustomMobsTest")),
                    MockBukkit.createMockPlugin("HexCustomMobsOwner"), ServicePriority.Normal);
        }
        assertNull(plugin.reloadRuntime());
        assertOperatingState(true, RuntimeState.RUNNING, true, EngineMode.RUNNING, true);
        assertEquals(STORED_RUN, plugin.engine().state().runId().orElseThrow());
        assertEquals(storedGeometry(), plugin.engine().activeOutpost().orElseThrow(), "po odblokowaniu walka trwa w zapisanej lokalizacji");
        assertEquals(2, plugin.engine().liveMobs().size());
        assertEquals(2L, zombies(), "fala uzupełniona dokładnie raz");
        for (org.bukkit.entity.Entity entity : world.getEntities()) {
            if (plugin.engine().liveMobs().containsKey(entity.getUniqueId())) {
                assertTrue(storedGeometry().region().contains(WORLD, entity.getLocation().getBlockX(),
                        entity.getLocation().getBlockY(), entity.getLocation().getBlockZ()));
            }
        }
        assertTrue(((TicketTrackingWorld) world).ticketCount() > 0);

        tickEverything(20);
        assertNull(plugin.reloadRuntime(), "kolejne poprawne przeładowanie");
        tickEverything(20);
        assertEquals(2L, zombies(), "brak podwójnych przeciwników po kolejnym przeładowaniu");
        assertTrue(plugin.schedulerRunning());
        assertEquals(RuntimeState.RUNNING, plugin.runtimeState());
        long reconciled = 0;
        for (int i = 0; i < 200 && reconciled == 0; i++) {
            reconciled = recoveryAudits("po-odblokowaniu");
            Thread.sleep(5L);
        }
        Thread.sleep(50L);
        assertEquals(1L, recoveryAudits("po-odblokowaniu"), "uzgodnienie walki wykonane dokładnie raz");
        assertTrue(fighter.isOnline());
    }

    @Test
    void aStoredFightWithAnEmptyCatalogIsProtectedButFrozenUntilAValidReload() throws Exception {
        storedFightSurvivesABlockedStart(Blocker.EMPTY_CATALOG);
    }

    @Test
    void aStoredFightWithAMovedCatalogAndEnabledFalseIsProtectedButFrozenUntilAValidReload() throws Exception {
        storedFightSurvivesABlockedStart(Blocker.MOVED_AND_DISABLED);
    }

    @Test
    void aStoredFightWithoutHexCustomMobsIsProtectedButFrozenUntilAValidReload() throws Exception {
        storedFightSurvivesABlockedStart(Blocker.CUSTOM_MOBS_UNAVAILABLE);
    }

    @Test
    void aStoredResetFinishesWhileEventsStayBlocked() throws Exception {
        writeConfig(false, "forest_zombie");
        writeOutposts(300);
        storeRun(true);
        world.getBlockAt(5, 64, 5).setType(Material.FIRE);
        load();

        waitUntil(() -> plugin.runtimeState() == RuntimeState.PAUSED, "wczytany przerwany reset");
        assertEquals(RunPhase.RESETTING, plugin.engine().phase());
        assertEquals(EngineMode.RESETTING, plugin.engine().mode());
        assertTrue(breakIsCancelled(8, 64, 9), "teren przerwanego resetu jest chroniony do jego końca");
        assertTrue(((TicketTrackingWorld) world).ticketCount() > 0);

        waitUntil(() -> plugin.engine().phase() == RunPhase.COOLDOWN, "reset zakończony przy zablokowanych eventach");
        assertEquals(Material.AIR, world.getBlockAt(5, 64, 5).getType(), "reset wyczyścił zapisaną lokalizację");
        assertEquals(0, ((TicketTrackingWorld) world).ticketCount());
        assertTrue(plugin.engine().context().regions().active().isEmpty());
        assertFalse(breakIsCancelled(8, 64, 9), "po resecie chroniony jest tylko aktualny katalog");
        assertTrue(breakIsCancelled(310, 64, 9));
        assertOperatingState(false, RuntimeState.PAUSED, false, EngineMode.STOPPED, false);

        tickEverything(20);
        assertEquals(RunPhase.COOLDOWN, plugin.engine().phase(), "po resecie nie startuje nowa runda");
        assertEquals(0L, zombies());
        assertEquals(RunPhase.COOLDOWN, new hexposterunki.persistence.PosterunkiRepository(SqlTestSupport.db(connection))
                .loadAll().run().phase());
    }

    @Test
    void anUnreachableDatabaseIsReportedAsNotLoadedStateNotAsABlockedEvent() throws Exception {
        writeConfig(true, "forest_zombie");
        storeRun(false);
        connection.close();   // the database is gone before the server starts
        load();

        waitUntil(() -> plugin.runtimeState() == RuntimeState.FAILED, "zgłoszony błąd wczytania stanu");
        assertEquals(RunPhase.RECOVERING, plugin.engine().phase(), "nic nie zostało wczytane");
        assertTrue(plugin.databaseStatus().contains("niedostępna"), plugin.databaseStatus());
        assertTrue(plugin.engine().stoppedReason().contains("baza danych"), plugin.engine().stoppedReason());
        assertFalse(plugin.engine().operational());
        assertFalse(plugin.schedulerRunning());
        assertTrue(plugin.engine().context().regions().active().isEmpty(), "zapisanej geometrii nie da się przywrócić");
        assertTrue(breakIsCancelled(8, 64, 9), "regiony z outposts.yml pozostają chronione");
        assertEquals(0L, zombies());
    }

    // ---------------------------------------------------------------- database status after a repaired load

    private void breakStateReads() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE posterunki_state RENAME COLUMN revision TO ukryta_rewizja");
        }
    }

    private void repairStateReads() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE posterunki_state RENAME COLUMN ukryta_rewizja TO revision");
        }
    }

    /** A real SQL read failure on the first bootstrap, a still failing retry, then a repaired retry. */
    private void storedRunLoadFailsAtStartup(boolean enabled) throws Exception {
        writeConfig(enabled, "forest_zombie");
        storeRun(false);
        breakStateReads();
        load();

        waitUntil(() -> plugin.runtimeState() == RuntimeState.FAILED, "błąd wczytania przy starcie");
        assertTrue(plugin.databaseStatus().contains("niedostępna"), plugin.databaseStatus());
        assertEquals(RunPhase.RECOVERING, plugin.engine().phase());

        // Still broken: the retry fails again and keeps reporting the database problem.
        plugin.reloadRuntime();
        waitUntil(() -> plugin.runtimeState() == RuntimeState.FAILED && !plugin.databaseStatus().equals("ok"),
                "ponowny błąd wczytania");
        assertTrue(plugin.databaseStatus().contains("niedostępna"), plugin.databaseStatus());
        assertEquals(0L, zombies());

        repairStateReads();
    }

    @Test
    void aRepairedDatabaseIsReportedAsOkOnceTheStoredRunWasLoaded() throws Exception {
        storedRunLoadFailsAtStartup(true);

        assertNull(plugin.reloadRuntime());
        waitUntil(() -> plugin.runtimeState() == RuntimeState.RUNNING, "RUNNING po naprawie bazy");

        assertOperatingState(true, RuntimeState.RUNNING, true, EngineMode.RUNNING, true);
        assertEquals(STORED_RUN, plugin.engine().state().runId().orElseThrow());
        assertEquals(2, plugin.engine().liveMobs().size(), "przeciwnicy zapisanego runu odtworzeni");
        assertEquals(2L, zombies());
        assertEquals("ok", plugin.databaseStatus(), "po udanym wczytaniu status bazy nie może pokazywać starego błędu");
    }

    @Test
    void aRepairedDatabaseIsOkWhileEnabledFalseKeepsEventsPaused() throws Exception {
        storedRunLoadFailsAtStartup(false);

        String disabled = plugin.reloadRuntime();
        assertTrue(disabled != null && disabled.contains("enabled: false"), disabled);
        waitUntil(() -> plugin.runtimeState() == RuntimeState.PAUSED, "wczytany stan przy wyłączonych eventach");

        assertEquals("ok", plugin.databaseStatus(), "baza działa, choć eventy są wyłączone");
        assertOperatingState(false, RuntimeState.PAUSED, false, EngineMode.STOPPED, false);
        assertEquals(STORED_RUN, plugin.engine().state().runId().orElseThrow());
        assertTrue(breakIsCancelled(8, 64, 9), "teren zapisanego runu jest chroniony");
        assertEquals(0L, zombies(), "przy wyłączonych eventach walka nie jest odtwarzana");

        writeConfig(true, "forest_zombie");
        assertNull(plugin.reloadRuntime());
        assertOperatingState(true, RuntimeState.RUNNING, true, EngineMode.RUNNING, true);
        assertEquals(2L, zombies(), "odroczone odtworzenie walki wykonane dokładnie raz");
        assertEquals("ok", plugin.databaseStatus());
    }

    // ---------------------------------------------------------------- outstanding database writes

    private static final UUID REWARDED_PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000dd");
    private static final String OLD_CLAIM_RUN = "poprzednia-wygrana";

    /** A reward earned in an earlier run, waiting for its player to come back. */
    private String insertOldClaim() {
        hexposterunki.rewards.RewardPayload payload = new hexposterunki.rewards.RewardPayload(
                List.of(hexposterunki.rewards.RewardComponent.item("DIAMOND", 1, null, List.of())));
        hexposterunki.persistence.RewardClaim claim = new hexposterunki.persistence.RewardClaim(OLD_CLAIM_RUN,
                "fort", REWARDED_PLAYER, 1, hexposterunki.persistence.RewardClaim.Status.CLAIMED, 0, 0,
                payload.serialize(), null);
        new hexposterunki.persistence.PosterunkiRepository(SqlTestSupport.db(connection))
                .insertClaims(List.of(claim), 10L);
        return claim.key();
    }

    private String claimStatus(String key) throws Exception {
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM posterunki_reward_claims WHERE idempotency_key = ?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private int claimProgress(String key) throws Exception {
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "SELECT progress FROM posterunki_reward_claims WHERE idempotency_key = ?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private int diamonds(Player player) {
        return java.util.Arrays.stream(player.getInventory().getContents())
                .filter(stack -> stack != null && stack.getType() == Material.DIAMOND)
                .mapToInt(stack -> stack.getAmount()).sum();
    }

    /**
     * Lets the joined player's old claim be handed over and breaks the database exactly between the
     * payout and the status write: the delivery takes the claim (DELIVERING), then the column the
     * final write needs disappears.
     */
    private Player deliverOldClaimWithAFailingStatusWrite(String key) throws Exception {
        Player player = new PlayerMock(server, "Zwyciezca", REWARDED_PLAYER);
        server.addPlayer((PlayerMock) player);          // real PlayerJoinEvent -> delivery
        waitUntil(() -> {
            try {
                return "DELIVERING".equals(claimStatus(key));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }, "roszczenie przejęte do wydania");
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE posterunki_reward_claims RENAME COLUMN last_error TO ukryty_blad");
        }
        waitUntil(() -> plugin.persistence().hasPendingSideWrites(), "wynik wydania czeka na zapis");
        assertEquals(1, diamonds(player), "nagroda została wydana mimo zatrzymanego eventu");
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE posterunki_reward_claims RENAME COLUMN ukryty_blad TO last_error");
        }
        return player;
    }

    private void assertSettledWithoutASecondPayout(String key, Player player) throws Exception {
        waitUntil(() -> !plugin.persistence().hasPendingSideWrites(), "zapis statusu został powtórzony");
        assertEquals("DELIVERED", claimStatus(key), "status został zapisany bez restartu i reloadu");
        assertEquals(1, claimProgress(key));
        assertEquals(1, diamonds(player), "nagroda nie została wydana drugi raz");
    }

    @Test
    void aFailedRunStillSettlesAnOutstandingDeliveryStatus() throws Exception {
        startRunningFight();
        String key = insertOldClaim();
        plugin.engine().failRun("symulowany błąd techniczny");
        assertOperatingState(true, RuntimeState.PAUSED, false, EngineMode.STOPPED, true);

        Player player = deliverOldClaimWithAFailingStatusWrite(key);
        assertSettledWithoutASecondPayout(key, player);

        assertEquals(RunPhase.FAILED, plugin.engine().phase(), "ponawianie zapisów nie wznawia eventu");
        assertOperatingState(true, RuntimeState.PAUSED, false, EngineMode.STOPPED, true);
    }

    @Test
    void aDisabledPluginStillSettlesAnOutstandingDeliveryStatusOfAJoinedPlayer() throws Exception {
        writeConfig(false, "forest_zombie");
        load();
        waitUntil(() -> plugin.runtimeState() == RuntimeState.PAUSED, "wczytany stan przy wyłączonych eventach");
        assertOperatingState(false, RuntimeState.PAUSED, false, EngineMode.STOPPED, false);
        String key = insertOldClaim();

        Player player = deliverOldClaimWithAFailingStatusWrite(key);
        assertSettledWithoutASecondPayout(key, player);

        assertTrue(plugin.persistenceRetryRunning(), "ponawianie zapisów działa mimo zatrzymanego harmonogramu");
        assertEquals(RunPhase.COOLDOWN, plugin.engine().phase(), "nie wystartowała żadna runda");
        assertOperatingState(false, RuntimeState.PAUSED, false, EngineMode.STOPPED, false);
    }

    @Test
    void repeatedReloadsKeepExactlyOneRetryTaskAndDisableEndsIt() throws Exception {
        startRunningFight();
        assertTrue(plugin.persistenceRetryRunning());

        for (int i = 0; i < 3; i++) {
            assertNull(plugin.reloadRuntime());
        }
        assertTrue(plugin.persistenceRetryRunning(), "przeładowanie nie gubi ponawiania zapisów");

        long before = plugin.persistenceRetrySweeps();
        for (int i = 0; i < 20; i++) {
            server.getScheduler().performOneTick();
        }
        assertEquals(before + 1, plugin.persistenceRetrySweeps(),
                "jedno zadanie ponawiania na interwał, mimo kilku przeładowań");

        server.getPluginManager().disablePlugin(plugin);
        assertFalse(plugin.persistenceRetryRunning(), "wyłączenie kończy cykl ponawiania");
        long afterDisable = plugin.persistenceRetrySweeps();
        for (int i = 0; i < 60; i++) {
            server.getScheduler().performOneTick();
        }
        assertEquals(afterDisable, plugin.persistenceRetrySweeps(), "po wyłączeniu nic już nie sweepuje");
    }
}
