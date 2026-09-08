package hexbuildbattle;

import hexbuildbattle.arena.ArenaManager;
import hexbuildbattle.arena.ArenaResetService;
import hexbuildbattle.build.BuildSettingsService;
import hexbuildbattle.command.HexBuildBattleCommand;
import hexbuildbattle.command.LobbyCommand;
import hexbuildbattle.config.ConfigService;
import hexbuildbattle.config.MessageService;
import hexbuildbattle.effect.BuildBattleEffects;
import hexbuildbattle.game.GameManager;
import hexbuildbattle.game.GameSession;
import hexbuildbattle.game.GameTaskRegistry;
import hexbuildbattle.item.PluginItemKeys;
import hexbuildbattle.judging.JudgingService;
import hexbuildbattle.listener.BuildSettingsListener;
import hexbuildbattle.listener.PlayerConnectionListener;
import hexbuildbattle.listener.ProtectionListener;
import hexbuildbattle.listener.ThemeVotingListener;
import hexbuildbattle.placeholder.HexBuildBattlePlaceholderExpansion;
import hexbuildbattle.player.PlayerStateService;
import hexbuildbattle.protection.MaterialRules;
import hexbuildbattle.proxy.ProxyTransferService;
import hexbuildbattle.queue.QueueManager;
import hexbuildbattle.rating.RatingService;
import hexbuildbattle.report.ReportService;
import hexbuildbattle.result.ResultsService;
import hexbuildbattle.score.ScoreService;
import hexbuildbattle.statistics.SQLiteStatisticsRepository;
import hexbuildbattle.statistics.StatisticsRepository;
import hexbuildbattle.statistics.StatisticsService;
import hexbuildbattle.theme.ThemeManager;
import hexbuildbattle.theme.ThemeVotingService;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class HexBuildBattlePlugin extends JavaPlugin {

    private ConfigService configService;
    private MessageService messageService;
    private PlayerStateService playerStateService;
    private QueueManager queueManager;
    private GameTaskRegistry taskRegistry;
    private ProxyTransferService proxyTransferService;
    private GameSession session;
    private ArenaManager arenaManager;
    private ArenaResetService arenaResetService;
    private ThemeManager themeManager;
    private ThemeVotingService themeVotingService;
    private PluginItemKeys itemKeys;
    private BuildSettingsService buildSettingsService;
    private RatingService ratingService;
    private ReportService reportService;
    private BuildBattleEffects effects;
    private JudgingService judgingService;
    private ScoreService scoreService;
    private ResultsService resultsService;
    private StatisticsRepository statisticsRepository;
    private StatisticsService statisticsService;
    private GameManager gameManager;
    private HexBuildBattlePlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        this.configService = new ConfigService(this);
        if (!configService.reloadAll()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.messageService = new MessageService(configService);
        this.playerStateService = new PlayerStateService();
        this.queueManager = new QueueManager(configService::config);
        this.taskRegistry = new GameTaskRegistry(this);
        this.proxyTransferService = new ProxyTransferService(this, () -> configService.config().network().lobbyServer());
        this.session = new GameSession();
        this.arenaManager = new ArenaManager(configService, getLogger());
        this.arenaResetService = new ArenaResetService(this, configService, arenaManager, getLogger());
        this.themeManager = new ThemeManager(configService, getLogger());
        this.themeVotingService = new ThemeVotingService(this, configService, themeManager, taskRegistry);
        this.itemKeys = new PluginItemKeys(this);
        this.buildSettingsService = new BuildSettingsService(configService, messageService, arenaManager, itemKeys);
        this.ratingService = new RatingService(configService, itemKeys, getLogger());
        this.reportService = new ReportService(this);
        this.effects = new BuildBattleEffects(this, configService, taskRegistry);
        this.judgingService = new JudgingService(
                this,
                messageService,
                taskRegistry,
                buildSettingsService,
                ratingService,
                reportService,
                effects,
                session
        );
        this.scoreService = new ScoreService(configService);
        this.resultsService = new ResultsService(messageService, buildSettingsService, effects);
        if (!"sqlite".equalsIgnoreCase(configService.config().database().type())) {
            getLogger().warning("Only SQLite statistics backend is implemented. Using SQLite despite database.type="
                    + configService.config().database().type());
        }
        this.statisticsRepository = new SQLiteStatisticsRepository(configService.config().database().file(), getLogger());
        this.statisticsService = new StatisticsService(this, statisticsRepository, getLogger());

        proxyTransferService.register();
        statisticsService.initialize();

        this.gameManager = new GameManager(
                this,
                configService,
                messageService,
                playerStateService,
                queueManager,
                taskRegistry,
                proxyTransferService,
                session,
                arenaManager,
                arenaResetService,
                themeManager,
                themeVotingService,
                buildSettingsService,
                ratingService,
                effects,
                judgingService,
                scoreService,
                resultsService,
                statisticsService
        );

        if (!registerCommands()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        registerListeners();
        registerPlaceholderExpansion();
        gameManager.start();

        getLogger().info("HexBuildBattle enabled.");
    }

    @Override
    public void onDisable() {
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
        if (gameManager != null) {
            gameManager.shutdown();
            gameManager = null;
        }
        if (effects != null) {
            effects.cleanupGoatEffects();
        }
        if (statisticsService != null) {
            statisticsService.close();
            statisticsService = null;
        }
        statisticsRepository = null;
        if (proxyTransferService != null) {
            proxyTransferService.unregister();
            proxyTransferService = null;
        }
        HandlerList.unregisterAll(this);
        if (playerStateService != null) {
            playerStateService.clear();
            playerStateService = null;
        }
        queueManager = null;
        taskRegistry = null;
        messageService = null;
        configService = null;
        session = null;
        arenaManager = null;
        arenaResetService = null;
        themeManager = null;
        themeVotingService = null;
        itemKeys = null;
        buildSettingsService = null;
        ratingService = null;
        reportService = null;
        effects = null;
        judgingService = null;
        scoreService = null;
        resultsService = null;
        getLogger().info("HexBuildBattle disabled.");
    }

    public GameManager gameManager() {
        return gameManager;
    }

    public ConfigService configService() {
        return configService;
    }

    private void registerListeners() {
        MaterialRules materialRules = new MaterialRules(configService);
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(gameManager), this);
        getServer().getPluginManager().registerEvents(new ThemeVotingListener(themeVotingService), this);
        getServer().getPluginManager().registerEvents(new BuildSettingsListener(gameManager, buildSettingsService), this);
        getServer().getPluginManager().registerEvents(new ProtectionListener(
                gameManager,
                messageService,
                materialRules,
                buildSettingsService,
                ratingService,
                judgingService
        ), this);
    }

    private boolean registerCommands() {
        PluginCommand lobbyCommand = getCommand("lobby");
        if (lobbyCommand == null) {
            getLogger().severe("Command 'lobby' missing from plugin.yml.");
            return false;
        }
        lobbyCommand.setExecutor(new LobbyCommand(gameManager, messageService));

        PluginCommand adminCommand = getCommand("hexbuildbattle");
        if (adminCommand == null) {
            getLogger().severe("Command 'hexbuildbattle' missing from plugin.yml.");
            return false;
        }
        HexBuildBattleCommand executor = new HexBuildBattleCommand(gameManager, messageService);
        adminCommand.setExecutor(executor);
        adminCommand.setTabCompleter(executor);
        return true;
    }

    private void registerPlaceholderExpansion() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("PlaceholderAPI not detected; HexBuildBattle placeholders are disabled.");
            return;
        }
        this.placeholderExpansion = new HexBuildBattlePlaceholderExpansion(
                this,
                gameManager,
                statisticsService,
                configService
        );
        if (placeholderExpansion.register()) {
            getLogger().info("Registered PlaceholderAPI expansion: hexbuildbattle.");
        } else {
            getLogger().warning("Failed to register PlaceholderAPI expansion: hexbuildbattle.");
        }
    }
}
