package hex.elimination.command;

import hex.core.api.ui.UiTokens;
import hex.elimination.HexEliminationPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

public class EliminationToggleCommand implements CommandExecutor, TabCompleter {

    private final HexEliminationPlugin plugin;

    public EliminationToggleCommand(HexEliminationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            plugin.ui().send(sender, "elimination.toggle.usage");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "start" -> {
                if (plugin.getEliminationService().isActive()) {
                    plugin.ui().send(sender, "elimination.toggle.already_started");
                    return true;
                }
                plugin.getEliminationService().enable();
                plugin.ui().broadcast("elimination.toggle.started",
                        UiTokens.of("by", sender.getName()));
            }
            case "stop" -> {
                if (!plugin.getEliminationService().isActive()) {
                    plugin.ui().send(sender, "elimination.toggle.already_stopped");
                    return true;
                }
                plugin.getEliminationService().disable();
                plugin.ui().broadcast("elimination.toggle.stopped",
                        UiTokens.of("by", sender.getName()));
            }
            default -> plugin.ui().send(sender, "elimination.toggle.usage");
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            return List.of("start", "stop").stream()
                    .filter(s -> s.startsWith(input))
                    .toList();
        }
        return List.of();
    }
}




