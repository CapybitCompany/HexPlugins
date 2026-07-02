package hex.auctionbazaar.gui;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bezpieczny prompt dla wartosci liczbowej z uzyciem znaku (Sign).
 *
 * Mechanizm: podmieniamy tymczasowo blok w void-ie (Y=minY+1) na debowy znak,
 * otwieramy jego edytor, a po odebraniu {@link SignChangeEvent} przywracamy
 * oryginalny blok i wywolujemy callback z tekstem wpisanym przez gracza.
 *
 * Semantyka bezpieczenstwa:
 *  - jeden aktywny prompt na gracza (kolejny zastepuje poprzedni, przywracajac blok),
 *  - 30s timeout automatycznie przywraca blok i konczy sesje (callback dostaje null),
 *  - PlayerQuitEvent konczy sesje (blok wciaz przywracany),
 *  - wywolanie shutdown() konczy wszystkie sesje i wyrejestrowuje listener.
 *
 * W razie niepowodzenia (np. przez WorldGuard blokujacy setBlock lub brak
 * Sign state) prompt fallbackuje do prostego chat-prompt-a z ta sama semantyka.
 */
public final class SignPrompt implements Listener {

    private static final int TIMEOUT_TICKS = 30 * 20;

    private final Plugin plugin;
    private final Logger logger;
    private final ChatPromptFallback chatFallback;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public SignPrompt(Plugin plugin, ChatPromptFallback chatFallback) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = plugin.getLogger();
        this.chatFallback = Objects.requireNonNull(chatFallback, "chatFallback");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void shutdown() {
        for (UUID id : sessions.keySet()) {
            endSession(id);
        }
        HandlerList.unregisterAll(this);
    }

    /**
     * Otworz sesje znaku dla gracza. Callback dostaje wpisany tekst
     * (przycięty do 32 znakow) lub null gdy anulowano/timeout.
     */
    public void promptString(Player player, String prompt, Consumer<String> callback) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(callback, "callback");
        UUID uid = player.getUniqueId();
        endSession(uid);
        player.closeInventory();

        Location loc = pickHiddenLocation(player);
        Block block = loc.getBlock();
        Material orig = block.getType();
        BlockData origData = block.getBlockData();
        boolean signOpened = false;
        try {
            block.setType(Material.OAK_SIGN, false);
            BlockState state = block.getState();
            if (!(state instanceof Sign sign)) {
                throw new IllegalStateException("expected Sign block state, got " + state.getClass());
            }
            sign.line(0, net.kyori.adventure.text.Component.text(safeTrim(prompt == null ? "" : prompt, 15)));
            sign.line(1, net.kyori.adventure.text.Component.text(""));
            sign.line(2, net.kyori.adventure.text.Component.text("^^^^^^^^^^^^^^^^"));
            sign.line(3, net.kyori.adventure.text.Component.text("----------------"));
            sign.update(true, false);
            player.openSign(sign, Side.FRONT);
            signOpened = true;
        } catch (Throwable t) {
            logger.log(Level.WARNING,
                    "SignPrompt: sign trick failed for " + player.getName()
                            + " - falling back to chat: " + t.getMessage());
            try {
                block.setType(orig, false);
                block.setBlockData(origData, false);
            } catch (Throwable ignored) {
            }
        }
        if (!signOpened) {
            chatFallback.promptString(player, prompt, callback);
            return;
        }

        Session session = new Session(uid, loc, orig, origData, callback);
        sessions.put(uid, session);
        session.timeoutTask = new BukkitRunnable() {
            @Override
            public void run() {
                Session current = sessions.get(uid);
                if (current == session) {
                    endSession(uid);
                    Bukkit.getScheduler().runTask(plugin, () -> callback.accept(null));
                }
            }
        }.runTaskLater(plugin, TIMEOUT_TICKS);
    }

    /**
     * Wariant konwertujacy wpisana liczbe (BigDecimal). Callback dostaje null gdy
     * gracz anulowal, timeout, albo wpisal nie-numer.
     */
    public void promptNumber(Player player, String prompt, Consumer<java.math.BigDecimal> callback) {
        promptString(player, prompt, raw -> {
            if (raw == null || raw.isBlank()) {
                callback.accept(null);
                return;
            }
            try {
                java.math.BigDecimal v = new java.math.BigDecimal(raw.trim().replace(",", "."));
                callback.accept(v);
            } catch (NumberFormatException ex) {
                callback.accept(null);
            }
        });
    }

    /**
     * Wariant konwertujacy do calkowitej liczby dodatniej (Long).
     * Callback dostaje null gdy anulowano / nie-numer / <= 0.
     */
    public void promptLong(Player player, String prompt, Consumer<Long> callback) {
        promptString(player, prompt, raw -> {
            if (raw == null || raw.isBlank()) {
                callback.accept(null);
                return;
            }
            try {
                long v = Long.parseLong(raw.trim());
                callback.accept(v > 0 ? v : null);
            } catch (NumberFormatException ex) {
                callback.accept(null);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onSignChange(SignChangeEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s == null) return;
        Location eventLoc = event.getBlock().getLocation();
        if (!eventLoc.equals(s.location)) return;
        event.setCancelled(true);
        String line0 = event.getLine(0);
        String line1 = event.getLine(1);
        String input = pickPlayerInput(line0, line1);
        endSession(event.getPlayer().getUniqueId());
        String finalInput = input;
        Bukkit.getScheduler().runTask(plugin, () -> s.callback.accept(finalInput));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session s = sessions.remove(event.getPlayer().getUniqueId());
        if (s != null) {
            cancelSessionInternal(s);
        }
    }

    private String pickPlayerInput(String line0, String line1) {
        String l1 = line1 == null ? "" : line1.trim();
        if (!l1.isEmpty()) return l1;
        String l0 = line0 == null ? "" : line0.trim();
        return l0.isEmpty() ? "" : l0;
    }

    private void endSession(UUID uid) {
        Session s = sessions.remove(uid);
        if (s == null) return;
        cancelSessionInternal(s);
    }

    private void cancelSessionInternal(Session s) {
        if (s.timeoutTask != null) {
            try {
                s.timeoutTask.cancel();
            } catch (Throwable ignored) {
            }
        }
        Block block = s.location.getBlock();
        try {
            block.setType(s.originalMaterial, false);
            block.setBlockData(s.originalData, false);
        } catch (Throwable t) {
            logger.log(Level.WARNING,
                    "SignPrompt: could not restore block at " + s.location, t);
        }
    }

    private Location pickHiddenLocation(Player p) {
        World w = p.getWorld();
        int minY = w.getMinHeight();
        return new Location(w, p.getLocation().getBlockX(), minY + 1, p.getLocation().getBlockZ());
    }

    private String safeTrim(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    /**
     * Fallback chat-based prompt, uzywany gdy sign trick zawiedzie.
     */
    public interface ChatPromptFallback {
        void promptString(Player player, String prompt, Consumer<String> callback);
    }

    private static final class Session {
        final UUID playerId;
        final Location location;
        final Material originalMaterial;
        final BlockData originalData;
        final Consumer<String> callback;
        BukkitTask timeoutTask;

        Session(UUID playerId, Location loc, Material orig, BlockData origData, Consumer<String> cb) {
            this.playerId = playerId;
            this.location = loc;
            this.originalMaterial = orig;
            this.originalData = origData;
            this.callback = cb;
        }
    }
}
