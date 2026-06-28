package hex.economy;

import hex.core.api.compat.MinecraftCompatibility;
import hex.core.api.HexApi;
import hex.economy.api.HexEconomyApi;
import hex.economy.command.EconomyCommand;
import hex.economy.config.EconomyConfig;
import hex.economy.database.EconomyRepository;
import hex.economy.service.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class HexEconomyPlugin extends JavaPlugin {
    private HexApi hexApi;
    private EconomyService economyService;

    @Override
    public void onEnable() {
        MinecraftCompatibility.logStartupCompatibility(this);
        saveDefaultConfig();

        var registration = Bukkit.getServicesManager().getRegistration(HexApi.class);
        if (registration == null) {
            getLogger().severe("HexCore not found! Disabling HexEconomy.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.hexApi = registration.getProvider();

        EconomyConfig config = EconomyConfig.load(getConfig());
        EconomyRepository repository = new EconomyRepository(hexApi.db().db());
        this.economyService = new EconomyService(repository, config);

        registerCommands();
        Bukkit.getServicesManager().register(HexEconomyApi.class, economyService, this, ServicePriority.Normal);

        hexApi.db().async(() -> {
            repository.ensureTables();
            return null;
        }).thenRun(() -> getLogger().info("HexEconomy database ready: smp_economy"))
                .exceptionally(ex -> {
                    getLogger().severe("HexEconomy DB init failed: " + rootMessage(ex));
                    Bukkit.getScheduler().runTask(this, () -> getServer().getPluginManager().disablePlugin(this));
                    return null;
                });

        getLogger().info("HexEconomy enabled");
    }

    @Override
    public void onDisable() {
        if (economyService != null) {
            Bukkit.getServicesManager().unregister(HexEconomyApi.class, economyService);
        }
        getLogger().info("HexEconomy disabled");
    }

    public void reloadPluginConfig() {
        reloadConfig();
        if (economyService != null) {
            economyService.reload(EconomyConfig.load(getConfig()));
        }
    }

    public void sendConfiguredMessage(CommandSender sender, String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return;
        }
        String prefix = economyService == null ? "" : economyService.config().messages().prefix();
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix + rawMessage));
    }

    private void registerCommands() {
        EconomyCommand executor = new EconomyCommand(this, hexApi, economyService);
        PluginCommand smpEconomy = getCommand("smpeconomy");
        if (smpEconomy != null) {
            smpEconomy.setExecutor(executor);
            smpEconomy.setTabCompleter(executor);
        } else {
            getLogger().severe("Command 'smpeconomy' is missing from plugin.yml.");
        }
        PluginCommand money = getCommand("money");
        if (money != null) {
            money.setExecutor(executor);
            money.setTabCompleter(executor);
        } else {
            getLogger().severe("Command 'money' is missing from plugin.yml.");
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
