package hex.panel2.command;

import hex.panel2.service.BuildSessionService;
import hex.panel2.service.PanelAccessModeService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public final class HexPanel2StartCommand implements CommandExecutor {

    private final BuildSessionService buildSessionService;
    private final PanelAccessModeService panelAccessModeService;

    public HexPanel2StartCommand(BuildSessionService buildSessionService, PanelAccessModeService panelAccessModeService) {
        this.buildSessionService = buildSessionService;
        this.panelAccessModeService = panelAccessModeService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hex.panel2.start")) {
            sender.sendMessage("§cBrak uprawnien do tej komendy.");
            return true;
        }

        int minutes = 10;
        if (args.length >= 1) {
            try {
                minutes = Integer.parseInt(args[0]);
            } catch (NumberFormatException ex) {
                sender.sendMessage("§cPodaj poprawna liczbe minut. Przyklad: /hex_panel2_start 10");
                return true;
            }
            if (minutes <= 0) {
                sender.sendMessage("§cLiczba minut musi byc wieksza od 0.");
                return true;
            }
        }

        panelAccessModeService.disableOcenyMode();
        buildSessionService.start(minutes);
        Bukkit.broadcastMessage("§aRunda budowania wystartowala. Czas: " + minutes + " min.");
        sender.sendMessage("§aWlaczono budowanie na " + minutes + " min.");
        return true;
    }
}
