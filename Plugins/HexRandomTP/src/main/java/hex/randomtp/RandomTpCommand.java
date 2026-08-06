package hex.randomtp;

import hex.core.api.HexApi;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import java.util.List;

final class RandomTpCommand implements CommandExecutor, TabCompleter {
    private final HexRandomTpPlugin plugin;
    private final HexApi hexApi;
    private final RandomTeleportService teleportService;

    RandomTpCommand(HexRandomTpPlugin plugin, HexApi hexApi, RandomTeleportService teleportService) {
        this.plugin = plugin;
        this.hexApi = hexApi;
        this.teleportService = teleportService;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("hexrandomtp.admin")) {
                hexApi.ui().send(sender, "randomtp.no-permission");
                return true;
            }

            plugin.reloadPluginConfig();
            hexApi.ui().send(sender, "randomtp.reload-ok");
            return true;
        }

        if (args.length > 0) {
            hexApi.ui().send(sender, "randomtp.usage");
            return true;
        }

        if (!(sender instanceof Player player)) {
            hexApi.ui().send(sender, "randomtp.player-only");
            return true;
        }

        teleportService.request(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (args.length == 1 && sender.hasPermission("hexrandomtp.admin")
                && "reload".startsWith(args[0].toLowerCase())) {
            return List.of("reload");
        }
        return List.of();
    }
}
