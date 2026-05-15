package hexabovename.command;

import hexabovename.HexAboveNamePlugin;
import hexabovename.service.MessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class HexAboveNameCommand implements CommandExecutor, TabCompleter {

    private final HexAboveNamePlugin plugin;
    private final MessageService messageService;

    public HexAboveNameCommand(HexAboveNamePlugin plugin, MessageService messageService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!hasAdminPermission(sender)) {
            messageService.sendNoPermission(sender);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            boolean success = plugin.reloadPluginRuntime();
            if (success) {
                messageService.sendReloaded(sender);
            } else {
                messageService.sendReloadFailed(sender);
            }
            return true;
        }

        messageService.sendUsage(sender, label);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return Collections.singletonList("reload");
        }
        return Collections.emptyList();
    }

    private boolean hasAdminPermission(CommandSender sender) {
        return sender.hasPermission("hexabovename.admin") || sender.hasPermission("hexabovename.reload");
    }
}
