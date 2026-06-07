package hex.rankexpiry;

import hex.core.api.HexApi;
import hex.rankexpiry.config.RankExpirySettings;
import hex.rankexpiry.database.LuckyPermsRankRepository;
import hex.rankexpiry.listener.PlayerJoinListener;
import hex.rankexpiry.model.RankExpiry;
import hex.rankexpiry.placeholder.RankExpiryPlaceholderExpansion;
import hex.rankexpiry.service.RankExpiryService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

public final class HexRankExpiryPlugin extends JavaPlugin implements TabExecutor {
    private HexApi hexApi;
    private RankExpirySettings settings;
    private RankExpiryService service;
    private RankExpiryPlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        var registration = Bukkit.getServicesManager().getRegistration(HexApi.class);
        if (registration == null) {
            getLogger().severe("HexCore API not found. Disabling HexRankExpiry.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.hexApi = registration.getProvider();

        if (!loadPluginConfig()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.service = new RankExpiryService(this, hexApi, new LuckyPermsRankRepository(settings), settings, Clock.systemUTC());
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this, service), this);

        if (getCommand("hexrankexpiry") != null) {
            getCommand("hexrankexpiry").setExecutor(this);
            getCommand("hexrankexpiry").setTabCompleter(this);
        }

        registerPlaceholderExpansion();
        getLogger().info("HexRankExpiry enabled. ranks=" + settings.ranks().size() + ", table=" + settings.userPermissionsTable());
    }

    @Override
    public void onDisable() {
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
        getLogger().info("HexRankExpiry disabled.");
    }

    private boolean loadPluginConfig() {
        reloadConfig();
        try {
            this.settings = RankExpirySettings.load(getConfig());
            return true;
        } catch (RuntimeException exception) {
            getLogger().severe("Could not load config.yml: " + exception.getMessage());
            return false;
        }
    }

    private void reloadPlugin() {
        if (!loadPluginConfig()) {
            throw new IllegalStateException("Invalid config.yml");
        }
        service.reload(new LuckyPermsRankRepository(settings), settings);
    }

    private void registerPlaceholderExpansion() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("[PAPI] PlaceholderAPI not found, skipping %hexrankexpiry_% registration.");
            return;
        }

        try {
            this.placeholderExpansion = new RankExpiryPlaceholderExpansion(this, service);
            if (placeholderExpansion.register()) {
                getLogger().info("[PAPI] Registered expansion %hexrankexpiry_%.");
            } else {
                getLogger().warning("[PAPI] Could not register expansion %hexrankexpiry_%.");
                this.placeholderExpansion = null;
            }
        } catch (NoClassDefFoundError error) {
            getLogger().warning("[PAPI] PlaceholderAPI classes missing at runtime: " + error.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            try {
                reloadPlugin();
                sender.sendMessage(RankExpirySettings.color("&aHexRankExpiry przeładowany."));
            } catch (RuntimeException exception) {
                sender.sendMessage(RankExpirySettings.color("&cNie udało się przeładować HexRankExpiry: " + exception.getMessage()));
            }
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("refresh")) {
            Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player player ? player : null);
            if (target == null) {
                sender.sendMessage(RankExpirySettings.color("&cPodaj nick gracza online: /" + label + " refresh <nick>"));
                return true;
            }
            service.invalidate(target.getUniqueId());
            service.refreshNow(target.getUniqueId()).thenAccept(rank -> Bukkit.getScheduler().runTask(this, () -> sendRefreshResult(sender, target, rank)));
            sender.sendMessage(RankExpirySettings.color("&7Odświeżam cache rangi dla &e" + target.getName() + "&7..."));
            return true;
        }

        sender.sendMessage(RankExpirySettings.color("&eHexRankExpiry &7v" + getDescription().getVersion()));
        sender.sendMessage(RankExpirySettings.color("&7Tabela LuckyPerms: &f" + settings.userPermissionsTable()));
        sender.sendMessage(RankExpirySettings.color("&7Monitorowane rangi: &f" + settings.ranks().size()));
        sender.sendMessage(RankExpirySettings.color("&7Placeholdery: &f%hexrankexpiry_days%&7, &f%hexrankexpiry_rank%&7, &f%hexrankexpiry_message%"));
        return true;
    }

    private void sendRefreshResult(CommandSender sender, Player target, Optional<RankExpiry> rank) {
        if (rank.isEmpty()) {
            sender.sendMessage(RankExpirySettings.color("&e" + target.getName() + " &7nie ma aktywnej czasowej rangi z konfiguracji."));
            return;
        }
        RankExpiry activeRank = rank.get();
        sender.sendMessage(RankExpirySettings.color("&e" + target.getName() + "&7: " + service.format("{rank} &7wygasa za &a{days} {day_word}", activeRank)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("info", "reload", "refresh").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("refresh")) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
