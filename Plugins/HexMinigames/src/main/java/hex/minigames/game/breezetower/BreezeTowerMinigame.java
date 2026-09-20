package hex.minigames.game.breezetower;

import hex.minigames.game.*;
import hex.minigames.game.common.*;
import hex.minigames.runtime.RoundPlayerState;
import hex.minigames.util.Text;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import java.util.*;

/** Floating NPC shooters and native wind charges; only falling eliminates players. */
public final class BreezeTowerMinigame implements Minigame {
    private final Plugin plugin;
    private final BossBarDisplay bossBars = new BossBarDisplay();
    private final List<Breeze> shooters = new ArrayList<>();
    private final Map<UUID, Charge> charges = new LinkedHashMap<>();
    private final Set<UUID> ownedCharges = new HashSet<>();
    private BreezeTowerConfig config;
    private BreezeTowerRuntime runtime;
    private StandardTutorial tutorial;
    private boolean started;
    private RoundResult cachedResult;
    private final Random random = new Random();
    private final Set<ChunkPosition> heldChunks = new HashSet<>();
    private World arenaWorld;

    public BreezeTowerMinigame(Plugin plugin) { this.plugin = plugin; }

    @Override
    public String id() { return BreezeTowerConfig.ID; }

    @Override
    public MinigameAvailability availability(MinigameDefinition definition, int players) {
        List<String> errors = new ArrayList<>();
        BreezeTowerConfig.fromDefinition(definition, errors);
        return errors.isEmpty() ? MinigameAvailability.ok() : MinigameAvailability.unavailable(String.join("; ", errors));
    }

    @Override
    public void prepare(RoundContext context) {
        List<String> errors = new ArrayList<>();
        config = BreezeTowerConfig.fromDefinition(context.definition(), errors);
        if (!errors.isEmpty()) throw new IllegalStateException(String.join("; ", errors));
        World world = Bukkit.getWorld(config.region().worldName());
        if (world == null) throw new IllegalStateException("Breeze arena world is unavailable");
        clearEntities();
        arenaWorld = world;
        holdArenaChunks(world);
        started = false;
        cachedResult = null;
        runtime = new BreezeTowerRuntime(config.shotIntervalTicks(), config.firstShotDelayTicks(), config.breezes().size(), new Random());
        try (var spawnGuard = new BreezeNpcSpawnGuard(plugin)) {
        for (var position : config.breezes()) {
            Breeze breeze = world.spawn(position.toLocation(config.region().worldName()), Breeze.class, npc -> {
                spawnGuard.include(npc);
                npc.setAI(false);
                npc.setGravity(false);
                npc.setInvulnerable(true);
                npc.setSilent(true);
                npc.setCollidable(false);
                npc.setPersistent(false);
                npc.setRemoveWhenFarAway(false);
                npc.setDespawnInPeacefulOverride(net.kyori.adventure.util.TriState.FALSE);
            });
            shooters.add(breeze);
            if (!breeze.isValid()) {
                clearEntities();
                throw new IllegalStateException("Breeze NPC spawn was rejected in world " + world.getName()
                        + " at " + position.x() + ", " + position.y() + ", " + position.z()
                        + ". Check mob-spawn protection plugins and region flags.");
            }
        }
        }
        tutorial = new StandardTutorial(plugin, config.tutorial());
        bossBars.showAll(context.onlineParticipants(), config.bossBar());
        tutorial.begin(context, context.onlineParticipants(), Map.of("duration", String.valueOf(config.roundDurationSeconds())));
    }

    @Override
    public int countdownSeconds(MinigameDefinition definition, int globalCountdownSeconds) {
        return config != null ? config.tutorial().durationSeconds()
                : BreezeTowerConfig.fromDefinition(definition, new ArrayList<>()).tutorial().durationSeconds();
    }

    @Override
    public boolean handleCountdownTick(RoundContext context, int ticksRemaining) {
        return tutorial != null && tutorial.tick(context, ticksRemaining, Map.of("duration", String.valueOf(config.roundDurationSeconds())));
    }

    @Override
    public boolean finishWhenAllActiveResolved() { return false; }

    @Override
    public void start(RoundContext context) {
        requireShooters();
        tutorial.end(context);
        for (Player player : context.onlineParticipants()) {
            player.teleport(config.gameplaySpawn().toLocation(config.region().worldName()));
            player.setFallDistance(0);
        }
        runtime.start(context.elapsedTicks());
        started = true;
    }

    @Override
    public void handleTick(RoundContext context) {
        if (!started || runtime == null) return;
        requireShooters();
        RoundClock.show(context);
        for (Player player : context.onlineParticipants()) {
            if (context.state(player.getUniqueId()) == RoundPlayerState.ACTIVE
                    && player.getLocation().getY() <= config.eliminationY()) eliminate(context, player);
        }
        charges.values().removeIf(charge -> {
            if (!charge.entity().isValid() || context.elapsedTicks() >= charge.expires()
                    || !config.region().contains(charge.entity().getLocation())) {
                charge.entity().remove();
                return true;
            }
            return false;
        });
        List<UUID> active = context.onlineParticipants().stream()
                .filter(player -> context.state(player.getUniqueId()) == RoundPlayerState.ACTIVE)
                .map(Player::getUniqueId).toList();
        for (var shot : runtime.shots(context.elapsedTicks(), active)) fire(context, shot);
    }

