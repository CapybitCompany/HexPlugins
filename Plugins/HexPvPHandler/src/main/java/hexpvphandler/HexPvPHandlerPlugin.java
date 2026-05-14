package hexpvphandler;

import hexpvphandler.command.BlockPvpCommand;
import hexpvphandler.command.PvpStatusCommand;
import hexpvphandler.command.UnblockPvpCommand;
import hexpvphandler.config.HexPvPHandlerConfig;
import hexpvphandler.config.HexPvPHandlerConfigLoader;
import hexpvphandler.listener.PvpDamageListener;
import hexpvphandler.service.MessageService;
import hexpvphandler.service.PvpToggleService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.AtomicReference;

public final class HexPvPHandlerPlugin extends JavaPlugin {

    private final AtomicReference<HexPvPHandlerConfig> configRef = new AtomicReference<>();
    private HexPvPHandlerConfigLoader configLoader;
    private PvpToggleService pvpToggleService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.configLoader = new HexPvPHandlerConfigLoader(this);
        HexPvPHandlerConfig loadedConfig = configLoader.load();
        this.configRef.set(loadedConfig);

        MessageService messageService = new MessageService(configRef::get);
        this.pvpToggleService = new PvpToggleService(this, loadedConfig.blocked());

        if (!registerCommands(messageService)) {
            return;
        }
        getServer().getPluginManager().registerEvents(new PvpDamageListener(configRef::get, pvpToggleService), this);

        getLogger().info("HexPvPHandler uruchomiony.");
    }

    @Override
    public void onDisable() {
        getLogger().info("HexPvPHandler zatrzymany.");
    }

    private boolean registerCommands(MessageService messageService) {
        PluginCommand blockCommand = getCommand("hex_blokujpvp");
        PluginCommand unblockCommand = getCommand("hex_odblokujpvp");
        PluginCommand statusCommand = getCommand("hex_pvpstatus");

        if (blockCommand == null || unblockCommand == null || statusCommand == null) {
            getLogger().severe("Brak komend hex_blokujpvp/hex_odblokujpvp/hex_pvpstatus w plugin.yml. Wyłączam plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        blockCommand.setExecutor(new BlockPvpCommand(configRef::get, pvpToggleService, messageService, getLogger()));
        unblockCommand.setExecutor(new UnblockPvpCommand(configRef::get, pvpToggleService, messageService, getLogger()));
        statusCommand.setExecutor(new PvpStatusCommand(configRef::get, pvpToggleService, messageService, getLogger()));
        return true;
    }
}
