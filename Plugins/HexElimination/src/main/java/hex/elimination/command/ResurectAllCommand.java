package hex.elimination.command;

import hex.core.api.ui.UiTokens;
import hex.elimination.HexEliminationPlugin;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

public class ResurectAllCommand implements CommandExecutor, TabCompleter {

    private final HexEliminationPlugin plugin;

    public ResurectAllCommand(HexEliminationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        GameMode targetMode;

        if (args.length == 0) {
            targetMode = plugin.getEliminationService().getResurrectGamemode();
        } else if (args.length == 1) {
            try {
                targetMode = GameMode.valueOf(args[0].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.ui().send(sender, "elimination.resurectall.invalid_gamemode",
                        UiTokens.of("input", args[0]));
                return true;
            }
        } else {
            plugin.ui().send(sender, "elimination.resurectall.usage");
            return true;
        }

        int count = plugin.getEliminationService().resurrectAll(targetMode);

        if (count == 0) {
            plugin.ui().send(sender, "elimination.resurectall.empty");
            return true;
        }

        plugin.ui().broadcast("elimination.resurectall.announce",
                UiTokens.of("count", String.valueOf(count))
                        .put("gamemode", targetMode.name().toLowerCase(Locale.ROOT))
                        .put("by", sender.getName()));

        plugin.ui().send(sender, "elimination.resurectall.ok",
                UiTokens.of("count", String.valueOf(count)));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            return List.of("survival", "creative", "adventure", "spectator")
                    .stream()
                    .filter(m -> m.startsWith(input))
                    .toList();
        }
        return List.of();
    }
}

