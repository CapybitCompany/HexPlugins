package hex.gui.command;

import hex.gui.HexGUIPlugin;
import hex.gui.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class HexCommand implements CommandExecutor {
    private final HexGUIPlugin plugin;

    public HexCommand(HexGUIPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Text.send(sender, plugin, plugin.hubConfig().playerOnlyMessage());
            return true;
        }
        plugin.hubMenu().open(player);
        return true;
    }
}
