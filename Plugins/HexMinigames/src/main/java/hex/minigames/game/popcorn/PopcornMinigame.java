package hex.minigames.game.popcorn;

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
import hex.minigames.runtime.RoundPlayerState;
import hex.minigames.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.UUID;

public final class PopcornMinigame implements Minigame {
    private final Plugin plugin;
    private PopcornConfig config;
    private PopcornRuntime runtime;
    private BlockChangeTracker blocks;
    private StandardTutorial tutorial;
    private final BossBarDisplay bossBars = new BossBarDisplay();
    private RoundResult cachedResult;
    private final PopcornMovementTracker movement = new PopcornMovementTracker();
    private boolean started;

    public PopcornMinigame(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return PopcornConfig.ID;
    }

    @Override
    public MinigameAvailability availability(MinigameDefinition definition, int players) {
        List<String> errors = new ArrayList<>();
        PopcornConfig.fromDefinition(definition, errors);
        return errors.isEmpty() ? MinigameAvailability.ok() : MinigameAvailability.unavailable(String.join("; ", errors));
    }

    @Override
    public void prepare(RoundContext context) {
        List<String> errors = new ArrayList<>();
        config = PopcornConfig.fromDefinition(context.definition(), errors);
        if (!errors.isEmpty()) throw new IllegalStateException(String.join("; ", errors));
        runtime = new PopcornRuntime(config, new Random());
        blocks = new BlockChangeTracker(config.region().worldName());
        blocks.fill(config.platform(), config.whiteMaterial());
        tutorial = new StandardTutorial(plugin, config.tutorial());
        cachedResult = null;
        started = false;
        movement.clear();
        bossBars.showAll(context.onlineParticipants(), config.bossBar());
        tutorial.begin(context, context.onlineParticipants(), Map.of("duration", String.valueOf(config.roundDurationSeconds())));
    }

    @Override
    public int countdownSeconds(MinigameDefinition definition, int globalCountdownSeconds) {
        if (config != null) return config.tutorial().durationSeconds();
        List<String> errors = new ArrayList<>();
        PopcornConfig parsed = PopcornConfig.fromDefinition(definition, errors);
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
    public void start(RoundContext context) {
        if (tutorial != null) tutorial.end(context);
        started = true;
        for (Player player : context.onlineParticipants()) {
            var location = player.getLocation();
            movement.idleTicks(player.getUniqueId(), location.getX(), location.getZ(), context.elapsedTicks());
            player.sendMessage(Text.component("&eRuszaj się! Po 1,5 sekundy bezruchu masz 2 sekundy, aby uniknąć eliminacji."));
        }
    }

    @Override
    public void handleTick(RoundContext context) {
        if (!started || runtime == null || blocks == null) return;
        hex.minigames.game.common.RoundClock.show(context);
        for (Player player : context.onlineParticipants()) {
            if (context.state(player.getUniqueId()) != RoundPlayerState.ACTIVE) continue;
            var location = player.getLocation();
            long idle = movement.idleTicks(player.getUniqueId(), location.getX(), location.getZ(), context.elapsedTicks());
            if (idle >= 70) {
                eliminate(context, player);
            } else if (idle >= 30) {
                int seconds = (int) ((70 - idle + 19) / 20);
                player.sendActionBar(Text.component("&c&lRUSZAJ SIĘ! &eEliminacja za " + seconds + " s"));
                if (idle == 30 || idle == 50) {
                    player.playSound(location, "minecraft:block.note_block.hat", 1.0f, 0.8f);
                }
            }
        }
        for (PopcornRuntime.Command command : runtime.tick(context.elapsedTicks())) {
            blocks.setType(command.block(), command.material());
        }
    }

    @Override
    public void handlePlayerQuit(RoundContext context, UUID playerId) {
        movement.remove(playerId);
        if (tutorial != null) tutorial.remove(context, playerId);
        bossBars.remove(playerId);
    }

    @Override
    public RoundResult finish(RoundContext context, RoundEndReason reason) {
        if (cachedResult != null) return cachedResult;
        started = false;
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
        started = false;
        movement.clear();
        if (tutorial != null) tutorial.end(context);
        bossBars.clear();
        if (blocks != null && config != null) {
            blocks.fill(config.platform(), config.whiteMaterial());
            blocks.forgetSnapshots();
        }
        for (Player player : context.onlineParticipants()) {
            player.sendActionBar(Text.component(""));
            player.sendTitle("", "", 0, 1, 0);
        }
        runtime = null;
        blocks = null;
    }

    @Override
    public EventDecision onMove(RoundContext context, PlayerMoveEvent event) {
        if (context.state(event.getPlayer().getUniqueId()) != RoundPlayerState.ACTIVE) return EventDecision.PASS;
        if (event.getTo() != null && event.getTo().getY() < config.platform().minY() - 4.0) {
            eliminate(context, event.getPlayer());
        }
        return EventDecision.PASS;
    }

    @Override
    public EventDecision onDamage(RoundContext context, EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return EventDecision.DENY;
        if (context.state(player.getUniqueId()) == RoundPlayerState.ACTIVE
                && (event.getCause() == EntityDamageEvent.DamageCause.LAVA || event.getCause() == EntityDamageEvent.DamageCause.VOID)) {
            eliminate(context, player);
        }
        return EventDecision.DENY;
    }

    private void eliminate(RoundContext context, Player player) {
        if (context.state(player.getUniqueId()) != RoundPlayerState.ACTIVE) return;
        movement.remove(player.getUniqueId());
        player.sendActionBar(Text.component(""));
        hex.minigames.game.common.RespawnEffects.explosion(player);
        context.state(player.getUniqueId(), RoundPlayerState.GHOST);
        player.setGameMode(org.bukkit.GameMode.SPECTATOR);
        var platform = config.platform();
        var target = new org.bukkit.Location(player.getWorld(),
                (platform.minX() + platform.maxX() + 1) / 2.0,
                platform.maxY() + 6.0,
                (platform.minZ() + platform.maxZ() + 1) / 2.0,
                player.getLocation().getYaw(), 35.0f);
        context.respawn(player, target, null);
    }
}
