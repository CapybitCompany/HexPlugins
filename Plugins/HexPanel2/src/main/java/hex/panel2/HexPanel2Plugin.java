package hex.panel2;

import hex.panel2.command.HexPanel2Command;
import hex.panel2.command.HexPanel2OcenyCommand;
import hex.panel2.command.HexPanel2OcenyStopCommand;
import hex.panel2.command.HexPanel2StartCommand;
import hex.panel2.command.HexPanel2StopCommand;
import hex.panel2.listener.BuildRestrictionListener;
import hex.panel2.listener.CraftRestrictionListener;
import hex.panel2.listener.ForbiddenItemListener;
import hex.panel2.listener.LiquidRestrictionListener;
import hex.panel2.listener.PanelBoundaryListener;
import hex.panel2.service.BuildSessionService;
import hex.panel2.service.PanelAccessModeService;
import hex.panel2.service.PanelService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class HexPanel2Plugin extends JavaPlugin {

    private PanelService panelService;
    private BuildSessionService buildSessionService;
    private PanelAccessModeService panelAccessModeService;

    @Override
    public void onEnable() {
        this.panelService = new PanelService(this);
        this.buildSessionService = new BuildSessionService(this);
        this.panelAccessModeService = new PanelAccessModeService();
        this.panelService.scanPanels();

        registerCommands();
        registerListeners();

        getLogger().info("HexPanel2 enabled. Detected panels: " + panelService.getPanelCount());
    }

    @Override
    public void onDisable() {
        if (buildSessionService != null) {
            buildSessionService.shutdown();
        }
        if (panelAccessModeService != null) {
            panelAccessModeService.disableOcenyMode();
        }
    }

    private void registerCommands() {
        PluginCommand assignCommand = Objects.requireNonNull(getCommand("hex_panel2"), "Command hex_panel2 missing in plugin.yml");
        assignCommand.setExecutor(new HexPanel2Command(panelService));

        PluginCommand startCommand = Objects.requireNonNull(getCommand("hex_panel2_start"), "Command hex_panel2_start missing in plugin.yml");
        startCommand.setExecutor(new HexPanel2StartCommand(buildSessionService, panelAccessModeService));

        PluginCommand stopCommand = Objects.requireNonNull(getCommand("hex_panel2_stop"), "Command hex_panel2_stop missing in plugin.yml");
        stopCommand.setExecutor(new HexPanel2StopCommand(buildSessionService));

        PluginCommand ocenyCommand = Objects.requireNonNull(getCommand("hex_panel2_oceny"), "Command hex_panel2_oceny missing in plugin.yml");
        ocenyCommand.setExecutor(new HexPanel2OcenyCommand(panelAccessModeService));

        PluginCommand ocenyStopCommand = Objects.requireNonNull(getCommand("hex_panel2_oceny_stop"), "Command hex_panel2_oceny_stop missing in plugin.yml");
        ocenyStopCommand.setExecutor(new HexPanel2OcenyStopCommand(panelAccessModeService));
    }

    private void registerListeners() {
        var pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new BuildRestrictionListener(panelService, buildSessionService), this);
        pluginManager.registerEvents(new LiquidRestrictionListener(), this);
        pluginManager.registerEvents(new CraftRestrictionListener(), this);
        pluginManager.registerEvents(new ForbiddenItemListener(), this);
        pluginManager.registerEvents(new PanelBoundaryListener(panelService, panelAccessModeService), this);
    }
}
