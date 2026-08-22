package hex.gui.command;

import hex.gui.HexGUIPlugin;
import hex.gui.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class HexGuiAdminCommand implements CommandExecutor, TabCompleter {
    private final HexGUIPlugin plugin;

    public HexGuiAdminCommand(HexGUIPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("hexgui.admin")) {
            Text.send(sender, plugin, plugin.hubConfig().noPermissionMessage());
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage("§eUżycie: /hexgui reload");
            return true;
        }

        boolean success = plugin.reloadHubConfig();
        Text.send(sender, plugin, success ? plugin.hubConfig().reloadedMessage() : plugin.hubConfig().reloadFailedMessage());
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("hexgui.admin")) return List.of();
        if (args.length == 1 && "reload".startsWith(args[0].toLowerCase())) return List.of("reload");
        return List.of();
    }
}
