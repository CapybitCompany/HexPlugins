package hex.minigames.game.discofloor;

import hex.minigames.game.*;
import hex.minigames.game.common.*;
import hex.minigames.model.*;
import hex.minigames.runtime.RoundPlayerState;
import hex.minigames.util.Text;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;
import java.util.*;

/** Uses the existing concrete pattern and restores the original tiles between rounds. */
public final class DiscoFloorMinigame implements Minigame {
    private static final Map<Material, String> COLORS = Map.of(
            Material.ORANGE_CONCRETE, "&6POMARAŃCZOWY", Material.BROWN_CONCRETE, "&6BRĄZOWY",
            Material.PURPLE_CONCRETE, "&5FIOLETOWY", Material.YELLOW_CONCRETE, "&eŻÓŁTY",
            Material.RED_CONCRETE, "&cCZERWONY", Material.LIGHT_BLUE_CONCRETE, "&bJASNONIEBIESKI",
            Material.CYAN_CONCRETE, "&3CYJANOWY", Material.LIME_CONCRETE, "&aLIMONKOWY",
            Material.GRAY_CONCRETE, "&8SZARY", Material.PINK_CONCRETE, "&dRÓŻOWY");
    private final Plugin plugin;
    private final Map<BlockPosition, Material> floor = new LinkedHashMap<>();
    private final BossBarDisplay bars = new BossBarDisplay();
    private List<Material> available;
    private DiscoFloorRuntime runtime;
    private BlockChangeTracker blocks;
    private StandardTutorial tutorial;
    private boolean started;
    private int completedRounds;
    private int removedRound = -1;
    private RoundResult result;

