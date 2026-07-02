package hex.auctionbazaar.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Fallback dla {@link SignPrompt}. Uzywany gdy sign trick zawiedzie
 * (WorldGuard blokuje setBlock, klient nie obsluguje sign editora, itp).
 *
 * Interakcja:
 *  - do gracza wysyla prompt oraz linijke "wpisz na czacie ...".
 *  - Nastepna wiadomosc gracza jest przechwycona (AsyncChatEvent), NIE trafia
 *    do publicznego czatu (event.setCancelled(true)).
 *  - 30s timeout -> callback dostaje null.
 */
public final class ChatPromptFallbackImpl implements SignPrompt.ChatPromptFallback, Listener {

    private static final int TIMEOUT_TICKS = 30 * 20;

    private final Plugin plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public ChatPromptFallbackImpl(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void shutdown() {
        for (UUID id : sessions.keySet()) {
            Session s = sessions.remove(id);
            if (s != null && s.timeoutTask != null) s.timeoutTask.cancel();
        }
        HandlerList.unregisterAll(this);
    }

    @Override
    public void promptString(Player player, String prompt, Consumer<String> callback) {
        UUID uid = player.getUniqueId();
        Session existing = sessions.remove(uid);
        if (existing != null && existing.timeoutTask != null) existing.timeoutTask.cancel();

        // Informujemy gracza. Chat jest deliberately unformatted zeby dzialal
        // niezaleznie od messages.yml (fallback path).
        player.sendMessage(net.kyori.adventure.text.Component.text("§eWpisz na czacie: " + prompt));
        player.sendMessage(net.kyori.adventure.text.Component.text("§7(Wpisz 'anuluj' aby przerwac; timeout 30s)"));

        Session session = new Session(uid, callback);
        sessions.put(uid, session);
        session.timeoutTask = new BukkitRunnable() {
            @Override
            public void run() {
                Session current = sessions.get(uid);
                if (current == session) {
                    sessions.remove(uid);
                    Bukkit.getScheduler().runTask(plugin, () -> callback.accept(null));
                }
            }
        }.runTaskLater(plugin, TIMEOUT_TICKS);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s == null) return;
        event.setCancelled(true);
        String plain = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        sessions.remove(event.getPlayer().getUniqueId());
        if (s.timeoutTask != null) s.timeoutTask.cancel();
        String reply = plain.equalsIgnoreCase("anuluj") || plain.equalsIgnoreCase("cancel")
                ? null : plain;
        Bukkit.getScheduler().runTask(plugin, () -> s.callback.accept(reply));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session s = sessions.remove(event.getPlayer().getUniqueId());
        if (s != null && s.timeoutTask != null) s.timeoutTask.cancel();
    }

    private static final class Session {
        final UUID playerId;
        final Consumer<String> callback;
        BukkitTask timeoutTask;

        Session(UUID uid, Consumer<String> cb) {
            this.playerId = uid;
            this.callback = cb;
        }
    }
}
