package hex.drawn.command;

import hex.drawn.WaterDrawnPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

public class WaterDrawnCommand implements CommandExecutor, TabCompleter {

    private final WaterDrawnPlugin plugin;

    public WaterDrawnCommand(WaterDrawnPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§6/waterdrawn reload §7- przeladuj konfiguracje");
            sender.sendMessage("§6/waterdrawn status §7- pokaz status");
            sender.sendMessage("§6/waterdrawn mode <global|regions> §7- przelacz tryb toniecia");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            plugin.reloadWaterDrawnConfig();
            sender.sendMessage("§aWaterDrawn: konfiguracja przeladowana.");
            return true;
        }

        if (sub.equals("status")) {
            var cfg = plugin.getConfigModel();
            sender.sendMessage("§eWaterDrawn status:");
            sender.sendMessage("§7Enabled: §f" + cfg.isEnabled());
            sender.sendMessage("§7Mode: §f" + cfg.getDrownMode());
            sender.sendMessage("§7Regions count: §f" + cfg.getRegionsCount());
            sender.sendMessage("§7Excluded regions count: §f" + cfg.getExcludedRegionsCount());
            sender.sendMessage("§7Drown seconds: §f" + cfg.getDrownSeconds());
            sender.sendMessage("§7Check interval ticks: §f" + cfg.getCheckIntervalTicks());
            sender.sendMessage("§7Head-submerge-below-y: §f" + cfg.getHeadSubmergeBelowY());
            sender.sendMessage("§7Drown-water-level-y: §f" + cfg.getDrownWaterLevelY());
            return true;
        }

        if (sub.equals("mode")) {
            if (args.length < 2) {
                sender.sendMessage("§cUzycie: /waterdrawn mode <global|regions>");
                return true;
            }

            if (!plugin.setMode(args[1])) {
                sender.sendMessage("§cNieprawidlowy tryb. Uzyj: global albo regions.");
                return true;
            }

            sender.sendMessage("§aWaterDrawn: ustawiono tryb §f" + plugin.getConfigModel().getDrownMode());
            return true;
        }

        sender.sendMessage("§cNieznana subkomenda.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "status", "mode").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("mode")) {
            return List.of("global", "regions").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        return List.of();
    }
}
