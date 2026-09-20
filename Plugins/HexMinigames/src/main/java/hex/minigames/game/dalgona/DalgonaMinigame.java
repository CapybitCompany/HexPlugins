package hex.minigames.game.dalgona;

import hex.minigames.config.ConfiguredSound;
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
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.UUID;

public final class DalgonaMinigame implements Minigame {
    private final Plugin plugin;
    private DalgonaConfig config;
    private DalgonaRuntime runtime;
    private BlockChangeTracker blocks;
    private StandardTutorial tutorial;
    private final BossBarDisplay bossBars = new BossBarDisplay();
    private RoundResult cachedResult;
    private boolean gameplayStarted;

    public DalgonaMinigame(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return DalgonaConfig.ID;
    }

    @Override
    public MinigameAvailability availability(MinigameDefinition definition, int players) {
        List<String> errors = new ArrayList<>();
        DalgonaConfig config = DalgonaConfig.fromDefinition(definition, errors);
        if (players > config.stations().size()) errors.add("Too many players for Dalgona stations: " + players + "/" + config.stations().size());
        return errors.isEmpty() ? MinigameAvailability.ok() : MinigameAvailability.unavailable(String.join("; ", errors));
    }

    @Override
    public void prepare(RoundContext context) {
        List<String> errors = new ArrayList<>();
        config = DalgonaConfig.fromDefinition(context.definition(), errors);
        if (!errors.isEmpty()) throw new IllegalStateException(String.join("; ", errors));
        runtime = new DalgonaRuntime(config, context.participants(), new Random());
        blocks = new BlockChangeTracker(config.region().worldName());
        tutorial = new StandardTutorial(plugin, config.tutorial());
        cachedResult = null;
        gameplayStarted = false;
        renderStations();
        bossBars.showAll(context.onlineParticipants(), config.bossBar());
        for (Player player : context.onlineParticipants()) {
            DalgonaConfig.Station station = runtime.station(player.getUniqueId());
            if (station == null) continue;
            Location target = station.spawn().toLocation(config.region().worldName());
            if (target != null) player.teleport(target);
        }
        tutorial.begin(context, context.onlineParticipants(), Map.of("duration", String.valueOf(config.roundDurationSeconds()), "pattern", runtime.pattern().id()));
    }

    @Override
    public int countdownSeconds(MinigameDefinition definition, int globalCountdownSeconds) {
        if (config != null) return config.tutorial().durationSeconds();
        List<String> errors = new ArrayList<>();
        DalgonaConfig parsed = DalgonaConfig.fromDefinition(definition, errors);
        return errors.isEmpty() ? parsed.tutorial().durationSeconds() : globalCountdownSeconds;
    }

    @Override
    public boolean handleCountdownTick(RoundContext context, int ticksRemaining) {
        return tutorial != null && tutorial.tick(context, ticksRemaining, Map.of("duration", String.valueOf(config.roundDurationSeconds()), "pattern", runtime.pattern().id()));
    }

    @Override
    public void start(RoundContext context) {
        if (tutorial != null) tutorial.end(context);
        gameplayStarted = true;
        for (Player player : context.onlineParticipants()) {
            if (context.state(player.getUniqueId()) == RoundPlayerState.ACTIVE) {
                player.setGameMode(org.bukkit.GameMode.SURVIVAL);
            }
        }
    }

    @Override
    public void handlePlayerQuit(RoundContext context, UUID playerId) {
        if (tutorial != null) tutorial.remove(context, playerId);
        bossBars.remove(playerId);
    }

    @Override
    public void handleTick(RoundContext context) {
        hex.minigames.game.common.RoundClock.show(context);
    }

    @Override
    public RoundResult finish(RoundContext context, RoundEndReason reason) {
        if (cachedResult != null) return cachedResult;
        Map<UUID, PlayerRoundResult> out = new LinkedHashMap<>();
        for (UUID playerId : context.participants()) {
            boolean completed = runtime != null && runtime.finished(playerId);
            boolean eliminated = runtime != null && runtime.eliminated(playerId);
            out.put(playerId, new PlayerRoundResult(
                    completed ? config.completePoints() : 0,
                    OptionalInt.empty(),
                    completed,
                    !completed || eliminated,
                    Map.of("completed", String.valueOf(completed))
            ));
        }
        cachedResult = new RoundResult(out, Map.of("game", id(), "pattern", runtime == null ? "-" : runtime.pattern().id()));
        return cachedResult;
    }

    @Override
    public void reset(RoundContext context) {
        if (tutorial != null) tutorial.end(context);
        bossBars.clear();
        if (blocks != null) blocks.restoreAll();
        for (Player player : context.onlineParticipants()) {
            player.sendActionBar(Text.component(""));
            Text.clearTitle(player);
        }
        gameplayStarted = false;
        runtime = null;
        blocks = null;
    }

