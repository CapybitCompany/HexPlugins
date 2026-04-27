package hex.drawn;

import hex.core.api.HexApi;
import hex.core.api.ui.UiService;
import hex.drawn.command.WaterDrawnCommand;
import hex.drawn.config.WaterDrawnConfig;
import hex.drawn.integration.HexMessageBridge;
import hex.drawn.listener.DrownListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public final class WaterDrawnPlugin extends JavaPlugin {

    private WaterDrawnConfig configModel;
    private DrownListener drownListener;
    private HexMessageBridge messageBridge;
    private volatile int runtimeWaterLevel = Integer.MAX_VALUE;
    private UiService ui;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Opcjonalne podlaczenie HexCore UI (softdepend)
        var reg = Bukkit.getServicesManager().getRegistration(HexApi.class);
        if (reg != null) {
            HexApi api = reg.getProvider();
            this.ui = api.ui();

            // Rejestracja domyslnych szablonow (admin nadpisuje w ui.yml > overrides)
            ui.registerDefaults("drawn", Map.of(
                "warning.immediate",  "<red><bold>Natychmiast wyjdz z wody!</bold></red>",
                "warning.countdown",  "<red>Topisz sie! <white><seconds>s</white> do utoniecia</red>",
                "death",              "<red><player> utonal.</red>"
            ));
        }

        this.configModel = new WaterDrawnConfig(this);
        this.configModel.setDynamicWaterLevel(runtimeWaterLevel);
        this.drownListener = new DrownListener(this, configModel);
        getServer().getPluginManager().registerEvents(drownListener, this);

        var cmd = getCommand("waterdrawn");
        if (cmd != null) {
            var executor = new WaterDrawnCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        this.messageBridge = new HexMessageBridge(this);
        this.messageBridge.trySubscribe();

        getLogger().info("WaterDrawn zaladowany.");
    }

    // ...existing code...
    @Override
    public void onDisable() {
        if (messageBridge != null) {
            messageBridge.tryUnsubscribe();
        }
        if (drownListener != null) {
            drownListener.shutdown();
        }
    }

    public WaterDrawnConfig getConfigModel() {
        return configModel;
    }

    public void setRuntimeWaterLevel(int level) {
        this.runtimeWaterLevel = level;
        if (configModel != null) {
            configModel.setDynamicWaterLevel(level);
        }
    }

    public void reloadWaterDrawnConfig() {
        reloadConfig();
        this.configModel = new WaterDrawnConfig(this);
        this.configModel.setDynamicWaterLevel(runtimeWaterLevel);
        if (drownListener != null) {
            drownListener.updateConfig(configModel);
        }
        getLogger().info("Konfiguracja WaterDrawn przeladowana.");
    }

    public boolean setMode(String mode) {
        String normalized = mode == null ? "" : mode.toLowerCase();
        if (!normalized.equals("global") && !normalized.equals("regions")) {
            return false;
        }

        getConfig().set("drown-mode", normalized);
        saveConfig();
        reloadWaterDrawnConfig();
        return true;
    }

    /** Returns HexCore UiService if available (softdepend), otherwise null. */
    public UiService ui() {
        return ui;
    }
}
