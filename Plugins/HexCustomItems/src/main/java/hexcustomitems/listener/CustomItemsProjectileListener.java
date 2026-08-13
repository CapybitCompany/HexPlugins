package hexcustomitems.listener;

import hexcustomitems.service.SpecialItemActionService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;

public final class CustomItemsProjectileListener implements Listener {

    private final SpecialItemActionService specialActions;

    public CustomItemsProjectileListener(SpecialItemActionService specialActions) {
        this.specialActions = specialActions;
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        specialActions.handleProjectileHit(event);
    }
}
