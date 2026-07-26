package hexmobplaceholder.command;

import hexmobplaceholder.HexMobPlaceholderPlugin;
import hexmobplaceholder.Text;
import hexmobplaceholder.config.MobPlaceholderConfig;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class HexMobPlaceholderCommand implements CommandExecutor, TabCompleter {

    private final HexMobPlaceholderPlugin plugin;

    public HexMobPlaceholderCommand(HexMobPlaceholderPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        MobPlaceholderConfig config = plugin.config();
        if (config == null) {
            sender.sendMessage(Text.color("&cHexMobPlaceholder is not loaded correctly."));
            return true;
        }

        if (!sender.hasPermission("hexmobplaceholder.admin")) {
            sender.sendMessage(Text.color(config.messages().withPrefix(config.messages().noPermission())));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            boolean ok = plugin.reloadPluginRuntime();
            MobPlaceholderConfig current = plugin.config();
            sender.sendMessage(Text.color(current.messages().withPrefix(ok
                    ? current.messages().reloadSuccess()
                    : current.messages().reloadFailed())));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("debug")) {
            sender.sendMessage(Text.color("&8[&cHexMobPlaceholder&8] &7PlaceholderAPI: &f"
                    + (plugin.isPlaceholderApiEnabled() ? "enabled" : "disabled")));
            sender.sendMessage(Text.color("&8[&cHexMobPlaceholder&8] &7Expansion: &f"
                    + (plugin.isPlaceholderExpansionRegistered() ? "registered" : "not registered")));
            sender.sendMessage(Text.color("&8[&cHexMobPlaceholder&8] &7Placeholders: &f%hexmobplaceholder_hostile_kills%&7, &f%hexmobplaceholder_top_player%&7, &f%hexmobplaceholder_top_kills%"));
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            OfflinePlayer target = plugin.findKnownPlayer(args[1]).orElse(null);
            if (target == null) {
                sender.sendMessage(Text.color(config.messages().withPrefix(config.messages().playerNotFound()),
                        Map.of("player", args[1])));
                return true;
            }

            plugin.counter().resetProgress(target);
            String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();
            if (!plugin.saveData()) {
                sender.sendMessage(Text.color(config.messages().withPrefix(config.messages().resetFailed()),
                        Map.of("player", name)));
                return true;
            }
            sender.sendMessage(Text.color(config.messages().withPrefix(config.messages().resetSuccess()),
                    Map.of("player", name)));
            return true;
        }

        sender.sendMessage(Text.color(config.messages().withPrefix(config.messages().usage())));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
                                      @NotNull Command command,
                                      @NotNull String alias,
                                      @NotNull String[] args) {
        if (!sender.hasPermission("hexmobplaceholder.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("debug", "reload", "reset").stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return plugin.knownPlayers().stream()
                    .map(OfflinePlayer::getName)
                    .filter(Objects::nonNull)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
        return List.of();
    }
}
