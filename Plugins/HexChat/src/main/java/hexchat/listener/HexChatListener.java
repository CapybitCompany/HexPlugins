package hexchat.listener;

import hexchat.config.HexChatConfig;
import hexchat.mute.MuteEntry;
import hexchat.permission.HexChatPermissions;
import hexchat.service.ChatConflictGuard;
import hexchat.service.ChatContentFilterService;
import hexchat.service.ChatCooldownService;
import hexchat.service.ChatFormatService;
import hexchat.service.GlobalChatMuteService;
import hexchat.service.HexChatMessageService;
import hexchat.service.PlayerMuteService;
import hexchat.util.DurationUtil;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.Optional;

public final class HexChatListener implements Listener {

    private final JavaPlugin plugin;
    private final ChatFormatService chatFormatService;
    private final ChatCooldownService cooldownService;
    private final GlobalChatMuteService globalChatMuteService;
    private final PlayerMuteService playerMuteService;
    private final ChatContentFilterService contentFilterService;
    private final ChatConflictGuard conflictGuard;
    private final HexChatMessageService messageService;

    public HexChatListener(
            JavaPlugin plugin,
            ChatFormatService chatFormatService,
            ChatCooldownService cooldownService,
            GlobalChatMuteService globalChatMuteService,
            PlayerMuteService playerMuteService,
            ChatContentFilterService contentFilterService,
            ChatConflictGuard conflictGuard,
            HexChatMessageService messageService
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.chatFormatService = Objects.requireNonNull(chatFormatService, "chatFormatService");
        this.cooldownService = Objects.requireNonNull(cooldownService, "cooldownService");
        this.globalChatMuteService = Objects.requireNonNull(globalChatMuteService, "globalChatMuteService");
        this.playerMuteService = Objects.requireNonNull(playerMuteService, "playerMuteService");
        this.contentFilterService = Objects.requireNonNull(contentFilterService, "contentFilterService");
        this.conflictGuard = Objects.requireNonNull(conflictGuard, "conflictGuard");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
    }

    // Nasłuch na HIGH: HexChat rozstrzyga moderację i render po typowych pluginach czatu
    // (NORMAL), dzięki czemu jest autorytatywny. Nigdy nie modyfikujemy podpisanej treści
    // (event.message(...)) — tylko anulujemy lub podmieniamy render, co jest bezpieczne dla
    // podpisanego czatu 1.19+ i nie wywołuje "Chat Verification Error".
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();

        boolean bypassedCooldown = isCooldownBypassed(player);
        if (bypassedCooldown) {
            cooldownService.clear(player);
        }

        // 1) Indywidualne wyciszenie gracza.
        if (playerMuteService.isMutedFor(player)) {
            event.setCancelled(true);
            sendPrivateMuteMessage(event, player);
            return;
        }

        // 2) Globalne wyciszenie czatu.
        if (globalChatMuteService.isMutedFor(player)) {
            event.setCancelled(true);
            runMainThread(event, () -> messageService.sendChatMuted(player));
            return;
        }

        // 3) Filtr treści (reklama/blacklista/spam).
        Component censoredMessage = null;
        if (!isContentFilterBypassed(player)) {
            String plain = PlainTextComponentSerializer.plainText().serialize(event.message());
            ChatContentFilterService.InspectionResult result = contentFilterService.inspect(player, plain);
            switch (result.decision()) {
                case BLOCK -> {
                    event.setCancelled(true);
                    String message = result.blockMessage();
                    runMainThread(event, () -> messageService.sendContentBlocked(player, message));
                    return;
                }
                case CENSOR -> censoredMessage = Component.text(result.censoredText());
                case ALLOWED -> {
                    // brak działania
                }
            }
        }

        // 4) Cooldown.
        if (!bypassedCooldown) {
            ChatCooldownService.CooldownResult cooldownResult = cooldownService.checkAndApply(player);
            if (cooldownResult.blocked()) {
                event.setCancelled(true);
                long secondsLeft = cooldownResult.secondsLeft();
                runMainThread(event, () -> messageService.sendCooldownWait(player, secondsLeft));
                return;
            }
        }

