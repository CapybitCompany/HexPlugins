package hexposterunki.listener;

import hex.core.api.ui.UiTokens;
import hexposterunki.config.OutpostDefinition;
import hexposterunki.config.PointDef;
import hexposterunki.engine.OutpostEngine;
import hexposterunki.engine.Participation;
import hexposterunki.ui.PosterunkiUi;
import hexposterunki.ui.UiKeys;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Combat rules of the encounter.
 *
 * <p>Damage on outpost mobs and the boss requires a full participation check, not just town
 * membership: a live combat phase, a registered encounter entity, and a player who is online,
 * alive, not spectating, standing inside the region and belonging to the controlling town.
 * Projectiles are judged by the shooter's state at impact, so a shot fired before dying or leaving
 * no longer earns anything.
 *
 * <p>Different towns may fight each other inside the region, members of the same town may not,
 * and encounter mobs cannot be leashed, mounted or lured out of the region.
 *
 * <p>While the engine does not allow progress ({@link hexposterunki.engine.EngineMode}) the fight is
 * frozen from both sides: no damage reaches an encounter entity - direct hits, projectiles already in
 * flight, fire, poison, explosions and every other cause alike - and encounter entities deal none
 * either. Deaths that still happen are ignored by the engine itself.
 */
public final class EncounterListener implements Listener {

    private final OutpostEngine engine;
    private final PosterunkiUi ui;

    public EncounterListener(OutpostEngine engine, PosterunkiUi ui) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.ui = Objects.requireNonNull(ui, "ui");
    }

    /**
     * Runs before every other damage rule. {@link EntityDamageEvent} also delivers the by-entity and
     * by-block subclasses, so indirect damage cannot slip through while the encounter is frozen.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFrozenEncounterDamage(EntityDamageEvent event) {
        if (engine.mode().allowsProgress()) {
            return;
        }
        if (engine.isEncounterEntity(event.getEntity())) {
            // The victim alone decides: no shooter lookup is needed to freeze a hit on the encounter.
            event.setCancelled(true);
            if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Player attacker) {
                // Only direct hits get the hint; a volley of arrows would flood the chat.
                ui.send(attacker, UiKeys.EVENT_PAUSED, new UiTokens());
            }
            return;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity
                && engine.isEncounterEntity(resolveSource(byEntity.getDamager()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null) {
            return;
        }
        if (engine.isEncounterEntity(event.getEntity())) {
            Participation eligibility = engine.participationOrBypass(attacker,
                    attacker.hasPermission(RegionProtectionListener.BYPASS));
            if (!eligibility.mayDamage()) {
                event.setCancelled(true);
                explain(attacker, eligibility);
            }
            return;
        }
        if (event.getEntity() instanceof Player victim) {
            handlePvp(event, attacker, victim);
        }
    }

    private void explain(Player attacker, Participation eligibility) {
        switch (eligibility) {
            case NO_TOWN -> ui.send(attacker, UiKeys.NO_TOWN, new UiTokens());
            case WRONG_TOWN -> ui.send(attacker, UiKeys.NOT_CONTROLLING,
                    UiTokens.of("town", engine.state().controllingTownName()));
            case OUTSIDE_REGION -> ui.send(attacker, UiKeys.NOT_PARTICIPATING, new UiTokens());
            case SUSPENDED -> ui.send(attacker, UiKeys.EVENT_PAUSED, new UiTokens());
            default -> {
                // NO_ACTIVE_RUN / NOT_ALIVE need no message; the situation speaks for itself.
            }
        }
    }

    private void handlePvp(EntityDamageByEntityEvent event, Player attacker, Player victim) {
        if (!engine.activeRegionContains(victim.getLocation())
                && !engine.activeRegionContains(attacker.getLocation())) {
            return;
        }
        if (attacker.hasPermission(RegionProtectionListener.BYPASS)) {
            return;
        }
        Optional<UUID> attackerTown = engine.participation().townOf(attacker.getUniqueId());
        Optional<UUID> victimTown = engine.participation().townOf(victim.getUniqueId());
        // Different towns are free to fight over the outpost; friendly fire inside one town is not.
        if (attackerTown.isPresent() && attackerTown.equals(victimTown)) {
            event.setCancelled(true);
            ui.send(attacker, UiKeys.SAME_TOWN_PVP, new UiTokens());
        }
    }

    /** The entity behind a hit: the shooter of a projectile, otherwise the damager itself. */
    private Entity resolveSource(Entity damager) {
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            return shooter;
        }
        return damager;
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (engine.isRunBoss(entity)) {
            // STORMBOSSY keeps full ownership of the boss drops and its native reward system.
            engine.onBossDeath(entity);
            return;
        }
        if (engine.isRunMob(entity)) {
            engine.onRunMobDeath(entity, entity.getKiller());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent event) {
        if (engine.isEncounterEntity(event.getEntity())
                && !event.getPlayer().hasPermission(RegionProtectionListener.BYPASS)) {
            event.setCancelled(true);
        }
    }

    /**
     * Encounter entities are neither ridden nor riders: nobody mounts a defender, and a defender does not
     * board a boat, a minecart or another entity - not even one brought into the fortress from outside.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (engine.isEncounterEntity(event.getMount()) || engine.isEncounterEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** Vehicles fire their own enter event; a defender boarding one is refused there as well. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (engine.isEncounterEntity(event.getEntered())) {
            event.setCancelled(true);
        }
    }

    /** Encounter mobs never chase a target out of the region. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (!engine.isRunMob(event.getEntity())) {
            return;
        }
        if (event.getTarget() != null && !engine.mode().allowsProgress()) {
            // A frozen encounter does not pick fights it could not finish.
            event.setCancelled(true);
            return;
        }
        Entity target = event.getTarget();
        if (target != null && !engine.activeRegionContains(target.getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * Containment sweep, called from the scheduler. Encounter mobs that left the region are
     * teleported back; nothing is removed or re-spawned, so nothing can be duplicated.
     */
    public void containEntities() {
        Optional<OutpostDefinition> outpost = engine.activeOutpost();
        if (outpost.isEmpty() || !engine.phase().hasCombat()) {
            return;
        }
        OutpostDefinition definition = outpost.get();
        World world = Bukkit.getWorld(definition.world());
        if (world == null) {
            return;
        }
        PointDef center = definition.center();
        for (UUID id : List.copyOf(engine.liveMobs().keySet())) {
            Entity entity = Bukkit.getEntity(id);
            if (entity == null || entity.isDead() || insideRegion(definition, entity)) {
                continue;
            }
            entity.teleport(new Location(world, center.x(), center.y(), center.z()));
        }
        engine.state().bossEntityId().ifPresent(bossId -> {
            Entity boss = Bukkit.getEntity(bossId);
            if (boss == null || boss.isDead() || insideRegion(definition, boss)) {
                return;
            }
            PointDef spawn = definition.bossSpawn();
            boss.teleport(new Location(world, spawn.x(), spawn.y(), spawn.z()));
        });
    }

    private boolean insideRegion(OutpostDefinition definition, Entity entity) {
        Location location = entity.getLocation();
        return location.getWorld() != null
                && definition.region().contains(location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }
}
