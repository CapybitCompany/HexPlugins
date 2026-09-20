package hex.minigames.game.redlight;

import hex.minigames.config.ConfiguredSound;
import hex.minigames.game.EventDecision;
import hex.minigames.game.Minigame;
import hex.minigames.game.MinigameAvailability;
import hex.minigames.game.MinigameDefinition;
import hex.minigames.game.PlayerRoundResult;
import hex.minigames.game.RoundContext;
import hex.minigames.game.RoundEndReason;
import hex.minigames.game.RoundResult;
import hex.minigames.game.common.BossBarDisplay;
import hex.minigames.game.common.StandardTutorial;
import hex.minigames.runtime.RoundPlayerState;
import hex.minigames.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class RedLightGreenLightMinigame implements Minigame {
    private final Plugin plugin;
    private RedLightGreenLightConfig config;
    private RedLightGreenLightRuntime runtime;
    private StandardTutorial tutorial;
    private final BossBarDisplay bossBars = new BossBarDisplay();
    private final Set<UUID> resettingPlayers = new HashSet<>();
    private RoundResult cachedResult;
    private boolean started;

    public RedLightGreenLightMinigame(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return RedLightGreenLightConfig.ID;
    }

    @Override
    public MinigameAvailability availability(MinigameDefinition definition, int players) {
        List<String> errors = new ArrayList<>();
        RedLightGreenLightConfig.fromDefinition(definition, errors);
        return errors.isEmpty() ? MinigameAvailability.ok() : MinigameAvailability.unavailable(String.join("; ", errors));
    }

    @Override
    public void prepare(RoundContext context) {
        List<String> errors = new ArrayList<>();
        config = RedLightGreenLightConfig.fromDefinition(context.definition(), errors);
        if (!errors.isEmpty()) throw new IllegalStateException(String.join("; ", errors));
        runtime = new RedLightGreenLightRuntime(config, new Random());
        tutorial = new StandardTutorial(plugin, config.tutorial());
        cachedResult = null;
        started = false;
        resettingPlayers.clear();
        bossBars.showAll(context.onlineParticipants(), config.bossBar());
        tutorial.begin(context, context.onlineParticipants(), Map.of("duration", String.valueOf(config.roundDurationSeconds())));
    }

    @Override
    public int countdownSeconds(MinigameDefinition definition, int globalCountdownSeconds) {
        if (config != null) return config.tutorial().durationSeconds();
        List<String> errors = new ArrayList<>();
        RedLightGreenLightConfig parsed = RedLightGreenLightConfig.fromDefinition(definition, errors);
        return errors.isEmpty() ? parsed.tutorial().durationSeconds() : globalCountdownSeconds;
    }

    @Override
    public boolean handleCountdownTick(RoundContext context, int ticksRemaining) {
        return tutorial != null && tutorial.tick(context, ticksRemaining, Map.of("duration", String.valueOf(config.roundDurationSeconds())));
    }

    @Override
    public void start(RoundContext context) {
        if (runtime == null) return;
        tutorial.end(context);
        started = true;
        runtime.start(context.elapsedTicks());
        fillInventories(context, config.greenInventoryMaterial());
        playAll(context, config.greenSound());
    }

    @Override
    public void handleTick(RoundContext context) {
        if (runtime == null) return;
        if (runtime.tick(context.elapsedTicks())) {
            Material material = runtime.light() == RedLightGreenLightRuntime.Light.GREEN
                    ? config.greenInventoryMaterial()
                    : config.redInventoryMaterial();
            fillInventories(context, material);
            playAll(context, runtime.light() == RedLightGreenLightRuntime.Light.GREEN ? config.greenSound() : config.redSound());
        }
        if (context.elapsedTicks() % 10L == 0L) {
            for (Player player : context.onlineParticipants()) sendActionbar(context, player);
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
        Map<UUID, Integer> points = runtime == null ? Map.of() : runtime.pointsByFinishOrder();
        int placement = 1;
        for (Map.Entry<UUID, Integer> entry : points.entrySet()) {
            out.put(entry.getKey(), new PlayerRoundResult(
                    entry.getValue(),
                    OptionalInt.of(placement++),
                    true,
                    false,
                    Map.of(
                            "time", formatTicks(runtime.finishTick(entry.getKey()).orElse(0L)),
                            "finish_tick", String.valueOf(runtime.finishTick(entry.getKey()).orElse(0L))
                    )
            ));
        }
        for (UUID playerId : context.participants()) {
            if (reason == RoundEndReason.TIME_LIMIT && !points.containsKey(playerId)) {
                context.player(playerId).ifPresent(hex.minigames.game.common.RespawnEffects::explosion);
            }
            out.putIfAbsent(playerId, new PlayerRoundResult(0, OptionalInt.empty(), false, true, Map.of("dnf", "true")));
        }
        cachedResult = new RoundResult(out, Map.of("game", id()));
        return cachedResult;
    }

    @Override
    public void reset(RoundContext context) {
        if (tutorial != null) tutorial.end(context);
        bossBars.clear();
        for (Player player : context.onlineParticipants()) {
            player.getInventory().clear();
            player.sendActionBar(Text.component(""));
            player.sendTitle("", "", 0, 1, 0);
        }
        runtime = null;
        started = false;
        resettingPlayers.clear();
    }

    @Override
    public EventDecision onMove(RoundContext context, PlayerMoveEvent event) {
        if (config == null || runtime == null) return EventDecision.DENY;
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (!started) {
            hex.minigames.game.common.StartBoundary.pushBack(event, config.startLine().maxZ() + 1.0, 1);
            return EventDecision.PASS;
        }
        if (resettingPlayers.contains(playerId)) return EventDecision.PASS;
        if (context.state(playerId) == RoundPlayerState.FINISHED) {
            hex.minigames.game.common.StartBoundary.pushBack(event, config.finishRegion().minZ(), -1);
            return EventDecision.PASS;
        }
        if (context.state(playerId) != RoundPlayerState.ACTIVE) return EventDecision.PASS;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to != null && (crossedStartLine(from) || crossedStartLine(to))
                && runtime.movementViolation(context.elapsedTicks(), new RedLightGreenLightRuntime.MovementSample(
                from.getX(), from.getY(), from.getZ(), from.getYaw(), from.getPitch(),
                to.getX(), to.getY(), to.getZ(), to.getYaw(), to.getPitch()
        ))) {
            punishViolation(context, player);
            return EventDecision.PASS;
        }
        if (config.finishRegion().contains(event.getTo())) {
            finishPlayer(context, player);
            return EventDecision.PASS;
        }
        return EventDecision.PASS;
    }

    @Override
    public EventDecision onToggleSneak(RoundContext context, PlayerToggleSneakEvent event) {
        if (!started || runtime == null || resettingPlayers.contains(event.getPlayer().getUniqueId()) || context.state(event.getPlayer().getUniqueId()) != RoundPlayerState.ACTIVE) {
            return EventDecision.ALLOW;
        }
        if (crossedStartLine(event.getPlayer().getLocation()) && runtime.strictRedActive(context.elapsedTicks())) {
            punishViolation(context, event.getPlayer());
            return EventDecision.DENY;
        }
        return EventDecision.ALLOW;
    }

    private void finishPlayer(RoundContext context, Player player) {
        UUID playerId = player.getUniqueId();
        if (!runtime.markFinished(playerId, context.elapsedTicks())) return;
        context.state(playerId, RoundPlayerState.FINISHED);
        player.getInventory().clear();
        sendActionbar(context, player);
    }

    private void punishViolation(RoundContext context, Player player) {
        UUID playerId = player.getUniqueId();
        if (!resettingPlayers.add(playerId)) return;
        runtime.clearFinish(playerId);
        player.playSound(player.getLocation(), "minecraft:entity.villager.no", 1.0f, 1.0f);
        hex.minigames.game.common.RespawnEffects.explosion(player);
        Location target = config.resetSpawn().toLocation(config.region().worldName());
        context.respawn(player, target, () -> resettingPlayers.remove(playerId));
    }

    private boolean crossedStartLine(Location to) {
        if (to == null) return false;
        return to.getZ() > config.startLine().maxZ() + 1.0;
    }

    private void fillInventories(RoundContext context, Material material) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(Text.component(material == config.greenInventoryMaterial() ? "&aZIELONE" : "&cCZERWONE")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        for (Player player : context.onlineParticipants()) {
            if (context.state(player.getUniqueId()) != RoundPlayerState.ACTIVE) continue;
            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, item);
            player.updateInventory();
        }
    }

    private void sendActionbar(RoundContext context, Player player) {
        long remainingTicks = Math.max(0L, config.roundDurationSeconds() * 20L - context.elapsedTicks());
        String remaining = formatTicks(remainingTicks);
        String message = runtime.finishTick(player.getUniqueId()).isPresent()
                ? config.actionbarFinished()
                .replace("{remaining}", remaining)
                .replace("{time}", formatTicks(runtime.finishTick(player.getUniqueId()).orElse(0L)))
                : config.actionbarActive().replace("{remaining}", remaining);
        player.sendActionBar(Text.component(message));
    }

    private void playAll(RoundContext context, ConfiguredSound configured) {
        if (configured == null || !configured.enabled()) return;
        Sound sound;
        try {
            sound = configured.bukkitSound();
        } catch (Throwable error) {
            plugin.getLogger().warning("Invalid RedLight sound: " + configured.sound());
            return;
        }
        if (sound == null) return;
        for (Player player : context.onlineParticipants()) {
            player.playSound(player.getLocation(), sound, configured.volume(), configured.pitch());
        }
    }

    private String formatTicks(long ticks) {
        long seconds = Math.max(0L, ticks / 20L);
        long minutes = seconds / 60L;
        long rest = seconds % 60L;
        return String.format("%02d:%02d", minutes, rest);
    }
}
