package hex.elimination.command;

import hex.core.api.ui.UiTokens;
import hex.elimination.HexEliminationPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ResurectCommand implements CommandExecutor, TabCompleter {

    private final HexEliminationPlugin plugin;

    public ResurectCommand(HexEliminationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            plugin.ui().send(sender, "elimination.resurect.usage");
            return true;
        }

        String nick = args[0];
        OfflinePlayer target = plugin.getEliminationService().findPlayerByName(nick);
        if (target == null) {
            plugin.ui().send(sender, "elimination.error.player_not_found",
                    UiTokens.of("nick", nick));
            return true;
        }

        boolean ok = plugin.getEliminationService().resurrect(target);
        if (!ok) {
            plugin.ui().send(sender, "elimination.error.not_eliminated");
            return true;
        }

        String by = sender.getName();
        String targetName = target.getName() == null ? nick : target.getName();

        plugin.ui().broadcast("elimination.resurect.announce",
                UiTokens.of("target", targetName).put("by", by));

        plugin.ui().send(sender, "elimination.resurect.ok",
                UiTokens.of("target", targetName));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }

        String input = args[0].toLowerCase(Locale.ROOT);
        List<String> completions = new ArrayList<>();
        for (var player : Bukkit.getOnlinePlayers()) {
            if (plugin.getEliminationService().isEliminated(player.getUniqueId())
                    && player.getName().toLowerCase(Locale.ROOT).startsWith(input)) {
                completions.add(player.getName());
            }
        }
        return completions;
    }
}
