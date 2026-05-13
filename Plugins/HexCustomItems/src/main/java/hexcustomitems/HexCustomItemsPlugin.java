package hexcustomitems;

import hexcustomitems.command.HexCustomItemsCommand;
import hexcustomitems.command.LegacyGiveCommand;
import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.config.HexCustomItemsConfigLoader;
import hexcustomitems.listener.CustomItemsDropListener;
import hexcustomitems.listener.CustomItemsInteractListener;
import hexcustomitems.listener.CustomItemsProjectileListener;
import hexcustomitems.service.CustomItemRegistryService;
import hexcustomitems.service.CustomItemUseService;
import hexcustomitems.service.GiveService;
import hexcustomitems.service.MessageService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class HexCustomItemsPlugin extends JavaPlugin {

    private final AtomicReference<HexCustomItemsConfig> configRef = new AtomicReference<>();
    private HexCustomItemsConfigLoader configLoader;
    private CustomItemRegistryService registryService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.configLoader = new HexCustomItemsConfigLoader(this);
        HexCustomItemsConfig loadedConfig = this.configLoader.load();
        this.configRef.set(loadedConfig);

        MessageService messageService = new MessageService(configRef::get);
        this.registryService = new CustomItemRegistryService(this, loadedConfig);
        GiveService giveService = new GiveService(configRef::get, registryService, messageService);
        CustomItemUseService useService = new CustomItemUseService(this, configRef::get, registryService, messageService);

        if (!registerCommands(messageService, giveService)) {
            return;
        }

        getServer().getPluginManager().registerEvents(new CustomItemsInteractListener(registryService, useService), this);
        getServer().getPluginManager().registerEvents(new CustomItemsProjectileListener(useService), this);
        getServer().getPluginManager().registerEvents(new CustomItemsDropListener(registryService, messageService), this);

        getLogger().info("HexCustomItems uruchomiony.");
    }

    @Override
    public void onDisable() {
        getLogger().info("HexCustomItems zatrzymany.");
    }

    private boolean registerCommands(MessageService messageService, GiveService giveService) {
        PluginCommand mainCommand = getCommand("hexcustomitems");
        if (mainCommand == null) {
            getLogger().severe("Brak komendy 'hexcustomitems' w plugin.yml. Wyłączam plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        HexCustomItemsCommand executor = new HexCustomItemsCommand(
                configRef::get,
                registryService,
                giveService,
                messageService,
                this::reloadHexCustomItemsConfiguration
        );
        mainCommand.setExecutor(executor);
        mainCommand.setTabCompleter(executor);

        for (Map.Entry<String, String> binding : configRef.get().legacyCommandBindings().entrySet()) {
            PluginCommand command = getCommand(binding.getKey());
            if (command == null) {
                getLogger().warning("Brak komendy '" + binding.getKey() + "' w plugin.yml.");
                continue;
            }
            command.setExecutor(new LegacyGiveCommand(configRef::get, giveService, messageService, binding.getValue()));
        }

        return true;
    }

    private void reloadHexCustomItemsConfiguration() {
        reloadConfig();
        HexCustomItemsConfig updated = configLoader.load();
        configRef.set(updated);
        registryService.updateConfig(updated);
    }
}
