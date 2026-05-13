package hexchat.command;

import hexchat.HexChatPlugin;
import hexchat.permission.HexChatPermissions;
import hexchat.service.HexChatMessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class HexChatCommand implements CommandExecutor, TabCompleter {

    private static final String RELOAD_SUBCOMMAND = "reload";
    private static final String MUTE_SUBCOMMAND = "mute";
    private static final String UNMUTE_SUBCOMMAND = "unmute";
    private static final String TOGGLE_MUTE_SUBCOMMAND = "togglemute";
    private static final String MUTE_STATUS_SUBCOMMAND = "mutestatus";
    private static final List<String> SUBCOMMANDS = List.of(
            RELOAD_SUBCOMMAND,
            MUTE_SUBCOMMAND,
            UNMUTE_SUBCOMMAND,
            TOGGLE_MUTE_SUBCOMMAND,
            MUTE_STATUS_SUBCOMMAND
    );

    private final HexChatPlugin plugin;
    private final HexChatMessageService messages;

    public HexChatCommand(HexChatPlugin plugin, HexChatMessageService messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(HexChatPermissions.ADMIN)) {
            messages.sendNoPermission(sender);
            return true;
        }

        if (args.length != 1) {
            messages.sendUsage(sender);
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case RELOAD_SUBCOMMAND -> {
                plugin.reloadHexChatConfiguration();
                messages.sendReloaded(sender);
            }
            case MUTE_SUBCOMMAND -> handleMute(sender, true);
            case UNMUTE_SUBCOMMAND -> handleMute(sender, false);
            case TOGGLE_MUTE_SUBCOMMAND -> handleToggleMute(sender);
            case MUTE_STATUS_SUBCOMMAND -> messages.sendChatMuteStatus(sender, plugin.isGlobalChatMuted());
            default -> messages.sendUsage(sender);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(HexChatPermissions.ADMIN)) {
            return List.of();
        }

        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String subcommand : SUBCOMMANDS) {
                if (subcommand.startsWith(input)) {
                    matches.add(subcommand);
                }
            }
            return matches;
        }

        return List.of();
    }

    private void handleMute(CommandSender sender, boolean targetState) {
        boolean previous = plugin.setGlobalChatMuted(targetState);
        if (targetState) {
            if (previous) {
                messages.sendChatMuteAlreadyEnabled(sender);
                return;
            }
            messages.sendChatMuteEnabled(sender);
            return;
        }

        if (!previous) {
            messages.sendChatMuteAlreadyDisabled(sender);
            return;
        }
        messages.sendChatMuteDisabled(sender);
    }

    private void handleToggleMute(CommandSender sender) {
        boolean mutedNow = plugin.toggleGlobalChatMuted();
        if (mutedNow) {
            messages.sendChatMuteEnabled(sender);
            return;
        }
        messages.sendChatMuteDisabled(sender);
    }
}