    public DiscoFloorMinigame(Plugin plugin) { this.plugin = plugin; }
    @Override public String id() { return "disco_floor"; }
    @Override public boolean finishWhenAllActiveResolved() { return false; }
    @Override public int countdownSeconds(MinigameDefinition definition, int global) { return 20; }
    @Override public MinigameAvailability availability(MinigameDefinition definition, int players) {
        return definition.region().isPresent() ? MinigameAvailability.ok() : MinigameAvailability.unavailable("Disco Floor: brak regionu");
    }
    @Override public void prepare(RoundContext context) {
        String worldName = context.definition().region().orElseThrow().worldName();
        World world = Bukkit.getWorld(worldName);
        if (world == null) throw new IllegalStateException("Disco Floor world unavailable");
        floor.clear();
        for (int x = 552; x <= 587; x++) for (int z = -27; z <= 8; z++) {
            Material material = world.getBlockAt(x, -32, z).getType();
            if (COLORS.containsKey(material)) floor.put(new BlockPosition(x, -32, z), material);
        }
        if (floor.isEmpty()) throw new IllegalStateException("Disco Floor: no colored concrete at Y=-32 inside 552,-27 to 587,8");
        available = floor.values().stream().distinct().sorted().toList();
        runtime = new DiscoFloorRuntime(available.size(), new Random());
        blocks = new BlockChangeTracker(worldName);
        started = false;
        completedRounds = 0;
        removedRound = -1;
        result = null;
        List<String> errors = new ArrayList<>();
        var settings = context.definition().settings();
        tutorial = new StandardTutorial(plugin, CommonGameConfig.tutorial(settings, "disco_floor",
                List.of("&d&lDISCO FLOOR", "&fPrzetrwaj &e7 rund&f.",
                        "&fPo pokazaniu koloru masz &e3 sekundy&f, aby na nim stanąć.",
                        "&fPozostałe kolory znikają na &e5 sekund&f. Nie spadnij!",
                        "&fPrzetrwanie wszystkich rund daje &a4 punkty&f."), errors));
        bars.showAll(context.onlineParticipants(), CommonGameConfig.bossBar(settings, "disco_floor", "&d&lDISCO FLOOR", errors));
        tutorial.begin(context, context.onlineParticipants(), Map.of());
    }
    @Override public boolean handleCountdownTick(RoundContext context, int remaining) {
        return tutorial != null && tutorial.tick(context, remaining, Map.of());
    }
    @Override public void start(RoundContext context) {
        tutorial.end(context);
        started = true;
        handleTick(context);
    }
    @Override public void handleTick(RoundContext context) {
        if (!started) return;
        long tick = context.elapsedTicks();
        for (Player player : context.onlineParticipants()) {
            if (context.state(player.getUniqueId()) == RoundPlayerState.ACTIVE && player.getLocation().getY() <= -47) eliminate(context, player);
        }
        int ended = Math.min(7, (int) (tick / DiscoFloorRuntime.ROUND_TICKS));
        if (ended > completedRounds) {
            for (Player player : context.onlineParticipants()) {
                if (context.state(player.getUniqueId()) == RoundPlayerState.ACTIVE) runtime.survive(player.getUniqueId(), ended);
            }
            completedRounds = ended;
            blocks.restoreAll();
        }
        if (completedRounds >= 7) {
            context.requestFinish(RoundEndReason.MINIGAME_REQUEST);
            return;
        }
        int round = (int) (tick / DiscoFloorRuntime.ROUND_TICKS);
        int phase = (int) (tick % DiscoFloorRuntime.ROUND_TICKS);
        Material selected = available.get(runtime.color(round));
        if (phase >= 120 && removedRound != round) {
            for (var entry : floor.entrySet()) if (entry.getValue() != selected) blocks.setType(entry.getKey(), Material.AIR);
            removedRound = round;
        }
        if (tick % 20 != 0) return;
        String color = phase < 60 ? "&f" + (3 - phase / 20) : COLORS.get(selected);
        for (Player player : context.onlineParticipants()) {
            player.sendActionBar(Text.component("&fRunda &e" + (round + 1) + " &7| &fKolor: " + color));
            if (phase >= 60 && phase < 120 && context.state(player.getUniqueId()) == RoundPlayerState.ACTIVE) {
                player.sendTitle("", Text.color(COLORS.get(selected) + " &f" + (3 - (phase - 60) / 20)), 0, 20, 0);
                player.playSound(player.getLocation(), "minecraft:block.note_block.hat", 1, 1.2f);
            }
        }
    }
    private void eliminate(RoundContext context, Player player) {
        if (context.state(player.getUniqueId()) != RoundPlayerState.ACTIVE || !runtime.eliminate(player.getUniqueId())) return;
        RespawnEffects.explosion(player);
        context.state(player.getUniqueId(), RoundPlayerState.GHOST);
        player.setGameMode(GameMode.SPECTATOR);
        context.respawn(player, new Location(player.getWorld(), 577, -26, -3), null);
    }
    @Override public EventDecision onMove(RoundContext context, PlayerMoveEvent event) {
        if (started && event.getTo() != null && event.getTo().getY() <= -47) eliminate(context, event.getPlayer());
        return EventDecision.PASS;
    }
    @Override public EventDecision onDamage(RoundContext context, EntityDamageEvent event) {
        if (started && event.getEntity() instanceof Player player && (player.getLocation().getY() <= -47
                || event.getCause() == EntityDamageEvent.DamageCause.VOID)) eliminate(context, player);
        return EventDecision.DENY;
    }
    @Override public void handlePlayerQuit(RoundContext context, UUID id) {
        if (runtime != null) runtime.eliminate(id);
        if (tutorial != null) tutorial.remove(context, id);
        bars.remove(id);
    }
    @Override public RoundResult finish(RoundContext context, RoundEndReason reason) {
        if (result != null) return result;
        started = false;
        Map<UUID, PlayerRoundResult> players = new LinkedHashMap<>();
        for (UUID id : context.participants()) {
            int survived = runtime == null ? 0 : runtime.survived(id);
            players.put(id, new PlayerRoundResult(runtime == null ? 0 : runtime.points(id), OptionalInt.empty(),
                    survived == 7, survived < 7, Map.of("rounds", String.valueOf(survived))));
        }
        result = new RoundResult(players, Map.of("game", id()));
        return result;
    }
    @Override public void reset(RoundContext context) {
        started = false;
        if (blocks != null) blocks.restoreAll();
        if (tutorial != null) tutorial.end(context);
        bars.clear();
        for (Player player : context.onlineParticipants()) {
            player.sendActionBar(Text.component(""));
            Text.clearTitle(player);
        }
        floor.clear();
    }
}
