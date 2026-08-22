package hex.minions.listener;

import hex.minions.machine.MachineService;
import hex.minions.service.MinionService;
import hex.towns.api.event.TownDestroyedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Legacy lifecycle listener kept for binary/source compatibility.
 *
 * <p>It is intentionally not registered by HexMinions anymore. Durable, retry-safe town cleanup
 * is driven by the per-subsystem TownDataNamespace handlers (minions/machines/cables/robot).
 * Keeping this event handler side-effect free prevents an accidental second destructive cleanup
 * path if an older bootstrap registers the listener.</p>
 */
@Deprecated(forRemoval = false)
public final class TownLifecycleListener implements Listener {
    public TownLifecycleListener(MinionService service, MachineService machines) {
        // Compatibility constructor only.
    }

    @EventHandler
    public void onTownDestroyed(TownDestroyedEvent event) {
        // Notification only. Persistent cleanup jobs are the source of truth.
    }
}
