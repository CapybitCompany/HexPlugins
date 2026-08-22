package hexnpc.shop.sign;

import hexnpc.shop.config.ShopConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Obsługuje wprowadzanie „własnej ilości". Preferuje wirtualny edytor
 * tabliczki (przez {@link SignTransport}, bez zmiany realnego bloku), ale
 * <b>równolegle</b> akceptuje wpis z czatu — dzięki temu nawet gdy klient po
 * cichu nie otworzy edytora, gracz nadal może po prostu wpisać liczbę.
 *
 * <p>Odporność:
 * <ul>
 *   <li>Pending (z oczekiwaną pozycją) powstaje ZANIM wysłane zostaną pakiety,
 *       więc odpowiedź klienta nie może wyścignąć rejestracji.</li>
 *   <li>UPDATE_SIGN akceptujemy tylko od właściwego gracza, w trybie sign i dla
 *       dokładnie oczekiwanej pozycji.</li>
 *   <li>Krótki failover wysyła po kilku sekundach polską podpowiedź o czacie,
 *       gdy edytor prawdopodobnie się nie otworzył.</li>
 *   <li>Callback i sprzątanie wykonują się co najwyżej raz (sign/czat/timeout/
 *       wylogowanie/reload/disable).</li>
 *   <li>Realny stan bloku po stronie klienta jest przywracany przy każdym
 *       zakończeniu.</li>
 * </ul>
 * Wszystkie operacje Bukkit wykonują się na wątku głównym.
 */
public final class SignInputService implements Listener, SignInputSink {

    private final Plugin plugin;
    private final Supplier<ShopConfig> config;
    private final SignTransport transport;

    private final Map<UUID, Pending> pendings = new ConcurrentHashMap<>();

