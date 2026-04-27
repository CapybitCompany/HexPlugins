package hex.areaeffects;

import hex.areaeffects.command.AreaEffectsCommand;
import hex.areaeffects.config.AreaEffectsConfig;
import hex.areaeffects.service.AreaEffectsService;
import org.bukkit.plugin.java.JavaPlugin;

public final class AreaEffectsPlugin extends JavaPlugin {

    private AreaEffectsConfig configModel;
    private AreaEffectsService service;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.configModel = new AreaEffectsConfig(this);
        this.service = new AreaEffectsService(this, configModel);

        var cmd = getCommand("areaeffects");
        if (cmd != null) {
            AreaEffectsCommand command = new AreaEffectsCommand(this);
            cmd.setExecutor(command);
            cmd.setTabCompleter(command);
        }

        getLogger().info("AreaEffects loaded.");
    }

    @Override
    public void onDisable() {
        if (service != null && service.isRunning()) {
            service.stop();
        }
    }

    public AreaEffectsService getService() {
        return service;
    }

    public void reloadEffectsConfig() {
        reloadConfig();
        this.configModel = new AreaEffectsConfig(this);
        this.service.updateConfig(configModel);
    }
}

