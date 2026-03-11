package hex.panel2.listener;

import hex.panel2.util.AccessControl;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class LiquidRestrictionListener implements Listener {

    private static final long BYPASS_TTL_MILLIS = 5 * 60 * 1000L;
    private final Map<String, Long> bypassLiquidSources = new HashMap<>();

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (AccessControl.isBypass(event.getPlayer())) {
            Location placed = event.getBlockClicked().getRelative(event.getBlockFace()).getLocation();
            registerBypassLiquid(placed);
            return;
        }

        event.setCancelled(true);
        event.getPlayer().sendMessage("§cWylewanie plynow jest zablokowane.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (AccessControl.isBypass(event.getPlayer())) {
            return;
        }

        event.setCancelled(true);
        event.getPlayer().sendMessage("§cZbieranie plynow jest zablokowane.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        if (event.getBlock().isLiquid()) {
            purgeExpiredBypassLiquids();
            String sourceKey = key(event.getBlock().getLocation());
            if (bypassLiquidSources.containsKey(sourceKey)) {
                registerBypassLiquid(event.getToBlock().getLocation());
                return;
            }
            event.setCancelled(true);
        }
    }

    private void registerBypassLiquid(Location location) {
        bypassLiquidSources.put(key(location), System.currentTimeMillis() + BYPASS_TTL_MILLIS);
    }

    private void purgeExpiredBypassLiquids() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> iterator = bypassLiquidSources.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (entry.getValue() < now) {
                iterator.remove();
            }
        }
    }

    private String key(Location location) {
        String worldName = location.getWorld() == null ? "world" : location.getWorld().getName();
        return worldName + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }
}