    private void fire(RoundContext context, BreezeTowerRuntime.Shot shot) {
        Breeze shooter = shooters.get(shot.shooterIndex());
        Player target = shot.target() == null ? null : context.player(shot.target()).orElse(null);
        if (!shooter.isValid()) return;
        if (target == null || !target.getWorld().equals(shooter.getWorld()) || !shooter.hasLineOfSight(target)) {
            List<Player> visible = context.onlineParticipants().stream()
                    .filter(player -> context.state(player.getUniqueId()) == RoundPlayerState.ACTIVE
                            && player.getWorld().equals(shooter.getWorld()) && shooter.hasLineOfSight(player)).toList();
            target = visible.isEmpty() ? null : visible.get(random.nextInt(visible.size()));
        }
        Location origin = shooter.getEyeLocation();
        Location aim = target == null ? randomPlatformTarget(shooter.getWorld()) : target.getLocation().add(0, 0.9, 0);
        Vector direction = aim.toVector().subtract(origin.toVector());
        if (direction.lengthSquared() < 0.01) direction = new Vector(0, 0, 1);
        direction.normalize();
        Location facing = origin.clone().setDirection(direction);
        shooter.setRotation(facing.getYaw(), facing.getPitch());
        Vector velocity = direction.clone().multiply(config.projectileSpeed());
        BreezeWindCharge charge = shooter.getWorld().spawn(origin, BreezeWindCharge.class, projectile -> {
            projectile.setShooter(shooter);
            projectile.setPersistent(false);
            projectile.setGravity(false);
            projectile.setIsIncendiary(false);
            projectile.setAcceleration(new Vector());
            projectile.setVelocity(velocity);
        });
        ownedCharges.add(charge.getUniqueId());
        charges.put(charge.getUniqueId(), new Charge(charge, context.elapsedTicks() + config.projectileLifetimeTicks()));
        shooter.getWorld().playSound(origin, "minecraft:entity.breeze.shoot", 1.0f, 1.0f);
    }

    /** Pick an exposed solid surface inside the rectangle enclosed by the four NPCs. */
    private Location randomPlatformTarget(World world) {
        int minX = (int) Math.ceil(config.breezes().stream().mapToDouble(p -> p.x()).min().orElse(29)) + 1;
        int maxX = (int) Math.floor(config.breezes().stream().mapToDouble(p -> p.x()).max().orElse(51)) - 1;
        int minZ = (int) Math.ceil(config.breezes().stream().mapToDouble(p -> p.z()).min().orElse(-94)) + 1;
        int maxZ = (int) Math.floor(config.breezes().stream().mapToDouble(p -> p.z()).max().orElse(-59)) - 1;
        int top = (int) Math.ceil(config.breezes().stream().mapToDouble(p -> p.y()).max().orElse(3));
        for (int attempt = 0; attempt < 32 && minX <= maxX && minZ <= maxZ; attempt++) {
            int x = minX + random.nextInt(maxX - minX + 1);
            int z = minZ + random.nextInt(maxZ - minZ + 1);
            for (int y = top; y > config.eliminationY(); y--) {
                var block = world.getBlockAt(x, y, z);
                if (!block.isPassable() && !block.isLiquid()) return new Location(world, x + 0.5, y + 0.9, z + 0.5);
            }
        }
        return config.gameplaySpawn().toLocation(config.region().worldName()).add(0, -0.1, 0);
    }

    /** Fail visibly if an external plugin removed an NPC instead of running a hazard-free round. */
    private void requireShooters() {
        if (shooters.size() != config.breezes().size() || shooters.stream().anyMatch(npc -> !npc.isValid())) {
            throw new IllegalStateException("Breeze NPC disappeared in world " + config.region().worldName()
                    + ". Check entity-removal plugins and mob-spawn region protection.");
        }
    }

    @Override
    public EventDecision onMove(RoundContext context, PlayerMoveEvent event) {
        if (started && event.getTo() != null && context.state(event.getPlayer().getUniqueId()) == RoundPlayerState.ACTIVE
                && event.getTo().getY() <= config.eliminationY()) eliminate(context, event.getPlayer());
        return EventDecision.PASS;
    }

