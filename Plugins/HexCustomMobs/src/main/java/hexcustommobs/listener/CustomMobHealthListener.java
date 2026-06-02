package hexcustommobs.listener;

import hexcustommobs.service.CustomMobService;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

public final class CustomMobHealthListener implements Listener {

    private final Plugin plugin;
    private final CustomMobService customMobService;

    public CustomMobHealthListener(Plugin plugin, CustomMobService customMobService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.customMobService = Objects.requireNonNull(customMobService, "customMobService");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity livingEntity)) {
            return;
        }
        if (!customMobService.isCustomMob(livingEntity)) {
            return;
        }
        scheduleHpBarUpdate(livingEntity);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof LivingEntity livingEntity)) {
            return;
        }
        if (!customMobService.isCustomMob(livingEntity)) {
            return;
        }
        scheduleHpBarUpdate(livingEntity);
    }

    private void scheduleHpBarUpdate(LivingEntity livingEntity) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!livingEntity.isValid() || livingEntity.isDead()) {
                return;
            }
            customMobService.updateHpBar(livingEntity);
        });
    }
}
