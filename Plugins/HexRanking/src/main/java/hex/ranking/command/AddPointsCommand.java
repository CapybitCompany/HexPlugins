package hex.ranking.command;

import hex.ranking.model.PointsTable;
import hex.ranking.service.RankingService;
import hex.ranking.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

public final class AddPointsCommand implements CommandExecutor {

    private final Plugin plugin;
    private final RankingService rankingService;

    public AddPointsCommand(Plugin plugin, RankingService rankingService) {
        this.plugin = plugin;
        this.rankingService = rankingService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 3) {
            sender.sendMessage(MessageUtil.error("Uzycie: /dajpunkt <tabela> <gracz> <ilosc>"));
            return true;
        }

        PointsTable pointsTable;
        try {
            pointsTable = PointsTable.fromArgument(args[0]);
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(MessageUtil.error(ex.getMessage()));
            return true;
        }

        String playerName = args[1];

        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(MessageUtil.error("Ilosc musi byc liczba."));
            return true;
        }

        rankingService.addPointsByName(pointsTable, playerName, amount)
                .thenRun(() -> Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(MessageUtil.success(
                                "Dodano " + amount + " punktow graczowi " + playerName + " do tabeli " + pointsTable.argument() + "."
                        ))
                ))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            sender.sendMessage(MessageUtil.error("Nie udalo sie dodac punktow: " + MessageUtil.causeMessage(ex)))
                    );
                    return null;
                });

        return true;
    }
}
