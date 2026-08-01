package hexcasino.command;

import hexcasino.HexCasinoPlugin;
import hexcasino.Text;
import hexcasino.config.CasinoConfig;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class HexCasinoCommand implements CommandExecutor, TabCompleter {

    private final HexCasinoPlugin plugin;

    public HexCasinoCommand(HexCasinoPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        CasinoConfig config = plugin.config();
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("hexcasino.admin")) {
                sender.sendMessage(Text.component(config.messages().withPrefix(config.messages().noPermission())));
                return true;
            }
            boolean ok = plugin.reloadPluginRuntime();
            CasinoConfig current = plugin.config();
            sender.sendMessage(Text.component(current.messages().withPrefix(ok
                    ? current.messages().reloadSuccess()
                    : current.messages().reloadFailed())));
            return true;
        }

        sender.sendMessage(Text.component(config.messages().withPrefix(config.messages().usage())));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
                                      @NotNull Command command,
                                      @NotNull String alias,
                                      @NotNull String[] args) {
        if (args.length != 1 || !sender.hasPermission("hexcasino.admin")) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("reload").stream()
                .filter(value -> value.startsWith(prefix))
                .toList();
    }
}
