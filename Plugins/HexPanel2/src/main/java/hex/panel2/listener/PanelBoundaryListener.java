package hex.panel2.listener;

import hex.panel2.model.Panel;
import hex.panel2.service.PanelAccessModeService;
import hex.panel2.service.PanelService;
import hex.panel2.util.AccessControl;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class PanelBoundaryListener implements Listener {

    private final PanelService panelService;
    private final PanelAccessModeService panelAccessModeService;
    private final Map<UUID, Long> lastBoundaryMessageAt = new HashMap<>();

    public PanelBoundaryListener(PanelService panelService, PanelAccessModeService panelAccessModeService) {
        this.panelService = panelService;
        this.panelAccessModeService = panelAccessModeService;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (AccessControl.isBypass(event.getPlayer())) {
            return;
        }

        Location to = event.getTo();
        if (to == null) {
            return;
        }

        Location from = event.getFrom();
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        if (panelAccessModeService.isOcenyMode()) {
            handleOcenyMode(event, to);
            return;
        }

        Optional<Panel> playerPanel = panelService.getOwnedPanel(event.getPlayer().getUniqueId());
        if (playerPanel.isEmpty()) {
            return;
        }

        Panel panel = playerPanel.get();
        if (panel.contains(to)) {
            return;
        }

        Location center = panel.getCenter();
        center.setYaw(to.getYaw());
        center.setPitch(to.getPitch());
        event.setTo(center);

        UUID playerId = event.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        long lastMessageAt = lastBoundaryMessageAt.getOrDefault(playerId, 0L);
        if (now - lastMessageAt >= 1000L) {
            event.getPlayer().sendMessage("§cNie mozesz opuszczac swojego panelu.");
            lastBoundaryMessageAt.put(playerId, now);
        }
    }

    private void handleOcenyMode(PlayerMoveEvent event, Location to) {
        UUID playerId = event.getPlayer().getUniqueId();
        Optional<Panel> targetPanel = panelService.findPanelContaining(to);
        if (targetPanel.isEmpty()) {
            return;
        }

        Panel panel = targetPanel.get();
        UUID owner = panel.getOwner();
        if (owner == null || owner.equals(playerId)) {
            return;
        }

        event.setTo(event.getFrom());
        sendBoundaryMessage(event.getPlayer(), playerId, "§cTryb ocen: nie mozesz wejsc na cudza dzialke.");
    }

    private void sendBoundaryMessage(org.bukkit.entity.Player player, UUID playerId, String message) {
        long now = System.currentTimeMillis();
        long lastMessageAt = lastBoundaryMessageAt.getOrDefault(playerId, 0L);
        if (now - lastMessageAt >= 1000L) {
            player.sendMessage(message);
            lastBoundaryMessageAt.put(playerId, now);
        }
    }
}
