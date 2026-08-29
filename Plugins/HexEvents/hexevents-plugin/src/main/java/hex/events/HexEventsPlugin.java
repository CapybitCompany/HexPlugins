package hex.events;

import hex.core.api.HexApi;
import hex.events.api.HexEventsApi;
import hex.events.api.HexEventsApiImpl;
import hex.events.command.EventCommand;
import hex.events.config.EngineConfig;
import hex.events.config.EventsConfigLoader;
import hex.events.lifecycle.EventLifecycleService;
import hex.events.listener.PlayerEventListener;
import hex.events.persistence.AdmissionRepository;
import hex.events.persistence.EventInstanceRepository;
import hex.events.persistence.EventSchedule7dRepository;
import hex.events.persistence.PaymentRepository;
import hex.events.persistence.PersistenceExecutor;
import hex.events.persistence.RegistrationRepository;
import hex.events.persistence.ResultRepository;
import hex.events.persistence.RewardRepository;
import hex.events.placeholder.HexEventsPlaceholderExpansion;
import hex.events.provider.*;
import hex.events.registration.AdmissionService;
import hex.events.registration.RegistrationService;
import hex.events.registry.CostProviderRegistry;
import hex.events.registry.EventModuleRegistry;
import hex.events.registry.RequirementProviderRegistry;
import hex.events.registry.RewardProviderRegistry;
import hex.events.reward.RewardEngine;
import hex.events.reward.RewardService;
import hex.events.ui.CalendarMenu;
import hex.events.ui.EventCountdownBossBarService;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import hex.economy.api.HexEconomyApi;
import hex.economy.api.CurrencyType;
import hexcustomitems.api.HexCustomItemsApi;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HexEventsPlugin extends JavaPlugin {
    private HexApi hex;
    private EngineConfig engineConfig;
    private EventModuleRegistry moduleRegistry;
    private RequirementProviderRegistry requirementRegistry;
    private CostProviderRegistry costRegistry;
    private RewardProviderRegistry rewardRegistry;
    private RewardService rewardService;
    private EventLifecycleService lifecycle;
    private RegistrationService registrations;
    private AdmissionService admissions;
    private EventCountdownBossBarService countdownBossBars;
    private HexEventsApiImpl api;
    private HexEventsPlaceholderExpansion placeholders;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    @Override public void onEnable() {
        saveDefaultConfig();
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        if (!new java.io.File(getDataFolder(), "events.yml").exists()) saveResource("events.yml", false);

        var hexReg = Bukkit.getServicesManager().getRegistration(HexApi.class);
        if (hexReg == null) { getLogger().severe("HexCore API not found; disabling HexEvents."); getServer().getPluginManager().disablePlugin(this); return; }
        this.hex = hexReg.getProvider();

        try { this.engineConfig = EngineConfig.load(getConfig()); }
        catch (Exception ex) { getLogger().severe("Niepoprawny config.yml HexEvents: " + ex.getMessage()); getServer().getPluginManager().disablePlugin(this); return; }

        EventsConfigLoader.LoadResult loaded = new EventsConfigLoader(this).load();
        if (!loaded.success()) { loaded.errors().forEach(e -> getLogger().severe("events.yml: " + e)); getServer().getPluginManager().disablePlugin(this); return; }

        this.moduleRegistry = new EventModuleRegistry();
        this.requirementRegistry = new RequirementProviderRegistry();
        this.costRegistry = new CostProviderRegistry();
        this.rewardRegistry = new RewardProviderRegistry();
        registerBuiltins();

        PersistenceExecutor persistence = new PersistenceExecutor(hex.db(), this);
        EventInstanceRepository instanceRepo = new EventInstanceRepository(hex.db().db());
        EventSchedule7dRepository schedule7dRepo = new EventSchedule7dRepository(hex.db().db());
        RegistrationRepository registrationRepo = new RegistrationRepository(hex.db().db());
        PaymentRepository paymentRepo = new PaymentRepository(hex.db().db());
        AdmissionRepository admissionRepo = new AdmissionRepository(hex.db().db());
        ResultRepository resultRepo = new ResultRepository(hex.db().db());
        RewardRepository rewardRepo = new RewardRepository(hex.db().db());
        this.rewardService = new RewardService(this, new RewardEngine(), rewardRegistry, resultRepo, rewardRepo, persistence);
        Clock clock = Clock.systemUTC();
        this.countdownBossBars = new EventCountdownBossBarService(this, clock);
        this.registrations = new RegistrationService(this, requirementRegistry, costRegistry, registrationRepo, paymentRepo, admissionRepo, moduleRegistry, persistence);
        this.admissions = new AdmissionService(this, clock, admissionRepo, registrations, persistence);
        this.lifecycle = new EventLifecycleService(this, clock, engineConfig, moduleRegistry, requirementRegistry, costRegistry, rewardRegistry,
                instanceRepo, schedule7dRepo, registrationRepo, admissionRepo, registrations, admissions, persistence, rewardService, countdownBossBars);
        this.api = new HexEventsApiImpl(this, moduleRegistry, lifecycle);
        Bukkit.getServicesManager().register(HexEventsApi.class, api, this, ServicePriority.Normal);

        CalendarMenu menu = new CalendarMenu(lifecycle, registrations, this);
        getServer().getPluginManager().registerEvents(menu, this);
        getServer().getPluginManager().registerEvents(new PlayerEventListener(lifecycle), this);
        PluginCommand command = getCommand("event");
        if (command != null) { EventCommand executor = new EventCommand(lifecycle, registrations, menu, this::reloadAtomic); command.setExecutor(executor); command.setTabCompleter(executor); }

        hex.db().asyncRun(() -> {
                    instanceRepo.ensureTable();
                    schedule7dRepo.ensureTable();
                    registrationRepo.ensureTables();
                    paymentRepo.ensureTable();
                    admissionRepo.ensureTable();
                    resultRepo.ensureTables();
                    rewardRepo.ensureTable();
                    paymentRepo.recoverAmbiguousOperations();
                    rewardRepo.recoverAmbiguousOperations();
                })
                .whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(this, () -> {
                    if (error != null) { getLogger().severe("Nie udało się zainicjalizować bazy HexEvents: " + rootMessage(error)); getServer().getPluginManager().disablePlugin(this); return; }
                    lifecycle.initializeAsync(loaded.config()).whenComplete((initIgnored, initError) -> Bukkit.getScheduler().runTask(this, () -> {
                        if (initError != null) { getLogger().severe("Nie udało się odtworzyć stanu HexEvents: " + rootMessage(initError)); getServer().getPluginManager().disablePlugin(this); return; }
                        ready.set(true); registerPlaceholders(); getLogger().info("HexEvents ready. Definitions=" + loaded.config().definitions().size());
                    }));
                }));
    }

    @Override public void onDisable() {
        ready.set(false);
        if (placeholders != null) { placeholders.unregister(); placeholders = null; }
        if (countdownBossBars != null) countdownBossBars.hideAll();
        if (lifecycle != null) lifecycle.shutdown();
        if (api != null) Bukkit.getServicesManager().unregister(HexEventsApi.class, api);
    }

    private void registerBuiltins() {
        // Providery Bukkit/vanilla są zawsze dostępne.
        requirementRegistry.register(new PermissionRequirementProvider());
        costRegistry.register(new VanillaItemCostProvider());
        rewardRegistry.register(new VanillaItemRewardProvider());

        // Integracje opcjonalne rejestrujemy wyłącznie, gdy plugin faktycznie jest
        // obecny. Dzięki temu brak HexEconomy/HexTowns/HexCustomItems nie powoduje
        // NoClassDefFoundError podczas ładowania HexEvents. Event korzystający z
        // brakującego providera otrzyma DEPENDENCY_UNAVAILABLE w /event validate.
        if (Bukkit.getPluginManager().isPluginEnabled("HexTowns")) {
            requirementRegistry.register(new TownMemberRequirementProvider());
        }
        if (Bukkit.getPluginManager().isPluginEnabled("HexCustomItems")) {
            requirementRegistry.register(new CustomItemPresentRequirementProvider());
            costRegistry.register(new CustomItemCostProvider());
            var customRegistration = Bukkit.getServicesManager().getRegistration(HexCustomItemsApi.class);
            if (customRegistration != null) rewardRegistry.register(new CustomItemRewardProvider(customRegistration.getProvider()));
        }
        if (Bukkit.getPluginManager().isPluginEnabled("HexEconomy")) {
            var economyRegistration = Bukkit.getServicesManager().getRegistration(HexEconomyApi.class);
            if (economyRegistration != null) {
                HexEconomyApi economyApi = economyRegistration.getProvider();
                costRegistry.register(new EconomyCostProvider("money", "MONEY", economyApi));
                costRegistry.register(new EconomyCostProvider("hexcoins", "HEX_COINS", economyApi));
                rewardRegistry.register(new EconomyRewardProvider("money", CurrencyType.MONEY, economyApi));
                rewardRegistry.register(new EconomyRewardProvider("hexcoins", CurrencyType.HEX_COINS, economyApi));
            }
        }
    }

    public void reloadAtomic() {
        try {
            reloadConfig();
            EngineConfig candidateEngine = EngineConfig.load(getConfig());
            EventsConfigLoader.LoadResult candidate = new EventsConfigLoader(this).load();
            if (!candidate.success()) { candidate.errors().forEach(e -> getLogger().warning("Reload rejected: " + e)); return; }
            lifecycle.reload(candidateEngine, candidate.config()); this.engineConfig = candidateEngine;
            getLogger().info("HexEvents reload OK. Definitions=" + candidate.config().definitions().size());
        } catch (Throwable throwable) { getLogger().warning("HexEvents reload rejected; poprzednia konfiguracja pozostaje aktywna: " + rootMessage(throwable)); }
    }

    private void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return;
        this.placeholders = new HexEventsPlaceholderExpansion(this, lifecycle);
        if (!placeholders.register()) placeholders = null;
    }
    public boolean ready(){return ready.get();}
    private static String rootMessage(Throwable t){Throwable c=t;while(c.getCause()!=null)c=c.getCause();return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage();}
}
