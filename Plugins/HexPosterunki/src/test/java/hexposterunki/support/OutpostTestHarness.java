package hexposterunki.support;

import hexposterunki.boss.BossEngineAdapter;
import hexposterunki.boss.BossRoller;
import hexposterunki.config.BlockVec;
import hexposterunki.config.BossConfig;
import hexposterunki.config.Cuboid;
import hexposterunki.config.LootConfig;
import hexposterunki.config.OutpostCatalog;
import hexposterunki.config.OutpostDefinition;
import hexposterunki.config.PointDef;
import hexposterunki.config.PosterunkiConfig;
import hexposterunki.config.RewardsConfig;
import hexposterunki.config.WaveDefinition;
import hexposterunki.engine.BlockStateService;
import hexposterunki.engine.ChunkTicketService;
import hexposterunki.engine.EngineContext;
import hexposterunki.engine.LootService;
import hexposterunki.engine.OutpostEngine;
import hexposterunki.engine.WaveScaling;
import hexposterunki.engine.WaveSpawner;
import hexposterunki.listener.EncounterListener;
import hexposterunki.listener.PlayerTrackingListener;
import hexposterunki.listener.RegionProtectionListener;
import hexposterunki.mobs.RunTags;
import hexposterunki.persistence.PersistenceService;
import hexposterunki.persistence.PosterunkiRepository;
import hexposterunki.persistence.SideWriteRetryTask;
import hexposterunki.persistence.SqlTestSupport;
import hexposterunki.recovery.RecoveryService;
import hexposterunki.region.RegionIndex;
import hexposterunki.rewards.CompositeRewardExecutor;
import hexposterunki.rewards.RewardService;
import hexposterunki.selection.OutpostSelector;
import hexposterunki.ui.DisplayService;
import hexposterunki.ui.PosterunkiUi;
import hexposterunki.ui.UiKeys;
import hexposterunki.util.RandomSource;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Boots a mock server with the <b>real</b> engine, the real listeners, the real UI service and a
 * real (H2) database, so integration tests exercise the production collaboration instead of a
 * second implementation of it.
 *
 * <p>Only external boundaries are stood in for: HexTowns ({@code TownsAdapter}), HexCustomMobs
 * ({@code CustomMobsApi}) and - when a test needs a boss - the plugin's own
 * {@link BossEngineAdapter} interface. None of them is on this build's classpath in a usable form.
 *
 * <p>{@link #restart()} simulates a server restart on the same database: the mock server and the
 * database thread are stopped without any flush, and a fresh engine starts in {@code RECOVERING}.
 */
public final class OutpostTestHarness implements AutoCloseable {

    public static final String WORLD = "posterunki_test";

    public final ServerMock server;
    public final Plugin plugin;
    public final World world;
    public final TicketTrackingWorld ticketWorld;
    public final OutpostEngine engine;
    public final PosterunkiUi ui;
    public final FakeTownsAdapter towns;
    public final FakeCustomMobsApi customMobs;
    public final RegionProtectionListener protectionListener;
    public final EncounterListener encounterListener;
    public final PlayerTrackingListener trackingListener;
    public final OutpostDefinition outpost;
    public final PersistenceService persistence;
    public final RunTags tags;
    public final LootService loot;
    public final RegionIndex regions;
    public final RewardService rewards;
    /** Mirrors the plugin: outstanding database writes are repeated independently of the engine. */
    public final SideWriteRetryTask sideWriteRetry;
    public final BossEngineAdapter bossAdapter;

    private final Connection connection;
    private final TestHexApi api;
    private final Logger logger;
    private final AtomicReference<PosterunkiConfig> configRef = new AtomicReference<>();
    private final AtomicReference<OutpostCatalog> catalogRef = new AtomicReference<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private boolean ownsConnection = true;
    private boolean closed;

    public OutpostTestHarness() throws Exception {
        this(SqlTestSupport.open(), null, false);
    }

    /** Harness whose run can enter the boss phase through the given boss engine test double. */
    public static OutpostTestHarness withBoss(BossEngineAdapter bossAdapter) throws Exception {
        return new OutpostTestHarness(SqlTestSupport.open(), bossAdapter, false);
    }

    private OutpostTestHarness(Connection connection, BossEngineAdapter bossAdapter, boolean recovering)
            throws Exception {
        this.server = MockBukkit.mock();
        this.plugin = MockBukkit.createMockPlugin("HexPosterunkiTest");
        this.ticketWorld = new TicketTrackingWorld(WORLD);
        server.addWorld(ticketWorld);
        this.world = ticketWorld;
        this.bossAdapter = bossAdapter;

        this.logger = Logger.getLogger("HexPosterunkiTest");
        logger.setLevel(Level.OFF);

        this.connection = connection;
        PosterunkiRepository repository = SqlTestSupport.freshRepository(connection);

        TestUiService uiService = new TestUiService();
        this.api = new TestHexApi(uiService.service(), SqlTestSupport.db(connection));
        this.ui = new PosterunkiUi(api);
        ui.registerDefaults();

        this.persistence = new PersistenceService(api, repository, logger);
        this.sideWriteRetry = new SideWriteRetryTask(plugin, persistence);
        sideWriteRetry.start();
        this.towns = new FakeTownsAdapter();
        this.customMobs = new FakeCustomMobsApi(plugin);
        this.tags = new RunTags(plugin);
        this.loot = new LootService(RandomSource.seeded(7L), logger);

        this.outpost = new OutpostDefinition("fort", "Fort Północny", WORLD,
                Cuboid.of(WORLD, new BlockVec(0, 60, 0), new BlockVec(31, 80, 31)),
                new PointDef(16, 64, 16, 0, 0),
                new PointDef(16, 64, 16, 0, 0),
                Map.of("brama", new PointDef(4, 64, 4, 0, 0)),
                Map.of("skrzynia", new BlockVec(10, 64, 10)),
                10);
        catalogRef.set(new OutpostCatalog(Map.of("fort", outpost), new hexposterunki.config.ValidationReport()));

        configRef.set(bossAdapter == null ? config(120L) : withBossPhase(config(120L)));

        this.regions = new RegionIndex();
        regions.rebuild(catalogRef.get());

        DisplayService displays = new DisplayService(ui, tags);
        this.rewards = new RewardService(plugin, persistence, ui, CompositeRewardExecutor.standard());

        EngineContext context = new EngineContext(plugin, logger, configRef::get, catalogRef::get,
                persistence, ui, displays, regions, tags, towns, customMobs, bossAdapter,
                new BossRoller(), new OutpostSelector(), RandomSource.seeded(11L), loot,
                new BlockStateService(plugin, logger), new ChunkTicketService(plugin, logger),
                rewards, new WaveSpawner(customMobs, tags, RandomSource.seeded(3L), WaveScaling.none(), logger));

        this.engine = new OutpostEngine(context);
        this.encounterListener = new EncounterListener(engine, ui);
        this.protectionListener = new RegionProtectionListener(regions, engine, ui);
        this.trackingListener = new PlayerTrackingListener(engine, ui, rewards, shuttingDown::get);

        server.getPluginManager().registerEvents(protectionListener, plugin);
        server.getPluginManager().registerEvents(encounterListener, plugin);
        server.getPluginManager().registerEvents(trackingListener, plugin);

        if (!recovering) {
            engine.state().restoreEmpty();
            engine.setOperational(true, null);
        }
    }

    /** Builds a configuration with a single wave and a given loot window. */
    public PosterunkiConfig config(long lootSeconds) {
        return new PosterunkiConfig(true, false,
                new PosterunkiConfig.Timing(3600L, 1800L, 0L, 0L, 120L, lootSeconds, 20L, 30L),
                3, true,
                List.of(new WaveDefinition(1, List.of(
                        new WaveDefinition.Group("forest_zombie", 2, List.of("brama"))))),
                new BossConfig(false, 0.0D, "stormbossy", "1.0", true, true, List.of()),
                new PosterunkiConfig.Towns("*"),
                new PosterunkiConfig.Protection(100_000L, 100_000L, 20_000L, 64, 40L, false,
                        Set.of("OAK_DOOR")),
                new PosterunkiConfig.Ui(false, 20_000.0D, false, 2.0F, 20L, false),
                RewardsConfig.disabled(),
                LootConfig.disabled());
    }

    /** The same configuration with a boss that is always rolled. */
    public static PosterunkiConfig withBossPhase(PosterunkiConfig base) {
        return new PosterunkiConfig(base.enabled(), base.debug(), base.timing(), base.requiredKills(),
                base.avoidImmediateRepeat(), base.waves(),
                new BossConfig(true, 1.0D, "test", "1.0", false, false,
                        List.of(new BossConfig.WeightedBoss("wladca_burzy", 1))),
                base.towns(), base.protection(), base.ui(), base.rewards(), base.loot());
    }

    /** The same configuration with top rewards enabled. */
    public static PosterunkiConfig withRewards(PosterunkiConfig base, RewardsConfig rewards) {
        return new PosterunkiConfig(base.enabled(), base.debug(), base.timing(), base.requiredKills(),
                base.avoidImmediateRepeat(), base.waves(), base.boss(), base.towns(), base.protection(),
                base.ui(), rewards, base.loot());
    }

    public void setConfig(PosterunkiConfig config) {
        configRef.set(config);
    }

    public PosterunkiConfig currentConfig() {
        return configRef.get();
    }

    /** Switches catalog and protection index exactly like {@code HexPosterunkiPlugin} applies a reload. */
    public void applyCatalog(OutpostCatalog catalog) {
        catalogRef.set(catalog);
        regions.rebuild(catalog);
    }

    public Location inside(double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    public Location outside() {
        return new Location(world, 200, 64, 200);
    }

    /** Runs the engine tick plus any scheduled main-thread tasks, like the real scheduler would. */
    public void tick() {
        engine.tick();
        server.getScheduler().performOneTick();
    }

    /** Waits until every queued database write completed, so assertions see a settled state. */
    public void settle() throws Exception {
        for (int i = 0; i < 50 && persistence.hasPendingWork(); i++) {
            server.getScheduler().performOneTick();
            Thread.sleep(5L);
        }
        for (int i = 0; i < 5; i++) {
            server.getScheduler().performOneTick();
            Thread.sleep(5L);
        }
    }

    /**
     * Waits for queued database writes <b>without</b> running scheduled main-thread tasks, so
     * multi-tick work such as a reset scan stays exactly where it is.
     */
    public void awaitWrites() throws Exception {
        for (int i = 0; i < 400 && persistence.hasPendingWork(); i++) {
            Thread.sleep(5L);
        }
        Thread.sleep(10L);
    }

    /** Blocks the database thread until the returned handle is run. */
    public Runnable holdDatabase() {
        return api.holdDatabase();
    }

    public Connection database() {
        return connection;
    }

    public void sql(String statement) throws Exception {
        try (Statement sql = connection.createStatement()) {
            sql.execute(statement);
        }
    }

    public String uiKeyForLeave() {
        return UiKeys.PROGRESS_LOST_LEAVE;
    }

    /**
     * Simulates a crash-restart on the same database. Nothing is flushed: whatever the engine had not
     * handed to the database thread yet is lost, exactly like on a real crash.
     *
     * @return a new harness in {@code RECOVERING}; call {@link #recover()} to run the startup recovery
     */
    public OutpostTestHarness restart() throws Exception {
        ownsConnection = false;
        shuttingDown.set(true);
        sideWriteRetry.stop();
        api.shutdownGracefully();
        closed = true;
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
        return new OutpostTestHarness(connection, bossAdapter, true);
    }

    /**
     * Runs the production startup recovery against the stored state, then applies the plugin's rule for
     * an enabled, bootstrapped plugin: operational unless the recovered run is a technical failure.
     */
    public void recover() throws Exception {
        new RecoveryService(engine, logger).recover(persistence.loadAll().get());
        boolean failed = engine.phase() == hexposterunki.domain.RunPhase.FAILED;
        engine.setOperational(!failed, failed ? engine.state().failureReason() : null);
    }

    /** Encoded outpost definition stored with the given run, read with plain SQL. */
    public String storedDefinition(String runId) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT outpost_definition FROM posterunki_runs WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    @Override
    public void close() throws Exception {
        if (closed) {
            // Already handed over by restart(); the successor owns server and database.
            return;
        }
        closed = true;
        shuttingDown.set(true);
        sideWriteRetry.stop();
        api.shutdown();
        if (ownsConnection && connection != null) {
            connection.close();
        }
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }
}
