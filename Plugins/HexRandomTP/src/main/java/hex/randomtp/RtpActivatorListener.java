package hex.randomtp;

import org.bukkit.block.Block;
import org.bukkit.block.data.Powerable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class RtpActivatorListener implements Listener {
    private static final long DUPLICATE_WINDOW_NANOS = 500_000_000L;

    private final HexRandomTpPlugin plugin;
    private final RandomTeleportService teleportService;
    private final Map<UUID, Long> lastActivationNanos = new HashMap<>();

    RtpActivatorListener(HexRandomTpPlugin plugin, RandomTeleportService teleportService) {
        this.plugin = plugin;
        this.teleportService = teleportService;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.PHYSICAL) {
            return;
        }
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null || !(block.getBlockData() instanceof Powerable)) {
            return;
        }

        RtpConfig config = plugin.rtpConfig();
        if (!config.isActivator(
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ()
        )) {
            return;
        }

        UUID playerId = event.getPlayer().getUniqueId();
        long now = System.nanoTime();
        Long previous = lastActivationNanos.put(playerId, now);
        if (previous != null && now - previous < DUPLICATE_WINDOW_NANOS) {
            return;
        }

        teleportService.request(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        lastActivationNanos.remove(event.getPlayer().getUniqueId());
    }
}

