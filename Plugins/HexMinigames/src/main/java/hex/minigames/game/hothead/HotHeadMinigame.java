package hex.minigames.game.hothead;

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
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class HotHeadMinigame implements Minigame {
    private final Plugin plugin;
    private HotHeadConfig config;
    private HotHeadRuntime runtime;
    private BlockChangeTracker blocks;
    private StandardTutorial tutorial;
    private final BossBarDisplay bossBars = new BossBarDisplay();
    private final Map<BlockPosition, RaisedBlock> raisedBlocks = new LinkedHashMap<>();
    private final Map<BlockPosition, Integer> raisedUsers = new LinkedHashMap<>();
    private final Map<UUID, Long> burningUntil = new LinkedHashMap<>();
    private RoundResult cachedResult;
    private Boolean previousFireDamage;

    public HotHeadMinigame(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return HotHeadConfig.ID;
    }

    @Override
    public MinigameAvailability availability(MinigameDefinition definition, int players) {
        List<String> errors = new ArrayList<>();
        HotHeadConfig.fromDefinition(definition, errors);
        return errors.isEmpty() ? MinigameAvailability.ok() : MinigameAvailability.unavailable(String.join("; ", errors));
    }

    @Override
    public void prepare(RoundContext context) {
        List<String> errors = new ArrayList<>();
        config = HotHeadConfig.fromDefinition(context.definition(), errors);
        if (!errors.isEmpty()) throw new IllegalStateException(String.join("; ", errors));
        runtime = new HotHeadRuntime(config, new Random());
        blocks = new BlockChangeTracker(config.region().worldName());
        tutorial = new StandardTutorial(plugin, config.tutorial());
        cachedResult = null;
        previousFireDamage = null;
        bossBars.showAll(context.onlineParticipants(), config.bossBar());
        tutorial.begin(context, context.onlineParticipants(), Map.of("duration", String.valueOf(config.roundDurationSeconds())));
    }

    @Override
    public int countdownSeconds(MinigameDefinition definition, int globalCountdownSeconds) {
        if (config != null) return config.tutorial().durationSeconds();
        List<String> errors = new ArrayList<>();
        HotHeadConfig parsed = HotHeadConfig.fromDefinition(definition, errors);
        return errors.isEmpty() ? parsed.tutorial().durationSeconds() : globalCountdownSeconds;
    }

    @Override
    public boolean handleCountdownTick(RoundContext context, int ticksRemaining) {
        return tutorial != null && tutorial.tick(context, ticksRemaining, Map.of("duration", String.valueOf(config.roundDurationSeconds())));
    }

    @Override
    public boolean finishWhenAllActiveResolved() {
        return false;
    }

    @Override
    public int startDelayTicks() { return 100; }

    @Override
    public void start(RoundContext context) {
        if (runtime == null) return;
        tutorial.end(context);
        enableFireDamage();
        runtime.start(context.elapsedTicks());
        for (Player player : context.onlineParticipants()) {
            Location target = config.gameplaySpawn().toLocation(config.region().worldName());
            if (target != null) player.teleport(target);
        }
    }

    @Override
    public void handleTick(RoundContext context) {
        if (runtime == null || blocks == null) return;
        hex.minigames.game.common.RoundClock.show(context);
        for (Player player : context.onlineParticipants()) {
            if (context.state(player.getUniqueId()) != RoundPlayerState.ACTIVE) continue;
            long remaining = burningUntil.getOrDefault(player.getUniqueId(), 0L) - context.elapsedTicks();
            if (remaining > 0) player.setFireTicks((int) remaining);
            else if (burningUntil.remove(player.getUniqueId()) != null) player.setFireTicks(0);
        }
        long roundTicks = Math.max(1L, config.roundDurationSeconds() * 20L);
        for (HotHeadRuntime.Command command : runtime.tick(context.elapsedTicks(), roundTicks)) {
            if (command.type() == HotHeadRuntime.CommandType.ACCELERATION_NOTICE) {
                for (Player player : context.onlineParticipants()) {
                    player.sendTitle("", Text.color("&6Przyspieszamy!"), 0, 40, 0);
                }
            } else {
                apply(command);
            }
        }
    }

    @Override
    public void handlePlayerQuit(RoundContext context, UUID playerId) {
        if (tutorial != null) tutorial.remove(context, playerId);
        bossBars.remove(playerId);
    }

    @Override
    public RoundResult finish(RoundContext context, RoundEndReason reason) {
        if (cachedResult != null) return cachedResult;
        Map<UUID, PlayerRoundResult> out = new LinkedHashMap<>();
        for (UUID playerId : context.participants()) {
            boolean survived = context.state(playerId) == RoundPlayerState.ACTIVE;
            out.put(playerId, new PlayerRoundResult(
                    survived ? config.survivorPoints() : 0,
                    OptionalInt.empty(),
                    survived,
                    !survived,
                    Map.of("survived", String.valueOf(survived))
            ));
        }
        cachedResult = new RoundResult(out, Map.of("game", id()));
        return cachedResult;
    }

    @Override
    public void reset(RoundContext context) {
        if (tutorial != null) tutorial.end(context);
        bossBars.clear();
        if (blocks != null) blocks.restoreAll();
        raisedBlocks.clear();
        raisedUsers.clear();
        burningUntil.clear();
        restoreFireDamage();
        for (Player player : context.onlineParticipants()) {
            player.setFireTicks(0);
            player.sendActionBar(Text.component(""));
            player.sendTitle("", "", 0, 1, 0);
        }
        runtime = null;
        blocks = null;
    }

    @Override
    public EventDecision onDamage(RoundContext context, EntityDamageEvent event) {
        if (context.startDelayRemaining() > 0) return EventDecision.DENY;
        if (!(event.getEntity() instanceof Player player)) return EventDecision.DENY;
        if (context.state(player.getUniqueId()) != RoundPlayerState.ACTIVE) return EventDecision.DENY;
        if (event.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK
                && context.elapsedTicks() >= burningUntil.getOrDefault(player.getUniqueId(), 0L)) {
            player.setFireTicks(0);
            return EventDecision.DENY;
        }
        if (isFireDamage(event.getCause()) && config.allowFireDamage() && inHotHeadRegion(player)) {
            if (event.getFinalDamage() >= player.getHealth()) {
                eliminate(context, player);
                return EventDecision.DENY;
            }
            if (event.getCause() != EntityDamageEvent.DamageCause.FIRE_TICK) {
                burningUntil.put(player.getUniqueId(), context.elapsedTicks() + 40L);
                player.setFireTicks(40);
            }
            return EventDecision.ALLOW;
        }
        if (isHazard(event.getCause())) {
            eliminate(context, player);
        }
        return EventDecision.DENY;
    }

    @Override
    public EventDecision onRegainHealth(RoundContext context, org.bukkit.event.entity.EntityRegainHealthEvent event) {
        return EventDecision.DENY;
    }

    private void apply(HotHeadRuntime.Command command) {
        switch (command.type()) {
            case LAMP_ON -> setLamps(command.positions(), true);
            case LAMP_OFF -> setLamps(command.positions(), false);
            case SEGMENT_ON -> raiseSegment(command.positions());
            case SEGMENT_OFF -> lowerSegment(command.positions());
        }
    }

    private void raiseSegment(List<BlockPosition> positions) {
        World world = Bukkit.getWorld(config.region().worldName());
        if (world == null || blocks == null) return;
        for (BlockPosition floorPosition : positions) {
            raisedUsers.merge(floorPosition, 1, Integer::sum);
            if (raisedBlocks.containsKey(floorPosition)) continue;
            Block floor = world.getBlockAt(floorPosition.x(), floorPosition.y(), floorPosition.z());
            BlockPosition raisedPosition = raisedPosition(floorPosition);
            Block raised = world.getBlockAt(raisedPosition.x(), raisedPosition.y(), raisedPosition.z());
            BlockData floorData = floor.getBlockData();
            BlockData raisedData = raised.getBlockData();
            raisedBlocks.put(floorPosition, new RaisedBlock(floorData, raisedData));
            BlockData movedFloor = floorData.getMaterial().isAir()
                    ? config.segmentMaterial().createBlockData()
                    : floorData;
            blocks.setBlockData(raisedPosition, movedFloor);
            blocks.setType(floorPosition, Material.AIR);
        }
        pushPlayersAbove(positions);
        playNearby(positions, Sound.BLOCK_PISTON_EXTEND);
    }

    private void lowerSegment(List<BlockPosition> positions) {
        if (blocks == null) return;
        for (BlockPosition floorPosition : positions) {
            int remaining = raisedUsers.getOrDefault(floorPosition, 1) - 1;
            if (remaining > 0) {
                raisedUsers.put(floorPosition, remaining);
                continue;
            }
            raisedUsers.remove(floorPosition);
            RaisedBlock raised = raisedBlocks.remove(floorPosition);
            if (raised == null) continue;
            BlockPosition raisedPosition = raisedPosition(floorPosition);
            blocks.setBlockData(floorPosition, raised.floorData());
            blocks.setBlockData(raisedPosition, raised.raisedData());
        }
        playNearby(positions, Sound.BLOCK_PISTON_CONTRACT);
    }

    static BlockPosition raisedPosition(BlockPosition floorPosition) {
        return new BlockPosition(floorPosition.x(), floorPosition.y() + 1, floorPosition.z());
    }

    private void pushPlayersAbove(List<BlockPosition> positions) {
        if (positions.isEmpty()) return;
        Set<BlockPosition> floors = new HashSet<>(positions);
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location location = player.getLocation();
            if (location.getWorld() == null || !location.getWorld().getName().equals(config.region().worldName())) continue;
            int x = (int) Math.floor(location.getX());
            int z = (int) Math.floor(location.getZ());
            for (BlockPosition floor : floors) {
                if (floor.x() != x || floor.z() != z) continue;
                if (location.getY() < floor.y() + 0.9 || location.getY() > floor.y() + 2.4) continue;
                Vector velocity = player.getVelocity();
                player.setVelocity(new Vector(velocity.getX(), Math.max(velocity.getY(), 0.65), velocity.getZ()));
                break;
            }
        }
    }

    private void setLamps(List<BlockPosition> positions, boolean lit) {
        BlockData data = Bukkit.createBlockData(Material.REDSTONE_LAMP);
        if (data instanceof Lightable lightable) {
            lightable.setLit(lit);
            data = lightable;
        }
        for (BlockPosition position : positions) blocks.setBlockData(position, data);
    }

    private void eliminate(RoundContext context, Player player) {
        burningUntil.remove(player.getUniqueId());
        context.state(player.getUniqueId(), RoundPlayerState.GHOST);
        player.setGameMode(org.bukkit.GameMode.SPECTATOR);
    }

    @Override
    public java.util.Optional<hex.minigames.model.CuboidRegion> ghostRegion(MinigameDefinition definition) {
        return definition.region().map(region -> new hex.minigames.model.CuboidRegion(region.worldName(),
                new BlockPosition(-27, -32, 37), new BlockPosition(-10, -30, 54)));
    }

    static boolean isFireDamage(EntityDamageEvent.DamageCause cause) {
        return cause == EntityDamageEvent.DamageCause.FIRE
                || cause == EntityDamageEvent.DamageCause.FIRE_TICK
                || cause == EntityDamageEvent.DamageCause.LAVA
                || cause == EntityDamageEvent.DamageCause.HOT_FLOOR;
    }

    static boolean isHazard(EntityDamageEvent.DamageCause cause) {
        return cause == EntityDamageEvent.DamageCause.FIRE
                || cause == EntityDamageEvent.DamageCause.FIRE_TICK
                || cause == EntityDamageEvent.DamageCause.LAVA
                || cause == EntityDamageEvent.DamageCause.HOT_FLOOR
                || cause == EntityDamageEvent.DamageCause.CONTACT
                || cause == EntityDamageEvent.DamageCause.VOID;
    }

    private boolean inHotHeadRegion(Player player) {
        return config != null && config.region() != null && config.region().contains(player.getLocation());
    }

    private void enableFireDamage() {
        World world = Bukkit.getWorld(config.region().worldName());
        if (world == null || !config.allowFireDamage()) return;
        previousFireDamage = world.getGameRuleValue(GameRule.FIRE_DAMAGE);
        world.setGameRule(GameRule.FIRE_DAMAGE, true);
    }

    private void restoreFireDamage() {
        World world = config == null || config.region() == null ? null : Bukkit.getWorld(config.region().worldName());
        if (world != null && previousFireDamage != null) {
            world.setGameRule(GameRule.FIRE_DAMAGE, previousFireDamage);
        }
        previousFireDamage = null;
    }

    private void playNearby(List<BlockPosition> positions, Sound sound) {
        if (positions.isEmpty()) return;
        BlockPosition first = positions.get(0);
        World world = Bukkit.getWorld(config.region().worldName());
        if (world == null) return;
        world.playSound(new Location(world, first.x() + 0.5, first.y() + 0.5, first.z() + 0.5), sound, 1.0f, 1.0f);
    }

    private record RaisedBlock(BlockData floorData, BlockData raisedData) {
    }
}
