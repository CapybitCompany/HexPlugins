package hexchat.listener;

import hexchat.permission.HexChatPermissions;
import hexchat.service.ChatCooldownService;
import hexchat.service.ChatFormatService;
import hexchat.service.GlobalChatMuteService;
import hexchat.service.HexChatMessageService;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class HexChatListener implements Listener {

    private final JavaPlugin plugin;
    private final ChatFormatService chatFormatService;
    private final ChatCooldownService cooldownService;
    private final GlobalChatMuteService globalChatMuteService;
    private final HexChatMessageService messageService;

    public HexChatListener(
            JavaPlugin plugin,
            ChatFormatService chatFormatService,
            ChatCooldownService cooldownService,
            GlobalChatMuteService globalChatMuteService,
            HexChatMessageService messageService
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.chatFormatService = Objects.requireNonNull(chatFormatService, "chatFormatService");
        this.cooldownService = Objects.requireNonNull(cooldownService, "cooldownService");
        this.globalChatMuteService = Objects.requireNonNull(globalChatMuteService, "globalChatMuteService");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        boolean bypassedCooldown = isCooldownBypassed(event.getPlayer());
        if (bypassedCooldown) {
            cooldownService.clear(event.getPlayer());
        }

        if (globalChatMuteService.isMutedFor(event.getPlayer())) {
            event.setCancelled(true);
            sendMutedMessage(event);
            return;
        }

        if (!bypassedCooldown) {
            ChatCooldownService.CooldownResult cooldownResult = cooldownService.checkAndApply(event.getPlayer());
            if (cooldownResult.blocked()) {
                event.setCancelled(true);
                sendCooldownMessage(event, cooldownResult.secondsLeft());
                return;
            }
        }

        if (!chatFormatService.isChatEnabled()) {
            return;
        }

        event.renderer(ChatRenderer.viewerUnaware((source, sourceDisplayName, message) ->
                chatFormatService.render(sourceDisplayName, message)));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(PlayerQuitEvent event) {
        cooldownService.clear(event.getPlayer());
    }

    private void sendCooldownMessage(AsyncChatEvent event, long secondsLeft) {
        Runnable sendTask = () -> messageService.sendCooldownWait(event.getPlayer(), secondsLeft);
        if (event.isAsynchronous()) {
            Bukkit.getScheduler().runTask(plugin, sendTask);
            return;
        }
        sendTask.run();
    }

    private void sendMutedMessage(AsyncChatEvent event) {
        Runnable sendTask = () -> messageService.sendChatMuted(event.getPlayer());
        if (event.isAsynchronous()) {
            Bukkit.getScheduler().runTask(plugin, sendTask);
            return;
        }
        sendTask.run();
    }

    private boolean isCooldownBypassed(org.bukkit.entity.Player player) {
        if (player.isOp() || player.hasPermission(HexChatPermissions.ADMIN)) {
            return true;
        }

        String bypassPermission = chatFormatService.currentConfig().cooldown().bypassPermission();
        return !bypassPermission.isBlank() && player.hasPermission(bypassPermission);
    }
}
