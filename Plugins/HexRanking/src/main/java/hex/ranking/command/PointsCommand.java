package hex.ranking.command;

import hex.ranking.model.PointsTable;
import hex.ranking.service.RankingService;
import hex.ranking.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

public final class PointsCommand implements CommandExecutor {

    private final Plugin plugin;
    private final RankingService rankingService;

    public PointsCommand(Plugin plugin, RankingService rankingService) {
        this.plugin = plugin;
        this.rankingService = rankingService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 2) {
            sender.sendMessage(MessageUtil.error("Uzycie: /punkty <gracz> <tabela>"));
            return true;
        }

        String playerName = args[0];
        PointsTable pointsTable;
        try {
            pointsTable = PointsTable.fromArgument(args[1]);
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(MessageUtil.error(ex.getMessage()));
            return true;
        }

        rankingService.getPointsByName(pointsTable, playerName)
                .thenAccept(points -> Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(MessageUtil.info(
                                "Punkty " + pointsTable.argument() + " gracza " + playerName + ": " + points
                        ))
                ))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            sender.sendMessage(MessageUtil.error("Nie udalo sie pobrac punktow: " + MessageUtil.causeMessage(ex)))
                    );
                    return null;
                });

        return true;
    }
}
