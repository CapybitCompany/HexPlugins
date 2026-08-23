package hexleszek.command;

import hexleszek.HexLeszekPlugin;
import hexleszek.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class LeszekCommand implements CommandExecutor {

    private final HexLeszekPlugin plugin;

    public LeszekCommand(HexLeszekPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.component(plugin.getConfig().getString("messages.player-only", "")));
            return true;
        }
        return plugin.claim(player);
    }
}
