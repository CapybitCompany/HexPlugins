package hex.restrictions;

import hex.restrictions.command.RestrictionsCommand;
import hex.restrictions.config.RestrictionSettings;
import hex.restrictions.listener.RestrictionListener;
import hex.restrictions.service.RestrictionAudit;
import hex.restrictions.service.RestrictionService;
import hex.restrictions.service.WorldScanService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class HexRestrictionsPlugin extends JavaPlugin {
    private RestrictionService restrictions;
    private WorldScanService worldScanner;
    private BukkitTask playerSweepTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.restrictions = new RestrictionService(RestrictionSettings.load(getConfig(), getLogger()));
        this.worldScanner = new WorldScanService(this, restrictions);
        this.worldScanner.start();

        getServer().getPluginManager().registerEvents(new RestrictionListener(this, restrictions, worldScanner), this);

        RestrictionsCommand restrictionsCommand = new RestrictionsCommand(this);
        PluginCommand command = getCommand("hexrestrictions");
        if (command != null) {
            command.setExecutor(restrictionsCommand);
            command.setTabCompleter(restrictionsCommand);
        } else {
            getLogger().severe("Command 'hexrestrictions' is missing from plugin.yml.");
        }

        schedulePlayerSweep();
        if (restrictions.settings().scanLoadedChunksOnEnable()) worldScanner.queueLoadedChunks();

        // Clean already-online players as well, which matters for /reload and plugin managers.
        Bukkit.getScheduler().runTask(this, () -> Bukkit.getOnlinePlayers().forEach(this::scanAndNotify));

        getLogger().info("HexRestrictions enabled. Forbidden items=" + restrictions.settings().forbiddenItems()
                + ", forbidden enchantments=" + restrictions.settings().forbiddenEnchantments());
    }

    @Override
    public void onDisable() {
        if (playerSweepTask != null) {
            playerSweepTask.cancel();
            playerSweepTask = null;
        }
        if (worldScanner != null) worldScanner.stop();
        getLogger().info("HexRestrictions disabled");
    }

    public void reloadPlugin() {
        reloadConfig();
        restrictions.updateSettings(RestrictionSettings.load(getConfig(), getLogger()));
        schedulePlayerSweep();
        if (restrictions.settings().scanLoadedChunksOnEnable()) worldScanner.queueLoadedChunks();
        Bukkit.getOnlinePlayers().forEach(this::scanAndNotify);
    }

    private void schedulePlayerSweep() {
        if (playerSweepTask != null) {
            playerSweepTask.cancel();
            playerSweepTask = null;
        }
        if (!restrictions.settings().periodicPlayerScan()) return;

        long interval = restrictions.settings().playerScanIntervalTicks();
        playerSweepTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!restrictions.isEnabled()) return;
            for (Player player : Bukkit.getOnlinePlayers()) {
                RestrictionAudit audit = restrictions.sanitizePlayer(player);
                if (audit.totalChanges() > 0 && restrictions.settings().logScanSummaries()) {
                    getLogger().info("Periodic player scan cleaned " + player.getName()
                            + ": items=" + audit.removedItems()
                            + ", enchantments=" + audit.removedEnchantments());
                }
            }
        }, interval, interval);
    }

    public void scanAndNotify(Player player) {
        RestrictionAudit audit = restrictions.sanitizePlayer(player);
        if (audit.totalChanges() <= 0) return;
        String template = getConfig().getString("messages.cleaned-player", "&eUsunięto niedozwolone elementy: &6%count%&e.");
        String count = Integer.toString(audit.removedItems() + audit.removedEnchantments());
        player.sendMessage(color(prefix() + template.replace("%count%", count)));
    }

    public void notifyBlockedItem(Player player) {
        String message = getConfig().getString("messages.blocked-item", "&cTen przedmiot jest wyłączony na tym serwerze.");
        player.sendMessage(color(prefix() + message));
    }

    public void notifyBlockedEnchantment(Player player) {
        String message = getConfig().getString("messages.blocked-enchantment", "&cTen enchant jest wyłączony na tym serwerze.");
        player.sendMessage(color(prefix() + message));
    }

    public void logBlocked(String message) {
        if (restrictions.settings().logBlockedActions()) getLogger().info(message);
    }

    private String prefix() {
        return getConfig().getString("messages.prefix", "&8[&6Hex&8] ");
    }

    @SuppressWarnings("deprecation")
    private String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }

    public RestrictionService restrictions() {
        return restrictions;
    }

    public WorldScanService worldScanner() {
        return worldScanner;
    }
}
