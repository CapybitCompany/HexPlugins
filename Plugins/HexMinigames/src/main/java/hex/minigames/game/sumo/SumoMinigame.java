package hex.minigames.game.sumo;
import hex.minigames.game.*;
import hex.minigames.game.common.*;
import hex.minigames.runtime.RoundPlayerState;
import hex.minigames.util.Text;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import java.util.*;

/** Two launch pads feed a PvP arena; launches and respawns never reset earned arena time. */
public final class SumoMinigame implements Minigame {
    private final Plugin plugin;
    private final Random random = new Random();
    private final Map<UUID, Flight> flights = new HashMap<>();
    private final Map<UUID, Long> hitAfter = new HashMap<>();
    private final BossBarDisplay bars = new BossBarDisplay();
    private StandardTutorial tutorial;
    private BlockChangeTracker barriers;
    private SumoRuntime runtime;
    private boolean started;
    private RoundResult result;
    public SumoMinigame(Plugin plugin) { this.plugin = plugin; }
    @Override public String id() { return "monkey_run"; }
    @Override public boolean finishWhenAllActiveResolved() { return false; }
    @Override public int countdownSeconds(MinigameDefinition definition, int global) { return 20; }
    @Override public void prepare(RoundContext context) {
        runtime = new SumoRuntime(); flights.clear(); hitAfter.clear(); result = null; started = false;
        var errors = new ArrayList<String>();
        tutorial = new StandardTutorial(plugin, CommonGameConfig.tutorial(context.definition().settings(), id(),
                List.of("&d&lSUMO", "&fWejdź na złotą płytkę, aby polecieć na arenę.",
                        "&fSpychaj przeciwników! Upadek oznacza powrót na jeden ze spawnów.",
                        "&fPunkty za łączny czas na arenie: &e20 / 40 / 60 s &f= &a1 / 2 / 3 pkt&f."), errors));
        bars.showAll(context.onlineParticipants(), CommonGameConfig.bossBar(context.definition().settings(), id(), "&d&lSUMO", errors));
        barriers = new BlockChangeTracker(context.definition().region().orElseThrow().worldName());
        setBarriers(Material.BARRIER);
        List<Player> players = new ArrayList<>(context.onlineParticipants());
        Collections.shuffle(players, random);
        for (int i = 0; i < players.size(); i++) players.get(i).teleport(spawn(players.get(i).getWorld(), i % 2 == 0));
        tutorial.begin(context, players, Map.of());
    }
    @Override public boolean handleCountdownTick(RoundContext context, int ticks) { return tutorial.tick(context, ticks, Map.of()); }
    @Override public void start(RoundContext context) { tutorial.end(context); setBarriers(Material.AIR); started = true; }
    /** Keep both spawn exits closed throughout the tutorial. */
    private void setBarriers(Material material) {
        for (int x = 431; x <= 441; x++) barriers.setType(new hex.minigames.model.BlockPosition(x, 48, 370), material);
        for (int x = 443; x <= 451; x++) barriers.setType(new hex.minigames.model.BlockPosition(x, 50, 329), material);
    }
    private Location spawn(World world, boolean a) { return a ? new Location(world, 448, 49, 324, 0, 0) : new Location(world, 434, 47, 374, 180, 0); }
    private static boolean pad(Location p) {
        return p.getBlockY() == 49 && p.getBlockZ() == 329 && p.getBlockX() >= 443 && p.getBlockX() <= 451
                || p.getBlockY() == 47 && p.getBlockZ() == 370 && p.getBlockX() >= 431 && p.getBlockX() <= 440;
    }
    private void launch(RoundContext context, Player player, Location at) {
        if (!started || context.state(player.getUniqueId()) != RoundPlayerState.ACTIVE
                || flights.containsKey(player.getUniqueId()) || !pad(at)) return;
        flights.put(player.getUniqueId(), new Flight(context.elapsedTicks()));
        player.setVelocity(launchVelocity(player.getLocation()));
    }
    @Override public EventDecision onMove(RoundContext context, PlayerMoveEvent event) {
        if (started && event.getTo() != null) {
            if (event.getTo().getY() <= 39) respawn(context, event.getPlayer());
            else launch(context, event.getPlayer(), event.getTo());
        }
        return EventDecision.PASS;
    }
    @Override public EventDecision onInteract(RoundContext context, PlayerInteractEvent event) {
        if (event.getAction() == org.bukkit.event.block.Action.PHYSICAL && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == Material.LIGHT_WEIGHTED_PRESSURE_PLATE) launch(context, event.getPlayer(), event.getClickedBlock().getLocation());
        return EventDecision.DENY;
    }
    @Override public void handleTick(RoundContext context) {
        if (!started) return;
        for (Player player : context.onlineParticipants()) {
            UUID id = player.getUniqueId();
            if (context.state(id) != RoundPlayerState.ACTIVE) continue;
            if (player.getLocation().getY() <= 39) { respawn(context, player); continue; }
            Flight flight = flights.get(id);
            if (flight != null) {
                if ((context.elapsedTicks() - flight.tick() >= 3 && player.isOnGround())
                        || context.elapsedTicks() - flight.tick() >= 30) flights.remove(id);
                player.setFallDistance(0);
            } else {
                Location p = player.getLocation();
                runtime.tick(id, SumoRuntime.onArena(p.getX(), p.getY(), p.getZ()));
            }
            player.sendActionBar(Text.component("&fCzas: &e"
                    + RoundClock.remaining(context.definition().roundTimeSeconds(), context.elapsedTicks())
                    + " &7| &fNa arenie: &e" + SumoRuntime.formatTime(runtime.ticks(id))));
        }
    }
    /** One impulse; gravity, drag and player input control the landing. */
    static Vector launchVelocity(Location start) {
        double horizontalTravel = (1 - Math.pow(0.91, 18)) / (1 - 0.91);
        return new Vector((438.5 - start.getX()) / horizontalTravel, 0.62,
                (349.5 - start.getZ()) / horizontalTravel);
    }
    private void respawn(RoundContext context, Player player) {
        UUID id = player.getUniqueId();
        if (context.state(id) != RoundPlayerState.ACTIVE) return;
        flights.remove(id);
        context.state(id, RoundPlayerState.RESPAWN_DELAY);
        context.respawn(player, spawn(player.getWorld(), random.nextBoolean()), () -> context.state(id, RoundPlayerState.ACTIVE));
    }
    @Override public EventDecision onDamage(RoundContext context, EntityDamageEvent event) {
        if (!started || !(event.getEntity() instanceof Player victim) || context.state(victim.getUniqueId()) != RoundPlayerState.ACTIVE) return EventDecision.DENY;
        if (victim.getLocation().getY() <= 39 || event.getCause() == EntityDamageEvent.DamageCause.VOID) { respawn(context, victim); return EventDecision.DENY; }
        if (event instanceof EntityDamageByEntityEvent hit && hit.getDamager() instanceof Player attacker
                && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && context.participants().contains(attacker.getUniqueId()) && context.state(attacker.getUniqueId()) == RoundPlayerState.ACTIVE
                && !flights.containsKey(victim.getUniqueId()) && !flights.containsKey(attacker.getUniqueId())
                && inside(victim) && inside(attacker) && context.elapsedTicks() >= hitAfter.getOrDefault(victim.getUniqueId(), 0L)) {
            Vector away = victim.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 0.01) away = attacker.getLocation().getDirection().setY(0);
            if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
            victim.setVelocity(away.normalize().multiply(1.65).setY(0.52));
            hitAfter.put(victim.getUniqueId(), context.elapsedTicks() + 5);
            victim.playSound(victim.getLocation(), "minecraft:entity.player.attack.knockback", 1, 1);
        }
        return EventDecision.DENY;
    }
    private boolean inside(Player player) { Location p = player.getLocation(); return SumoRuntime.onArena(p.getX(), p.getY(), p.getZ()); }
    @Override public RoundResult finish(RoundContext context, RoundEndReason reason) {
        if (result != null) return result;
        started = false;
        Map<UUID, PlayerRoundResult> players = new LinkedHashMap<>();
        for (UUID id : context.participants()) players.put(id, new PlayerRoundResult(runtime.points(id), OptionalInt.empty(),
                runtime.points(id) > 0, runtime.points(id) == 0, Map.of("arena-seconds", String.valueOf(runtime.ticks(id) / 20.0))));
        return result = new RoundResult(players, Map.of("game", id()));
    }
    @Override public void handlePlayerQuit(RoundContext context, UUID id) { flights.remove(id); hitAfter.remove(id); tutorial.remove(context, id); bars.remove(id); }
    @Override public void reset(RoundContext context) {
        started = false; flights.clear(); hitAfter.clear(); bars.clear();
        if (barriers != null) { barriers.restoreAll(); barriers = null; }
        if (tutorial != null) tutorial.end(context);
        for (Player player : context.onlineParticipants()) { player.setVelocity(new Vector()); player.sendActionBar(Text.component("")); Text.clearTitle(player); }
    }
    private record Flight(long tick) { }
}
