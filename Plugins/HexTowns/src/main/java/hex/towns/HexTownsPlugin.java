package hex.towns;

import hex.core.api.HexApi;
import hex.towns.api.TownsApi;
import hex.towns.command.TownCommand;
import hex.towns.config.TownsConfig;
import hex.towns.database.TownRepository;
import hex.towns.listener.TownProtectionListener;
import hex.towns.map.TownMapService;
import hex.towns.gui.TownRenameAnvilListener;
import hex.towns.gui.TownCoopDecisionMenu;
import hex.towns.gui.NativeTownMenu;
import hex.towns.guide.TownGuideService;
import hex.towns.heart.TownHeartItem;
import hex.towns.heart.TownHeartListener;
import hex.towns.heart.TownHeartRenderer;
import hex.towns.heart.TownHeartReconciliationService;
import hex.towns.heart.HeartReconciliationReport;
import hex.towns.heart.TownHeartService;
import hex.towns.placeholder.TownsPlaceholderExpansion;
import hex.towns.service.TownDataRegistry;
import hex.towns.service.TownsApiImpl;
import hex.towns.service.TownsService;
import hex.towns.visual.VisualCheckService;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

// UI defaults are registered compatibly with older HexCore builds.
public final class HexTownsPlugin extends JavaPlugin {
    private HexApi hexApi;
    private TownsApi townsApi;
    private TownsService townsService;
    private VisualCheckService visualCheckService;
    private TownRenameAnvilListener renameAnvilListener;
    private TownCoopDecisionMenu coopDecisionMenu;
    private TownMapService townMapService;
    private TownHeartRenderer townHeartRenderer;
    private TownHeartService townHeartService;
    private TownHeartReconciliationService townHeartReconciliationService;
    private TownHeartListener townHeartListener;
    private NativeTownMenu nativeTownMenu;
    private TownGuideService townGuideService;
    private TownCommand townCommand;
    private TownsConfig config;
    private TownsPlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfig();

