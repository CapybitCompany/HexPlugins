package hex.vishopbroadcast.command;

import hex.vishopbroadcast.VishopBroadcastPlugin;
import hex.vishopbroadcast.text.PlaceholderRenderer;
import hex.vishopbroadcast.text.PurchaseTextFactory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Backend command intentionally exposes administration only. Purchases are
 * registered by the Velocity companion so a backend can never write one twice.
 */
public final class VishopBroadcastCommand implements CommandExecutor, TabCompleter {
    private final VishopBroadcastPlugin plugin;
    private final PurchaseTextFactory textFactory;

    public VishopBroadcastCommand(VishopBroadcastPlugin plugin, PurchaseTextFactory textFactory) {
        this.plugin = plugin;
        this.textFactory = textFactory;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("vishopbroadcast.admin")) {
            send(sender, "no-permission");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadPlugin();
            send(sender, "reloaded");
            return true;
        }
        send(sender, "reader-only");
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && sender.hasPermission("vishopbroadcast.admin") && "reload".startsWith(args[0].toLowerCase())) {
            return List.of("reload");
        }
        return List.of();
    }

    private void send(CommandSender sender, String key) {
        sender.sendMessage(textFactory.component(PlaceholderRenderer.render(plugin.settings().message(key), Map.of()), Map.of()));
    }
}
