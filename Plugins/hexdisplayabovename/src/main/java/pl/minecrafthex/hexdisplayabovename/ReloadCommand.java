package pl.minecrafthex.hexdisplayabovename;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;

public class ReloadCommand implements CommandExecutor, TabCompleter {

    private final HexDisplayAboveNamePlugin plugin;

    public ReloadCommand(HexDisplayAboveNamePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String prefix = color(plugin.getConfig().getString("messages.prefix", "&7[hexdisplayabovename] &r"));

        if (!sender.hasPermission("hexdisplayabovename.reload")) {
            sender.sendMessage(prefix + color(plugin.getConfig().getString("messages.no-permission", "&cBrak uprawnien.")));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadPlugin();
            sender.sendMessage(prefix + color(plugin.getConfig().getString("messages.reloaded", "&aPrzeladowano plugin.")));
            return true;
        }

        sender.sendMessage(color("&cUzycie: /" + label + " reload"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Collections.singletonList("reload");
        }
        return Collections.emptyList();
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}