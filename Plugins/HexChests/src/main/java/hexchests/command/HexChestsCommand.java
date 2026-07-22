package hexchests.command;

import hexchests.HexChestsPlugin;
import hexchests.Text;
import hexchests.config.HexChestsConfig;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class HexChestsCommand implements CommandExecutor, TabCompleter {

    private final HexChestsPlugin plugin;

    public HexChestsCommand(HexChestsPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {
        HexChestsConfig config = plugin.config();
        if (args.length != 1) {
            sender.sendMessage(Text.component(config.messages().withPrefix(config.messages().usage())));
            return true;
        }
        if (!sender.hasPermission("hexchests.admin")) {
            sender.sendMessage(Text.component(config.messages().withPrefix(config.messages().noPermission())));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (plugin.reloadPluginRuntime()) {
                sender.sendMessage(Text.component(plugin.config().messages().withPrefix(plugin.config().messages().reloadSuccess())));
            } else {
                sender.sendMessage(Text.component(config.messages().withPrefix(config.messages().reloadFailed())));
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.component(config.messages().withPrefix(config.messages().playerOnly())));
            return true;
        }

        var key = plugin.keyService().keyByCommand(args[0]);
        if (key.isEmpty()) {
            sender.sendMessage(Text.component(config.messages().withPrefix(config.messages().usage())));
            return true;
        }
        boolean fullFit = plugin.keyService().giveKey(player, key.get().id(), 1);
        player.sendMessage(Text.component(config.messages().withPrefix(config.messages().keyGiven()),
                Map.of("key", key.get().id(), "key_name", key.get().displayName())));
        if (!fullFit) {
            player.sendMessage(Text.component(config.messages().withPrefix(config.messages().inventoryFull())));
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String label,
                                                @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase();
        List<String> out = new ArrayList<>();
        if ("reload".startsWith(prefix)) {
            out.add("reload");
        }
        for (HexChestsConfig.KeyDefinition key : plugin.config().testKeys().keys().values()) {
            if (key.command().startsWith(prefix)) {
                out.add(key.command());
            }
        }
        return out;
    }
}
