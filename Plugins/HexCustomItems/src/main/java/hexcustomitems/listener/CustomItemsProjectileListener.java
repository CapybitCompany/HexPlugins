package hexcustomitems.listener;

import hexcustomitems.service.CustomItemUseService;
import org.bukkit.Location;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;

import java.util.Objects;

public final class CustomItemsProjectileListener implements Listener {

    private final CustomItemUseService useService;

    public CustomItemsProjectileListener(CustomItemUseService useService) {
        this.useService = Objects.requireNonNull(useService, "useService");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        Location hitLocation = event.getHitBlock() != null
                ? event.getHitBlock().getLocation().add(0.5D, 0.5D, 0.5D)
                : projectile.getLocation();
        useService.handleProjectileHit(projectile, hitLocation);
    }
}
