package hex.economy;

import hex.core.api.HexApi;
import hex.economy.api.HexEconomyApi;
import hex.economy.command.EconomyCommand;
import hex.economy.config.EconomyConfig;
import hex.economy.currency.HexCoinsBackend;
import hex.economy.currency.HexCoinsCurrencyProvider;
import hex.economy.currency.UnavailableHexCoinsBackend;
import hex.economy.database.EconomyRepository;
import hex.economy.placeholder.EconomyPlaceholderExpansion;
import hex.economy.service.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

public final class HexEconomyPlugin extends JavaPlugin {
    private HexApi hexApi;
    private EconomyService economyService;
    private EconomyRepository economyRepository;
    private EconomyPlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        var registration = Bukkit.getServicesManager().getRegistration(HexApi.class);
        if (registration == null) {
            getLogger().severe("HexCore not found! Disabling HexEconomy.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.hexApi = registration.getProvider();

        EconomyConfig config = EconomyConfig.load(getConfig());
        this.economyRepository = new EconomyRepository(hexApi.db().db());
        this.economyService = new EconomyService(economyRepository, config);

        HexCoinsBackend hexCoinsBackend = attachXConomy();
        HexCoinsCurrencyProvider hexCoinsProvider = new HexCoinsCurrencyProvider(
                hexCoinsBackend, hexApi, hexCoinsName(), hexCoinsFormat());
        economyService.registerProvider(hexCoinsProvider);

        registerCommands();
        Bukkit.getServicesManager().register(HexEconomyApi.class, economyService, this, ServicePriority.Normal);

        hexApi.db().async(() -> { economyRepository.ensureTables(); return null; })
                .thenRun(() -> Bukkit.getScheduler().runTask(this, () -> {
                    getLogger().info("HexEconomy database ready: smp_economy");
                    registerPlaceholders();
                }))
                .exceptionally(ex -> {
                    getLogger().severe("HexEconomy DB init failed: " + rootMessage(ex));
                    Bukkit.getScheduler().runTask(this, () -> getServer().getPluginManager().disablePlugin(this));
                    return null;
                });

        getLogger().info("MONEY provider: ready");
        getLogger().info(hexCoinsBackend.isAvailable()
                ? "HEX_COINS provider: XConomy ready"
                : "HEX_COINS provider unavailable: XConomy not found or API attach failed");
        getLogger().info("HexEconomy enabled");
    }

    @Override
    public void onDisable() {
        if (placeholderExpansion != null) {
            placeholderExpansion.stopRefreshing();
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
        if (economyService != null) Bukkit.getServicesManager().unregister(HexEconomyApi.class, economyService);
        getLogger().info("HexEconomy disabled");
    }

    public void reloadPluginConfig() {
        reloadConfig();
        if (economyService != null) {
            economyService.reload(EconomyConfig.load(getConfig()));
            economyService.configureHexCoins(hexCoinsName(), hexCoinsFormat());
        }
        if (placeholderExpansion != null) {
            placeholderExpansion.startRefreshing();
        }
    }

    public void sendConfiguredMessage(CommandSender sender, String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) return;
        String prefix = economyService == null ? "" : economyService.config().messages().prefix();
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix + rawMessage));
    }

    private HexCoinsBackend attachXConomy() {
        try {
            Plugin xconomy = getServer().getPluginManager().getPlugin("XConomy");
            if (xconomy == null || !pluginEnabled(xconomy)) return new UnavailableHexCoinsBackend();
            Class<?> backendClass = Class.forName("hex.economy.xconomy.XConomyReflectionBackend", true, getClass().getClassLoader());
            Method create = backendClass.getMethod("create", ClassLoader.class);
            return (HexCoinsBackend) create.invoke(null, xconomy.getClass().getClassLoader());
        } catch (Throwable ex) {
            getLogger().warning("Could not attach XConomy API: " + rootMessage(ex));
            return new UnavailableHexCoinsBackend();
        }
    }

    // Kept reflective-friendly for minimal Bukkit/Paper compatibility in test environments.
    private boolean pluginEnabled(Plugin plugin) {
        try { return (boolean) plugin.getClass().getMethod("isEnabled").invoke(plugin); }
        catch (ReflectiveOperationException ignored) { return true; }
    }

    private String hexCoinsName() { return getConfig().getString("hex-coins.name", "HexCoins"); }
    private String hexCoinsFormat() { return getConfig().getString("hex-coins.format", "{amount} {currency}"); }

    private void registerCommands() {
        EconomyCommand executor = new EconomyCommand(this, hexApi, economyService);
        PluginCommand smpEconomy = getCommand("smpeconomy");
        if (smpEconomy != null) { smpEconomy.setExecutor(executor); smpEconomy.setTabCompleter(executor); }
        else getLogger().severe("Command 'smpeconomy' is missing from plugin.yml.");
        PluginCommand money = getCommand("money");
        if (money != null) { money.setExecutor(executor); money.setTabCompleter(executor); }
        else getLogger().severe("Command 'money' is missing from plugin.yml.");
    }

    private void registerPlaceholders() {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            getLogger().info("PlaceholderAPI not found - economy placeholders are disabled.");
            return;
        }

        EconomyPlaceholderExpansion expansion = new EconomyPlaceholderExpansion(
                this,
                hexApi,
                economyService,
                economyRepository
        );
        if (!expansion.register()) {
            getLogger().warning("Could not register HexEconomy PlaceholderAPI expansion.");
            return;
        }

        this.placeholderExpansion = expansion;
        expansion.startRefreshing();
        getLogger().info("PlaceholderAPI expansion registered with identifier: hexeconomy");
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
