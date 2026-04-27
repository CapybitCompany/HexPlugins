package hex.areaeffects.command;

import hex.areaeffects.AreaEffectsPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

public class AreaEffectsCommand implements CommandExecutor, TabCompleter {

    private final AreaEffectsPlugin plugin;

    public AreaEffectsCommand(AreaEffectsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§6/areaeffects start §7- uruchom efekty");
            sender.sendMessage("§6/areaeffects stop §7- zatrzymaj efekty");
            sender.sendMessage("§6/areaeffects status §7- pokaz status");
            sender.sendMessage("§6/areaeffects reload §7- przeladuj config");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start" -> {
                plugin.getService().start();
                sender.sendMessage("§aAreaEffects started.");
            }
            case "stop" -> {
                plugin.getService().stop();
                sender.sendMessage("§eAreaEffects stopped.");
            }
            case "status" -> sender.sendMessage("§bAreaEffects status: §f" + plugin.getService().status());
            case "reload" -> {
                plugin.reloadEffectsConfig();
                sender.sendMessage("§aAreaEffects config reloaded.");
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

