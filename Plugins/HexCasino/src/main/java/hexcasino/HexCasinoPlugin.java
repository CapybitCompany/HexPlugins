package hexcasino;

import hexcasino.command.HexCasinoCommand;
import hexcasino.config.CasinoConfig;
import hexcasino.config.CasinoConfigLoader;
import hexcasino.machine.BusDriverService;
import hexcasino.machine.SlotMachineService;
import hexcasino.machine.WheelOfFortuneService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

public final class HexCasinoPlugin extends JavaPlugin {

    private final CasinoConfigLoader configLoader = new CasinoConfigLoader();
    private final AtomicReference<CasinoConfig> configRef = new AtomicReference<>();

    private SlotMachineService slotMachineService;
    private WheelOfFortuneService wheelOfFortuneService;
    private BusDriverService busDriverService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!reloadPluginConfig()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.slotMachineService = new SlotMachineService(this, configRef::get);
        this.wheelOfFortuneService = new WheelOfFortuneService(this, configRef::get);
        this.busDriverService = new BusDriverService(this, configRef::get);
        getServer().getPluginManager().registerEvents(slotMachineService, this);
        getServer().getPluginManager().registerEvents(wheelOfFortuneService, this);
        getServer().getPluginManager().registerEvents(busDriverService, this);
        slotMachineService.start();
        wheelOfFortuneService.start();
        busDriverService.start();

        HexCasinoCommand command = new HexCasinoCommand(this);
        var pluginCommand = getCommand("hexcasino");
        if (pluginCommand == null) {
            getLogger().severe("Command 'hexcasino' missing from plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);

        getLogger().info("HexCasino enabled.");
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        if (slotMachineService != null) {
            slotMachineService.stop();
            slotMachineService = null;
        }
        if (wheelOfFortuneService != null) {
            wheelOfFortuneService.stop();
            wheelOfFortuneService = null;
        }
        if (busDriverService != null) {
            busDriverService.stop();
            busDriverService = null;
        }
        getLogger().info("HexCasino disabled.");
    }

    public boolean reloadPluginRuntime() {
        reloadConfig();
        boolean loaded = reloadPluginConfig();
        if (loaded && slotMachineService != null) {
            slotMachineService.reload();
        }
        if (loaded && wheelOfFortuneService != null) {
            wheelOfFortuneService.reload();
        }
        if (loaded && busDriverService != null) {
            busDriverService.reload();
        }
        return loaded;
    }

    public CasinoConfig config() {
        return configRef.get();
    }

    private boolean reloadPluginConfig() {
        try {
            File configFile = new File(getDataFolder(), "config.yml");
            configRef.set(configLoader.load(YamlConfiguration.loadConfiguration(configFile), getLogger()));
            return true;
        } catch (RuntimeException ex) {
            getLogger().severe("Failed to load HexCasino config: " + ex.getMessage());
            return false;
        }
    }
}
