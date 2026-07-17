package hex.economy;

import hex.core.api.compat.MinecraftCompatibility;
import hex.core.api.HexApi;
import hex.economy.api.HexEconomyApi;
import hex.economy.command.EconomyCommand;
import hex.economy.config.EconomyConfig;
import hex.economy.database.EconomyRepository;
import hex.economy.placeholder.EconomyPlaceholderExpansion;
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
    private EconomyPlaceholderExpansion placeholderExpansion;

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

        if (!ensureDatabaseAvailable()) {
            return;
        }

        EconomyConfig config = EconomyConfig.load(getConfig());
        EconomyRepository repository = new EconomyRepository(hexApi.db().db());
        this.economyService = new EconomyService(repository, config);

        registerCommands();
        Bukkit.getServicesManager().register(HexEconomyApi.class, economyService, this, ServicePriority.Normal);
        registerPlaceholderExpansion();

        hexApi.db().async(() -> {
            repository.ensureTables();
            return null;
        }).thenRun(() -> getLogger().info("HexEconomy database ready: smp_economy"))
                .exceptionally(ex -> {
                    getLogger().severe("HexEconomy database startup failed: " + rootMessage(ex));
                    Bukkit.getScheduler().runTask(this, () -> getServer().getPluginManager().disablePlugin(this));
                    return null;
                });

        getLogger().info("HexEconomy enabled");
    }

    private boolean ensureDatabaseAvailable() {
        DatabaseAvailability availability = detectDatabaseAvailability();
        if (availability.available()) {
            return true;
        }

        String reason = availability.reason();
        getLogger().severe("HexEconomy requires HexCore database, but it is unavailable: " + reason);
        getServer().getPluginManager().disablePlugin(this);
        return false;
    }

    private DatabaseAvailability detectDatabaseAvailability() {
        Object dbService = hexApi.db();
        try {
            Object available = dbService.getClass().getMethod("isAvailable").invoke(dbService);
            if (available instanceof Boolean value && !value) {
                String reason = "unknown reason";
                try {
                    Object reflectedReason = dbService.getClass().getMethod("unavailableReason").invoke(dbService);
                    if (reflectedReason instanceof String text && !text.isBlank()) {
                        reason = text;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // Older HexCore builds do not expose unavailableReason().
                }
                return new DatabaseAvailability(false, reason);
            }
        } catch (NoSuchMethodException ignored) {
            // Older HexCore build: fall back to an actual lightweight query.
        } catch (ReflectiveOperationException | LinkageError ex) {
            return new DatabaseAvailability(false, rootMessage(ex));
        }

        try {
            hexApi.db().db().queryOne("SELECT 1", rs -> 1);
            return new DatabaseAvailability(true, "");
        } catch (RuntimeException | LinkageError ex) {
            return new DatabaseAvailability(false, rootMessage(ex));
        }
    }

    private record DatabaseAvailability(boolean available, String reason) {
        private DatabaseAvailability {
            if (reason == null || reason.isBlank()) {
                reason = "unknown reason";
            }
        }
    }

    @Override
    public void onDisable() {
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
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

    private void registerPlaceholderExpansion() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("PlaceholderAPI not found; skipping HexEconomy placeholders.");
            return;
        }
        try {
            this.placeholderExpansion = new EconomyPlaceholderExpansion(this, economyService);
            if (placeholderExpansion.register()) {
                getLogger().info("Registered PlaceholderAPI expansion %hexeconomy_%.");
            } else {
                getLogger().warning("Could not register PlaceholderAPI expansion %hexeconomy_%.");
                this.placeholderExpansion = null;
            }
        } catch (Throwable throwable) {
            getLogger().warning("Could not register HexEconomy placeholders: " + rootMessage(throwable));
            this.placeholderExpansion = null;
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
