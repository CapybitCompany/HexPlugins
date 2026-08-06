package hex.auctionbazaar;

import hex.auctionbazaar.audit.repository.AuditLogRepository;
import hex.auctionbazaar.audit.service.AuditService;
import hex.auctionbazaar.auction.command.AuctionCommand;
import hex.auctionbazaar.auction.repository.AuctionClaimRepository;
import hex.auctionbazaar.auction.repository.AuctionListingRepository;
import hex.auctionbazaar.auction.service.AuctionService;
import hex.auctionbazaar.auction.task.AuctionExpiryTask;
import hex.auctionbazaar.bazaar.command.BazaarCommand;
import hex.auctionbazaar.bazaar.repository.BazaarOrderRepository;
import hex.auctionbazaar.bazaar.repository.BazaarStockRepository;
import hex.auctionbazaar.bazaar.service.BazaarOrderService;
import hex.auctionbazaar.bazaar.service.BazaarService;
import hex.auctionbazaar.bazaar.task.BazaarOrderExpiryTask;
import hex.auctionbazaar.bridge.EconomyBridge;
import hex.auctionbazaar.bridge.HexCoreBridge;
import hex.auctionbazaar.config.ConfigLoader;
import hex.auctionbazaar.config.PluginConfig;
import hex.auctionbazaar.gui.BukkitSignPromptTransport;
import hex.auctionbazaar.gui.GuiInventoryListener;
import hex.auctionbazaar.gui.SignPrompt;
import hex.auctionbazaar.bazaar.gui.BazaarAutoRefreshTicker;
import hex.auctionbazaar.util.MessageFactory;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public final class HexAuctionBazaarPlugin extends JavaPlugin {

    private final AtomicReference<PluginConfig> configRef = new AtomicReference<>();
    // Maszyna stanów inicjalizacji/recovery bazy (dbHealthy + schemaReady + generacja).
    private DatabaseLifecycle dbLifecycle;

    private HexCoreBridge hexCore;
    private EconomyBridge economy;
    private MessageFactory messages;
    private SignPrompt signPrompt;
    private BazaarAutoRefreshTicker autoRefreshTicker;

    private AuctionListingRepository listingRepo;
    private AuctionClaimRepository claimRepo;
    private BazaarStockRepository stockRepo;
    private BazaarOrderRepository orderRepo;
    private AuditLogRepository auditRepo;

    private AuctionService auctionService;
    private BazaarService bazaarService;
    private BazaarOrderService orderService;
    private AuditService auditService;
    private AuctionExpiryTask expiryTask;
    private BazaarOrderExpiryTask orderExpiryTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureBundledResource("bazaar-items.yml");
        ensureBundledResource("messages.yml");

        PluginConfig loaded = ConfigLoader.load(getDataFolder(), getConfig(), getLogger());
        configRef.set(loaded);
        if (loaded.debug()) {
            getLogger().info("[debug] HexAuctionBazaar: tryb diagnostyczny włączony (debug=true).");
        }
        this.messages = new MessageFactory(() -> configRef.get().messages(), () -> configRef.get().prefix());

        this.hexCore = new HexCoreBridge(getLogger());
        if (!hexCore.tryBootstrap()) {
            getLogger().severe("HexCore jest niedostępny - wyłączam plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.economy = new EconomyBridge(getLogger());
        boolean economyOk = economy.tryBootstrap();
        if (!economyOk && loaded.economyRequired()) {
            getLogger().severe("HexEconomyApi jest niedostępne, a economy.required=true - wyłączam plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.listingRepo = new AuctionListingRepository(hexCore.rawDb());
        this.claimRepo = new AuctionClaimRepository(hexCore.rawDb());
        this.stockRepo = new BazaarStockRepository(hexCore.rawDb());
        this.orderRepo = new BazaarOrderRepository(hexCore.rawDb());
        this.auditRepo = new AuditLogRepository(hexCore.rawDb());

        this.auditService = new AuditService(getLogger(), hexCore, auditRepo, messages);
        // pluginEnabled = globalny przełącznik (enabled). false = tryb konserwacji: usługi odrzucają
        // nowe komercyjne mutacje SERWEROWO (także dla GUI otwartego przed reloadem).
        this.orderService = new BazaarOrderService(this, hexCore, economy, orderRepo, claimRepo,
                auditService,
                () -> configRef.get().bazaar(),
                () -> configRef.get().bazaar().maxOrdersPerPlayer(),
                () -> configRef.get().enabled());
        this.auctionService = new AuctionService(this, hexCore, economy, listingRepo, claimRepo,
                auditService,
                () -> configRef.get().auction(),
                () -> configRef.get().enabled());
        this.bazaarService = new BazaarService(this, hexCore, economy, stockRepo, claimRepo,
                auditService, orderService,
                () -> configRef.get().bazaar(),
                () -> configRef.get().bazaar().requirePlainItem(),
                () -> configRef.get().enabled());

        // Wspólna baza HexCore - nie tworzymy puli, nie logujemy sekretów. Prefiks tabel
        // czytamy DOPIERO po udanej inicjalizacji (chroniony dostęp) - patrz buildDbEffects().
        getLogger().info("HexAuctionBazaar: używam wspólnej bazy MySQL z HexCore.");
        this.dbLifecycle = new DatabaseLifecycle(buildDbEffects());
        var dbCfg = loaded.database();
        dbLifecycle.start(dbCfg.required(), dbCfg.healthCheckOnStartup());

        getServer().getPluginManager().registerEvents(new GuiInventoryListener(), this);
        this.signPrompt = new SignPrompt(this, new BukkitSignPromptTransport(this), messages,
                () -> configRef.get().inputFallbackHintTicks(),
                () -> configRef.get().inputTimeoutTicks());
        this.autoRefreshTicker = new BazaarAutoRefreshTicker(this,
                () -> configRef.get().bazaar());
        this.autoRefreshTicker.start();
        registerCommand("hexauction", new AuctionCommand(this));
        registerCommand("hexbazaar", new BazaarCommand(this));

        getLogger().info("HexAuctionBazaar został włączony.");
    }

    @Override
    public void onDisable() {
        // Unieważnij ewentualną inicjalizację w locie - spóźnione wyniki async nie ruszą tasków.
        if (dbLifecycle != null) {
            dbLifecycle.invalidate();
        }
        if (expiryTask != null) {
            expiryTask.stop();
            expiryTask = null;
        }
        if (orderExpiryTask != null) {
            orderExpiryTask.stop();
            orderExpiryTask = null;
        }
        if (autoRefreshTicker != null) {
            autoRefreshTicker.stop();
            autoRefreshTicker = null;
        }
        if (orderService != null) {
            // Przejmij NIEROZPOCZĘTE zwroty wystawionych przedmiotów SELL NA WĄTKU GŁÓWNYM, zanim wyłączymy
            // plugineigene Infrastruktur. Ograniczone oczekiwanie na persystencję claim-ów; HexCore (jego
            // pula/executor) pozostaje aktywne i NIE jest zamykane.
            orderService.drainPendingReturnsOnDisable(2000L);
        }
        if (auditService != null) {
            // Ograniczone oczekiwanie na trwające wpisy audytu; potem BRAK nowych insertów.
            // Pula/executor HexCore NIE jest zamykany (należy do HexCore).
            auditService.awaitPending(2000L, true);
        }
        if (signPrompt != null) {
            signPrompt.shutdown();
            signPrompt = null;
        }
        if (economy != null) {
            economy.shutdown();
            economy = null;
        }
        getLogger().info("HexAuctionBazaar został wyłączony.");
    }

    public SignPrompt signPrompt() {
        return signPrompt;
    }

    public BazaarAutoRefreshTicker autoRefreshTicker() {
        return autoRefreshTicker;
    }

    public boolean schemaReady() {
        return dbLifecycle != null && dbLifecycle.schemaReady();
    }

    /** false tylko gdy database.required=false i baza jest niedostępna (transakcje wyłączone). */
    public boolean dbHealthy() {
        return dbLifecycle == null || dbLifecycle.dbHealthy();
    }

    /** Chroniony odczyt prefiksu tabel - nigdy nie rzuca (np. NoopDatabaseService). */
    public java.util.Optional<String> safeTablePrefix() {
        try {
            return java.util.Optional.ofNullable(hexCore.rawDb().tablePrefix());
        } catch (Throwable t) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Efekty dla {@link DatabaseLifecycle}. Jedyne miejsce z (chronionym) dostępem do
     * DB przy starcie/recovery. Healthcheck i initSchema łapią wszystkie wyjątki, prefiks
     * czytany dopiero po gotowym schemacie i wyłącznie przez {@link #safeTablePrefix()}.
     */
    private DatabaseLifecycle.Effects buildDbEffects() {
        return new DatabaseLifecycle.Effects() {
            @Override
            public java.util.concurrent.CompletableFuture<Boolean> healthCheck() {
                try {
                    return hexCore.async(() -> {
                        try {
                            hexCore.rawDb().queryOne("SELECT 1 AS ok", rs -> rs.getInt("ok"));
                            return true;
                        } catch (Throwable t) {
                            return false;
                        }
                    });
                } catch (Throwable t) {
                    return java.util.concurrent.CompletableFuture.completedFuture(false);
                }
            }

            @Override
            public java.util.concurrent.CompletableFuture<Void> initSchema() {
                try {
                    return hexCore.asyncRun(() -> {
                        listingRepo.ensureTable();
                        claimRepo.ensureTable();
                        stockRepo.ensureTable();
                        orderRepo.ensureTable();
                        auditRepo.ensureTable();
                        bazaarService.seedItemsBlocking();
                    });
                } catch (Throwable t) {
                    return java.util.concurrent.CompletableFuture.failedFuture(t);
                }
            }

            @Override
            public void runMain(Runnable task) {
                // Kontrolowany no-op gdy plugin wyłączony - żadnej IllegalPluginAccessException na zewnątrz.
                if (!isEnabled()) {
                    return;
                }
                try {
                    Bukkit.getScheduler().runTask(HexAuctionBazaarPlugin.this, task);
                } catch (org.bukkit.plugin.IllegalPluginAccessException | IllegalStateException ex) {
                    // Wyścig podczas wyłączania pluginu - ignorujemy.
                }
            }

            @Override
            public void startTasks() {
                stopTasks();   // defensywnie - nigdy dwa razy tego samego taska
                // Tryb konserwacji (enabled:false): NIE uruchamiamy zadań wygasania (Aukcji/Rynku).
                // startTasks jest wołane po każdym udanym init/reload, więc wyjście z konserwacji
                // uruchamia zadania DOKŁADNIE raz, a wejście w konserwację je zatrzymuje (punkt #7).
                if (!configRef.get().enabled()) {
                    getLogger().info("HexAuctionBazaar: tryb konserwacji (enabled=false) - zadania "
                            + "wygasania Aukcji/Rynku nie są uruchamiane.");
                    return;
                }
                expiryTask = new AuctionExpiryTask(HexAuctionBazaarPlugin.this, auctionService,
                        () -> configRef.get().auction());
                expiryTask.start();
                orderExpiryTask = new BazaarOrderExpiryTask(HexAuctionBazaarPlugin.this, orderService,
                        () -> configRef.get().bazaar());
                orderExpiryTask.start();
                getLogger().info("HexAuctionBazaar: aktywny prefiks tabel: '"
                        + safeTablePrefix().orElse("(niedostępny)") + "'.");
            }

            @Override
            public void stopTasks() {
                if (expiryTask != null) {
                    expiryTask.stop();
                    expiryTask = null;
                }
                if (orderExpiryTask != null) {
                    orderExpiryTask.stop();
                    orderExpiryTask = null;
                }
            }

            @Override
            public void disablePlugin() {
                getServer().getPluginManager().disablePlugin(HexAuctionBazaarPlugin.this);
            }

            @Override
            public void logInfo(String message) {
                getLogger().info(message);
            }

            @Override
            public void logWarn(String message) {
                getLogger().warning(message);
            }

            @Override
            public void logSevere(String message, Throwable error) {
                if (error != null) {
                    getLogger().log(Level.SEVERE, message, error);
                } else {
                    getLogger().severe(message);
                }
            }
        };
    }

    public PluginConfig config() {
        return configRef.get();
    }

    public MessageFactory messages() {
        return messages;
    }

    public HexCoreBridge hexCore() {
        return hexCore;
    }

    public EconomyBridge economy() {
        return economy;
    }

    public AuctionService auctionService() {
        return auctionService;
    }

    public BazaarService bazaarService() {
        return bazaarService;
    }

    public BazaarOrderService orderService() {
        return orderService;
    }

    public AuditService auditService() {
        return auditService;
    }

    public void reloadAllConfigs() {
        reloadConfig();
        PluginConfig fresh = ConfigLoader.load(getDataFolder(), getConfig(), getLogger());
        configRef.set(fresh);
        if (fresh.debug()) {
            getLogger().info("[debug] HexAuctionBazaar: konfiguracja przeładowana (auction.enabled="
                    + fresh.auction().enabled() + ", bazaar.enabled=" + fresh.bazaar().enabled() + ").");
        }
        if (autoRefreshTicker != null) {
            // Reload usuwa wszystkie sesje auto-refresh (odbudują się przy ponownym otwarciu).
            autoRefreshTicker.onReload();
        }
        if (signPrompt != null) {
            // Reload przywraca bloki wszystkich aktywnych promptów tabliczki.
            signPrompt.cancelAll();
        }
        // Recovery DB: ponowna, śledzona inicjalizacja z nowymi wartościami. Nowa generacja
        // unieważnia starą inicjalizację, stare taski są zatrzymywane, a seed jest częścią
        // initSchema (bez osobnego fire-and-forget). Przy błędzie respektowane jest 'required'.
        if (dbLifecycle != null) {
            var dbCfg = fresh.database();
            dbLifecycle.start(dbCfg.required(), dbCfg.healthCheckOnStartup());
        }
    }

    private void registerCommand(String name, Object executor) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().warning("Nie znaleziono komendy '" + name + "' w plugin.yml.");
            return;
        }
        cmd.setExecutor((org.bukkit.command.CommandExecutor) executor);
        cmd.setTabCompleter((org.bukkit.command.TabCompleter) executor);
    }

    private void ensureBundledResource(String name) {
        if (!new java.io.File(getDataFolder(), name).exists()) {
            try {
                saveResource(name, false);
            } catch (Exception ignored) {
            }
        }
    }
}
