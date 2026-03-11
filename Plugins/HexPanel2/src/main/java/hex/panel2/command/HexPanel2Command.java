package hex.panel2.command;

import hex.panel2.model.Panel;
import hex.panel2.service.PanelService;
import hex.panel2.util.AccessControl;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Optional;

public final class HexPanel2Command implements CommandExecutor {

    private final PanelService panelService;

    public HexPanel2Command(PanelService panelService) {
        this.panelService = panelService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hex.panel2.assignall")) {
            sender.sendMessage("§cBrak uprawnien do tej komendy.");
            return true;
        }

        if (Bukkit.getOnlinePlayers().isEmpty()) {
            sender.sendMessage("§eBrak graczy online.");
            return true;
        }

        int assigned = 0;
        int alreadyAssigned = 0;
        int missingPanels = 0;
        int bypassed = 0;

        for (Player target : Bukkit.getOnlinePlayers()) {
            if (AccessControl.isBypass(target)) {
                panelService.unassignPanel(target.getUniqueId());
                bypassed++;
                continue;
            }

            Optional<Panel> existing = panelService.getOwnedPanel(target.getUniqueId());
            if (existing.isPresent()) {
                alreadyAssigned++;
                teleportToPanelCenter(target, existing.get());
                target.sendMessage("§eMasz juz przypisany panel. Teleportowano na srodek.");
                continue;
            }

            Optional<Panel> panel = panelService.assignRandomFreePanel(target.getUniqueId());
            if (panel.isEmpty()) {
                missingPanels++;
                target.sendMessage("§cBrak wolnych paneli.");
                continue;
            }

            assigned++;
            teleportToPanelCenter(target, panel.get());
            target.sendMessage("§aAdministrator przydzielil ci panel.");
        }

        sender.sendMessage("§aPrzydzielono nowe panele: " + assigned
                + " §7| §eJuz przypisane: " + alreadyAssigned
                + " §7| §cBrak wolnych: " + missingPanels
                + " §7| §bBypass(OP): " + bypassed);
        return true;
    }

    private void teleportToPanelCenter(Player player, Panel panel) {
        Location center = panel.getCenter();
        center.setYaw(player.getLocation().getYaw());
        center.setPitch(player.getLocation().getPitch());
        player.teleport(center);
    }
}
