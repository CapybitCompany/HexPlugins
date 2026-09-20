package hex.minigames.game.tag;

import hex.minigames.game.*;
import hex.minigames.game.common.*;
import hex.minigames.runtime.RoundPlayerState;
import hex.minigames.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;
import java.util.*;

/** Non-damaging melee tag with colored roles and time-based scoring. */
public final class TagMinigame implements Minigame {
    private final Plugin plugin;
    private final BossBarDisplay bars = new BossBarDisplay();
    private final TagRoleDisplay roles = new TagRoleDisplay();
    private TagConfig config;
    private StandardTutorial tutorial;
    private TagRuntime runtime;
    private RoundResult result;
    private boolean started;

    public TagMinigame(Plugin plugin) { this.plugin = plugin; }
    @Override public String id() { return TagConfig.ID; }

    @Override public MinigameAvailability availability(MinigameDefinition definition, int players) {
        List<String> errors = new ArrayList<>();
        TagConfig.fromDefinition(definition, errors);
        if (players < 2) errors.add("Berek wymaga co najmniej dwóch graczy do testu.");
        return errors.isEmpty() ? MinigameAvailability.ok() : MinigameAvailability.unavailable(String.join("; ", errors));
    }

    @Override public void prepare(RoundContext context) {
        List<String> errors = new ArrayList<>();
        config = TagConfig.fromDefinition(context.definition(), errors);
        if (!errors.isEmpty()) throw new IllegalStateException(String.join("; ", errors));
        started = false;
        result = null;
        tutorial = new StandardTutorial(plugin, config.tutorial());
        bars.showAll(context.onlineParticipants(), config.bossBar());
        tutorial.begin(context, context.onlineParticipants(), Map.of("duration", String.valueOf(config.durationSeconds())));
    }

    @Override public int countdownSeconds(MinigameDefinition definition, int fallback) {
        return TagConfig.fromDefinition(definition, new ArrayList<>()).tutorial().durationSeconds();
    }

    @Override public boolean handleCountdownTick(RoundContext context, int ticks) {
        return tutorial.tick(context, ticks, Map.of("duration", String.valueOf(config.durationSeconds())));
    }

    @Override public void start(RoundContext context) {
        tutorial.end(context);
        runtime = new TagRuntime(context.participants(), new Random(), config.immunityTicks(), context.elapsedTicks(), config.durationSeconds() * 20L);
        started = true;
        roles.show(context.onlineParticipants(), runtime);
        for (Player player : context.onlineParticipants()) if (runtime.isTagger(player.getUniqueId())) announceTagger(player);
        handleTick(context);
    }

    @Override public EventDecision onDamage(RoundContext context, EntityDamageEvent event) {
        if (!started || !(event instanceof EntityDamageByEntityEvent hit)
                || hit.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(hit.getDamager() instanceof Player attacker) || !(hit.getEntity() instanceof Player victim)
                || context.state(attacker.getUniqueId()) != RoundPlayerState.ACTIVE
                || context.state(victim.getUniqueId()) != RoundPlayerState.ACTIVE) return EventDecision.DENY;
        if (runtime.transfer(attacker.getUniqueId(), victim.getUniqueId(), context.elapsedTicks())) {
            roles.update(attacker, false);
            roles.update(victim, true);
            announceTagger(victim);
            showStatus(context, attacker);
            showStatus(context, victim);
        }
        return EventDecision.DENY;
    }

    private void announceTagger(Player player) {
        player.sendTitle("", Text.color("&cBerek!"), 0, 20, 0);
        player.playSound(player.getLocation(), "minecraft:entity.villager.no", 1.0f, 0.7f);
    }

    @Override public void handleTick(RoundContext context) {
        if (!started || context.elapsedTicks() % 2 != 0) return;
        for (Player player : context.onlineParticipants()) showStatus(context, player);
    }

    private void showStatus(RoundContext context, Player player) {
        player.sendActionBar(Text.component("&fCzas: &e" + RoundClock.remaining(config.durationSeconds(), context.elapsedTicks())
                + " &8| " + (runtime.isTagger(player.getUniqueId()) ? "&cBerek" : "&aUciekający")));
    }

    @Override public EventDecision onMove(RoundContext context, PlayerMoveEvent event) {
        StartBoundary.pushInside(event, config.region());
        return EventDecision.PASS;
    }

    @Override public void handlePlayerQuit(RoundContext context, UUID id) {
        roles.restore(id);
        bars.remove(id);
        tutorial.remove(context, id);
        if (started) {
            Set<UUID> newTaggers = runtime.remove(id, context.elapsedTicks());
            for (Player player : context.onlineParticipants()) {
                if (player.getUniqueId().equals(id)) continue;
                roles.update(player, runtime.isTagger(player.getUniqueId()));
                if (newTaggers.contains(player.getUniqueId())) announceTagger(player);
            }
            if (context.participants().stream().filter(other -> !other.equals(id)).count() < 2) context.requestFinish(RoundEndReason.MINIGAME_REQUEST);
        }
    }

    @Override public RoundResult finish(RoundContext context, RoundEndReason reason) {
        if (result != null) return result;
        started = false;
        Map<UUID, PlayerRoundResult> players = new LinkedHashMap<>();
        for (UUID id : context.participants()) {
            int points = runtime == null ? 0 : runtime.points(id, context.elapsedTicks(), config.pointThresholdsSeconds());
            players.put(id, new PlayerRoundResult(points, OptionalInt.empty(), points > 0, points == 0,
                    Map.of("runner-seconds", String.valueOf(runtime == null ? 0 : runtime.runnerTicks(id, context.elapsedTicks()) / 20))));
        }
        result = new RoundResult(players, Map.of("game", id()));
        roles.clear();
        return result;
    }

    @Override public void reset(RoundContext context) {
        started = false;
        roles.clear();
        bars.clear();
        if (tutorial != null) tutorial.end(context);
        for (Player player : context.onlineParticipants()) {
            player.sendActionBar(Text.component(""));
            Text.clearTitle(player);
        }
        runtime = null;
    }
}
