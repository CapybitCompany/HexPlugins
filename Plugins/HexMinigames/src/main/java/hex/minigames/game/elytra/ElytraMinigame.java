package hex.minigames.game.elytra;
import hex.minigames.game.*;
import hex.minigames.game.common.*;
import hex.minigames.model.*;
import hex.minigames.runtime.RoundPlayerState;
import hex.minigames.util.Text;
import org.bukkit.*;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import java.util.*;

/** Exact-mask Elytra race with individual ring completion and persistent marker recovery. */
public final class ElytraMinigame implements Minigame {
    private final Plugin plugin;
    private final BossBarDisplay bars = new BossBarDisplay();
    private final Set<UUID> flown = new HashSet<>();
    private final Map<UUID, Long> lastFlight = new HashMap<>(), padAfter = new HashMap<>();
    private final Map<UUID, Set<BlockPosition>> colored = new HashMap<>();

    private List<RingDefinition> rings = List.of();
    private List<Set<BlockPosition>> frames = List.of();
    private ElytraRuntime runtime;
    private StandardTutorial tutorial;
    private World world;
    private Location spawn;
    private boolean started;
    private boolean ownsJournal;
    private RoundResult result;
    private final List<BlockPosition> gates = List.of(new BlockPosition(277,-21,63),new BlockPosition(276,-21,63));
    private final Set<BlockPosition> pads = Set.of(new BlockPosition(276,-21,55),new BlockPosition(277,-21,63),
            new BlockPosition(276,-21,63),new BlockPosition(276,-21,67),new BlockPosition(275,-21,67),
            new BlockPosition(274,-21,67),new BlockPosition(273,-21,67),new BlockPosition(272,-21,65));

