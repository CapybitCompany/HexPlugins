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
import java.util.function.Supplier;

public final class HexMinigamesCommand implements CommandExecutor, TabCompleter {
    private static final List<String> ROOT = List.of(
            "reload",
            "status",
            "stop",
            "test",
            "testareny",
            "resetpunkty"
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
            case "resetpunkty" -> {
                if (args.length != 1) sender.sendMessage(Text.color("&cUzycie: /hexminigames resetpunkty"));
                else sessions.resetGlobalScores(sender);
            }
            case "reload" -> handleReload(sender);
            case "status" -> sender.sendMessage(Text.color(messages().prefix() + sessions.status()));
            case "stop" -> sender.sendMessage(Text.color(messages().prefix() + sessions.stopActiveFromAdmin()));
            case "test" -> handleTest(sender, args);
            case "testareny" -> handleTestAreny(sender, args);
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

    private void handleTest(CommandSender sender, String[] args) {
        List<Player> targets = new ArrayList<>();
        if (args.length == 1 && sender instanceof Player player) targets.add(player);
        for (String name : parsePlayerNames(args, 1)) {
            Player target = Bukkit.getPlayerExact(name);
            if (target == null) {
                sender.sendMessage(Text.color(messages().prefix() + "&cGracz offline: " + name));
                return;
            }
            if (!targets.contains(target)) targets.add(target);
        }
        if (targets.isEmpty()) {
            sender.sendMessage(Text.color("&cUzycie: /hexminigames test <gracze...>"));
            return;
        }
        String result = sessions.startAdminSeries(targets);
        sender.sendMessage(result == null
                ? Text.color(messages().prefix() + "&aUruchomiono test 5 losowych minigier.")
                : Text.color(result));
    }

    private void handleTestAreny(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.color(messages().prefix() + "&cUzycie: /hexminigames testareny <game> <gracze...>"));
            return;
        }
        String gameId = args[1];
        List<Player> targets = new ArrayList<>();
        for (String playerName : parsePlayerNames(args, 2)) {
            Player target = Bukkit.getPlayerExact(playerName);
            if (target == null) {
                sender.sendMessage(Text.color(messages().prefix() + "&cGracz offline: " + playerName));
                return;
            }
            targets.add(target);
        }
        if (targets.isEmpty()) {
            sender.sendMessage(Text.color(messages().prefix() + "&cPodaj co najmniej jednego gracza."));
            return;
        }
        String result = sessions.startAdminSingle(gameId, targets);
        sender.sendMessage(Text.color(result));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Text.color(messages().prefix()
                + "&7/hexminigames <reload|status|stop|test|testareny|resetpunkty>"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("hexminigames.admin")) return List.of();
        if (args.length == 1) return matches(ROOT, args[0]);
        String root = args[0].toLowerCase(Locale.ROOT);
        if (args.length >= 2 && root.equals("test")) {
            return matches(onlinePlayerNames(), args[args.length - 1]);
        }
        if (args.length == 2 && root.equals("testareny")) {
            return matches(gameIds(), args[1]);
        }
        if (args.length >= 3 && root.equals("testareny")) {
            return matches(onlinePlayerNames(), args[args.length - 1]);
        }
        return List.of();
    }

    static List<String> parsePlayerNames(String[] args, int startIndex) {
        List<String> names = new ArrayList<>();
        if (args == null) return names;
        for (int i = Math.max(0, startIndex); i < args.length; i++) {
            for (String part : args[i].split(",")) {
                String name = part.trim();
                if (!name.isBlank()) names.add(name);
            }
        }
        return names;
    }

    private List<String> gameIds() {
        List<String> ids = new ArrayList<>();
        for (MinigameDefinition definition : registry.definitions()) {
            ids.add(definition.id());
        }
        return ids;
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
