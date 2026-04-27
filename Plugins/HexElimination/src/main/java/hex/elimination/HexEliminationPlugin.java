package hex.elimination;

import hex.core.api.HexApi;
import hex.core.api.ui.UiService;
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
        ui.registerDefaults("elimination", Map.of(
            "kill.announce",
                "<dark_gray>[<red>ELIMINACJA</red>]</dark_gray>"
                + " <white>Gracz</white> <yellow><victim></yellow>"
                + " <white>zostal</white> <red><bold>WYELIMINOWANY</bold></red><white>!</white>",
            "resurect.announce",
                "<dark_gray>[<green>WSKRZESZENIE</green>]</dark_gray>"
                + " <white>Gracz</white> <yellow><target></yellow>"
                + " <white>zostal wskrzeszony przez</white> <aqua><by></aqua><white>.</white>",
            "resurect.ok",
                "<green>Wskrzeszono gracza: <white><target></white></green>",
            "resurect.usage",
                "<yellow>Uzycie: /resurect <nick_gracza></yellow>",
            "error.not_eliminated",
                "<red>Ten gracz nie jest wyeliminowany.</red>",
            "error.player_not_found",
                "<red>Nie znaleziono gracza: <white><nick></white></red>"
        ));

        this.eliminationService = new EliminationService(this);
        getServer().getPluginManager().registerEvents(new EliminationListener(this), this);

        var cmd = getCommand("resurect");
        if (cmd != null) {
            ResurectCommand executor = new ResurectCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
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