    public ElytraMinigame(Plugin plugin) { this.plugin = plugin; }
    @Override public String id() { return "elytra"; }
    @Override public boolean finishWhenAllActiveResolved() { return false; }
    @Override public int countdownSeconds(MinigameDefinition definition, int global) { return 20; }
    static List<BlockPosition> order(MinigameDefinition definition) {
        List<BlockPosition> order = new ArrayList<>();
        for (Object raw : GameSettings.list(GameSettings.child(definition.settings(), "ring-order"))) {
            if (GameSettings.child(raw, "x") == null || GameSettings.child(raw,"y") == null || GameSettings.child(raw,"z") == null) throw new IllegalArgumentException("Each ring-order entry needs x/y/z");
            order.add(new BlockPosition(GameSettings.integer(raw,"x",0),GameSettings.integer(raw,"y",0),GameSettings.integer(raw,"z",0)));
        }
        return order;
    }
    @Override public MinigameAvailability availability(MinigameDefinition definition, int players) {
        try {
            if (order(definition).isEmpty()) return MinigameAvailability.unavailable("Elytra: wpisz markery PINK_CONCRETE w settings.ring-order");
            return MinigameAvailability.ok();
        } catch (IllegalArgumentException error) { return MinigameAvailability.unavailable(error.getMessage()); }
    }
    @Override public void prepare(RoundContext context) {
        var definition = context.definition();
        var region = definition.region().orElseThrow();
        world = Objects.requireNonNull(Bukkit.getWorld(region.worldName()), "Elytra world unavailable");
        ElytraMarkerJournal.recover(plugin);
        Map<BlockPosition, Boolean> markers = new HashMap<>();
        // Chunk snapshots avoid millions of live block wrapper allocations. Never scan outside the arena.
        for (int cx = region.minX() >> 4; cx <= region.maxX() >> 4; cx++) for (int cz = region.minZ() >> 4; cz <= region.maxZ() >> 4; cz++) {
            var snapshot = world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false);
            for (int x = Math.max(region.minX(), cx << 4); x <= Math.min(region.maxX(), (cx << 4) + 15); x++)
                for (int z = Math.max(region.minZ(), cz << 4); z <= Math.min(region.maxZ(), (cz << 4) + 15); z++)
                    for (int y = region.minY(); y <= region.maxY(); y++) {
                        Material type = snapshot.getBlockType(x & 15, y, z & 15);
                        if (type == Material.PINK_CONCRETE || type == Material.YELLOW_WOOL) markers.put(new BlockPosition(x,y,z), type == Material.PINK_CONCRETE);
                    }
        }
        rings = RingMaskBuilder.build(markers, order(definition));
        frames = rings.stream().map(ring -> {
            Set<BlockPosition> frame = new HashSet<>();
            for (var cell : ring.passableCells()) for (var n : RingMaskBuilder.neighbors(cell)) {
                boolean inPlane = switch (ring.axis()) { case X -> n.x() == cell.x(); case Y -> n.y() == cell.y(); case Z -> n.z() == cell.z(); };
                if (inPlane && !markers.containsKey(n) && region.contains(n)
                        && !world.getBlockAt(n.x(),n.y(),n.z()).isEmpty()) frame.add(n);
            }
            return Set.copyOf(frame);
        }).toList();
        // Launch plates may occupy the gate positions in this arena.
        Set<BlockPosition> recovery = new HashSet<>(markers.keySet()); recovery.addAll(gates);
        ElytraMarkerJournal.save(plugin, world, recovery);
        ownsJournal = true;
        for (var marker : markers.keySet()) world.getBlockAt(marker.x(),marker.y(),marker.z()).setType(Material.AIR, false);
        for (var gate : gates) setGate(gate, false);
        spawn = definition.participantSpawns().getFirst().toLocation(region.worldName());
        runtime = new ElytraRuntime(rings);
        started = false; result = null; flown.clear(); lastFlight.clear(); padAfter.clear(); colored.clear();
        var errors = new ArrayList<String>();
        tutorial = new StandardTutorial(plugin, CommonGameConfig.tutorial(definition.settings(), id(),
                List.of("&d&lELYTRA", "&fPrzeleć przez wszystkie obręcze w dowolnej kolejności.",
                        "&fZielone obręcze masz już zaliczone — kolor widzisz tylko Ty.",
                        "&fUżywaj fajerwerków. Złote płytki pomagają wystartować.",
                        "&fLądowanie lub niebezpieczne zderzenie cofa na spawn, zachowując postęp.",
                        "&fPunkty za ukończenie: &e1. = 3, 2.–5. = 2, 6.–8. = 1&f."), errors));
        bars.showAll(context.onlineParticipants(), CommonGameConfig.bossBar(definition.settings(), id(), "&d&lELYTRA", errors));
        tutorial.begin(context, context.onlineParticipants(), Map.of());
    }
    private void setGate(BlockPosition gate, boolean open) {
        var block = world.getBlockAt(gate.x(),gate.y(),gate.z());
        if (block.getBlockData() instanceof Openable data) {
            data.setOpen(open); block.setBlockData(data, false);
        }
    }
    @Override public boolean handleCountdownTick(RoundContext context, int ticks) { return tutorial.tick(context, ticks, Map.of()); }
    @Override public void start(RoundContext context) {
        tutorial.end(context);
        for (Player player : context.onlineParticipants()) equip(player);
        for (var gate : gates) setGate(gate, true);
        started = true;
    }
    private void equip(Player player) {
        player.getInventory().clear();
        ItemStack wings = new ItemStack(Material.ELYTRA);
        var meta = wings.getItemMeta(); meta.setUnbreakable(true); wings.setItemMeta(meta);
        player.getInventory().setChestplate(wings);
        ItemStack rockets = new ItemStack(Material.FIREWORK_ROCKET, 64);
        FireworkMeta firework = (FireworkMeta) rockets.getItemMeta(); firework.setPower(1); rockets.setItemMeta(firework);
        for (int slot = 0; slot < 9; slot++) player.getInventory().setItem(slot, rockets.clone());
    }
    @Override public void handleTick(RoundContext context) {
        if (!started) return;
        for (Player player : context.onlineParticipants()) {
            UUID id = player.getUniqueId();
            if (context.elapsedTicks() % 10 == 0) showFrames(player);
            if (context.state(id) != RoundPlayerState.ACTIVE) continue;
            if (player.isGliding()) { flown.add(id); lastFlight.put(id, context.elapsedTicks()); }
            else if (flown.contains(id) && (player.isOnGround() || context.elapsedTicks() - lastFlight.getOrDefault(id, context.elapsedTicks()) >= 5)) {
                respawn(context, player); continue;
            }
            if (player.getLocation().getY() <= context.definition().region().orElseThrow().minY()) { respawn(context, player); continue; }
            if (context.elapsedTicks() % 10 == 0) showProgress(context, player);
        }
    }
    @Override public EventDecision onMove(RoundContext context, PlayerMoveEvent event) {
        Player player = event.getPlayer(); UUID id = player.getUniqueId();
        if (!started || runtime == null || event.getTo() == null || context.state(id) != RoundPlayerState.ACTIVE) return EventDecision.PASS;
        Location from = event.getFrom(), to = event.getTo();
        if (!world.equals(from.getWorld()) || !world.equals(to.getWorld())) return EventDecision.PASS;

        int passed = runtime.move(id, player.isGliding(), from.getX(),from.getY()+0.3,from.getZ(),to.getX(),to.getY()+0.3,to.getZ(),context.elapsedTicks());
        if (passed > 0) {
            for (int i : runtime.passedRings(id)) colored.computeIfAbsent(id, ignored -> new HashSet<>()).addAll(frames.get(i));
            showFrames(player);
            player.playSound(player.getLocation(), "minecraft:block.note_block.pling", 1, 1.4f);
            showProgress(context, player);
            if (runtime.finished(id)) {
                context.state(id, RoundPlayerState.FINISHED);
                player.setGliding(false); player.setAllowFlight(true); player.setFlying(true); player.setVelocity(new Vector());
                if (context.participants().stream().allMatch(runtime::finished)) context.requestFinish(RoundEndReason.MINIGAME_REQUEST);
            }
        }
        return EventDecision.PASS;
    }
    private void showProgress(RoundContext context, Player player) {
        player.sendActionBar(Text.component("&fCzas: &e" + RoundClock.remaining(context.definition().roundTimeSeconds(),context.elapsedTicks())
                + " &7| &fObręcze: &a" + runtime.progress(player.getUniqueId()) + "/" + runtime.total()));
    }
    private void showFrames(Player player) {
        if (!player.getWorld().equals(world)) return;
        for (var p : colored.getOrDefault(player.getUniqueId(), Set.of())) {
            Location at = new Location(world,p.x(),p.y(),p.z());
            player.sendBlockChange(at, Material.LIME_CONCRETE.createBlockData());
        }
    }

    /** Reapply personal colors after the chunk packet replaces client-side block changes. */
    @Override public void onChunkLoad(RoundContext context, Player player, int x, int z) {
        if (!player.getWorld().equals(world)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !player.getWorld().equals(world)) return;
            for (var p : colored.getOrDefault(player.getUniqueId(), Set.of())) {
                if ((p.x() >> 4) == x && (p.z() >> 4) == z)
                    player.sendBlockChange(new Location(world, p.x(), p.y(), p.z()), Material.LIME_CONCRETE.createBlockData());
            }
        });
    }
    private void clearFrames(Player player) {
        if (!player.getWorld().equals(world)) {
            colored.remove(player.getUniqueId());
            return;
        }
        for (var p : colored.getOrDefault(player.getUniqueId(), Set.of())) {
            player.sendBlockChange(new Location(world,p.x(),p.y(),p.z()),world.getBlockAt(p.x(),p.y(),p.z()).getBlockData());
        }
        colored.remove(player.getUniqueId());
    }
    @Override public EventDecision onInteract(RoundContext context, PlayerInteractEvent event) {
        if (!started || context.state(event.getPlayer().getUniqueId()) != RoundPlayerState.ACTIVE) return EventDecision.DENY;
        var block = event.getClickedBlock();
        if (event.getAction() == org.bukkit.event.block.Action.PHYSICAL && block != null
                && block.getType() == Material.LIGHT_WEIGHTED_PRESSURE_PLATE
                && pads.contains(new BlockPosition(block.getX(),block.getY(),block.getZ()))
                && context.elapsedTicks() >= padAfter.getOrDefault(event.getPlayer().getUniqueId(), 0L)) {
            event.getPlayer().setVelocity(new Vector(0, 0.9, 0));
            padAfter.put(event.getPlayer().getUniqueId(),context.elapsedTicks()+20);
        }
        if (event.getItem() != null && event.getItem().getType() == Material.FIREWORK_ROCKET) {
            event.setUseItemInHand(org.bukkit.event.Event.Result.ALLOW);
            return EventDecision.ALLOW;
        }
        return EventDecision.DENY;
    }
    private void respawn(RoundContext context, Player player) {
        UUID id = player.getUniqueId();
        if (context.state(id) != RoundPlayerState.ACTIVE) return;
        flown.remove(id); lastFlight.remove(id);
        player.setGliding(false);
        context.state(id,RoundPlayerState.RESPAWN_DELAY);
        context.respawn(player,spawn,() -> { equip(player); context.state(id,RoundPlayerState.ACTIVE); showProgress(context,player); });
    }
    @Override public EventDecision onDamage(RoundContext context, EntityDamageEvent event) {
        if (started && event.getEntity() instanceof Player player && context.state(player.getUniqueId()) == RoundPlayerState.ACTIVE
                && (event.getFinalDamage() >= player.getHealth() || event.getCause() == EntityDamageEvent.DamageCause.FALL || event.getCause() == EntityDamageEvent.DamageCause.VOID)) respawn(context,player);
        return EventDecision.DENY;
    }
    @Override public void handlePlayerQuit(RoundContext context, UUID id) {
        context.player(id).ifPresent(this::clearFrames);
        flown.remove(id); lastFlight.remove(id); padAfter.remove(id);

        if (tutorial != null) tutorial.remove(context,id); bars.remove(id);
    }
    @Override public RoundResult finish(RoundContext context, RoundEndReason reason) {
        if (result != null) return result;
        started = false;
        Map<UUID,PlayerRoundResult> out = new LinkedHashMap<>();
        for (UUID id : context.participants()) {
            boolean complete = runtime != null && runtime.finished(id);
            out.put(id,new PlayerRoundResult(complete ? runtime.points(id) : 0, complete ? OptionalInt.of(runtime.place(id)) : OptionalInt.empty(),
                    complete,!complete,Map.of("rings",String.valueOf(runtime == null ? 0 : runtime.progress(id)),
                            "finish-tick",String.valueOf(runtime == null ? 0 : runtime.finishTick(id)))));
        }
        return result = new RoundResult(out,Map.of("game",id()));
    }
    @Override public void reset(RoundContext context) {
        started = false;
        if (ownsJournal) { ElytraMarkerJournal.recover(plugin); ownsJournal = false; }
        for (Player player : context.onlineParticipants()) {
            clearFrames(player); player.setGliding(false); player.getInventory().clear(); player.sendActionBar(Text.component("")); Text.clearTitle(player);
        }
        if (tutorial != null) tutorial.end(context);
        bars.clear(); flown.clear(); lastFlight.clear(); padAfter.clear(); colored.clear();

    }
}
