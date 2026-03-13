package hex.ranking.command;

import hex.ranking.service.RankingService;
import hex.ranking.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class AddEventPointsCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final RankingService rankingService;

    public AddEventPointsCommand(Plugin plugin, RankingService rankingService) {
        this.plugin = plugin;
        this.rankingService = rankingService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Map<UUID, String> targetPlayers;
        if (args.length == 0) {
            targetPlayers = collectOnlinePlayers();
        } else {
            targetPlayers = collectSpecifiedOnlinePlayers(sender, args);
        }

        if (targetPlayers.isEmpty()) {
            sender.sendMessage(MessageUtil.error("Brak graczy do przyznania punktu eventowego."));
            return true;
        }

        rankingService.addEventPoints(targetPlayers)
                .thenAccept(awarded -> Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(MessageUtil.success(
                                "Przyznano punkt eventowy (season + global) dla " + awarded + " graczy."
                        ))
                ))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            sender.sendMessage(MessageUtil.error("Nie udalo sie przyznac punktow eventowych: " + MessageUtil.causeMessage(ex)))
                    );
                    return null;
                });

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) {
            return List.of();
        }

        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> suggestions = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            String name = player.getName();
            if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                suggestions.add(name);
            }
        }
        return suggestions;
    }

    private static Map<UUID, String> collectOnlinePlayers() {
        Map<UUID, String> players = new LinkedHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            players.put(player.getUniqueId(), player.getName());
        }
        return players;
    }

    private static Map<UUID, String> collectSpecifiedOnlinePlayers(CommandSender sender, String[] names) {
        Map<UUID, String> players = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();

        for (String rawName : names) {
            if (rawName == null || rawName.isBlank()) {
                continue;
            }

            Player player = Bukkit.getPlayerExact(rawName);
            if (player == null) {
                missing.add(rawName);
                continue;
            }

            players.put(player.getUniqueId(), player.getName());
        }

        if (!missing.isEmpty()) {
            sender.sendMessage(MessageUtil.error("Nie znaleziono online: " + String.join(", ", missing)));
        }

        return players;
    }
}
