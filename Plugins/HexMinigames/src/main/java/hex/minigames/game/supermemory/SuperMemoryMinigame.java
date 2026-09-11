package hex.minigames.game.supermemory;

import hex.minigames.config.ConfiguredSound;
import hex.minigames.game.EventDecision;
import hex.minigames.game.Minigame;
import hex.minigames.game.MinigameAvailability;
import hex.minigames.game.MinigameDefinition;
import hex.minigames.game.RoundContext;
import hex.minigames.game.RoundEndReason;
import hex.minigames.game.RoundResult;
import hex.minigames.model.BlockPosition;
import hex.minigames.model.LocationSpec;
import hex.minigames.runtime.RoundPlayerState;
import hex.minigames.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class SuperMemoryMinigame implements Minigame {
    private final Plugin plugin;
    private SuperMemoryConfig config;
    private SuperMemoryRuntime runtime;
    private RoundResult cachedResult;
    private final Map<UUID, BukkitTask> resetTasks = new HashMap<>();
    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private final TemporaryMuteRegistry mutedPlayers = new TemporaryMuteRegistry();

    public SuperMemoryMinigame(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return SuperMemoryConfig.ID;
    }

    @Override
    public MinigameAvailability availability(MinigameDefinition definition, int players) {
        List<String> errors = new ArrayList<>();
        SuperMemoryConfig loaded = SuperMemoryConfig.fromDefinition(definition, errors);
        if (!errors.isEmpty()) return MinigameAvailability.unavailable(String.join("; ", errors));
        if (players > loaded.stations().size()) {
            return MinigameAvailability.unavailable("Too many players for configured SuperMemory stations: " + players + "/" + loaded.stations().size());
        }
        return MinigameAvailability.ok();
    }

    @Override
    public void prepare(RoundContext context) {
        List<String> errors = new ArrayList<>();
        config = SuperMemoryConfig.fromDefinition(context.definition(), errors);
        if (!errors.isEmpty()) {
            throw new IllegalStateException(String.join("; ", errors));
        }
        cachedResult = null;
        runtime = new SuperMemoryRuntime(config, context.participants(), new Random(), this::setBlock);
        for (Player player : context.onlineParticipants()) {
            SuperMemoryConfig.StationConfig station = runtime.station(player.getUniqueId());
            if (station == null) continue;
            addBossBar(player);
            teleport(player, station.spawn());
            sendTutorialChat(player);
            mute(player);
            sendTutorialTitle(player, config.tutorialDurationSeconds());
        }
    }

    @Override
    public int countdownSeconds(MinigameDefinition definition, int globalCountdownSeconds) {
        if (config != null) return config.tutorialDurationSeconds();
        List<String> errors = new ArrayList<>();
        SuperMemoryConfig parsed = SuperMemoryConfig.fromDefinition(definition, errors);
        return errors.isEmpty() ? parsed.tutorialDurationSeconds() : globalCountdownSeconds;
    }

    @Override
    public boolean handleCountdownTick(RoundContext context, int ticksRemaining) {
        if (config == null || runtime == null) return false;
        int seconds = Math.max(0, ticksRemaining / 20);
        for (Player player : context.onlineParticipants()) {
            if (runtime.station(player.getUniqueId()) == null) continue;
            sendTutorialTitle(player, seconds);
            if (seconds >= 1 && seconds <= 3) play(player, config.tutorialTickSound());
        }
        return true;
    }

    @Override
    public void start(RoundContext context) {
        if (runtime == null) return;
        unmuteAll();
        runtime.start(System.nanoTime());
        for (Player player : context.onlineParticipants()) {
            player.sendTitle("", "", 0, 1, 0);
            sendActionbar(player);
        }
    }

    @Override
    public void handleTick(RoundContext context) {
        if (runtime == null) return;
        if (context.elapsedTicks() % 10L == 0L) {
            for (Player player : context.onlineParticipants()) {
                sendActionbar(player);
            }
        }
        if (runtime.allResolved()) {
            context.requestFinish(RoundEndReason.ALL_ELIMINATED);
        }
    }

    @Override
    public void handlePlayerQuit(RoundContext context, UUID playerId) {
        cancelReset(playerId);
        unmute(playerId);
        removeBossBar(playerId);
        if (runtime != null) runtime.remove(playerId);
        if (runtime != null && runtime.allResolved()) {
            context.requestFinish(RoundEndReason.PLAYER_QUIT);
        }
    }

    @Override
    public RoundResult finish(RoundContext context, RoundEndReason reason) {
        if (cachedResult != null) return cachedResult;
        if (runtime == null) return RoundResult.empty();
        runtime.markTimeouts();
        cachedResult = runtime.result();
        sendResults(context, cachedResult);
        return cachedResult;
    }

    @Override
    public void reset(RoundContext context) {
        for (Player player : context.onlineParticipants()) {
            player.sendTitle("", "", 0, 1, 0);
            player.sendActionBar(Text.component(""));
        }
        for (UUID playerId : List.copyOf(resetTasks.keySet())) {
            cancelReset(playerId);
        }
        unmuteAll();
        for (UUID playerId : List.copyOf(bossBars.keySet())) {
            removeBossBar(playerId);
        }
        if (runtime != null) {
            runtime.cleanup(this::setBlock);
            runtime = null;
        } else if (config != null) {
            for (SuperMemoryConfig.StationConfig station : config.stations()) {
                for (BlockPosition position : station.clickBlocks()) setBlock(position, config.idleMaterial());
            }
        }
    }

    @Override
    public EventDecision onMove(RoundContext context, PlayerMoveEvent event) {
        if (runtime == null || config == null) return EventDecision.DENY;
        Player player = event.getPlayer();
        SuperMemoryConfig.StationConfig station = runtime.station(player.getUniqueId());
        if (station == null) return EventDecision.DENY;
        Location to = event.getTo();
        if (to != null && station.region().contains(to)) return EventDecision.PASS;
        teleport(player, station.spawn());
        return EventDecision.DENY;
    }

    @Override
    public EventDecision onInteract(RoundContext context, PlayerInteractEvent event) {
        if (runtime == null || config == null) return EventDecision.DENY;
        if (event.getHand() != EquipmentSlot.HAND) return EventDecision.DENY;
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) return EventDecision.DENY;
        Block clicked = event.getClickedBlock();
        if (clicked == null) return EventDecision.DENY;
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (context.state(playerId) != RoundPlayerState.ACTIVE) return EventDecision.DENY;
        SuperMemoryConfig.StationConfig station = runtime.station(playerId);
        if (station == null || !station.region().contains(clicked.getLocation())) return EventDecision.DENY;

        BlockPosition position = new BlockPosition(clicked.getX(), clicked.getY(), clicked.getZ());
        if (!station.clickBlocks().contains(position)) return EventDecision.DENY;

        SuperMemoryRuntime.ClickResult result = runtime.click(playerId, position, context.elapsedTicks(), System.nanoTime(), this::setBlock);
        switch (result.outcome()) {
            case CORRECT -> {
                play(player, config.correctSound());
                sendActionbar(player);
            }
            case WRONG -> {
                play(player, config.wrongSound());
                scheduleReset(playerId);
                sendActionbar(player);
            }
            case FINISHED -> {
                context.state(playerId, RoundPlayerState.FINISHED);
                play(player, config.correctSound());
                playCompletion(player);
                sendCompletionTitle(player);
                sendActionbar(player);
                if (runtime.allResolved()) context.requestFinish(RoundEndReason.ALL_ELIMINATED);
            }
            case IGNORED -> {
            }
        }
        return EventDecision.DENY;
    }

    @Override
    public EventDecision onBlockBreak(RoundContext context, BlockBreakEvent event) {
        return EventDecision.DENY;
    }

    @Override
    public EventDecision onBlockPlace(RoundContext context, BlockPlaceEvent event) {
        return EventDecision.DENY;
    }

    @Override
    public EventDecision onDamage(RoundContext context, EntityDamageEvent event) {
        return EventDecision.DENY;
    }

    @Override
    public EventDecision onDropItem(RoundContext context, PlayerDropItemEvent event) {
        return EventDecision.DENY;
    }

    @Override
    public EventDecision onInventoryClick(RoundContext context, InventoryClickEvent event) {
        return EventDecision.DENY;
    }

    @Override
    public EventDecision onInventoryDrag(RoundContext context, InventoryDragEvent event) {
        return EventDecision.DENY;
    }

    private void scheduleReset(UUID playerId) {
        cancelReset(playerId);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            resetTasks.remove(playerId);
            if (runtime == null) return;
            runtime.resetWrong(playerId, this::setBlock);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) sendActionbar(player);
        }, config.wrongResetDelayTicks());
        resetTasks.put(playerId, task);
    }

    private void cancelReset(UUID playerId) {
        BukkitTask task = resetTasks.remove(playerId);
        if (task != null) task.cancel();
    }

    private void setBlock(BlockPosition position, Material material) {
        if (config == null || config.gameRegion() == null) return;
        World world = Bukkit.getWorld(config.gameRegion().worldName());
        if (world == null) return;
        world.getBlockAt(position.x(), position.y(), position.z()).setType(material, false);
    }

    private void addBossBar(Player player) {
        removeBossBar(player.getUniqueId());
        BossBar bar = Bukkit.createBossBar(
                Text.color(config.bossBar().title()),
                config.bossBar().color(),
                config.bossBar().style()
        );
        bar.setProgress(1.0);
        bar.addPlayer(player);
        bossBars.put(player.getUniqueId(), bar);
    }

    private void removeBossBar(UUID playerId) {
        BossBar bar = bossBars.remove(playerId);
        if (bar != null) bar.removeAll();
    }

    private void sendTutorialChat(Player player) {
        for (String line : config.tutorialChatLines()) {
            player.sendMessage(Text.color(line.replace("{duration}", String.valueOf(config.roundDurationSeconds()))));
        }
    }

    private void sendTutorialTitle(Player player, int seconds) {
        player.sendTitle(
                Text.color(config.tutorialTitle()),
                Text.color(config.tutorialSubtitle().replace("{seconds}", String.valueOf(seconds))),
                0,
                config.tutorialTitleStayTicks(),
                0
        );
    }

    private void sendCompletionTitle(Player player) {
        long millis = runtime == null ? -1L : runtime.completionMillis(player.getUniqueId());
        String formatted = millis < 0L ? "-" : SuperMemoryRuntime.formatMillis(millis);
        player.sendTitle(
                Text.color(config.completionTitle()),
                Text.color(config.completionSubtitle().replace("{time}", formatted)),
                0,
                config.completionTitleStayTicks(),
                0
        );
    }

    private void sendActionbar(Player player) {
        if (runtime == null || config == null) return;
        UUID playerId = player.getUniqueId();
        String message;
        if (runtime.finished(playerId)) {
            long millis = runtime.completionMillis(playerId);
            message = config.actionbarFinished().replace("{completion_time}", SuperMemoryRuntime.formatMillis(millis));
        } else {
            message = config.actionbarActive()
                    .replace("{progress}", String.valueOf(runtime.progress(playerId)))
                    .replace("{remaining}", SuperMemoryRuntime.formatClock(runtime.remainingMillis(System.nanoTime())));
        }
        player.sendActionBar(Text.component(message));
    }

    private void sendResults(RoundContext context, RoundResult result) {
        List<UUID> ranked = result.players().entrySet().stream()
                .filter(entry -> entry.getValue().completed())
                .sorted(Comparator.comparingInt(entry -> entry.getValue().placement().orElse(Integer.MAX_VALUE)))
                .map(Map.Entry::getKey)
                .toList();
        List<String> dnf = result.players().entrySet().stream()
                .filter(entry -> entry.getValue().failed())
                .map(entry -> context.player(entry.getKey()).map(Player::getName).orElse(entry.getKey().toString()))
                .toList();

        List<String> lines = new ArrayList<>(config.results().headerLines());
        for (UUID playerId : ranked) {
            OptionalInt placement = result.players().get(playerId).placement();
            int place = placement.orElse(0);
            String name = context.player(playerId).map(Player::getName).orElse(playerId.toString());
            String time = result.players().get(playerId).data().getOrDefault("completion_time", "-");
            int points = result.players().get(playerId).points();
            lines.add(config.results().placementLine()
                    .replace("{place}", String.valueOf(place))
                    .replace("{player}", name)
                    .replace("{time}", time)
                    .replace("{points}", String.valueOf(points)));
        }
        if (!dnf.isEmpty()) {
            lines.add(config.results().dnfLine().replace("{players}", String.join(", ", dnf)));
        }
        lines.add(config.results().footerLine());

        for (Player player : context.onlineParticipants()) {
            for (String line : lines) player.sendMessage(Text.color(line));
        }
    }

    private void playCompletion(Player player) {
        play(player, config.completionLevelUpSound());
        play(player, config.completionChimeSound());
        play(player, config.completionCelebrateSound());
    }

    private void play(Player player, ConfiguredSound configured) {
        if (configured == null || !configured.enabled()) return;
        Sound sound;
        try {
            sound = configured.bukkitSound();
        } catch (Throwable error) {
            plugin.getLogger().warning("Invalid SuperMemory sound: " + configured.sound());
            return;
        }
        if (sound != null) player.playSound(player.getLocation(), sound, configured.volume(), configured.pitch());
    }

    private void teleport(Player player, LocationSpec spawn) {
        Location location = spawn.toLocation(config.gameRegion().worldName());
        if (location != null) player.teleport(location);
    }

    private void mute(Player player) {
        if (mutedPlayers.contains(player.getUniqueId())) return;
        boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "hexchat mute " + player.getName());
        if (dispatched) mutedPlayers.markMuted(player.getUniqueId(), player.getName());
    }

    private void unmuteAll() {
        for (UUID playerId : mutedPlayers.playerIds()) {
            unmute(playerId);
        }
    }

    private void unmute(UUID playerId) {
        String playerName = mutedPlayers.remove(playerId);
        if (playerName == null || playerName.isBlank()) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "hexchat unmute " + playerName);
    }
}
