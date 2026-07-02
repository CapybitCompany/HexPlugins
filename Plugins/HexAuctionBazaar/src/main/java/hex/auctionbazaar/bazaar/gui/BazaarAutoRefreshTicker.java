package hex.auctionbazaar.bazaar.gui;

import hex.auctionbazaar.config.BazaarConfig;
import hex.auctionbazaar.gui.GuiHolder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Jeden wspoldzielony Bukkit repeating task dla auto-refresh dowolnego
 * Bazaar-GUI. Kazdy widok rejestruje sie z callbackiem "odswiez" oraz
 * predykatem "GUI wciaz otwarte". Ticker uruchamia sie tylko gdy jakikolwiek
 * widok jest aktywny; nikt nie odpytuje bazy przy kazdym ticku.
 *
 * Semantyka:
 *  - Kazdy widok ma unikalny UUID sesji (nie player.uuid, zeby otwarcie
 *    nowego GUI przez tego samego gracza nie zakleszczylo poprzedniego
 *    refresh-a).
 *  - Konfiguracja interval-tickow i wlaczenia jest re-czytana kazdy sweep;
 *    zmiana w /reload propaguje sie na aktywne widoki.
 *  - Sesje sa czyszczone przy inventory close (predykat isOpen zwraca false)
 *    lub przy stop() (plugin disable).
 */
public final class BazaarAutoRefreshTicker {

    private final Plugin plugin;
    private final Supplier<BazaarConfig> configSupplier;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private BukkitTask task;
    private int currentInterval = -1;

    public BazaarAutoRefreshTicker(Plugin plugin, Supplier<BazaarConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    /**
     * Zarejestruj widok do auto-odświeżania. Zwraca UUID sesji, ktore powinien
     * zostac uzyty przy inventory close, aby zakonczyc odswiezanie.
     */
    public synchronized UUID register(Player viewer, GuiHolder.Kind kind, Runnable refresh) {
        BazaarConfig cfg = configSupplier.get();
        if (!cfg.autoRefreshEnabled()) return null;
        UUID sessionId = UUID.randomUUID();
        sessions.put(sessionId, new Session(sessionId, viewer.getUniqueId(), kind, refresh));
        ensureTaskRunning(cfg.autoRefreshIntervalTicks());
        return sessionId;
    }

    public synchronized void unregister(UUID sessionId) {
        if (sessionId == null) return;
        sessions.remove(sessionId);
        if (sessions.isEmpty() && task != null) {
            task.cancel();
            task = null;
            currentInterval = -1;
        }
    }

    public synchronized void start() {
        BazaarConfig cfg = configSupplier.get();
        if (!cfg.autoRefreshEnabled()) return;
        // Ticker startuje na zadanie register(); tu jest tylko lifecycle-init.
    }

    public synchronized void stop() {
        sessions.clear();
        if (task != null) {
            task.cancel();
            task = null;
            currentInterval = -1;
        }
    }

    private void ensureTaskRunning(int intervalTicks) {
        int wanted = Math.max(20, intervalTicks);
        if (task != null && currentInterval == wanted) return;
        if (task != null) task.cancel();
        currentInterval = wanted;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, wanted, wanted);
    }

    private synchronized void sweep() {
        // Usun sesje ktorych GUI juz nie jest otwarte.
        Iterator<Map.Entry<UUID, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Session s = it.next().getValue();
            Player p = Bukkit.getPlayer(s.playerId);
            if (p == null || !p.isOnline()) {
                it.remove();
                continue;
            }
            InventoryHolder holder = p.getOpenInventory().getTopInventory().getHolder();
            if (!(holder instanceof GuiHolder gh) || gh.kind() != s.kind) {
                it.remove();
                continue;
            }
            try {
                s.refresh.run();
            } catch (Throwable t) {
                plugin.getLogger().warning("auto-refresh callback failed: " + t.getMessage());
                it.remove();
            }
        }
        if (sessions.isEmpty()) {
            if (task != null) task.cancel();
            task = null;
            currentInterval = -1;
        }
    }

    private record Session(UUID sessionId, UUID playerId, GuiHolder.Kind kind, Runnable refresh) {}

    /**
     * Prosty helper do "attach auto-refresh do dowolnej sesji GUI".
     * Zwraca akcje ktora widoki wywolaja przy inventory-close.
     */
    public Consumer<Object> attach(Player viewer, GuiHolder.Kind kind, Runnable refresh) {
        UUID id = register(viewer, kind, refresh);
        return ignored -> unregister(id);
    }
}
