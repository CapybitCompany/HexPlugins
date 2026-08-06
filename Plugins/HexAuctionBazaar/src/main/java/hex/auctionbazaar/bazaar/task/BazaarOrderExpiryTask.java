package hex.auctionbazaar.bazaar.task;

import hex.auctionbazaar.bazaar.service.BazaarOrderService;
import hex.auctionbazaar.config.BazaarConfig;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Zadanie periodyczne wygasajace zlecenia Bazaru.
 * Uruchamia sie co bazaar.order-expiry-scan-interval-ticks tickow i wywoluje
 * BazaarOrderService.expireDueOrders. Jesli order-expiry-seconds = 0 zadanie
 * NIE jest uruchamiane (zlecenia sa wieczne).
 */
public final class BazaarOrderExpiryTask {

    private final Plugin plugin;
    private final BazaarOrderService service;
    private final Supplier<BazaarConfig> cfg;
    private BukkitTask task;

    public BazaarOrderExpiryTask(Plugin plugin, BazaarOrderService service,
                                  Supplier<BazaarConfig> cfg) {
        this.plugin = plugin;
        this.service = service;
        this.cfg = cfg;
    }

    public void start() {
        stop();
        BazaarConfig c = cfg.get();
        if (c.orderExpirySeconds() <= 0) {
            plugin.getLogger().info("Wygasanie zleceń Rynku jest wyłączone (order-expiry-seconds=0).");
            return;
        }
        int interval = Math.max(200, c.orderExpiryScanIntervalTicks());
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sweep, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void sweep() {
        service.expireDueOrders(200).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Skanowanie wygasłych zleceń Rynku nie powiodło się", ex);
            return 0;
        });
    }
}
