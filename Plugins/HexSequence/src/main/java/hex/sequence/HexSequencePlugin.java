package hex.sequence;

import hex.core.api.HexApi;
import hex.core.api.ui.UiService;
import hex.core.api.ui.UiTokens;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public final class HexSequencePlugin extends JavaPlugin {

    private SequenceConfigLoader configLoader;
    private SequenceService sequenceService;
    private UiService ui;

    private final Map<String, String> fallbackMessages = Map.of(
            "usage", "&eUzycie: /hexsequence <reload|nazwa_sekwencji>",
            "reloaded", "&aHexSequence config przeladowany.",
            "no-permission", "&cBrak uprawnien.",
            "unknown-sequence", "&cNie znaleziono sekwencji: &f<sequence>&c.",
            "parse-error", "&cBlad w sekwencji &f<sequence>&c: <error>",
            "started", "&aUruchomiono sekwencje &f<sequence>&a (&e<count>&a komend).",
            "no-player-context", "&cTa sekwencja zawiera wpis [player], wiec musi zostac uruchomiona przez gracza."
    );

    @Override
    public void onEnable() {
        saveDefaultConfig();
        setupHexCoreUi();

        this.configLoader = new SequenceConfigLoader(this);
        this.sequenceService = new SequenceService(this);

        HexSequenceCommand executor = new HexSequenceCommand(this);
        var command = getCommand("hexsequence");
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        getLogger().info("HexSequence loaded.");
    }

    public void reloadPluginConfig() {
        reloadConfig();
        setupHexCoreUi();
    }

    public SequenceConfigLoader configLoader() {
        return configLoader;
    }

    public SequenceService sequenceService() {
        return sequenceService;
    }

    public void sendMessage(CommandSender sender, String key, Map<String, String> tokens) {
        if (ui != null) {
            UiTokens uiTokens = new UiTokens();
            tokens.forEach(uiTokens::put);
            ui.send(sender, "sequence." + key, uiTokens);
            return;
        }

        String message = getConfig().getString("messages." + key, fallbackMessages.getOrDefault(key, key));
        for (Map.Entry<String, String> token : tokens.entrySet()) {
            message = message.replace("<" + token.getKey() + ">", token.getValue());
        }
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    private void setupHexCoreUi() {
        RegisteredServiceProvider<HexApi> registration = Bukkit.getServicesManager().getRegistration(HexApi.class);
        if (registration == null) {
            this.ui = null;
            return;
        }

        HexApi api = registration.getProvider();
        this.ui = api.ui();
        this.ui.registerDefaults("sequence", fallbackMessages);
    }
}

