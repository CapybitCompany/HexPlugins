package hexpvphandler.listener;

import hexpvphandler.config.HexPvPHandlerConfig;
import hexpvphandler.service.PvpToggleService;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Objects;
import java.util.function.Supplier;

public final class PvpDamageListener implements Listener {

    private final Supplier<HexPvPHandlerConfig> configSupplier;
    private final PvpToggleService toggleService;

    public PvpDamageListener(
            Supplier<HexPvPHandlerConfig> configSupplier,
            PvpToggleService toggleService
    ) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.toggleService = Objects.requireNonNull(toggleService, "toggleService");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!toggleService.isBlocked()) {
            return;
        }

        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        HexPvPHandlerConfig config = configSupplier.get();
        if (config.isWorldExempt(victim.getWorld().getName())) {
            return;
        }

        if (!isHandledCause(event.getCause())) {
            return;
        }

        Entity damager = event.getDamager();
        if (isPotionProjectile(damager)) {
            return;
        }

        Player attacker = resolvePlayerDamager(damager);
        if (attacker == null) {
            return;
        }

        event.setCancelled(true);
    }

    private boolean isHandledCause(EntityDamageEvent.DamageCause cause) {
        return cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                || cause == EntityDamageEvent.DamageCause.PROJECTILE;
    }

    private boolean isPotionProjectile(Entity damager) {
        return damager instanceof ThrownPotion;
    }

    private Player resolvePlayerDamager(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }
}
