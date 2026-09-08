package hexbuildbattle.command;

import hexbuildbattle.config.MessageService;
import hexbuildbattle.game.GameManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HexBuildBattleCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "status",
            "reload",
            "forcestart",
            "forcetest",
            "stop",
            "reset",
            "skipbuild"
    );

    private final GameManager gameManager;
    private final MessageService messages;

    public HexBuildBattleCommand(GameManager gameManager, MessageService messages) {
        this.gameManager = gameManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hexbuildbattle.admin")) {
            messages.sendWithPrefix(sender, "command.no-permission", Map.of());
            return true;
        }

        if (args.length == 0) {
            messages.sendWithPrefix(sender, "command.usage", Map.of());
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "status" -> gameManager.sendStatus(sender);
            case "reload" -> {
                if (gameManager.reloadConfiguration()) {
                    messages.sendWithPrefix(sender, "command.reload-success", Map.of());
                }
            }
            case "forcestart" -> gameManager.forceStart(sender);
            case "forcetest", "teststart", "forcestarttest" -> gameManager.forceStartTesting(sender);
            case "stop" -> gameManager.stopRound(sender);
            case "reset" -> gameManager.resetArenas(sender);
            case "skipbuild" -> gameManager.skipBuild(sender);
            default -> messages.sendWithPrefix(sender, "command.unknown", Map.of());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("hexbuildbattle.admin")) {
            return List.of();
        }
        if (args.length != 1) {
            return List.of();
        }

        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> completions = new ArrayList<>();
        for (String subcommand : SUBCOMMANDS) {
            if (subcommand.startsWith(prefix)) {
                completions.add(subcommand);
            }
        }
        return completions;
    }
}
