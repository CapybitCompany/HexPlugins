package hex.panel2.listener;

import hex.panel2.service.BuildSessionService;
import hex.panel2.service.PanelService;
import hex.panel2.util.AccessControl;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BuildRestrictionListener implements Listener {

    private final PanelService panelService;
    private final BuildSessionService buildSessionService;
    private final Map<UUID, Long> lastBuildMessageAt = new HashMap<>();

    public BuildRestrictionListener(PanelService panelService, BuildSessionService buildSessionService) {
        this.panelService = panelService;
        this.buildSessionService = buildSessionService;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (AccessControl.isBypass(event.getPlayer())) {
            return;
        }
        if (event.getBlock().getType() == Material.BEDROCK) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cNiszczenie bedrocka jest zablokowane.");
            return;
        }
        if (event.getBlock().getType() == Material.RED_CONCRETE) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cNie mozesz niszczyc znacznikow paneli.");
            return;
        }
        if (!buildSessionService.isBuildActive()) {
            event.setCancelled(true);
            sendBuildInactiveMessage(event.getPlayer().getUniqueId(), () ->
                    event.getPlayer().sendMessage("§cBudowanie jest obecnie wylaczone.")
            );
            return;
        }
        if (!panelService.canBuild(event.getPlayer().getUniqueId(), event.getBlock().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cMozesz niszczyc bloki tylko na swoim panelu.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (AccessControl.isBypass(event.getPlayer())) {
            return;
        }
        if (event.getBlockPlaced().getType() == Material.BEDROCK) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cStawianie bedrocka jest zablokowane.");
            return;
        }
        if (!buildSessionService.isBuildActive()) {
            event.setCancelled(true);
            sendBuildInactiveMessage(event.getPlayer().getUniqueId(), () ->
                    event.getPlayer().sendMessage("§cBudowanie jest obecnie wylaczone.")
            );
            return;
        }
        if (!panelService.canBuild(event.getPlayer().getUniqueId(), event.getBlockPlaced().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cMozesz stawiac bloki tylko na swoim panelu.");
        }
    }

    private void sendBuildInactiveMessage(UUID playerId, Runnable action) {
        long now = System.currentTimeMillis();
        long last = lastBuildMessageAt.getOrDefault(playerId, 0L);
        if (now - last >= 1000L) {
            action.run();
            lastBuildMessageAt.put(playerId, now);
        }
    }
}