        // 5) Render/wyświetlanie.
        applyRenderer(event, censoredMessage);
    }

    private void applyRenderer(AsyncChatEvent event, Component censoredMessage) {
        boolean applyOwnFormat = chatFormatService.isChatEnabled() && conflictGuard.shouldRenderFormat();
        ChatRenderer renderer = buildRenderer(
                applyOwnFormat,
                censoredMessage,
                event.renderer(),
                chatFormatService::render
        );
        if (renderer != null) {
            event.renderer(renderer);
        }
    }

    /**
     * Wyznacza renderer do ustawienia (lub {@code null}, gdy nie należy nic zmieniać).
     * <ul>
     *   <li>{@code applyOwnFormat} — HexChat formatuje sam (z ewentualną cenzurą).</li>
     *   <li>ustępujemy formatu, ale cenzura wymagana — opakowujemy istniejący renderer i
     *       podajemy mu tylko ocenzurowaną komponentę (format innego pluginu zachowany).</li>
     *   <li>ustępujemy formatu i brak cenzury — {@code null}: nie dotykamy renderera.</li>
     * </ul>
     * Nigdy nie modyfikuje podpisanej treści {@code event.message()}.
     */
    static ChatRenderer buildRenderer(
            boolean applyOwnFormat,
            Component censoredMessage,
            ChatRenderer existingRenderer,
            java.util.function.BiFunction<Component, Component, Component> formatFunction
    ) {
        if (applyOwnFormat) {
            return ChatRenderer.viewerUnaware((source, sourceDisplayName, message) ->
                    formatFunction.apply(sourceDisplayName, censoredMessage != null ? censoredMessage : message));
        }

        if (censoredMessage != null) {
            return (source, sourceDisplayName, message, viewer) ->
                    existingRenderer.render(source, sourceDisplayName, censoredMessage, viewer);
        }

        return null;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(PlayerQuitEvent event) {
        cooldownService.clear(event.getPlayer());
        contentFilterService.clearHistory(event.getPlayer().getUniqueId());
    }

    // Gracz wyciszony przy próbie pisania dostaje 'messages.private-muted'
    // (natychmiastowe powiadomienie przy nakładaniu mute'a ma osobny klucz w komendzie).
    private void sendPrivateMuteMessage(AsyncChatEvent event, Player player) {
        HexChatConfig config = chatFormatService.currentConfig();
        String permanentText = config.messages().muteTimePermanent();
        Optional<MuteEntry> mute = playerMuteService.activeMute(player.getUniqueId());
        String timeText;
        String reason;
        if (mute.isPresent()) {
            MuteEntry entry = mute.get();
            timeText = muteTimeText(entry.permanent(), playerMuteService.remainingMillis(entry), permanentText);
            reason = entry.reason().isBlank()
                    ? config.playerMute().defaultReason()
                    : entry.reason();
        } else {
            timeText = permanentText;
            reason = config.playerMute().defaultReason();
        }
        final String finalTime = timeText;
        final String finalReason = reason;
        runMainThread(event, () -> messageService.sendPrivateMuted(player, finalTime, finalReason));
    }

    /**
     * Tekst czasu wyciszenia: dla wyciszeń stałych skonfigurowany
     * {@code messages.mute-time-permanent}, w przeciwnym razie pozostały czas.
     */
    static String muteTimeText(boolean permanent, long remainingMillis, String permanentText) {
        return permanent ? permanentText : DurationUtil.formatRemaining(remainingMillis);
    }

    private void runMainThread(AsyncChatEvent event, Runnable task) {
        if (event.isAsynchronous()) {
            Bukkit.getScheduler().runTask(plugin, task);
            return;
        }
        task.run();
    }

    private boolean isCooldownBypassed(Player player) {
        if (player.isOp() || player.hasPermission(HexChatPermissions.ADMIN)) {
            return true;
        }

        String bypassPermission = chatFormatService.currentConfig().cooldown().bypassPermission();
        return !bypassPermission.isBlank() && player.hasPermission(bypassPermission);
    }

    private boolean isContentFilterBypassed(Player player) {
        if (player.isOp() || player.hasPermission(HexChatPermissions.ADMIN)) {
            return true;
        }
        HexChatConfig.ContentFilter contentFilter = chatFormatService.currentConfig().contentFilter();
        return player.hasPermission(contentFilter.bypassPermission());
    }
}
