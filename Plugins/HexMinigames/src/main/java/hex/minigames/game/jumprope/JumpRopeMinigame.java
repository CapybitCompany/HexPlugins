package hex.minigames.game.jumprope;

import hex.minigames.game.*;
import hex.minigames.game.common.*;
import hex.minigames.runtime.RoundPlayerState;
import hex.minigames.util.Text;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import java.util.*;

/** Rotating rope race with swept collisions, repeat attempts and a strict finish crossing. */
public final class JumpRopeMinigame implements Minigame {
    private final Plugin plugin;
    private final BossBarDisplay bars = new BossBarDisplay();
    private final Map<UUID, BoundingBox> previousBounds = new HashMap<>();
    private JumpRopeConfig config;
    private JumpRopeRuntime runtime;
    private RopeGeometry geometry;
    private RopeDisplay display;
    private StandardTutorial tutorial;
    private RoundResult result;
    private boolean started;
    private long previousTick;

    public JumpRopeMinigame(Plugin plugin) { this.plugin = plugin; }
    @Override public String id() { return JumpRopeConfig.ID; }
    @Override public boolean finishWhenAllActiveResolved() { return false; }

    @Override public MinigameAvailability availability(MinigameDefinition definition, int players) {
        List<String> errors = new ArrayList<>();
        JumpRopeConfig.fromDefinition(definition, errors);
        return errors.isEmpty() ? MinigameAvailability.ok() : MinigameAvailability.unavailable(String.join("; ", errors));
    }

    @Override public void prepare(RoundContext context) {
        List<String> errors = new ArrayList<>();
        config = JumpRopeConfig.fromDefinition(context.definition(), errors);
        if (!errors.isEmpty()) throw new IllegalStateException(String.join("; ", errors));
        World world = Bukkit.getWorld(config.region().worldName());
        if (world == null) throw new IllegalStateException("Jump-rope world is unavailable");
        runtime = new JumpRopeRuntime();
        result = null;
        started = false;
        previousTick = 0;
        previousBounds.clear();
        geometry = new RopeGeometry(config.ropeX(), config.pivotY(), config.bottomY(), config.nearZ(), config.farZ(), config.rotationTicks());
        display = new RopeDisplay(world, geometry);
        display.spawn(world);
        tutorial = new StandardTutorial(plugin, config.tutorial());
        bars.showAll(context.onlineParticipants(), config.bossBar());
        tutorial.begin(context, context.onlineParticipants(), Map.of("duration", String.valueOf(config.durationSeconds())));
    }

    @Override public int countdownSeconds(MinigameDefinition definition, int fallback) {
        return JumpRopeConfig.fromDefinition(definition, new ArrayList<>()).tutorial().durationSeconds();
    }

    @Override public boolean handleCountdownTick(RoundContext context, int ticks) {
        return tutorial.tick(context, ticks, Map.of("duration", String.valueOf(config.durationSeconds())));
    }

    @Override public void start(RoundContext context) {
        tutorial.end(context);
        previousTick = context.elapsedTicks();
        started = true;
    }

    @Override public void handleTick(RoundContext context) {
        if (!started) return;
        long tick = context.elapsedTicks();
        RoundClock.show(context);
        display.update(tick);
        for (Player player : context.onlineParticipants()) {
            UUID id = player.getUniqueId();
            if (context.state(id) == RoundPlayerState.RESPAWN_DELAY) {
                if (runtime.resume(id, tick)) {
                    context.state(id, RoundPlayerState.ACTIVE);
                    Text.clearTitle(player);
                } else if (tick % 20 == 0) showDelay(context, player);
            }
            if (context.state(id) != RoundPlayerState.ACTIVE) {
                previousBounds.remove(id);
                continue;
            }
            if (player.getLocation().getY() <= config.fallY()) {
                fall(context, player);
                continue;
            }
            if (!config.region().contains(player.getLocation())) continue;
            BoundingBox bounds = player.getBoundingBox();
            BoundingBox from = previousBounds.getOrDefault(id, bounds);
            geometry.collision(from, bounds, previousTick, tick).ifPresent(hit -> {
                if (runtime.canHit(id, tick, config.hitCooldownTicks())) {
                    Vector velocity = player.getVelocity();
                    player.setVelocity(new Vector(hit.horizontalSign() * config.knockback(), Math.max(0.18, velocity.getY()), velocity.getZ() * 0.5));
                    player.playSound(player.getLocation(), "minecraft:block.glass.hit", 0.8f, 0.7f);
                }
            });
            previousBounds.put(id, bounds.clone());
        }
        previousTick = tick;
        finishIfEveryoneCrossed(context);
    }

