package hex.gui.command;

import hex.gui.HexGUIPlugin;
import hex.gui.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class HexCommand implements CommandExecutor {
    private final HexGUIPlugin plugin;

    public HexCommand(HexGUIPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            Text.send(sender, plugin, plugin.hubConfig().playerOnlyMessage());
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("ranks")) {
            plugin.hubMenu().openRanks(player);
            return true;
        }

        plugin.hubMenu().open(player);
        return true;
    }
}
