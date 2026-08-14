package hexnpc.listener;

import hexnpc.service.NpcItemUseSuppressor;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;

public final class NpcItemUseListener implements Listener {

    private final NpcItemUseSuppressor suppressor;

    public NpcItemUseListener(NpcItemUseSuppressor suppressor) {
        this.suppressor = Objects.requireNonNull(suppressor, "suppressor");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (!isRightClick(event.getAction())) {
            return;
        }
        if (!suppressor.shouldCancelUse(event.getPlayer().getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        if (event.getPlayer().hasActiveItem()) {
            event.getPlayer().clearActiveItem();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (suppressor.shouldCancelUse(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            if (event.getPlayer().hasActiveItem()) {
                event.getPlayer().clearActiveItem();
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        suppressor.clear(event.getPlayer().getUniqueId());
    }

    static boolean isRightClick(Action action) {
        return action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
    }
}
