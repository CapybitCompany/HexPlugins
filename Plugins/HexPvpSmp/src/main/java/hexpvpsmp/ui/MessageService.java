package hexpvpsmp.ui;

import hexpvpsmp.config.HexPvpConfig;
import hexpvpsmp.util.LegacyFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Per-player rate-limited dispatch of chat / actionbar messages.
 * The cooldown comes from {@code safezones.warning-cooldown-ticks}.
 */
public final class MessageService {

    private final Server server;
    private final Supplier<HexPvpConfig> configSupplier;
    private final Map<UUID, Long> lastChatTick = new HashMap<>();
    private final Map<UUID, Long> lastActionBarTick = new HashMap<>();

    public MessageService(Server server, Supplier<HexPvpConfig> configSupplier) {
        this.server = Objects.requireNonNull(server, "server");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    public void sendChat(Player player, String legacyText) {
        if (player == null || legacyText == null || legacyText.isEmpty()) {
            return;
        }
        if (!shouldSend(player.getUniqueId(), lastChatTick)) {
            return;
        }
        player.sendMessage(LegacyFormat.component(legacyText));
    }

    public void sendActionBar(Player player, String legacyText) {
        if (player == null || legacyText == null || legacyText.isEmpty()) {
            return;
        }
        if (!shouldSend(player.getUniqueId(), lastActionBarTick)) {
            return;
        }
        Component c = LegacyFormat.component(legacyText);
        player.sendActionBar(c);
    }

    /** Unconditional actionbar — used by the combat-tag tick task. */
    public void sendActionBarUnthrottled(Player player, String legacyText) {
        if (player == null || legacyText == null || legacyText.isEmpty()) {
            return;
        }
        player.sendActionBar(LegacyFormat.component(legacyText));
    }

    private static final Title.Times TITLE_TIMES = Title.Times.times(
            Duration.ofMillis(250), Duration.ofMillis(2000), Duration.ofMillis(500));

    /**
     * Shows a title + subtitle. Either part may be empty. Used for safezone
     * entry (title + subtitle) and exit (subtitle-focused) info — never a
     * bossbar. Unthrottled; callers apply their own transition cooldown.
     */
    public void showTitle(Player player, String titleText, String subtitleText) {
        if (player == null) {
            return;
        }
        Component title = titleText == null ? Component.empty() : LegacyFormat.component(titleText);
        Component subtitle = subtitleText == null ? Component.empty() : LegacyFormat.component(subtitleText);
        player.showTitle(Title.title(title, subtitle, TITLE_TIMES));
    }

    public void broadcast(String legacyText) {
        if (legacyText == null || legacyText.isEmpty()) {
            return;
        }
        server.broadcast(LegacyFormat.component(legacyText));
    }

    public void clearCooldowns(UUID playerId) {
        lastChatTick.remove(playerId);
        lastActionBarTick.remove(playerId);
    }

    private boolean shouldSend(UUID playerId, Map<UUID, Long> bucket) {
        long now = server.getCurrentTick();
        int cooldown = configSupplier.get().safezones().warningCooldownTicks();
        Long last = bucket.get(playerId);
        if (last != null && (now - last) < cooldown) {
            return false;
        }
        bucket.put(playerId, now);
        return true;
    }
}
