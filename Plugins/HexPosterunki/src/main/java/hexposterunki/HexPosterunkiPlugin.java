package hexposterunki;

import hex.core.api.HexApi;
import hexcustommobs.api.CustomMobsApi;
import hexposterunki.boss.AdapterHealth;
import hexposterunki.boss.BossRoller;
import hexposterunki.boss.StormBossV1Adapter;
import hexposterunki.command.PosterunkiCommand;
import hexposterunki.config.ConfigLoader;
import hexposterunki.config.LootConfig;
import hexposterunki.config.LootLoader;
import hexposterunki.config.OutpostCatalog;
import hexposterunki.config.OutpostDefinition;
import hexposterunki.config.OutpostsLoader;
import hexposterunki.config.PosterunkiConfig;
import hexposterunki.domain.RunPhase;
import hexposterunki.engine.BlockStateService;
import hexposterunki.engine.ChunkTicketService;
import hexposterunki.engine.EngineContext;
import hexposterunki.engine.LootService;
import hexposterunki.engine.OutpostEngine;
import hexposterunki.engine.OutpostScheduler;
import hexposterunki.engine.WaveScaling;
import hexposterunki.engine.WaveSpawner;
import hexposterunki.listener.EncounterListener;
import hexposterunki.listener.PlayerTrackingListener;
import hexposterunki.listener.RegionProtectionListener;
import hexposterunki.mobs.RunTags;
import hexposterunki.persistence.AuditEvent;
import hexposterunki.persistence.PersistenceService;
import hexposterunki.persistence.PosterunkiRepository;
import hexposterunki.persistence.SideWriteRetryTask;
import hexposterunki.recovery.RecoveryService;
import hexposterunki.region.RegionIndex;
import hexposterunki.rewards.CompositeRewardExecutor;
import hexposterunki.rewards.RewardService;
import hexposterunki.selection.OutpostSelector;
import hexposterunki.towns.TownsAdapter;
import hexposterunki.towns.TownsAdapterFactory;
import hexposterunki.ui.DisplayService;
import hexposterunki.ui.PosterunkiUi;
import hexposterunki.util.RandomSource;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bootstrap only: resolve runtime services, build the object graph, wire listeners and commands.
 *
 * <p>All behaviour lives in the dedicated services. This class decides <i>whether</i> the event
 * operation may start, owns configuration reloading, and keeps the bootstrap lifecycle explicit
 * ({@link RuntimeState}) so a plugin that started blocked can still be brought up by a reload.
 *
 * <p>Configuration is handled as a <b>candidate</b>: files are parsed strictly, the outpost catalog
 * is built and every integration (HexTowns, HexCustomMobs, STORMBOSSY) is resolved into fresh
 * objects and validated before anything running is touched. A rejected reload therefore leaves the
 * working configuration, the protection index, the adapters and the scheduler exactly as they were.
 *
 * <p>Not {@code final}: MockBukkit loads plugins through a generated subclass, and the lifecycle
 * tests run this very class.
 */
public class HexPosterunkiPlugin extends JavaPlugin {

    private static final String DISABLED_BLOCKER = "plugin wyłączony w config.yml (enabled: false)";

    private final AtomicReference<PosterunkiConfig> configRef = new AtomicReference<>();
    private final AtomicReference<OutpostCatalog> catalogRef = new AtomicReference<>(OutpostCatalog.empty());
    private final AtomicReference<RuntimeState> runtimeState = new AtomicReference<>(RuntimeState.NOT_INITIALIZED);
    private final AtomicBoolean bootstrapping = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final TownsAdapterFactory townsFactory;

    private HexApi hexApi;
    private RunTags tags;
    private RandomSource random;
    private CustomMobsApi customMobsApi;
    private TownsAdapter townsAdapter;
    private StormBossV1Adapter bossAdapter;
    private WaveSpawner waveSpawner;
    private PosterunkiUi ui;
    private PersistenceService persistence;
    private SideWriteRetryTask sideWriteRetry;
    private RegionIndex regionIndex;
    private OutpostEngine engine;
    private OutpostScheduler scheduler;
    private EncounterListener encounterListener;
    private RewardService rewardService;
    private String customMobsStatus = "niezainicjalizowane";
    private String databaseStatus = "niezainicjalizowane";

