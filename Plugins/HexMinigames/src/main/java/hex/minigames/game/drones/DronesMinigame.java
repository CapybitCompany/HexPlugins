package hex.minigames.game.drones;

import hex.minigames.game.*;
import hex.minigames.game.common.*;
import hex.minigames.model.BlockPosition;
import hex.minigames.runtime.RoundPlayerState;
import hex.minigames.util.Text;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import java.util.*;
import java.util.function.LongSupplier;

/** Registry minigame: the real player is the small, colliding flight controller. */
public final class DronesMinigame implements Minigame {
    private final Plugin plugin;
    private final LongSupplier clock;
    private final NamespacedKey itemKey;
    private final BossBarDisplay bars=new BossBarDisplay();
    private final Map<UUID,Pilot> pilots=new LinkedHashMap<>();
    private final ArrayDeque<Runnable> deferred=new ArrayDeque<>();
    private DronesConfig config;
    private DronesRuntime runtime;
    private StandardTutorial tutorial;
    private boolean running;
    private static final class Pilot {
        final Player player;
        final DronesConfig.Station station;
        final BlockChangeTracker gate;
        final DronePhysics physics=new DronePhysics();
        DronePhysics.Controls input=DronePhysics.Controls.NONE;
        DronePlayerState original;
        DroneMovementLock movementLock;
        DroneBody body;
        DroneVisual visual;
        CheckpointBeams beams;
        DroneGui gui;
        Location lastValid;
        final Set<UUID> hiddenFrom=new HashSet<>();
        Pilot(Player player,DronesConfig.Station station,String world) { this.player=player; this.station=station; gate=new BlockChangeTracker(world); }
    }
    public DronesMinigame(Plugin plugin) { this(plugin,System::nanoTime); }
    DronesMinigame(Plugin plugin,LongSupplier clock) { this.plugin=plugin; this.clock=clock; itemKey=new NamespacedKey(plugin,"drone_controller"); }
    @Override public String id() { return DronesConfig.ID; }
    @Override public MinigameAvailability availability(MinigameDefinition definition,int count) {
        try { new DronesConfig(definition); return count>=1&&count<=14?MinigameAvailability.ok():MinigameAvailability.unavailable("Drony: 1..14 graczy"); }
        catch(RuntimeException error) { return MinigameAvailability.unavailable(error.getMessage()); }
    }
    @Override public boolean fullTutorialEachRound() { return true; }
    @Override public boolean allowSingleRemainingPlayer() { return true; }
    @Override public int countdownSeconds(MinigameDefinition definition,int fallback) { return new DronesConfig(definition).tutorial.durationSeconds(); }
    @Override public void prepare(RoundContext context) {
        config=new DronesConfig(context.definition());
        World world=Bukkit.getWorld(config.world); if(world==null) throw new IllegalStateException("Brak świata "+config.world);
        runtime=new DronesRuntime(context.participants(),config,clock,new Random());
        List<Player> players=new ArrayList<>(context.onlineParticipants());
        players.sort(Comparator.comparing(p -> p.getUniqueId().toString()));
        if(players.size()>14) throw new IllegalStateException("Drony: brak stanowisk");
        for(int i=0;i<players.size();i++) {
            Player player=players.get(i); Pilot pilot=new Pilot(player,config.stations.get(i),config.world);
            pilots.put(player.getUniqueId(),pilot);
            pilot.gate.snapshot(pilot.station.gate());
            pilot.lastValid=pilot.station.location(world);
            if(!player.teleport(pilot.lastValid)) throw new IllegalStateException("Nie można teleportować do boksu: "+player.getName());
            player.getInventory().clear(); player.setItemOnCursor(null);
            pilot.movementLock=new DroneMovementLock(player,new NamespacedKey(plugin,"drones_box_lock"));
        }
        tutorial=new StandardTutorial(plugin,config.tutorial);
        bars.showAll(players,config.bossbar); tutorial.begin(context,players,Map.of());
    }
    @Override public boolean handleCountdownTick(RoundContext context,int ticks) { return tutorial.tick(context,ticks,Map.of()); }
    @Override public void start(RoundContext context) {
        runtime.start(); running=true;
        tutorial.tick(context,0,Map.of()); tutorial.end(context);
        for(Pilot pilot:pilots.values()) {
            Player player=pilot.player; player.setGameMode(GameMode.ADVENTURE); player.setFlying(false); player.setAllowFlight(false);
            player.getInventory().clear(); player.getInventory().setHeldItemSlot(0);
            var item=DroneGui.item(config.controller,GameSettings.string(config.settings,"drone.item.name","&b&lDRON"));
            var meta=item.getItemMeta();
            meta.setHideTooltip(false); // Controller instructions remain visible outside puzzle inventories.
            meta.lore(GameSettings.stringList(GameSettings.child(config.settings,"drone.item.lore"),List.of("&7PPM - wypuść drona")).stream().map(Text::component).toList());
            meta.getPersistentDataContainer().set(itemKey,PersistentDataType.BYTE,(byte)1); item.setItemMeta(meta);
            player.getInventory().setItem(0,item);
        }
    }
    private boolean active(RoundContext context,Pilot pilot) {
        return running&&pilot!=null&&!runtime.expired()&&context.state(pilot.player.getUniqueId())==RoundPlayerState.ACTIVE;
    }
    @Override public EventDecision onInteract(RoundContext context,PlayerInteractEvent event) {
        Pilot pilot=pilots.get(event.getPlayer().getUniqueId());
        if(!active(context,pilot)||event.getHand()!=EquipmentSlot.HAND
                ||(event.getAction()!=Action.RIGHT_CLICK_AIR&&event.getAction()!=Action.RIGHT_CLICK_BLOCK)) return EventDecision.DENY;
        var progress=runtime.player(pilot.player.getUniqueId());
        if(progress.phase()==DronesRuntime.Phase.BOX) {
            var item=event.getItem();
            if(item!=null&&item.hasItemMeta()&&item.getItemMeta().getPersistentDataContainer().has(itemKey,PersistentDataType.BYTE)) launch(pilot);
            return EventDecision.DENY;
        }
        if(event.getAction()!=Action.RIGHT_CLICK_BLOCK||event.getClickedBlock()==null||pilot.gui!=null) return EventDecision.DENY;
        var block=event.getClickedBlock();
        // Server-side reach and line-of-sight validation, independent of client-provided clicks.
        var hit=pilot.player.rayTraceBlocks(DronePlayerState.reach(pilot.player),FluidCollisionMode.NEVER);
        if(hit==null||!block.equals(hit.getHitBlock())) return EventDecision.DENY;
        BlockPosition position=new BlockPosition(block.getX(),block.getY(),block.getZ());
        if(progress.phase()==DronesRuntime.Phase.DRONE) {
            for(int i=0;i<4;i++) if(config.checkpoints.get(i).block().equals(position)) {
                var puzzle=runtime.puzzle(pilot.player.getUniqueId(),i,context.elapsedTicks());
                if(puzzle!=null) open(pilot,puzzle,i,context.elapsedTicks());
                break;
            }
        } else if(progress.phase()==DronesRuntime.Phase.GENERATOR&&config.generators.contains(position)) {
            var puzzle=runtime.generator(pilot.player.getUniqueId(),context.elapsedTicks());
            if(puzzle!=null) open(pilot,puzzle,-1,context.elapsedTicks());
        }
        return EventDecision.DENY;
    }
    private void launch(Pilot pilot) {
        if(!runtime.launch(pilot.player.getUniqueId())) return;
        unlockMovement(pilot);
        Player player=pilot.player; pilot.original=DronePlayerState.capture(player);
        pilot.body=new DroneBody(player,pilot.station.location(player.getWorld()));
        player.getInventory().clear(); player.setItemOnCursor(null);
        DronePlayerState.scale(player,config.scale);
        player.setInvisible(true); player.setCollidable(false); player.setInvulnerable(true); player.setGravity(false);
        player.setFlySpeed(0); player.setWalkSpeed(0); player.setAllowFlight(true); player.setFlying(true);
        Location at=pilot.station.launch(player.getWorld(),config.distance);
        if(!player.teleport(at)) throw new IllegalStateException("Teleport drona nie powiódł się");
        pilot.lastValid=at; player.setVelocity(new Vector());
        pilot.visual=new DroneVisual(at,config.visualScale);
        pilot.beams=new CheckpointBeams(plugin,player,config);
        hideController(pilot);
    }
    private void hideController(Pilot pilot) {
        for(Player viewer:Bukkit.getOnlinePlayers()) if(!viewer.equals(pilot.player)&&pilot.hiddenFrom.add(viewer.getUniqueId())) viewer.hideEntity(plugin,pilot.player);
    }
    private void open(Pilot pilot,DronesPuzzles.Puzzle puzzle,int checkpoint,long tick) {
        pilot.physics.stop(); pilot.input=DronePhysics.Controls.NONE; pilot.player.setVelocity(new Vector());
        pilot.lastValid=pilot.player.getLocation();
        pilot.gui=new DroneGui(pilot.player,puzzle,checkpoint,config,deferred::add);
        pilot.gui.show(tick);
    }
    @Override public void handleTick(RoundContext context) {
        if(!running) return;
        if(runtime.expired()||context.elapsedTicks()>=config.duration*20L) { deferred.clear(); context.requestFinish(RoundEndReason.TIME_LIMIT); return; }
        for(int n=deferred.size();n>0;n--) deferred.removeFirst().run();
        for(Pilot pilot:List.copyOf(pilots.values())) {
            Player player=pilot.player; var progress=runtime.player(player.getUniqueId());
            if(context.state(player.getUniqueId())!=RoundPlayerState.ACTIVE) continue;
            if(context.elapsedTicks()%5==0) {
                int seconds=runtime.remainingSeconds();
                String status=progress.phase()==DronesRuntime.Phase.GENERATOR?config.message("generator","&cIDŹ DO GENERATORA!"):
                        config.message("progress","&fPunkty kontrolne: &e{completed}/4").replace("{completed}",String.valueOf(progress.count()));
                if(pilot.gui!=null&&pilot.gui.puzzle().locked(context.elapsedTicks())) status=config.message("penalty","&cKara: 2 sekundy");
                player.sendActionBar(Text.component(String.format(Locale.ROOT,"&fCzas: &e%02d:%02d &8| %s",seconds/60,seconds%60,status)));
            }
            if(pilot.beams!=null&&context.elapsedTicks()%40==0) pilot.beams.refresh();
            if(pilot.gui!=null) {
                pilot.physics.stop(); pilot.input=DronePhysics.Controls.NONE; player.setVelocity(new Vector());
                pilot.gui.tick(context.elapsedTicks());
                if(pilot.gui.puzzle().done()) completeGui(context,pilot);
                continue;
            }
            if(progress.phase()==DronesRuntime.Phase.DRONE) {
                hideController(pilot);
                player.setFlying(true); player.setFallDistance(0); player.setFireTicks(0);
                if(!DronePhysics.confined(config.region,player.getLocation(),config.scale)) {
                    player.teleport(pilot.lastValid); pilot.physics.stop(); player.setVelocity(new Vector());
                } else {
                    Vector velocity=pilot.physics.tick(pilot.input,player.getLocation().getDirection(),context.elapsedTicks(),config.physics);
                    player.setVelocity(velocity);
                }
                pilot.visual.move(player.getLocation());
            }
        }
        if(runtime.allFinished()) context.requestFinish(RoundEndReason.MINIGAME_REQUEST);
    }
    private void completeGui(RoundContext context,Pilot pilot) {
        int checkpoint=pilot.gui.checkpoint(); closeGui(pilot);
        Player player=pilot.player; UUID id=player.getUniqueId();
        if(checkpoint<0) {
            if(runtime.finish(id)) {
                context.state(id,RoundPlayerState.FINISHED);
                player.sendTitle(Text.color(config.message("finished","&aZALICZONO!")),Text.color(config.message("time","&fTwój czas: &e{time}").replace("{time}",DronesRuntime.format(runtime.nanos(id)))),0,80,10);
            }
            return;
        }
        if(!runtime.complete(id,checkpoint)) return;
        pilot.beams.complete(checkpoint);
        player.playSound(player.getLocation(),config.sound("checkpoint","minecraft:block.note_block.chime"),1,1.2f);
        int count=runtime.player(id).count();
        player.sendTitle("",Text.color(config.message("checkpoint","&aPUNKT ZALICZONY! &f{completed}/4").replace("{completed}",String.valueOf(count))),0,40,5);
        if(count==4) {
            endDrone(pilot);
            player.setGameMode(GameMode.ADVENTURE); player.setFlying(false); player.setAllowFlight(false);
            player.getInventory().clear();
            Location station=pilot.station.location(player.getWorld());
            if(!player.teleport(station)) throw new IllegalStateException("Powrót do boksu nie powiódł się");
            pilot.lastValid=station; openGate(pilot);
            player.sendTitle("",Text.color(config.message("returned","&ePunkty kontrolne &f- &aSukces!")),0,60,5);
            player.sendActionBar(Text.component(config.message("generator","&cIDŹ DO GENERATORA!")));
        }
    }
    @Override public void onInput(RoundContext context,PlayerInputEvent event) {
        Pilot pilot=pilots.get(event.getPlayer().getUniqueId());
        if(active(context,pilot)&&pilot.gui==null&&runtime.player(event.getPlayer().getUniqueId()).phase()==DronesRuntime.Phase.DRONE) pilot.input=DronePhysics.Controls.of(event.getInput());
    }
    @Override public EventDecision onHeldSlot(RoundContext context,PlayerItemHeldEvent event) { return EventDecision.DENY; }
    @Override public EventDecision onToggleFlight(RoundContext context,PlayerToggleFlightEvent event) { return EventDecision.DENY; }
    @Override public EventDecision onTeleport(RoundContext context,PlayerTeleportEvent event) {
        if(!running||config==null) return EventDecision.ALLOW;
        if(!DronePhysics.confined(config.region,event.getTo(),config.scale)) {
            Pilot pilot=pilots.get(event.getPlayer().getUniqueId());
            if(pilot!=null) { pilot.physics.stop(); pilot.player.setVelocity(new Vector()); }
            return EventDecision.DENY;
        }
        return EventDecision.ALLOW;
    }
    @Override public EventDecision onMove(RoundContext context,PlayerMoveEvent event) {
        Pilot pilot=pilots.get(event.getPlayer().getUniqueId()); if(pilot==null||event.getTo()==null) return EventDecision.DENY;
        var phase=runtime.player(pilot.player.getUniqueId()).phase();
        boolean locked=pilot.gui!=null;
        if(locked||!DronePhysics.confined(config.region,event.getTo(),phase==DronesRuntime.Phase.DRONE?config.scale:1)) {
            Location safe=pilot.lastValid.clone(); safe.setYaw(event.getTo().getYaw()); safe.setPitch(event.getTo().getPitch());
            event.setTo(safe); pilot.physics.stop(); pilot.player.setVelocity(new Vector());
        } else pilot.lastValid=event.getTo().clone();
        return EventDecision.PASS;
    }
    @Override public EventDecision onInventoryClick(RoundContext context,InventoryClickEvent event) {
        Pilot pilot=pilots.get(event.getWhoClicked().getUniqueId());
        if(active(context,pilot)&&pilot.gui!=null) pilot.gui.click(event,context.elapsedTicks());
        return EventDecision.DENY;
    }
    @Override public EventDecision onInventoryDrag(RoundContext context,InventoryDragEvent event) {
        Pilot pilot=pilots.get(event.getWhoClicked().getUniqueId());
        if(active(context,pilot)&&pilot.gui!=null) pilot.gui.drag(event,context.elapsedTicks());
        return EventDecision.DENY;
    }
    @Override public void onInventoryClose(RoundContext context,InventoryCloseEvent event) {
        Pilot pilot=pilots.get(event.getPlayer().getUniqueId());
        if(pilot!=null&&pilot.gui!=null&&event.getInventory()==pilot.gui.getInventory()) closeGui(pilot);
    }
    private void closeGui(Pilot pilot) {
        DroneGui gui=pilot.gui; pilot.gui=null; if(gui!=null) gui.close();
        pilot.input=DronePhysics.Controls.NONE; pilot.physics.stop(); pilot.player.setVelocity(new Vector());
    }
    private void endDrone(Pilot pilot) {
        if(pilot.body!=null) { pilot.body.remove(); pilot.body=null; }
        if(pilot.visual!=null) { pilot.visual.remove(); pilot.visual=null; }
        if(pilot.beams!=null) { pilot.beams.remove(); pilot.beams=null; }
        pilot.physics.stop(); pilot.input=DronePhysics.Controls.NONE;
        if(pilot.original!=null) { pilot.original.restore(pilot.player); pilot.original=null; }
        for(UUID id:pilot.hiddenFrom) { Player viewer=Bukkit.getPlayer(id); if(viewer!=null) viewer.showEntity(plugin,pilot.player); }
        pilot.hiddenFrom.clear();
    }
    private void cleanup(Pilot pilot) {
        // Independent cleanup steps ensure a failed GUI operation cannot leak displays or gate changes.
        for(Runnable action:List.<Runnable>of(() -> closeGui(pilot),() -> endDrone(pilot),() -> unlockMovement(pilot),pilot.gate::restoreAll,
                () -> { pilot.player.setItemOnCursor(null); pilot.player.getInventory().clear(); pilot.player.setVelocity(new Vector()); })) {
            try { action.run(); } catch(RuntimeException error) { plugin.getLogger().log(java.util.logging.Level.SEVERE,"Drones cleanup: "+pilot.player.getName(),error); }
        }
    }
    private void unlockMovement(Pilot pilot) {
        if(pilot.movementLock!=null) { pilot.movementLock.restore(); pilot.movementLock=null; }
    }
    private void openGate(Pilot pilot) {
        var region=pilot.station.gate();
        for(int x=region.minX();x<=region.maxX();x++) for(int y=region.minY();y<=region.maxY();y++)
            for(int z=region.minZ();z<=region.maxZ();z++) {
                var data=pilot.player.getWorld().getBlockAt(x,y,z).getBlockData();
                if(data instanceof org.bukkit.block.data.Openable gate) {
                    gate.setOpen(true); pilot.gate.setBlockData(new BlockPosition(x,y,z),gate);
                }
            }
    }
    @Override public void handlePlayerQuit(RoundContext context,UUID id) {
        Pilot pilot=pilots.remove(id); if(pilot!=null) cleanup(pilot);
        if(runtime!=null) runtime.leave(id); if(tutorial!=null) tutorial.remove(context,id); bars.remove(id);
    }
    @Override public RoundResult finish(RoundContext context,RoundEndReason reason) { running=false; deferred.clear(); return runtime==null?RoundResult.empty():runtime.result(); }
    @Override public void reset(RoundContext context) {
        running=false; deferred.clear(); pilots.values().forEach(this::cleanup); pilots.clear();
        if(tutorial!=null) tutorial.end(context); bars.clear();
    }
}
