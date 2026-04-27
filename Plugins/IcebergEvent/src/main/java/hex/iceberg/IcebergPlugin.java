package hex.iceberg;

import hex.core.api.HexApi;
import hex.core.api.ui.UiService;
import hex.iceberg.command.IcebergCommand;
import hex.iceberg.config.IcebergConfig;
import hex.iceberg.service.IcebergService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public final class IcebergPlugin extends JavaPlugin {

    private IcebergConfig configModel;
    private IcebergService service;
    private UiService ui;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Opcjonalne podlaczenie HexCore UI (softdepend)
        var reg = Bukkit.getServicesManager().getRegistration(HexApi.class);
        if (reg != null) {
            HexApi api = reg.getProvider();
            this.ui = api.ui();

            ui.registerDefaults("iceberg", Map.of(
                "started", "<aqua>[Iceberg]</aqua> <white>Gora lodowa pojawila sie na horyzoncie!</white>",
                "stopped", "<aqua>[Iceberg]</aqua> <white>Gora lodowa zostala zatrzymana.</white>",
                "arrived", "<aqua>[Iceberg]</aqua> <red><bold>ZDERZENIE!</bold></red> <white>Gora lodowa uderzyla!</white>",
                "removed", "<aqua>[Iceberg]</aqua> <gray>Gora lodowa zniknela.</gray>"
            ));
        }

        this.configModel = new IcebergConfig(this);
        this.service = new IcebergService(this, configModel);

        var cmd = getCommand("iceberg");
        if (cmd != null) {
            IcebergCommand command = new IcebergCommand(this);
            cmd.setExecutor(command);
            cmd.setTabCompleter(command);
        }

        getLogger().info("IcebergEvent loaded.");
    }

    @Override
    public void onDisable() {
        if (service != null && service.isRunning()) {
            service.stop();
        }
    }

    public IcebergService getService() {
        return service;
    }

    public void reloadIcebergConfig() {
        reloadConfig();
        this.configModel = new IcebergConfig(this);
        this.service.updateConfig(configModel);
    }

    /** Returns HexCore UiService if available, otherwise null. */
    public UiService ui() {
        return ui;
    }
}

