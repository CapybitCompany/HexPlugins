package hex.limbo;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import hex.limbo.account.AccountRepository;
import hex.limbo.account.InMemoryAccountRepository;
import hex.limbo.account.SqlAccountRepository;
import hex.limbo.auth.AuthService;
import hex.limbo.auth.PasswordHasher;
import hex.limbo.auth.SessionService;
import hex.limbo.command.ChangePasswordCommand;
import hex.limbo.command.HexLimboAdminCommand;
import hex.limbo.command.LimboCommand;
import hex.limbo.command.LoginCommand;
import hex.limbo.command.LogoutCommand;
import hex.limbo.command.PremiumCommand;
import hex.limbo.command.RegisterCommand;
import hex.limbo.config.ConfigLoader;
import hex.limbo.config.MessagesConfig;
import hex.limbo.config.PluginConfig;
import hex.limbo.config.RuntimeContext;
import hex.limbo.db.AuditLogService;
import hex.limbo.db.MySqlProvider;
import hex.limbo.limbo.LimboRouter;
import hex.limbo.limbo.LimboServer;
import hex.limbo.limbo.server.MinecraftLimboServer;
import hex.limbo.listener.ChatListener;
import hex.limbo.listener.CommandListener;
import hex.limbo.listener.DisconnectListener;
import hex.limbo.listener.FailFastKickListener;
import hex.limbo.listener.GameProfileListener;
import hex.limbo.listener.InitialServerListener;
import hex.limbo.listener.LoginListener;
import hex.limbo.listener.PreLoginListener;
import hex.limbo.listener.ServerConnectListener;
import hex.limbo.premium.CachedPremiumResolver;
import hex.limbo.premium.MojangPremiumResolver;
import hex.limbo.premium.PremiumResolverHandle;
import hex.limbo.prompt.PromptService;
import hex.limbo.security.IpHasher;
import hex.limbo.security.RateLimiter;
import hex.limbo.uuid.FakeUuidService;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Plugin(
        id = "hexlimbo",
        name = "HexLimbo",
        version = "1.0.0",
        description = "Mixed premium/cracked authentication limbo with stable offline UUIDs.",
        authors = {"CapybitCompany"}
)
public final class HexLimboPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private ConfigLoader configLoader;
    private RuntimeContext runtimeContext;

    private MySqlProvider mysqlProvider;
    private AccountRepository repository;
    private AuthService authService;
    private SessionService sessionService;
    private PremiumResolverHandle premiumResolver;
    private LimboServer limboServer;
    private LimboRouter router;
    private PromptService promptService;
    private PasswordHasher passwordHasher;
    private AuditLogService auditLog;
    private ExecutorService authExecutor;
    private ExecutorService auditExecutor;
    private ScheduledTask sessionPurgeTask;
    private long currentPurgeIntervalMinutes;
    private boolean dbEnabled;
    private boolean disabled;

    @Inject
    public HexLimboPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            initialize();
        } catch (Exception ex) {
            logger.error("HexLimbo failed to start", ex);
            disabled = true;
            installFailFastKickListener();
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        shutdown();
    }

    private void initialize() throws Exception {
        configLoader = new ConfigLoader(dataDirectory, logger);
        PluginConfig config = configLoader.loadConfig();
        MessagesConfig messages = configLoader.loadMessages();
        runtimeContext = new RuntimeContext(config, messages);

        AtomicInteger authThreadCount = new AtomicInteger();
        authExecutor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "HexLimbo-Auth-" + authThreadCount.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        AtomicInteger auditThreadCount = new AtomicInteger();
        auditExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "HexLimbo-Audit-" + auditThreadCount.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        if (!setupDatabase(config)) {
            disabled = true;
            installFailFastKickListener();
            logger.error("HexLimbo refused to start because the database is unavailable and database.fail-fast=true. "
                    + "Set database.fail-fast=false to allow an in-memory fallback (NOT recommended for production).");
            return;
        }

        passwordHasher = new PasswordHasher();
        IpHasher ipHasher = new IpHasher(config.security().ipHashPepper());
        RateLimiter rateLimiter = new RateLimiter(config.security().rateLimitPerMinute(), 60_000L);

        authService = new AuthService(repository, passwordHasher, rateLimiter, runtimeContext, logger);
        sessionService = new SessionService(
                dbEnabled ? mysqlProvider.dataSource() : null,
                runtimeContext,
                logger
        );
        auditLog = new AuditLogService(dbEnabled ? mysqlProvider.dataSource() : null, auditExecutor, logger);

        premiumResolver = new PremiumResolverHandle(buildPremiumResolver(config.premium()));

        // Start the internal void backend BEFORE registering listeners so that the moment
        // PreLoginEvent can fire, the limbo is either ready or the listeners will safely kick.
        limboServer = new MinecraftLimboServer(
                config.limbo(),
                () -> runtimeContext.messages().raw("disconnect.forwarding-failed"),
                logger);
        limboServer.start();
        if (limboServer.isReady()) {
            registerLimboWithVelocity(config.limbo());
        } else {
            logger.error("Internal limbo backend did not start: {}",
                    limboServer.lastStartError().orElse("unknown reason"));
        }

        router = new LimboRouter(proxy, runtimeContext, limboServer, logger);
        promptService = new PromptService(runtimeContext, (intervalSeconds, task) -> {
            ScheduledTask scheduled = proxy.getScheduler().buildTask(this, task)
                    .delay(intervalSeconds, TimeUnit.SECONDS)
                    .repeat(intervalSeconds, TimeUnit.SECONDS)
                    .schedule();
            return scheduled::cancel;
        });
        FakeUuidService fakeUuidService = new FakeUuidService();

        registerListeners(fakeUuidService, ipHasher);
        registerCommands();
        schedulePeriodicPurge();

        logger.info("HexLimbo started. storage={}, limbo='{}', target='{}', premium-mode={}, premium-fail-open={}, "
                        + "session-enabled={}, session-purge-interval-min={}, min-pw-length={}, max-failed-attempts={}, db-fail-fast={}",
                dbEnabled ? "MySQL" : "InMemory (UNSAFE fallback – set database.fail-fast=true for production)",
                config.limboServer(),
                config.targetServer(),
                config.premium().enabled() ? "mixed (per-player force online/offline)" : "offline-only",
                config.premium().failOpenOnCheckError(),
                sessionService.isEnabled(),
                currentPurgeIntervalMinutes,
                config.security().minPasswordLength(),
                config.security().maxFailedAttempts(),
                config.database().failFast()
        );
    }

    private boolean setupDatabase(PluginConfig config) {
        try {
            mysqlProvider = new MySqlProvider(config.database(), logger);
            if (!mysqlProvider.ping()) {
                logger.error("MySQL ping failed at startup.");
                closeMysqlProviderQuietly();
            } else {
                try {
                    SqlAccountRepository sql = new SqlAccountRepository(mysqlProvider, logger);
                    sql.initializeSchema();
                    repository = sql;
                    dbEnabled = true;
                    return true;
                } catch (Exception schemaEx) {
                    logger.error("MySQL schema initialization failed at startup.", schemaEx);
                    closeMysqlProviderQuietly();
                }
            }
        } catch (Exception ex) {
            logger.error("MySQL setup failed at startup.", ex);
            closeMysqlProviderQuietly();
        }
        if (config.database().failFast()) {
            return false;
        }
        logger.warn("HexLimbo running with in-memory account fallback. Accounts will NOT persist across restarts. "
                + "This mode is intended for development only. Set database.fail-fast=true in config.yml to refuse this fallback.");
        repository = new InMemoryAccountRepository();
        repository.initializeSchema();
        dbEnabled = false;
        return true;
    }

    private void closeMysqlProviderQuietly() {
        if (mysqlProvider == null) {
            return;
        }
        try {
            mysqlProvider.close();
        } catch (RuntimeException ex) {
            logger.warn("Could not close Hikari pool cleanly: {}", ex.getMessage());
        }
        mysqlProvider = null;
    }

    private void registerLimboWithVelocity(PluginConfig.Limbo cfg) {
        String name = cfg.serverName();
        proxy.getServer(name).ifPresent(existing -> {
            logger.warn("A server named '{}' is already registered with Velocity (velocity.toml or another plugin). "
                    + "HexLimbo will unregister it and re-register pointing at the internal backend.", name);
            proxy.unregisterServer(existing.getServerInfo());
        });
        try {
            ServerInfo info = new ServerInfo(name, new InetSocketAddress(cfg.bindHost(), cfg.bindPort()));
            RegisteredServer registered = proxy.registerServer(info);
            logger.info("Registered HexLimbo internal backend with Velocity as '{}' at {}:{}.",
                    registered.getServerInfo().getName(), cfg.bindHost(), cfg.bindPort());
        } catch (RuntimeException ex) {
            logger.error("Could not register limbo server '{}' with Velocity: {}", name, ex.getMessage());
        }
    }

    private void unregisterLimboFromVelocity() {
        if (runtimeContext == null) {
            return;
        }
        String name = runtimeContext.config().limbo().serverName();
        proxy.getServer(name).ifPresent(server -> proxy.unregisterServer(server.getServerInfo()));
    }

    private CachedPremiumResolver buildPremiumResolver(PluginConfig.Premium premiumConfig) {
        MojangPremiumResolver mojang = new MojangPremiumResolver(premiumConfig.httpTimeoutMs(), logger);
        return new CachedPremiumResolver(mojang, premiumConfig.cacheTtlSeconds(), premiumConfig.cacheMaxEntries());
    }

    private void registerListeners(FakeUuidService fakeUuidService, IpHasher ipHasher) {
        PreLoginListener preLogin = new PreLoginListener(premiumResolver, runtimeContext, logger);
        GameProfileListener gameProfile = new GameProfileListener(repository, fakeUuidService, logger);
        LoginListener loginListener = new LoginListener(proxy, this, authService, sessionService, repository, ipHasher, runtimeContext, auditLog, logger);
        InitialServerListener initialServer = new InitialServerListener(authService, router, runtimeContext);
        ServerConnectListener serverConnect = new ServerConnectListener(authService, router, runtimeContext, promptService);
        CommandListener commandListener = new CommandListener(authService, runtimeContext);
        ChatListener chatListener = new ChatListener(authService, runtimeContext);
        DisconnectListener disconnectListener = new DisconnectListener(authService, loginListener, promptService);

        proxy.getEventManager().register(this, preLogin);
        proxy.getEventManager().register(this, gameProfile);
        proxy.getEventManager().register(this, loginListener);
        proxy.getEventManager().register(this, initialServer);
        proxy.getEventManager().register(this, serverConnect);
        proxy.getEventManager().register(this, commandListener);
        proxy.getEventManager().register(this, chatListener);
        proxy.getEventManager().register(this, disconnectListener);
    }

    private void registerCommands() {
        CommandManager cm = proxy.getCommandManager();
        register(cm, "register", new RegisterCommand(authService, sessionService, router, runtimeContext, premiumResolver, auditLog, promptService, authExecutor, logger), "reg");
        register(cm, "login", new LoginCommand(authService, sessionService, router, runtimeContext, auditLog, promptService, authExecutor, logger), "l");
        register(cm, "logout", new LogoutCommand(authService, sessionService, router, runtimeContext, auditLog, promptService, authExecutor, logger));
        register(cm, "changepassword", new ChangePasswordCommand(authService, sessionService, runtimeContext, auditLog, authExecutor, logger), "cpw");
        register(cm, "premium", new PremiumCommand(authService, repository, runtimeContext, auditLog, authExecutor, logger));
        register(cm, "limbo", new LimboCommand(runtimeContext));
        register(cm, "hexlimbo", new HexLimboAdminCommand(this, proxy, authService, repository, sessionService, passwordHasher, router, runtimeContext, premiumResolver, auditLog, limboServer, authExecutor, logger));
    }

    private void register(CommandManager cm, String name, com.velocitypowered.api.command.Command command, String... aliases) {
        CommandMeta meta = cm.metaBuilder(name).aliases(aliases).plugin(this).build();
        cm.register(meta, command);
    }

    private void schedulePeriodicPurge() {
        long minutes = Math.max(1L, runtimeContext.config().session().purgeIntervalMinutes());
        if (sessionPurgeTask != null) {
            sessionPurgeTask.cancel();
        }
        sessionPurgeTask = proxy.getScheduler().buildTask(this, () -> {
            try {
                int purged = sessionService.purgeExpired();
                if (purged > 0) {
                    logger.debug("Purged {} expired HexLimbo sessions.", purged);
                }
            } catch (RuntimeException ex) {
                logger.warn("Session purge task failed: {}", ex.getMessage());
            }
        }).delay(minutes, TimeUnit.MINUTES).repeat(minutes, TimeUnit.MINUTES).schedule();
        currentPurgeIntervalMinutes = minutes;
    }

    private void installFailFastKickListener() {
        Supplier<Component> reason;
        if (runtimeContext != null) {
            reason = () -> Component.text(runtimeContext.messages().raw("disconnect.service-unavailable"));
        } else {
            reason = () -> Component.text("HexLimbo jest chwilowo niedostępne. Spróbuj ponownie później.");
        }
        proxy.getEventManager().register(this, new FailFastKickListener(reason));
    }

    /**
     * Re-reads {@code config.yml} and {@code messages.yml} and swaps them into the live
     * {@link RuntimeContext}. Listeners and commands read from the context on every invocation, so
     * after reload they immediately see new allowlists, server names, messages, premium options,
     * security limits, session enabled/duration, and the admin-bypass permission.
     *
     * <p>Additionally:
     * <ul>
     *     <li>If {@code session.purge-interval-minutes} changed, the periodic purge task is
     *     cancelled and rescheduled.</li>
     *     <li>If any field in the {@code premium} block changed
     *     ({@code cache-ttl-seconds}, {@code cache-max-entries}, {@code http-timeout-ms}, etc.),
     *     a fresh {@code CachedPremiumResolver} (with a fresh underlying
     *     {@code MojangPremiumResolver}) is built and swapped into the handle.</li>
     * </ul>
     *
     * <p>Settings intentionally NOT hot-reloaded (require a proxy restart):
     * database connection settings, the security rate-limit sliding-window size, and the IP-hash
     * pepper. These are logged with a warning when the user edits them.
     */
    public void reloadConfiguration() throws Exception {
        if (configLoader == null || runtimeContext == null) {
            return;
        }
        PluginConfig oldConfig = runtimeContext.config();
        PluginConfig parsedConfig = configLoader.loadConfig();
        MessagesConfig newMessages = configLoader.loadMessages();

        // limbo.* is restart-only in v1: the running TCP backend, the server name registered with
        // Velocity, and the spawn position all depend on values we cannot safely hot-swap. Force
        // the live runtime to keep the OLD limbo block regardless of what the on-disk file now
        // says, and emit a warning if the user touched it.
        if (!oldConfig.limbo().equals(parsedConfig.limbo())) {
            logger.warn("HexLimbo: limbo.* settings were edited (server-name, bind-host, bind-port, spawn, "
                    + "actionbar). These fields are RESTART-ONLY in v1; the running limbo backend continues "
                    + "with the previous values until you restart the proxy.");
        }
        PluginConfig effectiveConfig = new PluginConfig(
                parsedConfig.targetServer(),
                parsedConfig.loginTimeoutSeconds(),
                parsedConfig.adminBypassPermission(),
                parsedConfig.allowedCommandsUnauthenticated().stream().toList(),
                parsedConfig.database(),
                parsedConfig.session(),
                parsedConfig.security(),
                parsedConfig.premium(),
                oldConfig.limbo(),
                parsedConfig.prompts()
        );
        runtimeContext.update(effectiveConfig, newMessages);

        if (oldConfig.session().purgeIntervalMinutes() != effectiveConfig.session().purgeIntervalMinutes()) {
            logger.info("Session purge interval changed from {} → {} minutes. Rescheduling task.",
                    oldConfig.session().purgeIntervalMinutes(), effectiveConfig.session().purgeIntervalMinutes());
            schedulePeriodicPurge();
        }

        if (premiumResolver != null && !oldConfig.premium().equals(effectiveConfig.premium())) {
            CachedPremiumResolver fresh = buildPremiumResolver(effectiveConfig.premium());
            premiumResolver.swap(fresh);
            logger.info("Premium resolver replaced after reload (ttl={}s, max={}, http-timeout={}ms, enabled={}, fail-open={}).",
                    effectiveConfig.premium().cacheTtlSeconds(),
                    effectiveConfig.premium().cacheMaxEntries(),
                    effectiveConfig.premium().httpTimeoutMs(),
                    effectiveConfig.premium().enabled(),
                    effectiveConfig.premium().failOpenOnCheckError());
        }

        if (!oldConfig.database().equals(effectiveConfig.database())) {
            logger.warn("HexLimbo: database.* settings were edited. Connection settings only take effect after a proxy restart.");
        }
        if (oldConfig.security().rateLimitPerMinute() != effectiveConfig.security().rateLimitPerMinute()) {
            logger.warn("HexLimbo: security.rate-limit-per-minute was edited. The sliding-window size only takes effect after a proxy restart.");
        }
        if (!oldConfig.security().ipHashPepper().equals(effectiveConfig.security().ipHashPepper())) {
            logger.warn("HexLimbo: security.ip-hash-pepper was edited. The new pepper only takes effect after a proxy restart and will invalidate every existing IP hash.");
        }

        logger.info("HexLimbo configuration reloaded.");
    }

    public RuntimeContext runtimeContext() {
        return runtimeContext;
    }

    private void shutdown() {
        logger.info("HexLimbo shutting down…");
        if (sessionPurgeTask != null) {
            sessionPurgeTask.cancel();
            sessionPurgeTask = null;
        }
        if (limboServer != null) {
            try { limboServer.stop(); } catch (RuntimeException ignored) {}
            limboServer = null;
        }
        unregisterLimboFromVelocity();
        closeMysqlProviderQuietly();
        if (repository != null) {
            repository.close();
        }
        shutdownExecutor(authExecutor, "auth");
        shutdownExecutor(auditExecutor, "audit");
    }

    private void shutdownExecutor(ExecutorService executor, String label) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("HexLimbo {} executor did not terminate in 5s; forcing shutdown.", label);
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
