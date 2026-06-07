package hex.towns;

import hex.core.api.HexApi;
import hex.towns.api.TownsApi;
import hex.towns.command.TownCommand;
import hex.towns.config.TownsConfig;
import hex.towns.database.TownRepository;
import hex.towns.listener.TownProtectionListener;
import hex.towns.placeholder.TownsPlaceholderExpansion;
import hex.towns.service.TownDataRegistry;
import hex.towns.service.TownsApiImpl;
import hex.towns.service.TownsService;
import hex.towns.visual.VisualCheckService;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
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
    private TownsPlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        var reg = Bukkit.getServicesManager().getRegistration(HexApi.class);
        if (reg == null) {
            getLogger().severe("HexCore not found! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.hexApi = reg.getProvider();

        TownsConfig config = TownsConfig.load(getConfig());
        registerUiDefaults();

        TownRepository repository = new TownRepository(hexApi.db().db());
        TownDataRegistry dataRegistry = new TownDataRegistry(hexApi, repository);
        this.townsService = new TownsService(this, hexApi, repository, dataRegistry, config);
        this.townsApi = new TownsApiImpl(townsService, dataRegistry);
        this.visualCheckService = new VisualCheckService(this, townsService, config);

        Bukkit.getServicesManager().register(TownsApi.class, townsApi, this, ServicePriority.Normal);
        registerPlaceholderExpansion(config);

        TownCommand townCommand = new TownCommand(this, hexApi, townsService, visualCheckService, config);
        PluginCommand townPluginCommand = getCommand("town");
        if (townPluginCommand != null) {
            townPluginCommand.setExecutor(townCommand);
            townPluginCommand.setTabCompleter(townCommand);
        } else {
            getLogger().severe("Command 'town' is missing from plugin.yml; HexTowns commands will not work.");
        }

        getServer().getPluginManager().registerEvents(new TownProtectionListener(hexApi, townsService), this);
        getServer().getPluginManager().registerEvents(visualCheckService, this);

        hexApi.db().async(() -> {
            repository.ensureTables();
            return repository.loadInitialState();
        }).thenAccept(state -> Bukkit.getScheduler().runTask(this, () -> {
            townsService.load(state);
            townsService.startGrowthSync();
            getLogger().info("HexTowns loaded towns=" + state.towns().size() + ", chunks=" + state.chunks().size());
        })).exceptionally(ex -> {
            getLogger().severe("HexTowns DB init failed: " + ex.getMessage());
            Bukkit.getScheduler().runTask(this, () -> getServer().getPluginManager().disablePlugin(this));
            return null;
        });

        getLogger().info("HexTowns enabled");
    }

    @Override
    public void onDisable() {
        if (visualCheckService != null) {
            visualCheckService.shutdown();
        }
        if (townsService != null) {
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
                Map.entry("help", "<gray>/town create, claim, coop, accept, endcoop, destroy, check, info, here, map, growth</gray>"),
                Map.entry("error.player-only", "<red>Ta komenda jest tylko dla gracza.</red>"),
                Map.entry("error.no-town", "<red>Nie nalezysz do zadnego miasta.</red>"),
                Map.entry("error.not-owner", "<red>Tylko wlasciciel miasta moze to zrobic.</red>"),
                Map.entry("error.world-disabled", "<red>W tym swiecie nie mozna zakladac ani powiekszac miasta.</red>"),
                Map.entry("error.player-not-found", "<red>Nie znaleziono gracza <white><player></white>.</red>"),
                Map.entry("error.db", "<red>Blad bazy danych: <white><error></white></red>"),
                Map.entry("confirm.expired", "<red>Potwierdzenie wygaslo. Uzyj komendy ponownie.</red>"),
                Map.entry("create.success", "<green>Zalozono miasto <yellow><town></yellow>.</green>"),
                Map.entry("create.too-close", "<red>Za blisko innego miasta. Minimalna odleglosc: <white><distance></white> chunkow.</red>"),
                Map.entry("create.already-member", "<red>Jestes juz w miescie lub COOP-ie. Nie mozesz zalozyc kolejnego.</red>"),
                Map.entry("create.confirm", "<gold>Zalozenie miasta <yellow><town></yellow> oznacza, ze odejscie lub zniszczenie miasta zresetuje statystyki. </gold><click:run_command:'/town create confirm'><green>[POTWIERDZ]</green></click> <gray>albo wpisz: <white>/town create confirm</white></gray>"),
                Map.entry("claim.success", "<green>Zaclaimowano chunk <white><cx>, <cz></white>.</green>"),
                Map.entry("claim.no-growth", "<red>Miasto nie ma punktow rosnienia.</red>"),
                Map.entry("claim.not-adjacent", "<red>Ten chunk nie przylega bokiem do twojego miasta.</red>"),
                Map.entry("claim.buffer-violation", "<red>Ten chunk jest za blisko cudzego miasta. Musi zostac przynajmniej 1 pusty chunk przerwy.</red>"),
                Map.entry("claim.limit-reached", "<red>Miasto osiagnelo limit <white><max></white> chunkow.</red>"),
                Map.entry("claim.already-claimed", "<red>Ten chunk jest juz zajety.</red>"),
                Map.entry("claim.world-mismatch", "<red>Miasto jest w innym swiecie.</red>"),
                Map.entry("coop.request-sent", "<aqua><player></aqua> prosi o dolaczenie do twojego miasta. <click:run_command:'/town accept <player>'><green>[AKCEPTUJ]</green></click>"),
                Map.entry("coop.request-created", "<green>Wyslano prosbe o dolacenie do miasta <yellow><town></yellow>.</green>"),
                Map.entry("coop.not-in-town", "<red>Musisz stac w cudzym miescie, zeby poprosic o COOP.</red>"),
                Map.entry("coop.already-member", "<red>Jestes juz czlonkiem tego miasta.</red>"),
                Map.entry("coop.requester-has-town", "<red>Gracz posiada juz miasto albo jest w COOP-ie.</red>"),
                Map.entry("coop.full", "<red>Miasto ma juz maksymalna liczbe czlonkow.</red>"),
                Map.entry("accept.success", "<green>Dodano <aqua><player></aqua> do COOP.</green>"),
                Map.entry("accept.no-request", "<red>Ten gracz nie ma aktywnej prosby o COOP.</red>"),
                Map.entry("accept.must-stand-in-town", "<red>Musisz stac w swoim miescie, zeby zaakceptowac COOP.</red>"),
                Map.entry("endcoop.warn", "<red>Odejscie z COOP zresetuje twoje statystyki SMP i EQ. </red><click:run_command:'/town endcoop confirm'><dark_red>[POTWIERDZ]</dark_red></click> <gray>albo wpisz: <white>/town endcoop confirm</white></gray>"),
                Map.entry("endcoop.not-coop", "<red>Nie jestes graczem COOP.</red>"),
                Map.entry("endcoop.success", "<green>Opusciles COOP. Reset statystyk zostal zgloszony do pluginow SMP.</green>"),
                Map.entry("destroy.warn", "<red>Zniszczenie miasta zresetuje statystyki tobie i graczom COOP oraz wyczysci dane miasta z innych pluginow. </red><click:run_command:'/town destroy confirm'><dark_red>[POTWIERDZ]</dark_red></click> <gray>albo wpisz: <white>/town destroy confirm</white></gray>"),
                Map.entry("destroy.success", "<green>Zniszczono miasto <yellow><town></yellow>.</green>"),
                Map.entry("protect.no-build", "<red>Nie mozesz tu budowac. To teren miasta <yellow><town></yellow>.</red>"),
                Map.entry("check.on", "<green>Podglad miast wlaczony.</green>"),
                Map.entry("check.off", "<gray>Podglad miast wylaczony.</gray>"),
                Map.entry("info", "<gold><town></gold> <gray>| owner:</gray> <white><owner></white> <gray>| czlonkowie:</gray> <white><members></white> <gray>| chunki:</gray> <white><chunks></white> <gray>| growth:</gray> <white><growth></white>"),
                Map.entry("here.none", "<gray>Ten chunk nie nalezy do zadnego miasta.</gray>"),
                Map.entry("here", "<gray>Ten chunk nalezy do miasta</gray> <yellow><town></yellow><gray>.</gray>"),
                Map.entry("map.line", "<gray><line></gray>"),
                Map.entry("growth", "<gray>Punkty rosnienia miasta:</gray> <white><growth></white>"),
                Map.entry("admin.metrics", "<gray>Miasta:</gray> <white><towns></white>"),
                Map.entry("admin.growth-sync", "<green>Zsynchronizowano punkty rozwoju.</green> <gray>Sprawdzono:</gray> <white><scanned></white><gray>, zmieniono:</gray> <white><changed></white>"),
                Map.entry("admin.growth-sync.skipped", "<yellow>Synchronizacja punktow rozwoju jest juz w toku.</yellow>"),
                Map.entry("sound.success", Sound.ENTITY_PLAYER_LEVELUP.name())
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