    @Override public EventDecision onMove(RoundContext context, PlayerMoveEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (event.getTo() == null) return EventDecision.PASS;
        if (!started || context.state(id) == RoundPlayerState.RESPAWN_DELAY) {
            StartBoundary.pushBack(event, config.startBoundary().maxZ(), -1);
            return EventDecision.PASS;
        }
        if (context.state(id) != RoundPlayerState.ACTIVE) return EventDecision.PASS;
        if (event.getTo().getY() <= config.fallY()) {
            fall(context, event.getPlayer());
        } else if (crossedFinish(config, event.getFrom(), event.getTo()) && runtime.finish(id)) {
            context.state(id, RoundPlayerState.FINISHED);
            previousBounds.remove(id);
            finishIfEveryoneCrossed(context);
        }
        return EventDecision.PASS;
    }

    /** Crossing from the start side counts, including a jump over the line but not falling below the deck. */
    static boolean crossedFinish(JumpRopeConfig config, Location from, Location to) {
        if (from == null || to == null || to.getWorld() == null || from.getWorld() == null
                || !to.getWorld().equals(from.getWorld()) || !to.getWorld().getName().equals(config.region().worldName())) return false;
        double line = config.finishRegion().maxZ();
        if (from.getZ() <= line || to.getZ() > line) return false;
        double fraction = (from.getZ() - line) / (from.getZ() - to.getZ());
        double x = from.getX() + (to.getX() - from.getX()) * fraction;
        double y = from.getY() + (to.getY() - from.getY()) * fraction;
        return x >= config.finishRegion().minX() && x < config.finishRegion().maxX() + 1
                && y >= config.finishRegion().minY() && y <= config.finishRegion().maxY() + 2.5;
    }

    @Override public EventDecision onDamage(RoundContext context, EntityDamageEvent event) {
        if (started && event.getEntity() instanceof Player player && context.state(player.getUniqueId()) == RoundPlayerState.ACTIVE
                && (player.getLocation().getY() <= config.fallY() || event.getCause() == EntityDamageEvent.DamageCause.VOID)) {
            fall(context, player);
        }
        return EventDecision.DENY;
    }

    private void fall(RoundContext context, Player player) {
        UUID id = player.getUniqueId();
        if (!runtime.beginFall(id)) return;
        previousBounds.remove(id);
        RespawnEffects.explosion(player);
        context.state(id, RoundPlayerState.RESPAWN_DELAY);
        showDelay(context, player);
        JumpRopeRuntime roundRuntime = runtime;
        context.respawn(player, config.spawn().toLocation(config.region().worldName()), () -> {
            if (!started || runtime != roundRuntime) return;
            runtime.arrived(id, context.elapsedTicks(), config.respawnDelayTicks());
        });
    }

    private void showDelay(RoundContext context, Player player) {
        long remaining = runtime.delayRemaining(player.getUniqueId(), context.elapsedTicks(), config.respawnDelayTicks());
        player.sendTitle(Text.color("&c&lDELAY"), Text.color("&e" + Math.max(1, (remaining + 19) / 20) + " s"), 0, 25, 0);
    }

    private void finishIfEveryoneCrossed(RoundContext context) {
        if (!context.participants().isEmpty() && context.participants().stream().allMatch(runtime::finished)) {
            context.requestFinish(RoundEndReason.MINIGAME_REQUEST);
        }
    }

    @Override public void handlePlayerQuit(RoundContext context, UUID id) {
        if (tutorial != null) tutorial.remove(context, id);
        bars.remove(id);
        previousBounds.remove(id);
        if (runtime != null) runtime.remove(id);
    }

    @Override public RoundResult finish(RoundContext context, RoundEndReason reason) {
        if (result != null) return result;
        started = false;
        Map<UUID, PlayerRoundResult> players = new LinkedHashMap<>();
        for (UUID id : context.participants()) {
            boolean complete = runtime != null && runtime.finished(id);
            players.put(id, new PlayerRoundResult(complete ? config.finishPoints() : 0, OptionalInt.empty(), complete, !complete, Map.of()));
        }
        result = new RoundResult(players, Map.of("game", id()));
        if (display != null) display.clear();
        return result;
    }

    @Override public void reset(RoundContext context) {
        started = false;
        if (display != null) display.clear();
        if (tutorial != null) tutorial.end(context);
        bars.clear();
        previousBounds.clear();
        for (Player player : context.onlineParticipants()) {
            player.sendActionBar(Text.component(""));
            Text.clearTitle(player);
        }
        runtime = null;
    }
}