    public SignInputService(Plugin plugin, Supplier<ShopConfig> config, SignTransport transport) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.transport = transport == null ? SignTransport.unavailable() : transport;
    }

    /** Kompatybilny konstruktor — transport niedostępny (tylko czat). */
    public SignInputService(Plugin plugin, Supplier<ShopConfig> config) {
        this(plugin, config, SignTransport.unavailable());
    }

    /** Czy wirtualny sign jest w ogóle dostępny (transport gotowy). */
    public boolean signAvailable() {
        try {
            return transport.isAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Rozpoczyna oczekiwanie na ilość. {@code promptSender} dostaje informację,
     * czy sign faktycznie wysłano; {@code onFailoverPrompt} jest wołane po
     * krótkim czasie (podpowiedź o czacie); {@code onInput} dostaje surowy
     * tekst; {@code onTimeout} — po twardym timeoutcie. Wołać z wątku głównego.
     */
    public void request(Player player, Consumer<Boolean> promptSender, Runnable onFailoverPrompt,
                        Consumer<String> onInput, Runnable onTimeout) {
        UUID uuid = player.getUniqueId();
        cancel(uuid); // porzuć ewentualne wcześniejsze oczekiwanie

        ShopConfig cfg = config.get();
        int timeoutSeconds = cfg == null ? 30 : cfg.signTimeoutSeconds();
        int failoverSeconds = cfg == null ? 4 : cfg.signFailoverSeconds();
        boolean useSign = cfg != null && cfg.signEnabled() && signAvailable();

        // Bezpieczna, deterministyczna, czysto kliencka pozycja: NIE blok gracza,
        // lecz 2 bloki nad jego blokiem (w zasięgu, w załadowanym chunku, w
        // granicach świata). Przy suficie mapy schodzimy 2 bloki poniżej.
        Location loc = player.getLocation();
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int y = virtualY(world, loc.getBlockY());

        // Pending powstaje (z oczekiwaną pozycją) PRZED wysłaniem pakietów —
        // nawet natychmiastowa odpowiedź klienta nie wyścignie rejestracji.
        Pending pending = new Pending(uuid, x, y, z, onInput, onTimeout, onFailoverPrompt);
        // Optymistycznie na czas synchronicznego wysyłania (gdyby transport
        // oddzwonił natychmiast); finalnie ustawiane wg wyniku 3 pakietów.
        pending.signMode = useSign;
        pending.fakeBlockSent = useSign;
        pendings.put(uuid, pending);

        if (useSign) {
            SignTransport.OpenResult result;
            try {
                result = transport.openEditor(player, x, y, z);
            } catch (Throwable t) {
                result = SignTransport.OpenResult.none("wyjątek transportu");
            }
            // Ghost-blok istnieje, gdy poszedł choćby BlockChange. Tryb sign jest
            // aktywny WYŁĄCZNIE po pełnym sukcesie (wszystkie 3 pakiety).
            pending.fakeBlockSent = result.fakeBlockSent();
            pending.signMode = result.opened();
            if (pending.fakeBlockSent && !pending.signMode) {
                // Częściowy błąd: fałszywy blok poszedł, ale edytora nie ma —
                // natychmiast przywracamy realny blok (żaden ghost nie zostaje).
                revert(pending);
                pending.fakeBlockSent = false;
            }
        }

        if (promptSender != null) {
            promptSender.accept(pending.signMode);
        }

        // Krótki failover: jeśli edytor mógł się nie otworzyć, po kilku sekundach
        // przypomnij o możliwości wpisania na czacie (nie kończy sesji).
        if (pending.signMode && onFailoverPrompt != null) {
            pending.failoverTask = plugin.getServer().getScheduler().runTaskLater(
                    plugin, () -> {
                        if (!pending.done.get() && pendings.get(uuid) == pending
                                && pending.onFailoverPrompt != null) {
                            pending.onFailoverPrompt.run();
                        }
                    }, Math.max(1L, failoverSeconds) * 20L);
        }

        pending.timeoutTask = plugin.getServer().getScheduler().runTaskLater(
                plugin, () -> timeout(pending), Math.max(1L, timeoutSeconds) * 20L);
    }

    /**
     * Deterministyczna, bezpieczna pozycja wirtualnej tabliczki: 2 bloki nad
     * blokiem gracza; przy suficie mapy 2 poniżej; zawsze w granicach świata.
     * Nigdy nie jest to blok stóp gracza.
     */
    static int virtualY(World world, int feetY) {
        int candidate = feetY + 2;
        if (world == null) {
            return candidate;
        }
        int min = world.getMinHeight() + 1;
        int max = world.getMaxHeight() - 1;
        if (candidate > max) {
            candidate = feetY - 2;
        }
        return Math.max(min, Math.min(max, candidate));
    }

    /** Anuluje oczekiwanie gracza bez wołania onInput/onTimeout. */
    public void cancel(UUID uuid) {
        Pending pending = pendings.remove(uuid);
        if (pending != null && pending.done.compareAndSet(false, true)) {
            cancelTasks(pending);
            if (pending.fakeBlockSent) {
                runMain(() -> revert(pending));
            }
        }
    }

    /** Anuluje wszystkie oczekiwania (disable/reload). */
    public void cancelAll() {
        for (UUID uuid : Map.copyOf(pendings).keySet()) {
            cancel(uuid);
        }
        pendings.clear();
    }

    // --- Wejście z wirtualnej tabliczki (wątek netty) ---
    @Override
    public boolean onSignUpdate(UUID uuid, int x, int y, int z, String[] lines) {
        Pending pending = pendings.get(uuid);
        if (pending == null || !pending.signMode) {
            return false;
        }
        // Akceptujemy wyłącznie dokładnie oczekiwaną pozycję.
        if (x != pending.expX || y != pending.expY || z != pending.expZ) {
            return false;
        }
        complete(pending, firstNonBlank(lines));
        // To była nasza wirtualna tabliczka — pakiet zostanie anulowany.
        return true;
    }

    // --- Fallback czatu (wątek asynchroniczny) ---
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Pending pending = pendings.get(event.getPlayer().getUniqueId());
        if (pending == null) {
            return;
        }
        // Czat jest zawsze akceptowany jako niezawodna ścieżka wejścia.
        event.setCancelled(true);
        complete(pending, event.getMessage());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId());
    }

    private void complete(Pending pending, String raw) {
        if (!pending.done.compareAndSet(false, true)) {
            return;
        }
        pendings.remove(pending.uuid, pending);
        cancelTasks(pending);
        runMain(() -> {
            if (pending.fakeBlockSent) {
                revert(pending);
            }
            if (pending.onInput != null) {
                pending.onInput.accept(raw);
            }
        });
    }

    private void timeout(Pending pending) {
        if (!pending.done.compareAndSet(false, true)) {
            return;
        }
        pendings.remove(pending.uuid, pending);
        cancelTasks(pending);
        // Jesteśmy już na wątku głównym (runTaskLater).
        if (pending.fakeBlockSent) {
            revert(pending);
        }
        if (pending.onTimeout != null) {
            pending.onTimeout.run();
        }
    }

    private void revert(Pending pending) {
        Player player = Bukkit.getPlayer(pending.uuid);
        if (player == null || !player.isOnline()) {
            return;
        }
        try {
            transport.restore(player, pending.expX, pending.expY, pending.expZ);
        } catch (Throwable ignored) {
            // Rewert to tylko kosmetyka po stronie klienta.
        }
    }

    private void cancelTasks(Pending pending) {
        cancelTask(pending.timeoutTask);
        cancelTask(pending.failoverTask);
    }

    private void cancelTask(BukkitTask task) {
        if (task != null) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
            }
        }
    }

    private void runMain(Runnable runnable) {
        if (plugin.getServer().isPrimaryThread()) {
            runnable.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, runnable);
        }
    }

    static String firstNonBlank(String[] lines) {
        if (lines == null) {
            return "";
        }
        for (String line : lines) {
            if (line != null && !line.trim().isEmpty()) {
                return line.trim();
            }
        }
        return "";
    }

    private static final class Pending {
        final UUID uuid;
        final int expX;
        final int expY;
        final int expZ;
        final Consumer<String> onInput;
        final Runnable onTimeout;
        final Runnable onFailoverPrompt;
        final AtomicBoolean done = new AtomicBoolean(false);
        volatile boolean signMode;      // edytor w pełni zażądany (3 pakiety)
        volatile boolean fakeBlockSent; // istnieje ghost-blok do przywrócenia
        volatile BukkitTask timeoutTask;
        volatile BukkitTask failoverTask;

        Pending(UUID uuid, int expX, int expY, int expZ,
                Consumer<String> onInput, Runnable onTimeout, Runnable onFailoverPrompt) {
            this.uuid = uuid;
            this.expX = expX;
            this.expY = expY;
            this.expZ = expZ;
            this.onInput = onInput;
            this.onTimeout = onTimeout;
            this.onFailoverPrompt = onFailoverPrompt;
        }
    }
}
