package hex.panel2.command;

import hex.panel2.model.Panel;
import hex.panel2.service.PanelAccessModeService;
import hex.panel2.service.PanelService;
import hex.panel2.util.AccessControl;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class HexPanel2Command implements CommandExecutor {

    private final PanelService panelService;
    private final PanelAccessModeService panelAccessModeService;

    public HexPanel2Command(PanelService panelService, PanelAccessModeService panelAccessModeService) {
        this.panelService = panelService;
        this.panelAccessModeService = panelAccessModeService;
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

        panelAccessModeService.disableOcenyMode();
        panelService.scanPanels();
        if (panelService.getPanelCount() == 0) {
            sender.sendMessage("§cNie wykryto paneli z RED_CONCRETE w zaladowanych chunkach.");
            sender.sendMessage("§7Upewnij sie, ze panele sa w zaladowanych chunkach.");
            return true;
        }

        Set<UUID> onlinePlayerIds = new HashSet<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            onlinePlayerIds.add(online.getUniqueId());
        }
        int releasedOffline = panelService.unassignPlayersNotIn(onlinePlayerIds);

        int assigned = 0;
        int missingPanels = 0;
        int bypassed = 0;

        for (Player target : Bukkit.getOnlinePlayers()) {
            if (AccessControl.isBypass(target)) {
                panelService.unassignPanel(target.getUniqueId());
                bypassed++;
                continue;
            }

            // Force fresh assignment every run to avoid stale owner mappings from earlier rounds.
            panelService.unassignPanel(target.getUniqueId());
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
                + " §7| §cBrak wolnych: " + missingPanels
                + " §7| §bBypass(OP): " + bypassed
                + " §7| §dZwolniono offline: " + releasedOffline
                + " §7| §fWolne teraz: " + panelService.getFreePanelCount());
        return true;
    }

    private void teleportToPanelCenter(Player player, Panel panel) {
        Location center = panel.getCenter();
        center.setYaw(player.getLocation().getYaw());
        center.setPitch(player.getLocation().getPitch());
        player.teleport(center);
    }
}
