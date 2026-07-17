package hex.minions.listener;

import hex.minions.service.MinionService;
import hex.minions.machine.MachineService;
import hex.towns.api.event.TownDestroyedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class TownLifecycleListener implements Listener {
    private final MinionService service;
    private final MachineService machines;

    public TownLifecycleListener(MinionService service, MachineService machines) {
        this.service = service;
        this.machines = machines;
    }

    @EventHandler
    public void onTownDestroyed(TownDestroyedEvent event) {
        service.cleanupTownWorld(event.town(), event.chunks());
        machines.forgetMachinesInChunks(event.town().world(), event.chunks());
        service.purgeTown(event.town());
    }
}