    @Override
    public EventDecision onInteract(RoundContext context, PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!shouldProcessCut(gameplayStarted, context.state(player.getUniqueId()), event.getAction(), event.getHand())) return EventDecision.DENY;
        Block block = event.getClickedBlock();
        if (!canMine(context, player, block)) return EventDecision.DENY;
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.ALLOW);
        event.setUseItemInHand(org.bukkit.event.Event.Result.ALLOW);
        return EventDecision.ALLOW;
    }

    @Override
    public EventDecision onBlockBreak(RoundContext context, BlockBreakEvent event) {
        event.setDropItems(false);
        event.setExpToDrop(0);
        if (!canMine(context, event.getPlayer(), event.getBlock())) return EventDecision.DENY;
        return cutBlock(context, event.getPlayer(), event.getBlock());
    }

    @Override
    public EventDecision onBlockDamage(RoundContext context, BlockDamageEvent event) {
        if (!canMine(context, event.getPlayer(), event.getBlock())) return EventDecision.DENY;
        event.setInstaBreak(false);
        return EventDecision.ALLOW;
    }

    private boolean canMine(RoundContext context, Player player, Block block) {
        return gameplayStarted && runtime != null && block != null
                && context.state(player.getUniqueId()) == RoundPlayerState.ACTIVE
                && block.getWorld().getName().equals(config.region().worldName())
                && block.getType() == config.arenaMaterial()
                && runtime.canMine(player.getUniqueId(), new BlockPosition(block.getX(), block.getY(), block.getZ()));
    }

    static boolean isDalgonaCutAction(Action action, EquipmentSlot hand) {
        return action == Action.LEFT_CLICK_BLOCK && hand == EquipmentSlot.HAND;
    }

    static boolean shouldProcessCut(boolean gameplayStarted, RoundPlayerState state, Action action, EquipmentSlot hand) {
        return gameplayStarted && state == RoundPlayerState.ACTIVE && isDalgonaCutAction(action, hand);
    }

    private EventDecision cutBlock(RoundContext context, Player player, Block block) {
        BlockPosition position = new BlockPosition(block.getX(), block.getY(), block.getZ());
        DalgonaRuntime.BreakResult result = runtime.breakBlock(player.getUniqueId(), position);
        switch (result.outcome()) {
            case CORRECT -> blocks.setType(position, org.bukkit.Material.AIR);
            case COMPLETE -> {
                blocks.setType(position, org.bukkit.Material.AIR);
                context.state(player.getUniqueId(), RoundPlayerState.FINISHED);
            }
            case WRONG -> eliminate(context, player);
            case IGNORED -> {
            }
        }
        return EventDecision.DENY;
    }

    @Override
    public EventDecision onMove(RoundContext context, PlayerMoveEvent event) {
        if (runtime == null || context.state(event.getPlayer().getUniqueId()) == RoundPlayerState.GHOST) return EventDecision.PASS;
        DalgonaConfig.Station station = runtime.station(event.getPlayer().getUniqueId());
        if (station != null) {
            hex.minigames.game.common.StartBoundary.pushInside(event, station.arena());
        }
        return EventDecision.PASS;
    }

    private void renderStations() {
        for (DalgonaConfig.Station station : config.stations()) {
            blocks.fill(station.arena(), config.arenaMaterial());
            blocks.fill(station.board(), org.bukkit.Material.AIR);
            // Opaque background prevents the arena's original decoration showing through as a false target.
            blocks.fill(DalgonaRuntime.displayRegion(station), org.bukkit.Material.SMOOTH_SANDSTONE);
            for (BlockPosition block : runtime.boardBlocks(station)) {
                blocks.setType(block, config.boardMaterial());
            }
        }
    }

    private void eliminate(RoundContext context, Player player) {
        context.state(player.getUniqueId(), RoundPlayerState.GHOST);
        Location location = player.getLocation();
        World world = location.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.EXPLOSION, location, 1);
            world.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);
        }
        Vector current = player.getVelocity();
        player.setVelocity(new Vector(current.getX(), Math.max(current.getY(), config.wrongBlockUpwardVelocity()), current.getZ()));
    }

    private void play(Player player, ConfiguredSound configured) {
        if (configured == null || !configured.enabled()) return;
        Sound sound;
        try {
            sound = configured.bukkitSound();
        } catch (Throwable error) {
            plugin.getLogger().warning("Invalid Dalgona sound: " + configured.sound());
            return;
        }
        if (sound != null) player.playSound(player.getLocation(), sound, configured.volume(), configured.pitch());
    }
}
