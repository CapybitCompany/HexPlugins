package hexchat.listener;

import hexchat.service.CommandFilterService;
import hexchat.service.HelpCommandService;
import hexchat.service.HexChatMessageService;
import hexchat.service.TabCompleteFilterService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.server.TabCompleteEvent;

import java.util.Objects;

public final class HexCommandListener implements Listener {

    private final CommandFilterService commandFilterService;
    private final TabCompleteFilterService tabCompleteFilterService;
    private final HelpCommandService helpCommandService;
    private final HexChatMessageService messageService;

    public HexCommandListener(
            CommandFilterService commandFilterService,
            TabCompleteFilterService tabCompleteFilterService,
            HelpCommandService helpCommandService,
            HexChatMessageService messageService
    ) {
        this.commandFilterService = Objects.requireNonNull(commandFilterService, "commandFilterService");
        this.tabCompleteFilterService = Objects.requireNonNull(tabCompleteFilterService, "tabCompleteFilterService");
        this.helpCommandService = Objects.requireNonNull(helpCommandService, "helpCommandService");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        if (commandFilterService.isBlocked(player, event.getMessage())) {
            event.setCancelled(true);
            messageService.sendCommandBlocked(player, commandFilterService.blockedMessage());
            return;
        }

        if (helpCommandService.handleIfNeeded(player, event.getMessage(), messageService)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerCommandSend(PlayerCommandSendEvent event) {
        commandFilterService.filterCommandSendList(event.getPlayer(), event.getCommands());
        tabCompleteFilterService.filterCommandSendSuggestions(event.getPlayer(), event.getCommands());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onTabComplete(TabCompleteEvent event) {
        if (!event.isCommand()) {
            return;
        }

        String buffer = event.getBuffer();
        if (buffer != null && buffer.trim().contains(" ")) {
            return;
        }

        if (!(event.getSender() instanceof Player player)) {
            return;
        }

        commandFilterService.filterTabCompletions(player, event.getCompletions());
        tabCompleteFilterService.filterTabCompletions(player, event.getCompletions());
    }
}