    /**
     * Why new events may not run with the applied configuration, or null. Deliberately separate from
     * whether the stored state could be loaded: a blocked operation still loads and protects the stored
     * run (see {@link #bootstrap()}).
     */
    private volatile String operationBlocker;

    /** The stored fight was loaded while operation was blocked; its world is reconciled on release. */
    private boolean combatReconciliationPending;

    public HexPosterunkiPlugin() {
        this(TownsAdapterFactory.reflection());
    }

    /** Lets the lifecycle tests replace the HexTowns binding - and nothing else. */
    protected HexPosterunkiPlugin(TownsAdapterFactory townsFactory) {
        this.townsFactory = Objects.requireNonNull(townsFactory, "townsFactory");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("outposts.yml");
        saveResourceIfMissing("loot.yml");

        RegisteredServiceProvider<HexApi> hexRegistration = Bukkit.getServicesManager().getRegistration(HexApi.class);
        if (hexRegistration == null) {
            getLogger().severe("HexCore nie jest dostępne - wyłączam HexPosterunki.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.hexApi = hexRegistration.getProvider();
        this.ui = new PosterunkiUi(hexApi);
        ui.registerDefaults();
        this.tags = new RunTags(this);
        this.random = RandomSource.threadLocal();

        ParsedConfiguration parsed;
        try {
            parsed = parseConfiguration();
        } catch (ConfigurationException exception) {
            // There is no previous configuration to fall back to, and an empty one would silently
            // drop every protected region. Refuse to start instead.
            getLogger().severe("Konfiguracja HexPosterunki jest niepoprawna: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        RuntimeCandidate candidate = resolveCandidate(parsed);
        applyCandidate(candidate);

        buildRuntime();
        registerListeners();
        registerCommand();

        String blocker = findBlocker(candidate);
        this.operationBlocker = blocker;
        if (blocker != null) {
            engine.setOperational(false, blocker);
            getLogger().severe("Eventy posterunków NIE wystartują: " + blocker
                    + ". Po usunięciu przyczyny użyj /posterunki reload.");
        }
        // Loading the stored state does not depend on whether events may run: the stored run's
        // geometry has to be protected even while operation is blocked.
        bootstrap();
    }

    @Override
    public void onDisable() {
        shuttingDown.set(true);
        if (scheduler != null) {
            scheduler.stop();
        }
        if (sideWriteRetry != null) {
            // The shutdown flush below makes its own last attempt at the outstanding writes.
            sideWriteRetry.stop();
        }
        if (engine != null) {
            engine.context().blockStates().cancelScans();
        }
        if (engine != null && persistence != null && engine.state().phase() != RunPhase.RECOVERING) {
            // A shutdown is not a voluntary leave: flush the outstanding state before going down.
            String problem = persistence.flushOnShutdown(engine.captureSnapshot(), 5L);
            if (problem != null) {
                getLogger().severe("[db] Ostatni zapis stanu nie powiódł się: " + problem
                        + ". Po restarcie odzyskany zostanie ostatni spójny stan.");
            }
        }
        if (engine != null) {
            engine.context().displays().clearBossBars();
            engine.context().displays().removeHologram();
            engine.context().chunkTickets().release();
        }
        getLogger().info("HexPosterunki zatrzymany.");
    }

    private void saveResourceIfMissing(String name) {
        if (!new File(getDataFolder(), name).exists()) {
            saveResource(name, false);
        }
    }

    // ---------------------------------------------------------------- configuration

    /** Strictly parsed configuration files, not yet applied to the running plugin. */
    private record ParsedConfiguration(PosterunkiConfig config, OutpostCatalog catalog) {
    }

    /**
     * Everything a reload would switch to: the parsed configuration plus integrations resolved for
     * it. Built from fresh objects only, so building and validating it has no side effect.
     */
    private record RuntimeCandidate(ParsedConfiguration parsed, TownsAdapter towns, CustomMobsApi customMobs,
                                    String customMobsStatus, StormBossV1Adapter bossAdapter,
                                    WaveSpawner waveSpawner) {
    }

    /** A configuration file that cannot be read as written. Never replaced by an empty fallback. */
    private static final class ConfigurationException extends Exception {
        ConfigurationException(String message) {
            super(message);
        }
    }

    /**
     * Parses every configuration file into fresh objects without touching the running state.
     *
     * @throws ConfigurationException when a file is missing or is not valid YAML. Bukkit's
     *                                {@code loadConfiguration} would log and return an empty
     *                                configuration instead, which a reload must never apply.
     */
    private ParsedConfiguration parseConfiguration() throws ConfigurationException {
        try {
            YamlConfiguration main = readYaml("config.yml", true);
            PosterunkiConfig base = new ConfigLoader().load(main, getLogger());
            LootConfig loot = new LootLoader().load(readYaml("loot.yml", false), getLogger());
            PosterunkiConfig config = new PosterunkiConfig(base.enabled(), base.debug(), base.timing(),
                    base.requiredKills(), base.avoidImmediateRepeat(), base.waves(), base.boss(),
                    base.towns(), base.protection(), base.ui(), base.rewards(), loot);

            OutpostsLoader loader = new OutpostsLoader(
                    name -> Bukkit.getWorld(name) != null,
                    world -> {
                        World bukkitWorld = Bukkit.getWorld(world);
                        return bukkitWorld == null
                                ? new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE}
                                : new int[]{bukkitWorld.getMinHeight(), bukkitWorld.getMaxHeight() - 1};
                    });
            OutpostCatalog catalog = loader.load(readYaml("outposts.yml", false));
            return new ParsedConfiguration(config, catalog);
        } catch (RuntimeException exception) {
            throw new ConfigurationException("błąd przetwarzania konfiguracji: " + exception.getMessage());
        }
    }

    private YamlConfiguration readYaml(String name, boolean withBundledDefaults) throws ConfigurationException {
        File file = new File(getDataFolder(), name);
        if (!file.isFile()) {
            throw new ConfigurationException("brak pliku " + name);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException exception) {
            throw new ConfigurationException("nie można odczytać pliku " + name + ": " + exception.getMessage());
        } catch (InvalidConfigurationException exception) {
            throw new ConfigurationException("błąd składni YAML w pliku " + name + ": "
                    + firstLine(exception.getMessage()));
        }
        if (withBundledDefaults) {
            InputStream bundled = getResource(name);
            if (bundled != null) {
                yaml.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(bundled, StandardCharsets.UTF_8)));
            }
        }
        return yaml;
    }

    private static String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "nieznany błąd";
        }
        String trimmed = message.strip();
        int newline = trimmed.indexOf('\n');
        return newline < 0 ? trimmed : trimmed.substring(0, newline).strip();
    }

    /** Resolves the integrations a configuration needs into fresh, unapplied objects. */
    private RuntimeCandidate resolveCandidate(ParsedConfiguration parsed) {
        PosterunkiConfig config = parsed.config();
        TownsAdapter towns = townsFactory.bind(getLogger(), config.towns().requiredVersion());
        CustomMobsBinding customMobs = resolveCustomMobs();
        StormBossV1Adapter boss = resolveBossAdapter(config);
        WaveSpawner spawner = customMobs.api() == null ? null
                : new WaveSpawner(customMobs.api(), tags, random, WaveScaling.none(), getLogger());
        return new RuntimeCandidate(parsed, towns, customMobs.api(), customMobs.status(), boss, spawner);
    }

    /** Switches the running plugin to an already validated candidate. */
    private void applyCandidate(RuntimeCandidate candidate) {
        ParsedConfiguration parsed = candidate.parsed();
        configRef.set(parsed.config());
        catalogRef.set(parsed.catalog());
        parsed.catalog().report().skipped().forEach((id, reason) ->
                getLogger().severe("[outposts] Pomijam posterunek '" + id + "': " + reason));
        parsed.catalog().report().warnings().forEach(warning -> getLogger().warning("[outposts] " + warning));
        getLogger().info("[outposts] Poprawnych posterunków: " + parsed.catalog().all().size());

        this.townsAdapter = candidate.towns();
        this.customMobsApi = candidate.customMobs();
        this.customMobsStatus = candidate.customMobsStatus();
        this.bossAdapter = candidate.bossAdapter();
        this.waveSpawner = candidate.waveSpawner();

        if (regionIndex != null) {
            // The pinned geometry of a running outpost survives this rebuild (see RegionIndex).
            regionIndex.rebuild(parsed.catalog());
        }
        if (rewardService != null) {
            rewardService.setMaxAttempts(parsed.config().rewards().maxDeliveryAttempts());
        }
        if (engine != null) {
            rebuildRuntimeReferences();
        }
    }

    // ---------------------------------------------------------------- runtime graph

    private void buildRuntime() {
        this.regionIndex = new RegionIndex();
        regionIndex.rebuild(catalogRef.get());

        DisplayService displays = new DisplayService(ui, tags);

        // HexCore falls back to a no-op database service when MySQL is unavailable; db() then
        // throws. MySQL is mandatory for HexPosterunki, so this decides whether events may run.
        PosterunkiRepository repository = null;
        try {
            repository = new PosterunkiRepository(hexApi.db().db());
            databaseStatus = "ok";
        } catch (RuntimeException exception) {
            databaseStatus = "baza danych HexCore niedostępna: " + exception.getMessage();
            getLogger().severe("[db] " + databaseStatus);
        }
        this.persistence = new PersistenceService(hexApi, repository, getLogger());
        // Tied to the persistence service, not to the event operation: determined results have to reach
        // the database even while a blocker, a failed run or enabled: false keeps the events stopped.
        this.sideWriteRetry = new SideWriteRetryTask(this, persistence);
        sideWriteRetry.start();

        LootService loot = new LootService(random, getLogger());
        BlockStateService blockStates = new BlockStateService(this, getLogger());
        ChunkTicketService chunkTickets = new ChunkTicketService(this, getLogger());
        this.rewardService = new RewardService(this, persistence, ui, CompositeRewardExecutor.standard());
        rewardService.setMaxAttempts(configRef.get().rewards().maxDeliveryAttempts());

        EngineContext context = new EngineContext(this, getLogger(), configRef::get, catalogRef::get,
                persistence, ui, displays, regionIndex, tags, townsAdapter, customMobsApi, bossAdapter,
                new BossRoller(), new OutpostSelector(), random, loot, blockStates, chunkTickets,
                rewardService, waveSpawner);

        this.engine = new OutpostEngine(context);
        engine.setOperationListener(this::synchronizeOperation);
        this.encounterListener = new EncounterListener(engine, ui);
        this.scheduler = new OutpostScheduler(this, engine, encounterListener, rewardService, configRef::get);
    }

    /** Result of looking up the published CustomMobsApi. */
    private record CustomMobsBinding(CustomMobsApi api, String status) {
    }

    private CustomMobsBinding resolveCustomMobs() {
        try {
            RegisteredServiceProvider<CustomMobsApi> registration =
                    Bukkit.getServicesManager().getRegistration(CustomMobsApi.class);
            if (registration == null || registration.getProvider() == null) {
                return new CustomMobsBinding(null, "CustomMobsApi nie jest zarejestrowane");
            }
            CustomMobsApi api = registration.getProvider();
            if (!CustomMobsApi.API_VERSION.equals(api.apiVersion())) {
                return new CustomMobsBinding(null, "Niezgodna wersja CustomMobsApi: oczekiwano "
                        + CustomMobsApi.API_VERSION + ", znaleziono " + api.apiVersion());
            }
            return new CustomMobsBinding(api,
                    "HexCustomMobs API " + api.apiVersion() + " (" + api.mobIds().size() + " mobów)");
        } catch (NoClassDefFoundError error) {
            return new CustomMobsBinding(null, "Brak klas HexCustomMobs: " + error.getMessage());
        }
    }

    private StormBossV1Adapter resolveBossAdapter(PosterunkiConfig config) {
        if (!config.boss().enabled()) {
            return null;
        }
        Plugin storm = Bukkit.getPluginManager().getPlugin("STORMBOSSY");
        StormBossV1Adapter adapter = new StormBossV1Adapter(storm, config.boss().requiredVersion(),
                config.boss().requireNativeSchedulesOff(), config.boss().expectNativeRewards());
        if (!adapter.health().ready()) {
            getLogger().severe("[boss] Adapter STORMBOSSY niegotowy: " + adapter.health().message());
        }
        return adapter;
    }

    /**
     * @return the reason why the event operation must not run with this candidate, or null when
     * everything is fine. Mob ids, spawn points and boss ids are validated here, before the first
     * run, not merely logged.
     */
    private String findBlocker(RuntimeCandidate candidate) {
        PosterunkiConfig config = candidate.parsed().config();
        if (!config.enabled()) {
            return DISABLED_BLOCKER;
        }
        if (candidate.parsed().catalog().isEmpty()) {
            return "brak poprawnych posterunków w outposts.yml";
        }
        if (config.waveCount() == 0) {
            return "brak zdefiniowanych fal w config.yml";
        }
        if (persistence == null || !persistence.available()) {
            return databaseStatus;
        }
        if (candidate.customMobs() == null) {
            return "HexCustomMobs: " + candidate.customMobsStatus();
        }
        if (!candidate.towns().available()) {
            return "HexTowns: " + candidate.towns().status();
        }
        if (config.boss().enabled() && (candidate.bossAdapter() == null || !candidate.bossAdapter().health().ready())) {
            return "STORMBOSSY: " + (candidate.bossAdapter() == null
                    ? "brak pluginu" : candidate.bossAdapter().health().message());
        }
        List<String> waveProblems = validateWaves(candidate.parsed().catalog(), config, candidate.waveSpawner());
        if (!waveProblems.isEmpty()) {
            return "błędna konfiguracja fal: " + String.join("; ", waveProblems);
        }
        List<String> bossProblems = validateBosses(config, candidate.bossAdapter());
        if (!bossProblems.isEmpty()) {
            return "błędna konfiguracja bossów: " + String.join("; ", bossProblems);
        }
        return null;
    }

    // ---------------------------------------------------------------- bootstrap

    /**
     * Data bootstrap: creates the tables, loads the stored state and restores its protection. Then
     * {@link #synchronizeOperation()} decides whether events run.
     *
     * <p>Two kinds of obstacles are kept apart:
     * <ul>
     *   <li>the stored state cannot be loaded (no database, tables or reads failing): nothing is
     *       restored, only the regions from {@code outposts.yml} are protected, and the state says so
     *       ({@code NOT_INITIALIZED} / {@code FAILED});</li>
     *   <li>events may not run ({@link #operationBlocker}: {@code enabled: false}, an empty catalog,
     *       an unavailable integration, invalid waves): the stored run and its geometry are loaded and
     *       protected, an interrupted cleanup continues, but a stored fight is not reconciled - no
     *       opponents are created - until a valid reload releases operation ({@code PAUSED}).</li>
     * </ul>
     *
     * <p>Runs at most once successfully per enable and is guarded by {@link #bootstrapping}, so two
     * reloads can never run it concurrently or leave two schedulers behind.
     */
    private void bootstrap() {
        if (!bootstrapping.compareAndSet(false, true)) {
            getLogger().warning("[bootstrap] Inicjalizacja już trwa - pomijam kolejne żądanie.");
            return;
        }
        scheduler.stop();
        if (!persistence.available()) {
            runtimeState.set(RuntimeState.NOT_INITIALIZED);
            engine.setOperational(false, databaseStatus);
            getLogger().severe("[bootstrap] Nie można wczytać zapisanego stanu: " + databaseStatus
                    + ". Chronione są wyłącznie regiony z outposts.yml - teren zapisanego runu nie może zostać"
                    + " przywrócony.");
            bootstrapping.set(false);
            return;
        }
        runtimeState.set(RuntimeState.INITIALIZING);

        persistence.ensureTables()
                .thenCompose(ignored -> persistence.loadAll())
                .whenComplete((loaded, error) -> Bukkit.getScheduler().runTask(this, () -> {
                    try {
                        if (error != null) {
                            runtimeState.set(RuntimeState.FAILED);
                            String message = "baza danych niedostępna: " + error.getMessage();
                            databaseStatus = message;
                            engine.setOperational(false, message);
                            getLogger().severe("[bootstrap] Nie wczytano zapisanego stanu - " + message
                                    + ". Chronione są wyłącznie regiony z outposts.yml. Po naprawie użyj"
                                    + " /posterunki reload.");
                            return;
                        }
                        String blocker = operationBlocker;
                        combatReconciliationPending = new RecoveryService(engine, getLogger())
                                .recover(loaded, blocker == null);
                        // Loaded and applied: an earlier load failure no longer describes the database.
                        // Whether events run is a separate matter (see operationBlocker).
                        databaseStatus = "ok";
                        // The stored state is loaded and protected; operation is decided separately.
                        runtimeState.set(RuntimeState.PAUSED);
                        synchronizeOperation();
                        rewardService.deliverOutstanding();
                        if (blocker == null) {
                            getLogger().info("HexPosterunki uruchomiony. Faza: " + engine.phase());
                        } else {
                            getLogger().warning("HexPosterunki wczytał zapisany stan (faza: "
                                    + engine.phase().polishLabel() + ") i chroni jego teren, ale eventy są"
                                    + " wstrzymane: " + blocker);
                        }
                    } finally {
                        bootstrapping.set(false);
                    }
                }));
    }

    // ---------------------------------------------------------------- listeners / command

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(
                new RegionProtectionListener(regionIndex, engine, ui), this);
        getServer().getPluginManager().registerEvents(encounterListener, this);
        getServer().getPluginManager().registerEvents(
                new PlayerTrackingListener(engine, ui, rewardService, shuttingDown::get), this);
    }

    private void registerCommand() {
        PluginCommand command = getCommand("posterunki");
        if (command == null) {
            getLogger().severe("Brak komendy 'posterunki' w plugin.yml.");
            return;
        }
        PosterunkiCommand executor = new PosterunkiCommand(this, engine, ui);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    // ---------------------------------------------------------------- accessors

    public PosterunkiConfig config() {
        return configRef.get();
    }

    public OutpostCatalog catalog() {
        return catalogRef.get();
    }

    public TownsAdapter townsAdapter() {
        return townsAdapter;
    }

    public PersistenceService persistence() {
        return persistence;
    }

    public OutpostEngine engine() {
        return engine;
    }

    public RuntimeState runtimeState() {
        return runtimeState.get();
    }

    /** True while the repeating engine, containment and reward tasks are scheduled. */
    public boolean schedulerRunning() {
        return scheduler != null && scheduler.running();
    }

    /** True while outstanding database writes are repeated - independent of the event operation. */
    public boolean persistenceRetryRunning() {
        return sideWriteRetry != null && sideWriteRetry.running();
    }

    /** How often the persistence retry actually swept; one task means one sweep per interval. */
    public long persistenceRetrySweeps() {
        return sideWriteRetry == null ? 0L : sideWriteRetry.sweeps();
    }

    public String customMobsStatus() {
        return customMobsStatus;
    }

    public String databaseStatus() {
        return databaseStatus;
    }

    public String bossAdapterStatus() {
        if (!configRef.get().boss().enabled()) {
            return "wyłączony w config.yml";
        }
        if (bossAdapter == null) {
            return "brak pluginu STORMBOSSY";
        }
        AdapterHealth health = bossAdapter.health();
        return health.status() + " - " + health.message();
    }

    public List<String> validateWaves() {
        return validateWaves(catalogRef.get(), configRef.get(), waveSpawner);
    }

    private static List<String> validateWaves(OutpostCatalog catalog, PosterunkiConfig config, WaveSpawner spawner) {
        if (spawner == null) {
            return List.of("HexCustomMobs niedostępne - nie można sprawdzić fal");
        }
        List<String> problems = new ArrayList<>();
        for (OutpostDefinition outpost : catalog.all()) {
            spawner.validate(outpost, config.waves())
                    .forEach(problem -> problems.add(outpost.id() + ": " + problem));
        }
        return problems;
    }

    public List<String> validateBosses() {
        return validateBosses(configRef.get(), bossAdapter);
    }

    private static List<String> validateBosses(PosterunkiConfig config, StormBossV1Adapter adapter) {
        if (!config.boss().enabled()) {
            return List.of();
        }
        if (adapter == null) {
            return List.of("brak pluginu STORMBOSSY");
        }
        List<String> problems = new ArrayList<>();
        AdapterHealth health = adapter.health();
        if (!health.ready()) {
            problems.add(health.message());
            return problems;
        }
        // Unknown ids would only surface when the boss phase starts; reject them up front.
        adapter.unknownBossIds(config.boss().bosses().stream().map(entry -> entry.bossId()).toList())
                .forEach(id -> problems.add("nieznany boss STORMBOSSY: " + id));
        for (var boss : config.boss().bosses()) {
            AdapterHealth bossHealth = adapter.validateBoss(boss.bossId());
            if (!bossHealth.ready()) {
                problems.add(boss.bossId() + ": " + bossHealth.message());
            }
        }
        return problems;
    }

    // ---------------------------------------------------------------- admin operations

    /**
     * Admin stop: clean the world and drop into cooldown without completing the run.
     *
     * @return a Polish problem description, or null when the reset started
     */
    public String adminStop() {
        if (!runtimeState.get().initialized()) {
            return "system posterunków nie jest jeszcze zainicjalizowany";
        }
        return engine.adminStop() ? null
                : "resetowanie już trwa albo nie jest możliwe w fazie " + engine.phase().polishLabel();
    }

    /**
     * Admin reset: clean the world and make the next run start immediately. Whether operation runs
     * afterwards is decided by {@link #synchronizeOperation()} once the fortress is clean - also when
     * the cleanup needed retries or a restart - and never against {@code enabled: false}.
     *
     * @return a Polish problem description, or null when the reset started
     */
    public String adminReset() {
        if (!runtimeState.get().initialized()) {
            return "system posterunków nie jest jeszcze zainicjalizowany";
        }
        return engine.adminReset(null) ? null
                : "resetowanie już trwa albo nie jest możliwe w fazie " + engine.phase().polishLabel();
    }

    /**
     * The single owner of the operating state. Configuration, {@link RuntimeState}, the engine's
     * operational flag and the scheduler are derived here together, so they cannot drift apart:
     * <ul>
     *   <li>stored state not loaded: nothing changes - the bootstrap decides when it finished;</li>
     *   <li>an operation blocker ({@code enabled: false}, empty catalog, unavailable integration):
     *       scheduler stopped, engine stopped with that reason, {@code PAUSED};</li>
     *   <li>a failed run: scheduler running (an admin reset can be carried out), engine stopped with
     *       the failure reason, {@code PAUSED};</li>
     *   <li>otherwise: scheduler running, engine operational, {@code RUNNING}.</li>
     * </ul>
     * The engine calls this after a run failed and after every finished reset; bootstrap and reload
     * call it after they changed the inputs.
     */
    private void synchronizeOperation() {
        if (engine == null || scheduler == null || !runtimeState.get().initialized()) {
            return;
        }
        String blocker = operationBlocker;
        if (blocker != null) {
            scheduler.stop();
            engine.setOperational(false, blocker);
            runtimeState.set(RuntimeState.PAUSED);
            return;
        }
        if (combatReconciliationPending) {
            // Released for the first time since the stored fight was loaded: reconcile its world
            // exactly once, before anything ticks.
            combatReconciliationPending = false;
            new RecoveryService(engine, getLogger()).resumeDeferredCombat();
        }
        if (!scheduler.running()) {
            scheduler.start();
        }
        if (engine.phase() == RunPhase.FAILED) {
            engine.setOperational(false, engine.state().failureReason());
            runtimeState.set(RuntimeState.PAUSED);
            return;
        }
        engine.setOperational(true, null);
        runtimeState.set(RuntimeState.RUNNING);
    }

    /**
     * Reloads configuration and adapters.
     *
     * <p>A complete candidate - parsed files, outpost catalog, HexTowns, HexCustomMobs and STORMBOSSY
     * bindings, wave and boss validation - is built and checked <i>before</i> the running state is
     * touched. A rejected candidate changes nothing: configuration, protection index, adapters,
     * scheduler and the engine's operating state stay as they were. {@code enabled: false} is not a
     * rejection but a valid decision, so it is applied and pauses the event operation.
     *
     * <p>When the plugin was never initialised - for example because it started blocked - a valid
     * candidate also performs the missing database initialisation and recovery.
     *
     * @return a Polish problem description, or null on success
     */
    public String reloadRuntime() {
        if (bootstrapping.get()) {
            return "inicjalizacja jest w toku, spróbuj ponownie za chwilę";
        }
        ParsedConfiguration parsed;
        try {
            parsed = parseConfiguration();
        } catch (ConfigurationException exception) {
            return rejectReload("nie udało się wczytać konfiguracji: " + exception.getMessage());
        }
        RuntimeCandidate candidate = resolveCandidate(parsed);
        String blocker = findBlocker(candidate);
        boolean disabledByConfig = !parsed.config().enabled();
        if (blocker != null && !disabledByConfig) {
            return rejectReload(blocker);
        }

        scheduler.stop();
        applyCandidate(candidate);
        this.operationBlocker = blocker;

        if (!runtimeState.get().initialized()) {
            // The stored state was never loaded (e.g. the database failed at startup): try again. The
            // bootstrap then releases or keeps blocking operation.
            if (blocker != null) {
                engine.setOperational(false, blocker);
            }
            bootstrap();
            return blocker;
        }
        if (disabledByConfig) {
            synchronizeOperation();
            audit("reload: " + DISABLED_BLOCKER);
            return DISABLED_BLOCKER;
        }

        synchronizeOperation();
        persistence.retryPendingWrites();
        audit("reload ok");
        return null;
    }

    private String rejectReload(String reason) {
        getLogger().warning("[reload] Odrzucono nową konfigurację: " + reason
                + ". Dotychczasowa konfiguracja i praca eventów pozostają bez zmian.");
        audit("reload odrzucony: " + reason);
        return "przeładowanie odrzucone - " + reason + " (pozostawiono dotychczasową konfigurację)";
    }

    private void audit(String data) {
        if (persistence == null || engine == null) {
            return;
        }
        persistence.audit(engine.state().runId().orElse(null), engine.state().outpostId().orElse(null),
                AuditEvent.ADMIN_ACTION, "console", data);
    }

    /**
     * Rebuilds the collaborators that hold a direct reference to a swapped adapter.
     *
     * <p>Updating only the field would leave the engine talking to the old boss adapter, a stale
     * CustomMobs handle or the previous HexTowns binding.
     */
    private void rebuildRuntimeReferences() {
        EngineContext old = engine.context();
        EngineContext refreshed = new EngineContext(this, getLogger(), configRef::get, catalogRef::get,
                persistence, ui, old.displays(), regionIndex, old.tags(), townsAdapter, customMobsApi,
                bossAdapter, old.bossRoller(), old.selector(), old.random(), old.loot(), old.blockStates(),
                old.chunkTickets(), rewardService, waveSpawner);
        engine.rebind(refreshed);
    }

    /** Exposed for diagnostics: the loaded outpost definitions by id. */
    public Map<String, OutpostDefinition> outposts() {
        return catalogRef.get().outposts();
    }

    public RewardService rewards() {
        return rewardService;
    }
}
