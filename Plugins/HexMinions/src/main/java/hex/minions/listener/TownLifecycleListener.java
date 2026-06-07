package hex.minions.listener;

import hex.minions.service.MinionService;
import hex.towns.api.event.TownDestroyedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class TownLifecycleListener implements Listener {
    private final MinionService service;

    public TownLifecycleListener(MinionService service) {
        this.service = service;
    }

    @EventHandler
    public void onTownDestroyed(TownDestroyedEvent event) {
        service.purgeTown(event.town().id());
    }
}

