package hexcustommobs.listener;

import hexcustommobs.service.CustomMobService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.Objects;

public final class CustomMobDeathListener implements Listener {

    private final CustomMobService customMobService;

    public CustomMobDeathListener(CustomMobService customMobService) {
        this.customMobService = Objects.requireNonNull(customMobService, "customMobService");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        customMobService.getCustomMobId(event.getEntity()).ifPresent(mobId -> {
            event.getDrops().clear();
            event.getDrops().addAll(customMobService.rollDrops(mobId));
            event.setDroppedExp(customMobService.resolveExpDrop(mobId));
        });
    }
}
