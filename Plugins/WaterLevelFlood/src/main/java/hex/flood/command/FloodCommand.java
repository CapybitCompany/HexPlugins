package hex.flood.command;

import hex.flood.WaterLevelFloodPlugin;
import hex.flood.config.FloodConfig;
import hex.flood.manager.FloodManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Komenda /flood do zarządzania eventem zalewania.
 *
 * Użycie:
 *   /flood start   - rozpoczyna event (wypełnia start level + zaczyna podnoszenie)
 *   /flood stop    - zatrzymuje podnoszenie wody (nie usuwa wody)
 *   /flood reset   - usuwa wodę i przywraca stan początkowy
 *   /flood status  - pokazuje aktualny stan
 *   /flood reload  - przeładowuje konfigurację
 */
public class FloodCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList("start", "stop", "reset", "status", "reload");

    private final WaterLevelFloodPlugin plugin;
    private final FloodManager manager;

    public FloodCommand(WaterLevelFloodPlugin plugin, FloodConfig config, FloodManager manager) {
        this.plugin = plugin;
        // config jest zostawiony w sygnaturze dla kompatybilności miejsca rejestracji komendy.
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "start" -> handleStart(sender);
            case "stop" -> handleStop(sender);
            case "reset" -> handleReset(sender);
            case "status" -> handleStatus(sender);
            case "reload" -> handleReload(sender);
            default -> sendHelp(sender);
        }

        return true;
    }

    private void handleStart(CommandSender sender) {
        if (manager.isRunning()) {
            msg(sender, "&cFlood jest już aktywny! Użyj /flood stop najpierw.");
            return;
        }
        if (manager.isBusy()) {
            msg(sender, "&cTrwa operacja fill/reset, poczekaj...");
            return;
        }

        manager.start();

        // Broadcast do wszystkich graczy
        var startMsg = plugin.getFloodConfig().getMsgStarted();
        plugin.getServer().broadcastMessage(startMsg);

        msg(sender, "&aFlood rozpoczęty! Poziom startowy: &e" + manager.getCurrentWaterLevel()
                + " &a-> docelowy: &e" + manager.getTargetWaterLevel());
    }

    private void handleStop(CommandSender sender) {
        if (!manager.isRunning()) {
            msg(sender, "&cFlood nie jest aktywny.");
            return;
        }

        manager.stop();

        var stopMsg = plugin.getFloodConfig().getMsgStopped();
        plugin.getServer().broadcastMessage(stopMsg);

        msg(sender, "&aFlood zatrzymany na poziomie Y=" + manager.getCurrentWaterLevel());
    }

    private void handleReset(CommandSender sender) {
        if (manager.isBusy()) {
            msg(sender, "&cTrwa inna operacja, poczekaj...");
            return;
        }

        msg(sender, "&eResetuję flood... To może chwilę potrwać.");

        manager.reset(() -> {
            var resetMsg = plugin.getFloodConfig().getMsgReset();
            plugin.getServer().broadcastMessage(resetMsg);
            msg(sender, "&aReset zakończony!");
        });
    }

    private void handleStatus(CommandSender sender) {
        String status = manager.isRunning() ? "&aAKTYWNY" : "&7NIEAKTYWNY";
        String statusLine = plugin.getFloodConfig().getMsgStatus()
                .replace("%status%", status)
                .replace("%level%", String.valueOf(manager.getCurrentWaterLevel()))
                .replace("%target%", String.valueOf(manager.getTargetWaterLevel()))
                .replace('&', '§');
        sender.sendMessage(statusLine);

        var cfg = plugin.getFloodConfig();
        msg(sender, "&7Region: &f[" + cfg.getX1() + "," + cfg.getZ1() + "] -> [" + cfg.getX2() + "," + cfg.getZ2() + "]");
        msg(sender, "&7Y range: &f" + cfg.getYMin() + " - " + cfg.getYMax());
        msg(sender, "&7Warstwa: &f" + cfg.getLayerBlockCount() + " bloków (" + cfg.getRegionWidth() + "x" + cfg.getRegionDepth() + ")");
        msg(sender, "&7Duration: &f" + cfg.getDurationSeconds() + "s &7| Rise: &f" + cfg.getRiseBlocks() + " bloków");
        msg(sender, "&7Busy: &f" + manager.isBusy());
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadFloodConfig();
        msg(sender, "&aKonfiguracja przeładowana!");
    }

    private void sendHelp(CommandSender sender) {
        msg(sender, "&6&l=== WaterLevelFlood ===");
        msg(sender, "&e/flood start &7- Rozpocznij event zalewania");
        msg(sender, "&e/flood stop &7- Zatrzymaj podnoszenie wody");
        msg(sender, "&e/flood reset &7- Zresetuj (usuń wodę)");
        msg(sender, "&e/flood status &7- Pokaż status");
        msg(sender, "&e/flood reload &7- Przeładuj config");
    }

    private void msg(CommandSender sender, String message) {
        sender.sendMessage(message.replace('&', '§'));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(input))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
