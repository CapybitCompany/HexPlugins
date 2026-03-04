package hex.coins;

import hex.core.api.HexApi;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;

public final class CoinsCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final HexApi api;
    private final CoinsRepository repo;

    public CoinsCommand(Plugin plugin, HexApi api, CoinsRepository repo) {
        this.plugin = plugin;
        this.api = api;
        this.repo = repo;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (args.length < 2) {
            sender.sendMessage("§cUżycie:");
            sender.sendMessage("§7/coins get <nick>");
            sender.sendMessage("§7/coins add <nick> <amount>");
            sender.sendMessage("§7/coins set <nick> <amount>");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String nick = args[1];

        OfflinePlayer target = Bukkit.getOfflinePlayer(nick);
        if (target.getUniqueId() == null) {
            sender.sendMessage("§cNie można znaleźć UUID gracza.");
            return true;
        }

        switch (sub) {

            case "get" -> {
                api.db().async(() -> repo.find(target.getUniqueId()).orElse(0))
                        .thenAccept(coins -> Bukkit.getScheduler().runTask(plugin, () ->
                                sender.sendMessage("§eCoins §f" + nick + "§e: §f" + coins)
                        ))
                        .exceptionally(ex -> {
                            Bukkit.getScheduler().runTask(plugin, () ->
                                    sender.sendMessage("§cDB error: §f" + ex.getMessage())
                            );
                            return null;
                        });
                return true;
            }

            case "add", "set" -> {
                if (args.length < 3) {
                    sender.sendMessage("§cUżycie: /coins " + sub + " <nick> <amount>");
                    return true;
                }

                int amount;
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§camount musi być liczbą.");
                    return true;
                }

                if (sub.equals("add")) {
                    api.db().asyncRun(() -> repo.add(target.getUniqueId(), amount))
                            .thenRun(() -> Bukkit.getScheduler().runTask(plugin, () ->
                                    sender.sendMessage("§aDodano §f" + amount + "§a coinów dla §f" + nick)
                            ))
                            .exceptionally(ex -> {
                                Bukkit.getScheduler().runTask(plugin, () ->
                                        sender.sendMessage("§cDB error: §f" + ex.getMessage())
                                );
                                return null;
                            });
                } else {
                    api.db().asyncRun(() -> repo.set(target.getUniqueId(), amount))
                            .thenRun(() -> Bukkit.getScheduler().runTask(plugin, () ->
                                    sender.sendMessage("§aUstawiono coins §f" + nick + "§a na §f" + amount)
                            ))
                            .exceptionally(ex -> {
                                Bukkit.getScheduler().runTask(plugin, () ->
                                        sender.sendMessage("§cDB error: §f" + ex.getMessage())
                                );
                                return null;
                            });
                }

                return true;
            }
        }

        sender.sendMessage("§cNieznana akcja. Użyj get/add/set.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("get", "add", "set");
        return List.of();
    }
}