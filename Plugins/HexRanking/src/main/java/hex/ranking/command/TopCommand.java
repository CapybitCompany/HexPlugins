package hex.ranking.command;

import hex.ranking.model.RankingPlayer;
import hex.ranking.service.RankingService;
import hex.ranking.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;

public final class TopCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final RankingService rankingService;

    public TopCommand(Plugin plugin, RankingService rankingService) {
        this.plugin = plugin;
        this.rankingService = rankingService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !"top".equalsIgnoreCase(args[0])) {
            sender.sendMessage(MessageUtil.error("Uzycie: /ranking top [limit]"));
            return true;
        }

        int limit = 10;
        if (args.length >= 2) {
            try {
                limit = Integer.parseInt(args[1]);
            } catch (NumberFormatException ex) {
                sender.sendMessage(MessageUtil.error("Limit musi byc liczba."));
                return true;
            }
        }

        rankingService.getTopGlobal(limit)
                .thenAccept(players -> Bukkit.getScheduler().runTask(plugin, () -> sendTop(sender, players)))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            sender.sendMessage(MessageUtil.error("Nie udalo sie pobrac rankingu: " + MessageUtil.causeMessage(ex)))
                    );
                    return null;
                });

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            if ("top".startsWith(prefix)) {
                return List.of("top");
            }
        }
        if (args.length == 2 && "top".equalsIgnoreCase(args[0])) {
            return List.of("10", "25", "50");
        }
        return List.of();
    }

    private void sendTop(CommandSender sender, List<RankingPlayer> players) {
        if (players.isEmpty()) {
            sender.sendMessage(MessageUtil.info("Ranking jest pusty."));
            return;
        }

        sender.sendMessage(MessageUtil.info("Top ranking globalny:"));
        int index = 1;
        for (RankingPlayer player : players) {
            String name = player.getPlayerName();
            if (name == null || name.isBlank()) {
                name = Bukkit.getOfflinePlayer(player.getUuid()).getName();
            }
            if (name == null || name.isBlank()) {
                name = "Nieznany gracz";
            }
            sender.sendMessage("§7" + index + ". §f" + name + " §8- §e" + player.getGlobalPoints());
            index++;
        }
    }
}
