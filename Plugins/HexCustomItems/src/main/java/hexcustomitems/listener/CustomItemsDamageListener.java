package hexcustomitems.listener;

import hexcustomitems.service.SpecialItemActionService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

public final class CustomItemsDamageListener implements Listener {

    private final SpecialItemActionService specialActions;

    public CustomItemsDamageListener(SpecialItemActionService specialActions) {
        this.specialActions = specialActions;
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        specialActions.handleFallDamage(event);
    }
}
