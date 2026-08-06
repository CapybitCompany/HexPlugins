package hex.auctionbazaar.bazaar.gui;

import hex.auctionbazaar.config.BazaarConfig;
import hex.auctionbazaar.gui.GuiHolder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Jeden wspólny task auto-odświeżania GUI Bazaru, aktualizujący ceny/stany
 * W MIEJSCU - bez ponownego otwierania inventory (punkt #4).
 *
 * Kluczowe własności:
 *  - MAKSYMALNIE JEDNA sesja na gracza (kolejny register zastępuje poprzednią,
 *    więc wielokrotne otwarcia/odświeżenia nie mnożą sesji ani timerów),
 *  - tożsamość sesji sprawdzana przez konkretny {@link Inventory} i
 *    {@link GuiHolder} (nie tylko {@link GuiHolder.Kind}),
 *  - snapshot pobierany asynchronicznie, a zapis do inventory wyłącznie na
 *    wątku głównym i tylko gdy sesja jest wciąż aktualna (odrzucamy veraltete),
 *  - zamknięcie / wejście w inne GUI / reload / disable usuwają sesje.
 */
public final class BazaarAutoRefreshTicker implements Listener {

    /** Aktualizacja w miejscu; woła handle.runMain() z pobranym snapshotem i sam sprawdza aktualność. */
    @FunctionalInterface
    public interface InPlaceUpdater {
        void update(RefreshHandle handle);
    }

    /** Uchwyt przekazywany do updatera - pozwala sprawdzić aktualność i wejść na wątek główny. */
    public interface RefreshHandle {
        boolean isCurrent();

        void runMain(Runnable task);
    }

    private final Plugin plugin;
    private final Supplier<BazaarConfig> configSupplier;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private BukkitTask task;
    private int currentInterval = -1;
    private boolean listenerRegistered = false;

    public BazaarAutoRefreshTicker(Plugin plugin, Supplier<BazaarConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    /**
     * Zarejestruj widok do auto-odświeżania w miejscu. Zwraca id sesji lub null,
     * gdy auto-refresh jest wyłączony (wtedy działa tylko manualny przycisk).
     * Zastępuje ewentualną wcześniejszą sesję tego samego gracza.
     */
    public synchronized UUID register(Player viewer, GuiHolder holder, Inventory inventory,
                                      InPlaceUpdater updater) {
        BazaarConfig cfg = configSupplier.get();
        if (!cfg.autoRefreshEnabled()) {
            sessions.remove(viewer.getUniqueId());
            return null;
        }
        UUID sessionId = UUID.randomUUID();
        sessions.put(viewer.getUniqueId(),
                new Session(sessionId, viewer.getUniqueId(), holder, inventory, updater,
                        new java.util.concurrent.atomic.AtomicLong(0L)));
        ensureTaskRunning(cfg.autoRefreshIntervalTicks());
        return sessionId;
    }

    public synchronized void unregisterPlayer(UUID playerId) {
        if (playerId == null) return;
        sessions.remove(playerId);
        if (sessions.isEmpty()) {
            cancelTask();
        }
    }

    public synchronized void start() {
        if (!listenerRegistered && plugin.getServer() != null) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            listenerRegistered = true;
        }
    }

    public synchronized void stop() {
        sessions.clear();
        cancelTask();
        if (listenerRegistered) {
            HandlerList.unregisterAll(this);
            listenerRegistered = false;
        }
    }

    /** Reload: usuwamy wszystkie sesje (odbudują się przy ponownym otwarciu GUI). */
    public synchronized void onReload() {
        sessions.clear();
        cancelTask();
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        unregisterPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        unregisterPlayer(event.getPlayer().getUniqueId());
    }

    private void ensureTaskRunning(int intervalTicks) {
        int wanted = Math.max(20, intervalTicks);
        if (task != null && currentInterval == wanted) return;
        cancelTask();
        currentInterval = wanted;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, wanted, wanted);
    }

    private void cancelTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        currentInterval = -1;
    }

    /** Sweep wywoływany co interwał na wątku głównym. Pakietowo-widoczne do testów. */
    synchronized void sweep() {
        Iterator<Map.Entry<UUID, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Session s = it.next().getValue();
            if (!isViewOpen(s)) {
                it.remove();
                continue;
            }
            try {
                // Nowe żądanie = nowa generacja; tylko jego odpowiedź może zaktualizować widok.
                long g = s.gen().incrementAndGet();
                s.updater().update(new Handle(s, g));
            } catch (Throwable t) {
                plugin.getLogger().warning("Odświeżenie Rynku w otwartym oknie nie powiodło się: "
                        + t.getMessage());
                it.remove();
            }
        }
        if (sessions.isEmpty()) {
            cancelTask();
        }
    }

    /** Sesja aktualna tylko gdy wciąż zmapowana i to jej inventory/holder jest otwarte. */
    private synchronized boolean isCurrent(Session s) {
        return sessions.get(s.playerId) == s && isViewOpen(s);
    }

    private boolean isViewOpen(Session s) {
        Player p = Bukkit.getPlayer(s.playerId);
        if (p == null || !p.isOnline()) return false;
        Inventory top = p.getOpenInventory().getTopInventory();
        return top == s.inventory && top.getHolder() == s.holder;
    }

    private void runOnMain(Runnable r) {
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, r);
        }
    }

    int sessionCount() {
        return sessions.size();
    }

    private record Session(UUID sessionId, UUID playerId, GuiHolder holder, Inventory inventory,
                           InPlaceUpdater updater, java.util.concurrent.atomic.AtomicLong gen) {
    }

    private final class Handle implements RefreshHandle {
        private final Session session;
        private final long requestGen;   // generacja tego konkretnego żądania

        Handle(Session session, long requestGen) {
            this.session = session;
            this.requestGen = requestGen;
        }

        @Override
        public boolean isCurrent() {
            // Aktualne tylko gdy sesja jest bieżąca I to NAJNOWSZE żądanie (out-of-order guard):
            // starsza odpowiedź DB, która wróci po nowszej, nie nadpisze widoku.
            return session.gen().get() == requestGen && BazaarAutoRefreshTicker.this.isCurrent(session);
        }

        @Override
        public void runMain(Runnable task) {
            runOnMain(task);
        }
    }
}
