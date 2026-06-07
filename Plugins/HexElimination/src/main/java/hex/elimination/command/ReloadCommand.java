package hex.elimination.command;

import hex.elimination.HexEliminationPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class ReloadCommand implements CommandExecutor {

    private final HexEliminationPlugin plugin;

    public ReloadCommand(HexEliminationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        plugin.reloadConfig();
        plugin.getEliminationService().reloadConfig();
        sender.sendMessage("§aHexElimination config reloaded.");
        return true;
    }
}

