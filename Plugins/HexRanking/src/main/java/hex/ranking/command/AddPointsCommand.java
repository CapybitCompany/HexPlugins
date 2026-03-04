package hex.ranking.command;

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
        if (args.length != 2) {
            sender.sendMessage(MessageUtil.error("Uzycie: /dajpunkt <gracz> <ilosc>"));
            return true;
        }

        String playerName = args[0];

        int amount;
        try {
            amount = Integer.parseInt(args[1]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(MessageUtil.error("Ilosc musi byc liczba."));
            return true;
        }

        rankingService.addPointsByName(playerName, amount)
                .thenRun(() -> Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(MessageUtil.success("Dodano " + amount + " punktow graczowi " + playerName + "."))
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
