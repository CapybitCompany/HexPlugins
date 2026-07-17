package pl.hexnetwork.hexnametags;

import hex.core.api.HexApi;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import pl.hexnetwork.hexnametags.api.HexNameTagsProvider;
import pl.hexnetwork.hexnametags.command.HexNameTagCommand;
import pl.hexnetwork.hexnametags.listener.PlayerLifecycleListener;
import pl.hexnetwork.hexnametags.persistence.NameTagPersistenceService;

public final class HexNameTagsPlugin extends JavaPlugin {
    private NameTagManager manager;
    private NameTagPersistenceService persistenceService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        HexApi hexApi = getServer().getServicesManager().load(HexApi.class);
        this.persistenceService = new NameTagPersistenceService(this, hexApi);

        this.manager = new NameTagManager(this, persistenceService);
        this.manager.reloadSettings();
        this.manager.start();

        HexNameTagsProvider.register(manager);

        getServer().getPluginManager().registerEvents(new PlayerLifecycleListener(manager), this);
        registerCommand();

        getLogger().info("HexNameTags enabled. Packet TextDisplay passenger mode active.");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.stop();
        }
        if (persistenceService != null) {
            persistenceService.clearCache();
        }
        HexNameTagsProvider.unregister();
    }

    private void registerCommand() {
        PluginCommand command = getCommand("hexnametag");
        if (command == null) {
            getLogger().warning("Command hexnametag is missing from plugin.yml");
            return;
        }
        HexNameTagCommand executor = new HexNameTagCommand(manager);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }
}
