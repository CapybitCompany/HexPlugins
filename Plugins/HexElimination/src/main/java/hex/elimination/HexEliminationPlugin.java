package hex.elimination;

import hex.core.api.HexApi;
import hex.core.api.ui.UiService;
import hex.elimination.command.EliminationToggleCommand;
import hex.elimination.command.ResurectAllCommand;
import hex.elimination.command.ResurectCommand;
import hex.elimination.listener.EliminationListener;
import hex.elimination.service.EliminationService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public final class HexEliminationPlugin extends JavaPlugin {

    private EliminationService eliminationService;
    private UiService ui;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Pobranie HexCore API
        var reg = Bukkit.getServicesManager().getRegistration(HexApi.class);
        if (reg == null) {
            getLogger().severe("HexCore not found! Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        HexApi api = reg.getProvider();
        this.ui = api.ui();

        // Rejestracja domyslnych szablonow UI (admin moze nadpisac w ui.yml > overrides)
        ui.registerDefaults("elimination", Map.ofEntries(
            Map.entry("kill.announce",
                "<dark_gray>[<red>ELIMINACJA</red>]</dark_gray>"
                + " <white>Gracz</white> <yellow><victim></yellow>"
                + " <white>zostal</white> <red><bold>WYELIMINOWANY</bold></red><white>!</white>"),
            Map.entry("resurect.announce",
                "<dark_gray>[<green>WSKRZESZENIE</green>]</dark_gray>"
                + " <white>Gracz</white> <yellow><target></yellow>"
                + " <white>zostal wskrzeszony przez</white> <aqua><by></aqua><white>.</white>"),
            Map.entry("resurect.ok",
                "<green>Wskrzeszono gracza: <white><target></white></green>"),
            Map.entry("resurect.usage",
                "<yellow>Uzycie: /resurect <nick_gracza></yellow>"),
            Map.entry("error.not_eliminated",
                "<red>Ten gracz nie jest wyeliminowany.</red>"),
            Map.entry("error.player_not_found",
                "<red>Nie znaleziono gracza: <white><nick></white></red>"),
            Map.entry("resurectall.announce",
                "<dark_gray>[<green>WSKRZESZENIE</green>]</dark_gray>"
                + " <aqua><by></aqua> <white>wskrzesił wszystkich (<yellow><count></yellow>) graczy."
                + " Tryb: <yellow><gamemode></yellow></white>"),
            Map.entry("resurectall.ok",
                "<green>Wskrzeszono <white><count></white> graczy.</green>"),
            Map.entry("resurectall.empty",
                "<yellow>Lista wyeliminowanych graczy jest już pusta.</yellow>"),
            Map.entry("resurectall.usage",
                "<yellow>Użycie: /resurectall [survival|creative|adventure|spectator]</yellow>"),
            Map.entry("resurectall.invalid_gamemode",
                "<red>Nieznany tryb gry: <white><input></white>. Dostępne: survival, creative, adventure, spectator.</red>"),
            Map.entry("toggle.started",
                "<dark_gray>[<gold>ELIMINACJA</gold>]</dark_gray> <green>Okres eliminacji <bold>ROZPOCZĘTY</bold></green>"
                + " <gray>przez</gray> <aqua><by></aqua><white>.</white>"),
            Map.entry("toggle.stopped",
                "<dark_gray>[<gold>ELIMINACJA</gold>]</dark_gray> <red>Okres eliminacji <bold>ZAKOŃCZONY</bold></red>"
                + " <gray>przez</gray> <aqua><by></aqua><white>.</white>"),
            Map.entry("toggle.already_started",
                "<yellow>Okres eliminacji jest już aktywny.</yellow>"),
            Map.entry("toggle.already_stopped",
                "<yellow>Okres eliminacji jest już nieaktywny.</yellow>"),
            Map.entry("toggle.usage",
                "<yellow>Użycie: /elimination <start|stop></yellow>")
        ));

        this.eliminationService = new EliminationService(this);
        getServer().getPluginManager().registerEvents(new EliminationListener(this), this);

        var cmd = getCommand("resurect");
        if (cmd != null) {
            ResurectCommand executor = new ResurectCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        var cmdAll = getCommand("resurectall");
        if (cmdAll != null) {
            ResurectAllCommand executorAll = new ResurectAllCommand(this);
            cmdAll.setExecutor(executorAll);
            cmdAll.setTabCompleter(executorAll);
        }

        var cmdToggle = getCommand("elimination");
        if (cmdToggle != null) {
            EliminationToggleCommand executorToggle = new EliminationToggleCommand(this);
            cmdToggle.setExecutor(executorToggle);
            cmdToggle.setTabCompleter(executorToggle);
        }

        getLogger().info("HexElimination loaded.");
    }

    @Override
    public void onDisable() {
        if (eliminationService != null) {
            eliminationService.shutdown();
        }
    }

    public EliminationService getEliminationService() {
        return eliminationService;
    }

    public UiService ui() {
        return ui;
    }
}
