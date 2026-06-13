package hex.auctionbazaar.auction.task;

import hex.auctionbazaar.auction.service.AuctionService;
import hex.auctionbazaar.config.AuctionConfig;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.function.Supplier;
import java.util.logging.Level;

public final class AuctionExpiryTask {

    private final Plugin plugin;
    private final AuctionService service;
    private final Supplier<AuctionConfig> cfg;
    private BukkitTask task;

    public AuctionExpiryTask(Plugin plugin, AuctionService service, Supplier<AuctionConfig> cfg) {
        this.plugin = plugin;
        this.service = service;
        this.cfg = cfg;
    }

    public void start() {
        stop();
        int interval = Math.max(20, cfg.get().expiryScanIntervalTicks());
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sweep, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void sweep() {
        service.expireDueListings(500).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "expiry sweep failed", ex);
            return 0;
        });
    }
}
