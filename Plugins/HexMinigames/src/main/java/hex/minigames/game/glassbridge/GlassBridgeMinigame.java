package hex.minigames.game.glassbridge;

import hex.minigames.game.EventDecision;
import hex.minigames.game.Minigame;
import hex.minigames.game.MinigameAvailability;
import hex.minigames.game.MinigameDefinition;
import hex.minigames.game.PlayerRoundResult;
import hex.minigames.game.RoundContext;
import hex.minigames.game.RoundEndReason;
import hex.minigames.game.RoundResult;
import hex.minigames.game.common.BlockChangeTracker;
import hex.minigames.game.common.BossBarDisplay;
import hex.minigames.game.common.StandardTutorial;
import hex.minigames.model.BlockPosition;
import hex.minigames.runtime.RoundPlayerState;
import hex.minigames.util.Text;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.UUID;

public final class GlassBridgeMinigame implements Minigame {
    private final Plugin plugin;
    private GlassBridgeConfig config;
    private GlassBridgeRuntime runtime;
    private BlockChangeTracker blocks;
    private StandardTutorial tutorial;
    private final BossBarDisplay bossBars = new BossBarDisplay();
    private final Map<UUID, BukkitTask> respawnDelayTasks = new HashMap<>();
    private RoundResult cachedResult;
    private boolean started;
    private GlassBridgePvpSchedule pvpSchedule;
    private boolean pvpAnnounced;

    public GlassBridgeMinigame(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return GlassBridgeConfig.ID;
    }

    @Override
    public MinigameAvailability availability(MinigameDefinition definition, int players) {
        List<String> errors = new ArrayList<>();
        GlassBridgeConfig config = GlassBridgeConfig.fromDefinition(definition, errors);
        if (config.pairs().size() != 11) errors.add("GlassBridge must contain exactly 11 pairs");
        return errors.isEmpty() ? MinigameAvailability.ok() : MinigameAvailability.unavailable(String.join("; ", errors));
    }

    @Override
    public void prepare(RoundContext context) {
        List<String> errors = new ArrayList<>();
        config = GlassBridgeConfig.fromDefinition(context.definition(), errors);
        if (!errors.isEmpty()) throw new IllegalStateException(String.join("; ", errors));
        runtime = new GlassBridgeRuntime(config, context.participants(), new Random());
        blocks = new BlockChangeTracker(config.region().worldName());
        tutorial = new StandardTutorial(plugin, config.tutorial());
        cachedResult = null;
        started = false;
        pvpSchedule = new GlassBridgePvpSchedule(config.roundDurationSeconds() * 20L, new Random());
        pvpAnnounced = false;
        for (GlassBridgeConfig.Pair pair : config.pairs()) {
            for (BlockPosition block : pair.left().blocks()) blocks.setType(block, config.glassMaterial());
            for (BlockPosition block : pair.right().blocks()) blocks.setType(block, config.glassMaterial());
        }
        bossBars.showAll(context.onlineParticipants(), config.bossBar());
        tutorial.begin(context, context.onlineParticipants(), Map.of("duration", String.valueOf(config.roundDurationSeconds())));
    }

    @Override
    public int countdownSeconds(MinigameDefinition definition, int globalCountdownSeconds) {
        if (config != null) return config.tutorial().durationSeconds();
        List<String> errors = new ArrayList<>();
        GlassBridgeConfig parsed = GlassBridgeConfig.fromDefinition(definition, errors);
        return errors.isEmpty() ? parsed.tutorial().durationSeconds() : globalCountdownSeconds;
    }

    @Override
    public boolean handleCountdownTick(RoundContext context, int ticksRemaining) {
        return tutorial != null && tutorial.tick(context, ticksRemaining, Map.of("duration", String.valueOf(config.roundDurationSeconds())));
    }

    @Override
    public boolean finishWhenAllActiveResolved() {
        // A player waiting to respawn is still taking part in the bridge race.
        return false;
    }

    @Override
    public void start(RoundContext context) {
        if (tutorial != null) tutorial.end(context);
        started = true;
    }

    @Override
    public void handlePlayerQuit(RoundContext context, UUID playerId) {
        if (tutorial != null) tutorial.remove(context, playerId);
        bossBars.remove(playerId);
        cancelRespawnDelayTask(playerId);
    }

    @Override
    public RoundResult finish(RoundContext context, RoundEndReason reason) {
        if (cachedResult != null) return cachedResult;
        announcePvp(context, false);
        started = false;
        Map<UUID, PlayerRoundResult> out = new LinkedHashMap<>();
        for (UUID playerId : context.participants()) {
            int score = runtime == null ? 0 : runtime.score(playerId);
            double meters = runtime == null ? 0 : runtime.preciseProgressMeters(playerId);
            boolean finished = runtime != null && runtime.finished(playerId);
            out.put(playerId, new PlayerRoundResult(
                    score,
                    OptionalInt.empty(),
                    finished,
                    !finished,
                    Map.of("progress", String.valueOf(meters), "meters", String.valueOf(meters))
            ));
        }
        cachedResult = new RoundResult(out, Map.of("game", id()));
        return cachedResult;
    }

