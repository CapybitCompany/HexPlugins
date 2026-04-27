package hex.iceberg.command;

import hex.iceberg.IcebergPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

public class IcebergCommand implements CommandExecutor, TabCompleter {

    private final IcebergPlugin plugin;

    public IcebergCommand(IcebergPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§6/iceberg start §7- uruchom event");
            sender.sendMessage("§6/iceberg stop §7- zatrzymaj event");
            sender.sendMessage("§6/iceberg status §7- pokaz status");
            sender.sendMessage("§6/iceberg reload §7- przeladuj config");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start" -> {
                plugin.getService().start();
                sender.sendMessage("§aIceberg start command executed.");
            }
            case "stop" -> {
                plugin.getService().stop();
                sender.sendMessage("§eIceberg stopped.");
            }
            case "status" -> sender.sendMessage("§bIceberg status: §f" + plugin.getService().status());
            case "reload" -> {
                plugin.reloadIcebergConfig();
                sender.sendMessage("§aIceberg config reloaded.");
            }
            default -> sender.sendMessage("§cNieznana subkomenda.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("start", "stop", "status", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }
}

