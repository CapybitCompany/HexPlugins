package hex.randomtp;

import hex.core.api.HexApi;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public final class HexRandomTpPlugin extends JavaPlugin {
    private volatile RtpConfig rtpConfig;
    private HexApi hexApi;
    private RandomTeleportService randomTeleportService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.rtpConfig = RtpConfig.load(this);

        this.hexApi = resolveHexApi();
        if (hexApi == null) {
            getLogger().severe("Nie znaleziono usługi HexApi. Upewnij się, że HexCore jest zainstalowany i uruchomił się poprawnie.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        registerUiTemplates(hexApi);

        CooldownService cooldownService = new CooldownService();
        this.randomTeleportService = new RandomTeleportService(this, hexApi, cooldownService);
        RandomTpCommand commandHandler = new RandomTpCommand(this, hexApi, randomTeleportService);

        PluginCommand command = getCommand("randomtp");
        if (command == null) {
            getLogger().severe("Brak komendy randomtp w plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);
        getServer().getPluginManager().registerEvents(
                new RtpActivatorListener(this, randomTeleportService),
                this
        );

        getLogger().info("HexRandomTP uruchomiony. Świat RTP: " + rtpConfig.worldName()
                + ", granice X=" + rtpConfig.minX() + ".." + rtpConfig.maxX()
                + ", Z=" + rtpConfig.minZ() + ".." + rtpConfig.maxZ()
                + ", aktywatory=" + rtpConfig.activatorCount());
    }

    @Override
    public void onDisable() {
        if (randomTeleportService != null) {
            randomTeleportService.shutdown();
            randomTeleportService = null;
        }
        hexApi = null;
    }

    RtpConfig rtpConfig() {
        return rtpConfig;
    }

    void reloadPluginConfig() {
        reloadConfig();
        this.rtpConfig = RtpConfig.load(this);
        if (hexApi != null) {
            applyMessagePrefix(hexApi);
        }
        getLogger().info("Przeładowano config.yml HexRandomTP.");
    }

    private HexApi resolveHexApi() {
        RegisteredServiceProvider<HexApi> registration = Bukkit.getServicesManager().getRegistration(HexApi.class);
        return registration == null ? null : registration.getProvider();
    }

    private void registerUiTemplates(HexApi hexApi) {
        applyMessagePrefix(hexApi);
        hexApi.ui().registerDefaults("randomtp", Map.ofEntries(
                Map.entry("searching", "<gray>Wyszukiwanie bezpiecznego miejsca...</gray>"),
                Map.entry("success", "<green>Przeniesiono Cię na losowe koordynaty <yellow><x>, <y>, <z></yellow>.</green>"),
                Map.entry("cooldown", "<red>Możesz ponownie użyć <yellow>/rtp</yellow> za <yellow><seconds>s</yellow>.</red>"),
                Map.entry("already-searching", "<yellow>Bezpieczne miejsce jest już dla Ciebie wyszukiwane.</yellow>"),
                Map.entry("no-location", "<red>Nie udało się znaleźć bezpiecznego miejsca. Spróbuj ponownie później.</red>"),
                Map.entry("teleport-failed", "<red>Teleportacja nie powiodła się. Cooldown nie został naliczony.</red>"),
                Map.entry("world-missing", "<red>Świat RTP <yellow><world></yellow> nie jest załadowany.</red>"),
                Map.entry("player-only", "<red>Tej komendy może użyć tylko gracz.</red>"),
                Map.entry("no-permission", "<red>Nie masz uprawnień do tej komendy.</red>"),
                Map.entry("reload-ok", "<green>Przeładowano konfigurację HexRandomTP.</green>"),
                Map.entry("usage", "<gray>Użycie: <yellow>/rtp</yellow> lub <yellow>/rtp reload</yellow>.</gray>")
        ));
    }

    private void applyMessagePrefix(HexApi hexApi) {
        try {
            hexApi.ui().getClass()
                    .getMethod("registerPrefix", String.class, String.class)
                    .invoke(hexApi.ui(), "randomtp", rtpConfig.messagePrefix());
        } catch (ReflectiveOperationException exception) {
            getLogger().warning("Ta wersja HexCore nie obsługuje prefiksów pluginów. "
                    + "Zaktualizuj HexCore, aby zastosować messages.prefix.");
        }
    }
}