    @Override
    public void reset(RoundContext context) {
        if (tutorial != null) tutorial.end(context);
        bossBars.clear();
        for (UUID playerId : List.copyOf(respawnDelayTasks.keySet())) cancelRespawnDelayTask(playerId);
        if (blocks != null) blocks.restoreAll();
        for (Player player : context.onlineParticipants()) {
            player.sendActionBar(Text.component(""));
            player.sendTitle("", "", 0, 1, 0);
        }
        started = false;
        pvpSchedule = null;
        pvpAnnounced = false;
        runtime = null;
        blocks = null;
    }

    @Override
    public EventDecision onMove(RoundContext context, PlayerMoveEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        RoundPlayerState state = context.state(playerId);
        if (!started) {
            if (crossesStartBoundary(config.startBoundary(), event.getFrom(), event.getTo())
                    || pastStartBoundary(config.startBoundary(), event.getTo())) {
                hex.minigames.game.common.StartBoundary.pushBack(event, config.startBoundary().maxZ(), -1);
            }
            return EventDecision.PASS;
        }
        if (state == RoundPlayerState.RESPAWN_DELAY) {
            if (pastStartBoundary(config.startBoundary(), event.getTo())) {
                hex.minigames.game.common.StartBoundary.pushBack(event, config.startBoundary().maxZ(), -1);
            }
            return EventDecision.PASS;
        }
        if (state != RoundPlayerState.ACTIVE) return EventDecision.PASS;
        // The client may already be taking off while the server receives the landing.
        // Check the previous feet position too, before accepting another jump.
        if (breakUnsafeContact(context, event.getPlayer(), event.getFrom()) && event.getTo() != null
                && event.getTo().getY() > event.getFrom().getY()) {
            event.setTo(event.getFrom().clone());
            var velocity = event.getPlayer().getVelocity();
            event.getPlayer().setVelocity(velocity.setY(Math.min(0, velocity.getY())));
            return EventDecision.PASS;
        }
        if (breakUnsafeLandingBetweenPositions(context, event)) return EventDecision.PASS;
        return updateActivePlayer(context, event.getPlayer(), event.getTo(), true);
    }

    @Override
    public void handleTick(RoundContext context) {
        if (runtime == null || config == null) return;
        announcePvp(context, started && pvpSchedule != null && pvpSchedule.active(context.elapsedTicks()));
        for (Player player : context.onlineParticipants()) {
            if (context.state(player.getUniqueId()) == RoundPlayerState.ACTIVE) {
                updateActivePlayer(context, player, player.getLocation(), false);
            }
            if (context.elapsedTicks() % config.actionbarUpdateTicks() == 0L) sendActionbar(context, player);
        }
        if (!context.participants().isEmpty() && context.participants().stream().allMatch(runtime::finished)) {
            context.requestFinish(RoundEndReason.MINIGAME_REQUEST);
        }
    }

