package hex.panel2.command;

import hex.panel2.service.PanelAccessModeService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public final class HexPanel2OcenyStopCommand implements CommandExecutor {

    private final PanelAccessModeService panelAccessModeService;

    public HexPanel2OcenyStopCommand(PanelAccessModeService panelAccessModeService) {
        this.panelAccessModeService = panelAccessModeService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hex.panel2.oceny.stop")) {
            sender.sendMessage("§cBrak uprawnien do tej komendy.");
            return true;
        }

        if (!panelAccessModeService.isOcenyMode()) {
            sender.sendMessage("§eTryb ocen jest juz wylaczony.");
            return true;
        }

        panelAccessModeService.disableOcenyMode();
        Bukkit.broadcastMessage("§cTryb ocen zostal wylaczony przez administratora.");
        sender.sendMessage("§aWylaczono tryb ocen.");
        return true;
    }
}
