package hex.minigames.command;

import hex.minigames.config.LoadedMinigamesConfig;
import hex.minigames.game.MinigameDefinition;
import hex.minigames.game.MinigameRegistry;
import hex.minigames.runtime.MinigamesSessionService;
import hex.minigames.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public final class HexMinigamesCommand implements CommandExecutor, TabCompleter {
    private static final List<String> ROOT = List.of(
            "reload",
            "status",
            "forcestart",
            "stop",
            "test",
            "testjoin",
            "testleave",
            "forcegame",
            "nextround",
            "endround",
            "debug"
    );

    private final Supplier<LoadedMinigamesConfig> config;
    private final MinigamesSessionService sessions;
    private final MinigameRegistry registry;
    private final Runnable reload;

    public HexMinigamesCommand(
            Supplier<LoadedMinigamesConfig> config,
            MinigamesSessionService sessions,
            MinigameRegistry registry,
            Runnable reload
    ) {
        this.config = config;
        this.sessions = sessions;
        this.registry = registry;
        this.reload = reload;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hexminigames.admin")) {
            sender.sendMessage(messages().get("no-permission", "&cBrak uprawnien."));
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> handleReload(sender);
            case "status" -> sender.sendMessage(Text.color(messages().prefix() + sessions.status()));
            case "forcestart" -> handleForceStart(sender);
            case "stop" -> sender.sendMessage(Text.color(messages().prefix() + sessions.stopActiveFromAdmin()));
            case "test" -> handleTest(sender, args);
            case "testjoin" -> handleTestJoin(sender, args);
            case "testleave" -> handleTestLeave(sender, args);
            case "forcegame" -> handleForceGame(sender, args);
            case "nextround", "endround" -> {
                sessions.forceEndRound();
                sender.sendMessage(Text.color(messages().prefix() + "&aWymuszono zakonczenie aktualnej rundy."));
            }
            case "debug" -> handleDebug(sender, args);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void handleReload(CommandSender sender) {
        reload.run();
        if (config.get().valid()) {
            sender.sendMessage(messages().get("reload-success", "&aPrzeladowano konfiguracje HexMinigames."));
        } else {
            sender.sendMessage(messages().get("reload-failed", "&cKonfiguracja zawiera bledy."));
        }
    }

    private void handleForceStart(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages().get("player-only", "&cTa komenda wymaga gracza."));
            return;
        }
        String result = sessions.startAdminSeries(player);
        sender.sendMessage(result == null
                ? Text.color(messages().prefix() + "&aUruchomiono administracyjna serie HexMinigames.")
                : Text.color(result));
    }

    private void handleTest(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Text.color(messages().prefix() + "&cUzycie: /hexminigames test <game> [player]"));
            return;
        }
        if (args.length == 2 && sessions.developmentSessionActive()) {
            sender.sendMessage(Text.color(messages().prefix() + sessions.selectDevelopmentGame(args[1])));
            return;
        }
        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(Text.color(messages().prefix() + "&cGracz offline: " + args[2]));
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(messages().get("player-only", "&cTa komenda wymaga gracza."));
            return;
        }
        String result = sessions.startAdminSingle(args[1], target);
        sender.sendMessage(Text.color(result));
    }

    private void handleTestJoin(CommandSender sender, String[] args) {
        Player target = resolveTarget(sender, args, 1);
        if (target == null) return;
        sender.sendMessage(Text.color(sessions.testJoin(target)));
    }

    private void handleTestLeave(CommandSender sender, String[] args) {
        Player target = resolveTarget(sender, args, 1);
        if (target == null) return;
        sender.sendMessage(Text.color(sessions.testLeave(target)));
    }

    private void handleForceGame(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Text.color(messages().prefix() + "&cUzycie: /hexminigames forcegame <game>"));
            return;
        }
        sender.sendMessage(Text.color(messages().prefix() + sessions.forceNextGame(args[1])));
    }

    private void handleDebug(CommandSender sender, String[] args) {
        if (args.length < 3 || !"state".equalsIgnoreCase(args[1])) {
            sender.sendMessage(Text.color(messages().prefix() + "&cUzycie: /hexminigames debug state <player>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        UUID playerId = target == null ? null : target.getUniqueId();
        if (playerId == null) {
            sender.sendMessage(Text.color(messages().prefix() + "&cGracz offline: " + args[2]));
            return;
        }
        sender.sendMessage(Text.color(messages().prefix() + sessions.debugState(playerId)));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Text.color(messages().prefix()
                + "&7/hexminigames <reload|status|forcestart|stop|test|testjoin|testleave|forcegame|nextround|endround|debug>"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("hexminigames.admin")) return List.of();
        if (args.length == 1) return matches(ROOT, args[0]);
        String root = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (root.equals("test") || root.equals("forcegame"))) {
            return matches(gameIds(), args[1]);
        }
        if (args.length == 2 && root.equals("debug")) {
            return matches(List.of("state"), args[1]);
        }
        if (args.length == 2 && (root.equals("testjoin") || root.equals("testleave"))) {
            return matches(onlinePlayerNames(), args[1]);
        }
        if ((args.length == 3 && root.equals("test")) || (args.length == 3 && root.equals("debug") && args[1].equalsIgnoreCase("state"))) {
            return matches(onlinePlayerNames(), args[args.length - 1]);
        }
        return List.of();
    }

    private List<String> gameIds() {
        List<String> ids = new ArrayList<>();
        for (MinigameDefinition definition : registry.definitions()) {
            ids.add(definition.id());
        }
        return ids;
    }

    private Player resolveTarget(CommandSender sender, String[] args, int argumentIndex) {
        if (args.length > argumentIndex) {
            Player target = Bukkit.getPlayerExact(args[argumentIndex]);
            if (target == null) {
                sender.sendMessage(Text.color(messages().prefix() + "&cGracz offline: " + args[argumentIndex]));
                return null;
            }
            return target;
        }
        if (sender instanceof Player player) return player;
        sender.sendMessage(messages().get("player-only", "&cTa komenda wymaga gracza."));
        return null;
    }

    private List<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    private List<String> matches(List<String> values, String prefix) {
        String normalized = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized))
                .sorted()
                .toList();
    }

    private hex.minigames.config.Messages messages() {
        LoadedMinigamesConfig loaded = config.get();
        if (loaded == null) {
            return new hex.minigames.config.Messages(Map.of("prefix", "&8[&bHexMinigames&8] "));
        }
        return loaded.messages();
    }
}