    @Override
    public EventDecision onDamage(RoundContext context, EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return EventDecision.DENY;
        if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent attack) {
            Player attacker = attack.getDamager() instanceof Player direct ? direct
                    : attack.getDamager() instanceof org.bukkit.entity.Projectile projectile
                    && projectile.getShooter() instanceof Player shooter ? shooter : null;
            if (!started || pvpSchedule == null || !pvpSchedule.active(context.elapsedTicks())
                    || context.startDelayRemaining() > 0 || attacker == null
                    || !context.participants().contains(attacker.getUniqueId())
                    || context.state(attacker.getUniqueId()) != RoundPlayerState.ACTIVE
                    || context.state(player.getUniqueId()) != RoundPlayerState.ACTIVE) return EventDecision.DENY;
            if (event.getFinalDamage() >= player.getHealth()) {
                beginRespawnDelay(context, player);
                return EventDecision.DENY;
            }
            return EventDecision.ALLOW;
        }
        if (context.state(player.getUniqueId()) == RoundPlayerState.ACTIVE
                && (event.getCause() == EntityDamageEvent.DamageCause.VOID
                || event.getCause() == EntityDamageEvent.DamageCause.LAVA
                || event.getCause() == EntityDamageEvent.DamageCause.FALL)) {
            beginRespawnDelay(context, player);
        }
        return EventDecision.DENY;
    }

    private EventDecision updateActivePlayer(RoundContext context, Player player, Location location, boolean movementEvent) {
        if (location == null || runtime == null) return EventDecision.PASS;
        UUID playerId = player.getUniqueId();
        if (location.getY() <= config.fallY()) {
            beginRespawnDelay(context, player);
            return EventDecision.PASS;
        }
        if (location.getWorld() == null || !location.getWorld().getName().equals(config.region().worldName())) return EventDecision.PASS;
        runtime.updateProgress(playerId, location.getX(), location.getY(), location.getZ());
        if (config.finishRegion().contains(location)) {
            if (runtime.finish(playerId)) {
                context.state(playerId, RoundPlayerState.FINISHED);
                sendActionbar(context, player);
                if (context.participants().stream().allMatch(runtime::finished)) {
                    context.requestFinish(RoundEndReason.MINIGAME_REQUEST);
                }
            }
            return EventDecision.PASS;
        }
        breakUnsafeContact(context, player, location);
        return EventDecision.PASS;
    }

    /** Removes fragile glass without eliminating the player; gravity handles the fall. */
    private boolean breakUnsafeContact(RoundContext context, Player player, Location location) {
        BlockPosition top = topSurfaceBlock(location);
        if (top == null) return false;
        GlassBridgeRuntime.LandingResult result = runtime.land(player.getUniqueId(), top);
        if (result.outcome() != GlassBridgeRuntime.LandingOutcome.BAD_BROKEN
                && result.outcome() != GlassBridgeRuntime.LandingOutcome.BAD_ALREADY_BROKEN) return false;
        if (result.outcome() == GlassBridgeRuntime.LandingOutcome.BAD_BROKEN) breakPlatform(result.platform());
        return true;
    }

    @Override
    public EventDecision onJump(RoundContext context, com.destroystokyo.paper.event.player.PlayerJumpEvent event) {
        if (!started || runtime == null || context.state(event.getPlayer().getUniqueId()) != RoundPlayerState.ACTIVE) return EventDecision.ALLOW;
        if (!breakUnsafeContact(context, event.getPlayer(), event.getFrom())) return EventDecision.ALLOW;
        event.getPlayer().setVelocity(new org.bukkit.util.Vector(0, -0.08, 0));
        return EventDecision.DENY;
    }

    /** Detect descending contact even when a movement packet ends below the pane's top surface. */
    private boolean breakUnsafeLandingBetweenPositions(RoundContext context, PlayerMoveEvent event) {
        Location from = event.getFrom(), to = event.getTo();
        if (to == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld()) || to.getY() >= from.getY()) return false;
        for (GlassBridgeConfig.Pair pair : config.pairs()) {
            double deck = pair.left().region().maxY() + 1.0;
            if (from.getY() <= deck || to.getY() > deck) continue;
            double fraction = (from.getY() - deck) / (from.getY() - to.getY());
            Location contact = new Location(from.getWorld(),
                    from.getX() + (to.getX() - from.getX()) * fraction, deck,
                    from.getZ() + (to.getZ() - from.getZ()) * fraction);
            if (breakUnsafeContact(context, event.getPlayer(), contact)) return true;
        }
        return false;
    }

    private BlockPosition topSurfaceBlock(Location to) {
        if (to == null || to.getWorld() == null || !to.getWorld().getName().equals(config.region().worldName())) return null;
        for (GlassBridgeConfig.Pair pair : config.pairs()) {
            for (GlassBridgeConfig.Platform platform : List.of(pair.left(), pair.right())) {
                var region = platform.region();
                double feetY = region.maxY() + 1.0;
                if (to.getY() < feetY - 0.05 || to.getY() > feetY + 0.06) continue;
                // A player's feet can touch an edge before their centre enters the block.
                if (to.getX() + 0.3 <= region.minX() || to.getX() - 0.3 >= region.maxX() + 1
                        || to.getZ() + 0.3 <= region.minZ() || to.getZ() - 0.3 >= region.maxZ() + 1) continue;
                return new BlockPosition(Math.clamp(to.getBlockX(), region.minX(), region.maxX()),
                        region.maxY(), Math.clamp(to.getBlockZ(), region.minZ(), region.maxZ()));
            }
        }
        return null;
    }

    private void breakPlatform(GlassBridgeConfig.Platform platform) {
        if (platform == null) return;
        for (BlockPosition block : platform.blocks()) blocks.setType(block, org.bukkit.Material.AIR);
        BlockPosition first = platform.blocks().isEmpty() ? null : platform.blocks().get(0);
        org.bukkit.World world = first == null ? null : org.bukkit.Bukkit.getWorld(config.region().worldName());
        if (world != null) {
            Location location = new Location(world, first.x() + 0.5, first.y() + 0.5, first.z() + 0.5);
            world.playSound(location, "minecraft:block.glass.break", 1.0f, 1.0f);
            world.spawnParticle(Particle.BLOCK, location, 20, config.glassMaterial().createBlockData());
        }
    }

    private void beginRespawnDelay(RoundContext context, Player player) {
        UUID playerId = player.getUniqueId();
        if (runtime == null || !runtime.markFall(playerId)) return;
        cancelRespawnDelayTask(playerId);
        hex.minigames.game.common.RespawnEffects.explosion(player);
        context.state(playerId, RoundPlayerState.RESPAWN_DELAY);
        sendRespawnDelayTitle(player, config.respawnDelayTicks());
        Location target = config.respawnSpawn().toLocation(config.region().worldName());
        context.respawn(player, target, () -> startRespawnDelay(context, player));
    }

    private void startRespawnDelay(RoundContext context, Player player) {
        UUID playerId = player.getUniqueId();
        sendActionbar(context, player);
        if (config.respawnDelayTicks() <= 0) {
            finishRespawnDelay(context, playerId, player);
            return;
        }
        BukkitTask task = new BukkitRunnable() {
            private int remainingTicks = config.respawnDelayTicks();

            @Override
            public void run() {
                remainingTicks = Math.max(0, remainingTicks - 20);
                if (runtime == null || !player.isOnline()) {
                    respawnDelayTasks.remove(playerId);
                    cancel();
                    return;
                }
                if (remainingTicks <= 0) {
                    respawnDelayTasks.remove(playerId);
                    finishRespawnDelay(context, playerId, player);
                    cancel();
                    return;
                }
                sendRespawnDelayTitle(player, remainingTicks);
            }
        }.runTaskTimer(plugin, 20L, 20L);
        respawnDelayTasks.put(playerId, task);
    }

    private void finishRespawnDelay(RoundContext context, UUID playerId, Player player) {
        if (runtime == null) return;
        if (context.state(playerId) != RoundPlayerState.RESPAWN_DELAY) return;
        runtime.respawn(playerId);
        context.state(playerId, RoundPlayerState.ACTIVE);
        Text.clearTitle(player);
        sendActionbar(context, player);
    }

    private void sendRespawnDelayTitle(Player player, int ticks) {
        int seconds = Math.max(1, (int) Math.ceil(ticks / 20.0));
        player.sendTitle(
                Text.color("&c&lDELAY"),
                Text.color("&e" + seconds + " s"),
                0,
                25,
                0
        );
    }

    private void sendActionbar(RoundContext context, Player player) {
        if (runtime == null || config == null || player == null) return;
        long remainingTicks = Math.max(0L, config.roundDurationSeconds() * 20L - context.elapsedTicks());
        String message = config.actionbar()
                .replace("{remaining}", formatTicks(remainingTicks))
                .replace("{meters}", String.format(java.util.Locale.forLanguageTag("pl-PL"), "%.2f", runtime.preciseProgressMeters(player.getUniqueId())));
        player.sendActionBar(Text.component(message + (pvpAnnounced ? " &7| &6PVP &aON" : " &7| &6PVP &cOFF")));
    }

    /** Immediately refreshes the action bar and sounds only on PvP transitions. */
    private void announcePvp(RoundContext context, boolean enabled) {
        if (pvpAnnounced == enabled) return;
        pvpAnnounced = enabled;
        for (Player player : context.onlineParticipants()) {
            sendActionbar(context, player);
            player.playSound(player.getLocation(), enabled ? "minecraft:block.note_block.bell"
                    : "minecraft:block.note_block.chime", 0.8f, enabled ? 1.2f : 0.7f);
        }
    }

    private String formatTicks(long ticks) {
        long seconds = Math.max(0L, ticks / 20L);
        long minutes = seconds / 60L;
        long rest = seconds % 60L;
        return String.format("%02d:%02d", minutes, rest);
    }

    static boolean crossesStartBoundary(GlassBridgeConfig config, Location from, Location to) {
        if (config == null) return false;
        return crossesStartBoundary(config.startBoundary(), from, to);
    }

    static boolean crossesStartBoundary(hex.minigames.model.CuboidRegion boundary, Location from, Location to) {
        if (boundary == null || from == null || to == null) return false;
        int blockX = (int) Math.floor(to.getX());
        return blockX >= boundary.minX()
                && blockX <= boundary.maxX()
                && from.getZ() >= boundary.maxZ()
                && to.getZ() < boundary.maxZ();
    }

    static boolean pastStartBoundary(hex.minigames.model.CuboidRegion boundary, Location to) {
        if (boundary == null || to == null) return false;
        return to.getZ() < boundary.maxZ();
    }

    private void cancelRespawnDelayTask(UUID playerId) {
        BukkitTask task = respawnDelayTasks.remove(playerId);
        if (task != null) task.cancel();
    }
}