        var reg = Bukkit.getServicesManager().getRegistration(HexApi.class);
        if (reg == null) {
            getLogger().severe("HexCore not found! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.hexApi = reg.getProvider();

        if (!ensureDatabaseAvailable()) {
            return;
        }

        getLogger().info("HexTowns version=" + getPluginMeta().getVersion() + " build=source");
        this.config = TownsConfig.load(getConfig());
        registerUiDefaults();

        TownRepository repository = new TownRepository(hexApi.db().db());
        TownDataRegistry dataRegistry = new TownDataRegistry(hexApi, repository);
        this.townsService = new TownsService(this, hexApi, repository, dataRegistry, this.config);
        this.townGuideService = new TownGuideService(this, townsService);
        this.townsApi = new TownsApiImpl(townsService, dataRegistry, townGuideService);
        this.visualCheckService = new VisualCheckService(this, townsService, this.config);
        this.renameAnvilListener = new TownRenameAnvilListener(this, hexApi, townsService, this.config);
        this.coopDecisionMenu = new TownCoopDecisionMenu(this, hexApi, townsService);
        this.townMapService = new TownMapService(this, hexApi, townsService, this.config);
        this.nativeTownMenu = new NativeTownMenu(this, hexApi, townsService, visualCheckService, this.config, renameAnvilListener, townMapService, coopDecisionMenu, townGuideService);
        TownHeartItem townHeartItem = new TownHeartItem(this);
        townHeartItem.registerRecipe();
        this.townHeartRenderer = new TownHeartRenderer(this);
        this.townHeartService = new TownHeartService(this, hexApi, repository, townsService, townHeartRenderer);
        this.townHeartReconciliationService = new TownHeartReconciliationService(townsService, townHeartService, townHeartRenderer);
        this.townsService.setWorldCleanupHandler(townHeartService::cleanupJob);
        this.townHeartListener = new TownHeartListener(this, hexApi, townsService, this.config, townHeartItem, townHeartService, nativeTownMenu);

        Bukkit.getServicesManager().register(TownsApi.class, townsApi, this, ServicePriority.Normal);
        registerPlaceholderExpansion(this.config);

        this.townCommand = new TownCommand(this, hexApi, townsService, visualCheckService, this.config, renameAnvilListener, townMapService, coopDecisionMenu, townHeartListener, townHeartService, townHeartReconciliationService, nativeTownMenu);
        TownCommand townCommand = this.townCommand;
        PluginCommand townPluginCommand = getCommand("town");
        if (townPluginCommand != null) {
            townPluginCommand.setExecutor(townCommand);
            townPluginCommand.setTabCompleter(townCommand);
        } else {
            getLogger().severe("Command 'town' is missing from plugin.yml; HexTowns commands will not work.");
        }
        PluginCommand townAdminCommand = getCommand("townadmin");
        if (townAdminCommand != null) {
            townAdminCommand.setExecutor(townCommand);
            townAdminCommand.setTabCompleter(townCommand);
        }
        registerNativeMenuCommands();

        getServer().getPluginManager().registerEvents(new TownProtectionListener(hexApi, townsService, this.config), this);
        getServer().getPluginManager().registerEvents(townsService, this);
        getServer().getPluginManager().registerEvents(visualCheckService, this);
        getServer().getPluginManager().registerEvents(renameAnvilListener, this);
        getServer().getPluginManager().registerEvents(coopDecisionMenu, this);
        getServer().getPluginManager().registerEvents(townHeartRenderer, this);
        getServer().getPluginManager().registerEvents(townHeartReconciliationService, this);
        getServer().getPluginManager().registerEvents(nativeTownMenu, this);
        getServer().getPluginManager().registerEvents(townHeartListener, this);
        townHeartRenderer.startPulse();

        hexApi.db().async(() -> {
            repository.ensureTables();
            return repository.loadInitialState();
        }).thenAccept(state -> Bukkit.getScheduler().runTask(this, () -> {
            townsService.load(state);
            if (townHeartService != null) {
                townHeartService.loadAndRenderExistingHearts();
                HeartReconciliationReport report = townHeartReconciliationService.activateAndReconcile();
                getLogger().info("HexTowns heart reconciliation: loadedChunks=" + report.chunksScanned()
                        + " heartEntities=" + report.heartEntities()
                        + " valid=" + report.validGroups()
                        + " orphansRemoved=" + report.orphanEntitiesRemoved()
                        + " duplicates=" + report.duplicateGroups()
                        + " duplicatesRemoved=" + report.duplicateEntitiesRemoved()
                        + " malformed=" + report.malformedGroups()
                        + " registered=" + townHeartRenderer.registeredPartCount());
            }
            townsService.startGrowthSync();
            // Delay destroy recovery by one main-thread tick. TownsApi is published early, so
            // dependent plugins can register their V2 cleanup namespaces during the same server
            // enable pass; reconstructing a legacy DESTROYING job before that pass finishes could
            // otherwise snapshot an incomplete namespace set.
            Bukkit.getScheduler().runTask(this, () -> {
                townsService.recoverDestroyingJobs();
                townsService.startLifecycleMaintenance();
            });
            getLogger().info("HexTowns loaded towns=" + state.towns().size() + ", chunks=" + state.chunks().size());
        })).exceptionally(ex -> {
            getLogger().severe("HexTowns database startup failed: " + rootMessage(ex));
            Bukkit.getScheduler().runTask(this, () -> getServer().getPluginManager().disablePlugin(this));
            return null;
        });

        getLogger().info("HexTowns enabled");
    }

    private void migrateConfig() {
        int version = getConfig().getInt("config-version", 1);
        boolean changed = false;
        if (!getConfig().contains("towns.protection.allow-fire-spread") && getConfig().contains("towns.protection.fire-spread")) {
            // Historic key already meant "true = allow". Preserve that behavior while
            // moving to the explicit name required by the new protection model.
            getConfig().set("towns.protection.allow-fire-spread", getConfig().getBoolean("towns.protection.fire-spread", true));
            getConfig().set("towns.protection.fire-spread", null);
            getLogger().warning("Migrated towns.protection.fire-spread to towns.protection.allow-fire-spread.");
            changed = true;
        }
        if (version < 2) {
            getConfig().set("config-version", 2);
            changed = true;
        }
        if (version < 3) {
            installDefaultCreationBlockedCuboids();
            getConfig().set("config-version", 3);
            getLogger().info("Dodano domyslne blocked-cuboids dla spawn/NO_BUILD z buforem 500 blokow.");
            changed = true;
        }
        if (version < 4) {
            // Stare konfiguracje miały tylko 120 s na request COOP i nie posiadały
            // ograniczenia czasu odczytu LuckPerms/storage. Migracja zachowuje customowe
            // TTL > 120 s, ale naprawia historyczną wartość domyślną.
            if (!getConfig().isSet("towns.coop.request-ttl-seconds")
                    || getConfig().getInt("towns.coop.request-ttl-seconds", 120) <= 120) {
                getConfig().set("towns.coop.request-ttl-seconds", 600);
            }
            if (!getConfig().isSet("towns.coop.dynamic-limit.lookup-timeout-ms")) {
                getConfig().set("towns.coop.dynamic-limit.lookup-timeout-ms", 750);
            }
            getConfig().set("config-version", 4);
            getLogger().info("Zaktualizowano COOP: TTL requestu i bounded timeout dla dynamicznego limitu.");
            changed = true;
        }
        if (version < 5) {
            if (!getConfig().isSet("towns.protection.allow-member-ignite")) {
                getConfig().set("towns.protection.allow-member-ignite", true);
            }
            getConfig().set("config-version", 5);
            getLogger().info("Dodano ochronę ognia: członkowie mogą ręcznie rozpalać ogień w swoim mieście bez włączania fire spread.");
            changed = true;
        }
        if (changed) saveConfig();
    }

    private void installDefaultCreationBlockedCuboids() {
        String base = "towns.creation.blocked-cuboids";
        if (!getConfig().isSet(base + ".buffer-blocks")) {
            getConfig().set(base + ".buffer-blocks", 500);
        }

        setCreationBlockedCuboidIfMissing("spawn_safezone", 2701, 2827, 882, 993, "Spawn safezone HexPvpSmp");
        setCreationBlockedCuboidIfMissing("north_spawn", 2501, 3027, 682, 881, "Polnocna strefa NO_BUILD przy spawnie");
        setCreationBlockedCuboidIfMissing("south_spawn", 2501, 3027, 994, 1193, "Poludniowa strefa NO_BUILD przy spawnie");
        setCreationBlockedCuboidIfMissing("east_spawn", 2828, 3027, 882, 993, "Wschodnia strefa NO_BUILD przy spawnie");
        setCreationBlockedCuboidIfMissing("west_spawn", 2501, 2700, 882, 993, "Zachodnia strefa NO_BUILD przy spawnie");
    }

    private void setCreationBlockedCuboidIfMissing(String id, int minX, int maxX, int minZ, int maxZ, String description) {
        String base = "towns.creation.blocked-cuboids.regions." + id;
        if (getConfig().isSet(base)) return;

        getConfig().set(base + ".enabled", true);
        getConfig().set(base + ".world", "world");
        getConfig().set(base + ".min-x", minX);
        getConfig().set(base + ".max-x", maxX);
        getConfig().set(base + ".min-z", minZ);
        getConfig().set(base + ".max-z", maxZ);
        getConfig().set(base + ".description", description);
    }

    private boolean ensureDatabaseAvailable() {
        DatabaseAvailability availability = detectDatabaseAvailability();
        if (availability.available()) {
            return true;
        }

        String reason = availability.reason();
        getLogger().severe("HexTowns requires HexCore database, but it is unavailable: " + reason);
        getServer().getPluginManager().disablePlugin(this);
        return false;
    }

    private DatabaseAvailability detectDatabaseAvailability() {
        Object dbService = hexApi.db();
        try {
            Object available = dbService.getClass().getMethod("isAvailable").invoke(dbService);
            if (available instanceof Boolean value && !value) {
                String reason = "unknown reason";
                try {
                    Object reflectedReason = dbService.getClass().getMethod("unavailableReason").invoke(dbService);
                    if (reflectedReason instanceof String text && !text.isBlank()) {
                        reason = text;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // Older HexCore builds do not expose unavailableReason().
                }
                return new DatabaseAvailability(false, reason);
            }
        } catch (NoSuchMethodException ignored) {
            // Older HexCore build: fall back to an actual lightweight query.
        } catch (ReflectiveOperationException | LinkageError ex) {
            return new DatabaseAvailability(false, rootMessage(ex));
        }

        try {
            hexApi.db().db().queryOne("SELECT 1", rs -> 1);
            return new DatabaseAvailability(true, "");
        } catch (RuntimeException | LinkageError ex) {
            return new DatabaseAvailability(false, rootMessage(ex));
        }
    }

    private record DatabaseAvailability(boolean available, String reason) {
        private DatabaseAvailability {
            if (reason == null || reason.isBlank()) {
                reason = "unknown reason";
            }
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }


    private void registerNativeMenuCommands() {
        String[] commands = {
                "townmenu", "townmanage", "townclaims", "claimy", "towncoop",
                "towncollections", "towncollectionsresources", "towncollectionsfarming",
                "towncollectionsanimals", "towncollectionsmobs", "townminions", "towndanger"
        };
        for (String commandName : commands) {
            PluginCommand command = getCommand(commandName);
            if (command == null) {
                getLogger().warning("Command '" + commandName + "' is missing from plugin.yml; native HexTowns menu alias will not work.");
                continue;
            }
            command.setExecutor(nativeTownMenu);
            command.setTabCompleter(nativeTownMenu);
        }
    }


    public void reloadTownsConfig() {
        reloadConfig();
        this.config = TownsConfig.load(getConfig());
        registerUiDefaults();
        if (townsService != null) {
            townsService.reloadConfig(this.config);
        }
        if (visualCheckService != null) {
            visualCheckService.reloadConfig(this.config);
        }
        if (renameAnvilListener != null) {
            renameAnvilListener.reloadConfig(this.config);
        }
        if (townMapService != null) {
            townMapService.reloadConfig(this.config);
        }
        if (nativeTownMenu != null) {
            nativeTownMenu.reloadConfig(this.config);
        }
        if (townGuideService != null) {
            townGuideService.reload();
        }
        if (townHeartListener != null) {
            townHeartListener.reloadConfig(this.config);
        }
        if (townCommand != null) {
            townCommand.reloadConfig(this.config);
        }
        if (placeholderExpansion != null) {
            placeholderExpansion.reloadConfig(this.config);
        }
        getLogger().info("HexTowns config reloaded.");
    }

    @Override
    public void onDisable() {
        if (visualCheckService != null) {
            visualCheckService.shutdown();
        }
        if (townHeartRenderer != null) {
            townHeartRenderer.stopPulse();
        }
        if (townsService != null) {
            townsService.stopLifecycleMaintenance();
            townsService.stopGrowthSync();
        }
        if (townsApi != null) {
            Bukkit.getServicesManager().unregister(TownsApi.class, townsApi);
        }
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
        getLogger().info("HexTowns disabled");
    }

    private void registerPlaceholderExpansion(TownsConfig config) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("PlaceholderAPI not found; skipping HexTowns placeholders.");
            return;
        }
        try {
            this.placeholderExpansion = new TownsPlaceholderExpansion(townsService, config);
            if (placeholderExpansion.register()) {
                getLogger().info("Registered PlaceholderAPI expansion %hextowns_%.");
            } else {
                getLogger().warning("Could not register PlaceholderAPI expansion %hextowns_%.");
                this.placeholderExpansion = null;
            }
        } catch (Throwable throwable) {
            getLogger().warning("Could not register HexTowns placeholders: " + throwable.getMessage());
            this.placeholderExpansion = null;
        }
    }

    private void registerUiDefaults() {
        registerUiDefaultsCompat(Map.ofEntries(
                Map.entry("help", "<gray>/town guide, create, claim, accept, leave, rename, check, info, here, map</gray>"),
                Map.entry("error.player-only", "<red>Ta komenda jest tylko dla gracza.</red>"),
                Map.entry("error.no-town", "<red>Nie nalezysz do zadnego miasta.</red>"),
                Map.entry("error.not-owner", "<red>Tylko wlasciciel miasta moze to zrobic.</red>"),
                Map.entry("error.world-disabled", "<red>W tym swiecie nie mozna zakladac ani powiekszac miasta.</red>"),
                Map.entry("error.player-not-found", "<red>Nie znaleziono gracza <white><player></white>.</red>"),
                Map.entry("error.db", "<red>Blad bazy danych: <white><error></white></red>"),
                Map.entry("error.generic", "<red>Nie udalo sie wykonac operacji. Sprobuj ponownie.</red>"),
                Map.entry("confirm.expired", "<red>Potwierdzenie wygaslo. Uzyj komendy ponownie.</red>"),
                Map.entry("create.success", "<green>Zalozono miasto <yellow><town></yellow>.</green>"),
                Map.entry("rename.usage", "<gray>Uzycie: <white>/town rename <nazwa></white> albo <white>/town rename</white>, aby otworzyc kowadlo. Maks. <max> znakow.</gray>"),
                Map.entry("rename.invalid", "<red>Nieprawidlowa nazwa miasta. Uzyj 3-<max> znakow bez kolorow.</red>"),
                Map.entry("rename.success", "<green>Zmieniono nazwe miasta na <yellow><town></yellow>.</green>"),
                Map.entry("rename.cooldown", "<red>Nazwe miasta mozna zmienic raz na 48h. Sprobuj ponownie za okolo <hours>h.</red>"),
                Map.entry("create.too-close", "<red>Za blisko innego miasta. Minimalna odleglosc: <white><distance></white> chunkow.</red>"),
                Map.entry("create.blocked-area", "<red>W tym obszarze nie mozna zakladac miasta.</red>"),
                Map.entry("create.already-member", "<red>Jestes juz czlonkiem miasta. Nie mozesz zalozyc kolejnego.</red>"),
                Map.entry("create.confirm", "<gold>Zalozenie miasta <yellow><town></yellow> oznacza, ze odejscie lub zniszczenie miasta zresetuje statystyki. </gold><click:run_command:'/town create confirm'><green>[POTWIERDZ]</green></click> <gray>albo wpisz: <white>/town create confirm</white></gray>"),
                Map.entry("claim.success", "<green>Zaclaimowano chunk <white><cx>, <cz></white>.</green>"),
                Map.entry("claim.no-growth", "<red>Miasto nie ma Punktow Miasta potrzebnych do zajecia kolejnego chunka.</red>"),
                Map.entry("claim.not-adjacent", "<red>Ten chunk nie przylega bokiem do twojego miasta.</red>"),
                Map.entry("claim.buffer-violation", "<red>Ten chunk jest za blisko claimu innego miasta.</red>"),
                Map.entry("claim.limit-reached", "<red>Miasto osiagnelo limit <white><max></white> chunkow.</red>"),
                Map.entry("claim.shape-violation", "<red>Ten claim przekroczylby dozwolony rozmiar obszaru miasta (<white><max>x<max></white> chunkow).</red>"),
                Map.entry("claim.already-claimed", "<red>Ten chunk jest juz zajety.</red>"),
                Map.entry("claim.world-mismatch", "<red>Miasto jest w innym swiecie.</red>"),
                Map.entry("coop.request-sent", "<aqua><player></aqua> prosi o dolaczenie do twojego miasta."),
                Map.entry("coop.request-processing", "<gray>Wysylam prosbe o dolaczenie...</gray>"),
                Map.entry("coop.request-created", "<green>Wyslano prosbe o dolacenie do miasta <yellow><town></yellow>.</green>"),
                Map.entry("coop.not-in-town", "<red>Musisz stac w cudzym miescie, zeby poprosic o dolaczenie.</red>"),
                Map.entry("coop.already-member", "<red>Jestes juz czlonkiem tego miasta.</red>"),
                Map.entry("coop.requester-has-town", "<red>Gracz jest juz czlonkiem miasta.</red>"),
                Map.entry("coop.full", "<red>Miasto ma juz maksymalna liczbe czlonkow.</red>"),
                Map.entry("accept.processing", "<gray>Przetwarzam prosbe o dolaczenie...</gray>"),
                Map.entry("accept.success", "<green>Dodano <aqua><player></aqua> do miasta.</green>"),
                Map.entry("accept.no-request", "<red>Ta prosba o dolaczenie nie istnieje albo juz wygasla.</red>"),
                Map.entry("accept.must-stand-in-town", "<red>Musisz stac w swoim miescie, zeby zaakceptowac nowego gracza.</red>"),
                Map.entry("reject.success", "<yellow>Odrzucono prosbe gracza <aqua><player></aqua>.</yellow>"),
                Map.entry("kick.success", "<green>Usunieto <aqua><player></aqua> z miasta, wyczyszczono jego dostep i zgloszono reset gracza.</green>"),
                Map.entry("kick.target", "<red>Zostales usuniety z miasta <yellow><town></yellow>. Twoj dostep, EQ i dane gracza zostaly zresetowane.</red>"),
                Map.entry("kick.not-member", "<red>Ten gracz nie nalezy do twojego miasta.</red>"),
                Map.entry("kick.owner", "<red>Nie mozesz usunac wlasciciela miasta.</red>"),
                Map.entry("endcoop.warn", "<red>Odejscie z miasta zresetuje twoje statystyki SMP i EQ. </red><click:run_command:'/town leave confirm'><dark_red>[POTWIERDZ]</dark_red></click> <gray>albo wpisz: <white>/town leave confirm</white></gray>"),
                Map.entry("endcoop.not-coop", "<red>Nie jestes czlonkiem miasta.</red>"),
                Map.entry("endcoop.success", "<green>Opusciles miasto. Reset statystyk zostal zgloszony do pluginow SMP.</green>"),
                Map.entry("destroy.warn", "<red>Zniszczenie miasta zresetuje statystyki tobie i jego czlonkom oraz wyczysci dane miasta z innych pluginow. </red><click:run_command:'/town destroy confirm'><dark_red>[POTWIERDZ]</dark_red></click> <gray>albo wpisz: <white>/town destroy confirm</white></gray>"),
                Map.entry("destroy.success", "<green>Zniszczono miasto <yellow><town></yellow>.</green>"),
                Map.entry("destroy.member-removed", "<red>Miasto <yellow><town></yellow> zostalo usuniete. Nie jestes juz czlonkiem tego miasta.</red>"),
                Map.entry("protect.no-build", "<red>Nie mozesz tu budowac. To teren miasta <yellow><town></yellow>.</red>"),
                Map.entry("check.on", "<green>Podglad miast wlaczony.</green>"),
                Map.entry("check.off", "<gray>Podglad miast wylaczony.</gray>"),
                Map.entry("info", "<gold><town></gold> <gray>| wlasciciel:</gray> <white><owner></white> <gray>| czlonkowie:</gray> <white><members></white> <gray>| chunki:</gray> <white><chunks></white> <gray>| Punkty Miasta:</gray> <white><growth></white>"),
                Map.entry("here.none", "<gray>Ten chunk nie nalezy do zadnego miasta.</gray>"),
                Map.entry("here", "<gray>Ten chunk nalezy do miasta</gray> <yellow><town></yellow><gray>.</gray>"),
                Map.entry("map.line", "<gray><line></gray>"),
                Map.entry("growth", "<gray>Punkty Miasta:</gray> <white><growth></white>"),
                Map.entry("admin.metrics", "<gray>Miasta:</gray> <white><towns></white>"),
                Map.entry("admin.reload.success", "<green>Przeladowano konfiguracje HexTowns.</green>"),
                Map.entry("admin.reload.error", "<red>Nie udalo sie przeladowac konfiguracji HexTowns: <white><error></white></red>"),
                Map.entry("admin.growth-sync", "<green>Zsynchronizowano punkty rozwoju.</green> <gray>Sprawdzono:</gray> <white><scanned></white><gray>, zmieniono:</gray> <white><changed></white>"),
                Map.entry("admin.growth-sync.skipped", "<yellow>Synchronizacja punktow rozwoju jest juz w toku.</yellow>"),
                Map.entry("admin.addgrowth.usage", "<red>Uzycie: <white>/town admin addgrowth <uuid-miasta|nazwa-miasta> <punkty> [zrodlo]</white></red>"),
                Map.entry("admin.addgrowth.town-not-found", "<red>Nie znaleziono miasta: <white><town></white></red>"),
                Map.entry("admin.addgrowth.invalid-number", "<red>Liczba punktow musi byc poprawna liczba calkowita.</red>"),
                Map.entry("admin.addgrowth.zero", "<red>Liczba punktow nie moze wynosic 0.</red>"),
                Map.entry("admin.addgrowth.success", "<green>Dodano <white><amount></white> punktow wzrostu do miasta <yellow><town></yellow>. Zrodlo: <gray><source></gray>.</green>"),
                Map.entry("admin.giveheart.usage", "<red>Uzycie: <white>/town admin giveheart <gracz> [ilosc]</white></red>"),
                Map.entry("admin.giveheart.player-offline", "<red>Gracz musi byc online: <white><player></white></red>"),
                Map.entry("admin.giveheart.invalid-number", "<red>Ilosc musi byc liczba calkowita.</red>"),
                Map.entry("admin.giveheart.success", "<green>Dodano <white><amount>x</white> Serce Miasta dla <yellow><player></yellow>.</green>"),
                Map.entry("heart.craft.no-shift", "<red>Serce miasta craftuj pojedynczym kliknieciem, bez shift-clicka.</red>"),
                Map.entry("map.cooldown", "<red>Mape miasta mozesz odswiezyc ponownie za <white><seconds>s</white>.</red>"),
                Map.entry("map.refreshed", "<green>Odswiezono istniejaca mape miast w ekwipunku.</green>"),
                Map.entry("map.no-space", "<red>Nie masz miejsca w ekwipunku na mape miasta.</red>"),
                Map.entry("map.created", "<green>Dodano mape miast do ekwipunku. <gray>Wez ja do reki, aby zobaczyc granice i nazwy miast.</gray></green>"),
                Map.entry("heart.already-has-town", "<red>Masz juz miasto. Na produkcji nie mozna postawic drugiego serca.</red>"),
                Map.entry("heart.already-placed", "<red>To miasto ma juz postawione serce.</red>"),
                Map.entry("heart.item-missing", "<red>Nie masz juz w rece Serca Miasta.</red>"),
                Map.entry("heart.placed", "<green>Postawiono Serce Miasta dla <yellow><town></yellow>.</green>"),
                Map.entry("heart.indestructible", "<red>Serce miasta jest niezniszczalne.</red>"),
                Map.entry("heart.protected-zone", "<red>W centralnym chunku nad sercem mozna budowac tylko ponizej poziomu ochrony.</red>"),
                Map.entry("sound.success", "ENTITY_PLAYER_LEVELUP")
        ));
    }

    private void registerUiDefaultsCompat(Map<String, String> defaults) {
        try {
            Object ui = hexApi.getClass().getMethod("ui").invoke(hexApi);
            ui.getClass()
                    .getMethod("registerDefaults", String.class, Map.class)
                    .invoke(ui, "towns", defaults);
        } catch (NoSuchMethodException e) {
            getLogger().warning("HexCore UiService nie udostepnia registerDefaults(String, Map). "
                    + "Pomijam rejestracje domyslnych tekstow; zaktualizuj HexCore.jar albo dodaj teksty w ui.yml.");
        } catch (ReflectiveOperationException | LinkageError e) {
            getLogger().warning("Nie mozna zarejestrowac domyslnych tekstow UI: " + e.getMessage());
        }
    }
}
