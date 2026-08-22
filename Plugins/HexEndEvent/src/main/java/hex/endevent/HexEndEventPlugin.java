package hex.endevent;

import hex.core.api.HexApi;
import hex.endevent.command.EndEventCommand;
import hex.endevent.config.EndEventConfig;
import hex.endevent.listener.EndAccessListener;
import hex.endevent.placeholder.EndEventPlaceholderExpansion;
import hex.endevent.service.EndEventService;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;

public final class HexEndEventPlugin extends JavaPlugin {
    public record ReloadResult(boolean success, String message) { }

    private HexApi hex;
    private EndEventService service;
    private EndEventPlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        getLogger().info("Running on " + Bukkit.getName() + " " + Bukkit.getBukkitVersion() + " (Minecraft " + Bukkit.getMinecraftVersion() + ")");
        saveDefaultConfig();

        var hexReg = Bukkit.getServicesManager().getRegistration(HexApi.class);
        if (hexReg == null) {
            getLogger().severe("HexCore API not found; disabling HexEndEvent.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.hex = hexReg.getProvider();
        registerUiDefaults();

        EndEventConfig config;
        String startupConfigError = null;
        try {
            config = EndEventConfig.load(getConfig());
            List<String> worldErrors = config.validateLoadedWorlds(getServer());
            if (!worldErrors.isEmpty()) throw new IllegalArgumentException(String.join("; ", worldErrors));
        } catch (Exception ex) {
            startupConfigError = ex.getMessage();
            config = EndEventConfig.safeClosedDefaults();
            getLogger().severe("Niepoprawna konfiguracja HexEndEvent. Uruchamiam twarda blokade fail-closed: " + startupConfigError);
        }

        this.service = new EndEventService(this, hex, config);
        this.service.start();
        if (startupConfigError != null) this.service.forceErrorClosed("Niepoprawny config: " + startupConfigError);
        getServer().getPluginManager().registerEvents(new EndAccessListener(this, service), this);

        EndEventCommand command = new EndEventCommand(this, hex, service);
        PluginCommand pluginCommand = getCommand("endevent");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        registerPlaceholderExpansion();
        getLogger().info("HexEndEvent enabled; master switch event.enabled=" + config.enabled());
    }

    @Override
    public void onDisable() {
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
        if (service != null) service.shutdown();
        getLogger().info("HexEndEvent disabled");
    }

    public ReloadResult reloadEndEventConfig() {
        try {
            reloadConfig();
            EndEventConfig config = EndEventConfig.load(getConfig());
            List<String> worldErrors = config.validateLoadedWorlds(getServer());
            if (!worldErrors.isEmpty()) throw new IllegalArgumentException(String.join("; ", worldErrors));
            service.reload(config);
            return new ReloadResult(true, "OK");
        } catch (Exception ex) {
            getLogger().severe("Reload HexEndEvent odrzucony: " + ex.getMessage());
            return new ReloadResult(false, ex.getMessage());
        }
    }

    private void registerPlaceholderExpansion() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("PlaceholderAPI not found; skipping HexEndEvent placeholders.");
            return;
        }
        try {
            this.placeholderExpansion = new EndEventPlaceholderExpansion(this, service);
            if (placeholderExpansion.register()) {
                getLogger().info("Registered PlaceholderAPI expansion %hexendevent_%.");
            } else {
                getLogger().warning("Could not register PlaceholderAPI expansion %hexendevent_%.");
                this.placeholderExpansion = null;
            }
        } catch (Throwable throwable) {
            getLogger().warning("Could not register HexEndEvent placeholders: " + throwable.getMessage());
            this.placeholderExpansion = null;
        }
    }

    private void registerUiDefaults() {
        hex.ui().registerDefaults("endevent", Map.ofEntries(
                Map.entry("access.closed", "<red>End jest obecnie zamknięty.</red> <gray>Następne otwarcie:</gray> <yellow><next></yellow><gray>.</gray>"),
                Map.entry("status.disabled", "<red>End jest obecnie zamknięty.</red> <gray>Event Endu jest wyłączony w konfiguracji.</gray>"),
                Map.entry("status.closed", "<gold>End Event</gold> <dark_gray>•</dark_gray> <gray>Następne otwarcie:</gray> <yellow><next></yellow><gray>.</gray>"),
                Map.entry("status.preparing", "<gold>End Event</gold> <dark_gray>•</dark_gray> <yellow>Trwa przygotowanie świeżego Endu.</yellow> <gray>Planowane otwarcie:</gray> <white><next></white><gray>.</gray>"),
                Map.entry("status.open", "<green>End jest teraz otwarty!</green> <gray>Do zamknięcia pozostało:</gray> <white><remaining></white><gray>. Zamknięcie:</gray> <yellow><closes></yellow><gray>.</gray>"),
                Map.entry("broadcast.open", "<gold><bold>End został otwarty!</bold></gold> <gray>Macie <white><duration></white> na eksplorację.</gray>"),
                Map.entry("broadcast.closed", "<red><bold>End został zamknięty.</bold></red> <gray>Wszyscy gracze zostali przeniesieni do zwykłego świata.</gray>"),
                Map.entry("bossbar.active", "<gold>End Event</gold> <dark_gray>•</dark_gray> <gray>Do zamknięcia:</gray> <white><remaining></white>"),
                Map.entry("error.unavailable", "<red>End jest chwilowo niedostępny.</red> <gray>Spróbuj ponownie później.</gray>"),
                Map.entry("error.no-permission", "<red>Brak uprawnień.</red>"),
                Map.entry("admin.reload.success", "<green>Przeładowano HexEndEvent.</green>"),
                Map.entry("admin.reload.error", "<red>Nie udało się przeładować HexEndEvent:</red> <white><error></white>"),
                Map.entry("admin.status", "<gold>HexEndEvent</gold><newline><gray>Stan:</gray> <white><state></white> <dark_gray>|</dark_gray> <gray>enabled:</gray> <white><enabled></white><newline><gray>Strefa:</gray> <white><timezone></white> <dark_gray>|</dark_gray> <gray>Następny:</gray> <yellow><next></yellow><newline><gray>prepared:</gray> <white><prepared></white><newline><gray>active:</gray> <white><active></white><newline><gray>reset-required:</gray> <white><reset_required></white> <dark_gray>|</dark_gray> <gray>End loaded:</gray> <white><loaded></white> <dark_gray>|</dark_gray> <gray>gracze:</gray> <white><players></white>")
        ));
    }
}
