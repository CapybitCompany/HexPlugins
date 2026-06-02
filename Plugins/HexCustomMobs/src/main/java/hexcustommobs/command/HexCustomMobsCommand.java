package hexcustommobs.command;

import hexcustommobs.HexCustomMobsPlugin;
import hexcustommobs.util.LegacyFormat;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

public final class HexCustomMobsCommand implements CommandExecutor, TabCompleter {

    private final HexCustomMobsPlugin plugin;

    public HexCustomMobsCommand(HexCustomMobsPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("hexcustommobs.admin")) {
            sender.sendMessage(LegacyFormat.component("&cBrak uprawnień."));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            boolean success = plugin.reloadPluginRuntime();
            if (success) {
                sender.sendMessage(LegacyFormat.component("&aHexCustomMobs przeładowany."));
            } else {
                sender.sendMessage(LegacyFormat.component("&cReload nie powiódł się, sprawdź konsolę."));
            }
            return true;
        }

        sender.sendMessage(LegacyFormat.component("&cUżycie: /" + label + " reload"));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("reload");
        }
        return List.of();
    }
}
