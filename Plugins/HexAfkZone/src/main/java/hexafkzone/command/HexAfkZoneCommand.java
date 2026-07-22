package hexafkzone.command;

import hexafkzone.HexAfkZonePlugin;
import hexafkzone.Text;
import hexafkzone.config.AfkZoneConfig;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

public final class HexAfkZoneCommand implements CommandExecutor, TabCompleter {

    private final HexAfkZonePlugin plugin;

    public HexAfkZoneCommand(HexAfkZonePlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        AfkZoneConfig config = plugin.config();
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("hexafkzone.admin")) {
                sender.sendMessage(Text.component(config.messages().withPrefix(config.messages().noPermission())));
                return true;
            }
            if (plugin.reloadPluginRuntime()) {
                sender.sendMessage(Text.component(plugin.config().messages().withPrefix(plugin.config().messages().reloadSuccess())));
            } else {
                sender.sendMessage(Text.component(config.messages().withPrefix(config.messages().reloadFailed())));
            }
            return true;
        }
        sender.sendMessage(Text.component(config.messages().withPrefix(config.messages().usage())));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String label,
                                                @NotNull String[] args) {
        if (args.length == 1 && "reload".startsWith(args[0].toLowerCase())) {
            return List.of("reload");
        }
        return List.of();
    }
}
