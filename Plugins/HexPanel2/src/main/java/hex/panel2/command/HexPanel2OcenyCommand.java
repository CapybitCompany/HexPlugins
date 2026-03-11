package hex.panel2.command;

import hex.panel2.service.PanelAccessModeService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public final class HexPanel2OcenyCommand implements CommandExecutor {

    private final PanelAccessModeService panelAccessModeService;

    public HexPanel2OcenyCommand(PanelAccessModeService panelAccessModeService) {
        this.panelAccessModeService = panelAccessModeService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hex.panel2.oceny")) {
            sender.sendMessage("§cBrak uprawnien do tej komendy.");
            return true;
        }

        panelAccessModeService.enableOcenyMode();
        Bukkit.broadcastMessage("§eTryb ocen wlaczony: mozna wychodzic ze swoich dzialek, ale nie mozna wchodzic na cudze.");
        sender.sendMessage("§aWlaczono tryb ocen.");
        return true;
    }
}
