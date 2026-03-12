package hex.panel2.command;

import hex.panel2.service.BuildSessionService;
import hex.panel2.service.PanelAccessModeService;
import hex.panel2.service.PanelService;
import hex.panel2.util.AccessControl;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class HexPanel2StartCommand implements CommandExecutor {

    private final BuildSessionService buildSessionService;
    private final PanelAccessModeService panelAccessModeService;
    private final PanelService panelService;

    public HexPanel2StartCommand(BuildSessionService buildSessionService,
                                 PanelAccessModeService panelAccessModeService,
                                 PanelService panelService) {
        this.buildSessionService = buildSessionService;
        this.panelAccessModeService = panelAccessModeService;
        this.panelService = panelService;
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
        int playersWithoutPanel = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (AccessControl.isBypass(player)) {
                continue;
            }
            if (panelService.getOwnedPanel(player.getUniqueId()).isEmpty()) {
                playersWithoutPanel++;
            }
        }

        buildSessionService.start(minutes);
        Bukkit.broadcastMessage("§aRunda budowania wystartowala. Czas: " + minutes + " min.");
        sender.sendMessage("§aWlaczono budowanie na " + minutes + " min.");
        if (playersWithoutPanel > 0) {
            sender.sendMessage("§eUwaga: " + playersWithoutPanel + " graczy nie ma panelu (uzyj /hex_panel2).");
        }
        return true;
    }
}
