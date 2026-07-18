package hexdailyrewards.command;

import hexdailyrewards.HexDailyRewardsPlugin;
import hexdailyrewards.Text;
import hexdailyrewards.config.DailyRewardsConfig;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class HexDailyRewardsCommand implements CommandExecutor, TabCompleter {

    private final HexDailyRewardsPlugin plugin;

    public HexDailyRewardsCommand(HexDailyRewardsPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        DailyRewardsConfig config = plugin.config();
        if (args.length == 0 || args[0].equalsIgnoreCase("open")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Text.component(config.messages().withPrefix(config.messages().playerOnly())));
                return true;
            }
            plugin.openRewards(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            if (!sender.hasPermission("hexdailyrewards.admin")) {
                sender.sendMessage(Text.component(config.messages().withPrefix(config.messages().noPermission())));
                return true;
            }
            boolean ok = plugin.reloadPluginRuntime();
            DailyRewardsConfig current = plugin.config();
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
        if (args.length == 1) {
            return List.of("open", "reload").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }
}