    @Override
    public EventDecision onDamage(RoundContext context, EntityDamageEvent event) {
        if (!started || !(event.getEntity() instanceof Player player)
                || context.state(player.getUniqueId()) != RoundPlayerState.ACTIVE) return EventDecision.DENY;
        if (player.getLocation().getY() <= config.eliminationY() || event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            eliminate(context, player);
            return EventDecision.DENY;
        }
        Entity direct = event.getDamageSource() == null ? null : event.getDamageSource().getDirectEntity();
        if (direct != null && ownedCharges.contains(direct.getUniqueId())
                || event instanceof EntityDamageByEntityEvent hit && ownedCharges.contains(hit.getDamager().getUniqueId())) {
            // Keep native wind knockback while preventing health damage; melee/player projectiles remain denied.
            event.setDamage(0);
            return EventDecision.ALLOW;
        }
        return EventDecision.DENY;
    }

    @Override
    public void onEntityExplode(RoundContext context, EntityExplodeEvent event) {
        if (ownedCharges.contains(event.getEntity().getUniqueId())) {
            event.blockList().clear();
            event.setYield(0);
        }
    }

    @Override
    public void onKnockback(RoundContext context, io.papermc.paper.event.entity.EntityKnockbackEvent event) {
        if (!started || !(event instanceof io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent pushed)
                || !ownedCharges.contains(pushed.getPushedBy().getUniqueId())) return;
        double multiplier = GameSettings.decimal(context.definition().settings(), "shots.knockback-multiplier", 2.6);
        double lift = GameSettings.decimal(context.definition().settings(), "shots.minimum-upward-velocity", 1.15);
        if (!Double.isFinite(multiplier)) multiplier = 2.6;
        if (!Double.isFinite(lift)) lift = 1.15;
        Vector force = event.getKnockback().multiply(Math.clamp(multiplier, 1, 4));
        force.setY(Math.max(force.getY(), Math.clamp(lift, 0, 2) - event.getEntity().getVelocity().getY()));
        event.setKnockback(force);
    }

    private void eliminate(RoundContext context, Player player) {
        UUID id = player.getUniqueId();
        if (context.state(id) != RoundPlayerState.ACTIVE || !runtime.eliminate(id, context.elapsedTicks())) return;
        RespawnEffects.explosion(player);
        context.state(id, RoundPlayerState.GHOST);
        player.setGameMode(GameMode.SPECTATOR);
        context.respawn(player, config.spectatorSpawn().toLocation(config.region().worldName()), null);
    }

    @Override
    public void handlePlayerQuit(RoundContext context, UUID id) {
        if (runtime != null) runtime.eliminate(id, context.elapsedTicks());
        if (tutorial != null) tutorial.remove(context, id);
        bossBars.remove(id);
    }

    @Override
    public RoundResult finish(RoundContext context, RoundEndReason reason) {
        if (cachedResult != null) return cachedResult;
        started = false;
        clearEntities();
        Map<UUID, PlayerRoundResult> results = new LinkedHashMap<>();
        for (UUID id : context.participants()) {
            long survived = runtime == null ? 0 : runtime.survivedTicks(id, context.elapsedTicks());
            boolean completed = survived >= 1200 && context.state(id) == RoundPlayerState.ACTIVE;
            results.put(id, new PlayerRoundResult(runtime == null ? 0 : runtime.points(id, context.elapsedTicks()),
                    OptionalInt.empty(), completed, !completed, Map.of("survived-seconds", String.valueOf(survived / 20))));
        }
        cachedResult = new RoundResult(results, Map.of("game", id()));
        return cachedResult;
    }

    @Override
    public void reset(RoundContext context) {
        started = false;
        clearEntities();
        if (tutorial != null) tutorial.end(context);
        bossBars.clear();
        for (Player player : context.onlineParticipants()) {
            player.sendActionBar(Text.component(""));
            Text.clearTitle(player);
        }
        runtime = null;
    }

    /** Removes only entities created by this round, including on cancellation/plugin shutdown. */
    private void clearEntities() {
        charges.values().forEach(charge -> charge.entity().remove());
        charges.clear();
        ownedCharges.clear();
        shooters.forEach(Entity::remove);
        shooters.clear();
        if (arenaWorld != null) {
            for (ChunkPosition chunk : heldChunks) arenaWorld.removePluginChunkTicket(chunk.x(), chunk.z(), plugin);
        }
        heldChunks.clear();
        arenaWorld = null;
    }

    /** Load NPCs' chunks before spawning and retain the playing area through the tutorial and round. */
    private void holdArenaChunks(World world) {
        int minX = config.breezes().stream().mapToInt(p -> ((int) Math.floor(p.x())) >> 4).min().orElseThrow();
        int maxX = config.breezes().stream().mapToInt(p -> ((int) Math.floor(p.x())) >> 4).max().orElseThrow();
        int minZ = config.breezes().stream().mapToInt(p -> ((int) Math.floor(p.z())) >> 4).min().orElseThrow();
        int maxZ = config.breezes().stream().mapToInt(p -> ((int) Math.floor(p.z())) >> 4).max().orElseThrow();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (world.addPluginChunkTicket(x, z, plugin)) heldChunks.add(new ChunkPosition(x, z));
                world.getChunkAt(x, z).load();
            }
        }
    }

    private record ChunkPosition(int x, int z) { }
    private record Charge(BreezeWindCharge entity, long expires) { }
}
