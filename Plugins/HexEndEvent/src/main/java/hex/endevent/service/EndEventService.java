package hex.endevent.service;

import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
import hex.endevent.config.EndEventConfig;
import hex.endevent.integration.EndEventGateway;
import hex.endevent.model.EndEventSlot;
import hex.endevent.model.EndEventState;
import hex.endevent.state.EndEventRuntimeState;
import hex.endevent.state.RuntimeStateRepository;
import hex.endevent.ui.EndEventBossBarService;
import hex.endevent.util.TimeTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.*;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

public final class EndEventService {
    private final Plugin plugin; private final HexApi hex;
    private volatile EndEventConfig config;
    private final RuntimeStateRepository runtimeRepository;
    private final EndWorldResetService worldReset;
    private final EndEventBossBarService bossBar;
    private EndEventRuntimeState runtime;
    private volatile EndEventState state = EndEventState.CLOSED;
    private EndEventSlot openSlot;
    private BukkitTask maintenanceTask;
    private boolean runtimeHealthy;
    private int bossBarAccumulatedTicks;
    private volatile EndEventGateway gateway;
    private final Set<UUID> participants = new HashSet<>();
    private final Set<UUID> authorizedTeleports = new HashSet<>();

    public EndEventService(Plugin plugin, HexApi hex, EndEventConfig config) {
        this.plugin=plugin; this.hex=hex; this.config=config;
        this.runtimeRepository=new RuntimeStateRepository(plugin,config.runtimeStateFile());
        var loaded=runtimeRepository.load(); this.runtime=loaded.state(); this.runtimeHealthy=loaded.healthy();
        this.worldReset=new EndWorldResetService(plugin,config); this.bossBar=new EndEventBossBarService(hex,config);
    }

    public void start() {
        if(!runtimeHealthy) setError("runtime.yml jest uszkodzony lub ma nieobsługiwaną wersję");
        else if(!config.enabled()) state=EndEventState.DISABLED;
        else state=EndEventState.CLOSED;
        maintenanceTask=Bukkit.getScheduler().runTaskTimer(plugin,this::maintenanceTick,20L,20L);
        Bukkit.getScheduler().runTask(plugin,this::enforceOnlinePlayers);
    }
    public void shutdown(){if(maintenanceTask!=null){maintenanceTask.cancel();maintenanceTask=null;}bossBar.hideAll();runtimeRepository.save(runtime);}
    public void setGateway(EndEventGateway gateway){this.gateway=gateway;}

    /**
     * Runtime fail-closed boundary. Losing the central HexEvents manager must never leave the End open.
     * This method is intentionally synchronous/best-effort so PluginDisableEvent immediately blocks access.
     */
    public void clearGateway(){
        this.gateway=null;
        participants.clear();
        authorizedTeleports.clear();
        if(state==EndEventState.OPEN||state==EndEventState.PREPARING||state==EndEventState.READY||state==EndEventState.CLOSING){
            failClosedBecauseCentralManagerUnavailable("HexEvents unavailable");
        }
    }

    public void failClosedBecauseCentralManagerUnavailable(String reason){
        this.gateway=null;
        bossBar.hideAll();
        participants.clear();
        authorizedTeleports.clear();

        // First close authorization, then best-effort evacuation. Even when teleport fails,
        // ERROR_CLOSED + EndAccessListener prevents any new legal entry.
        boolean evacuated=worldReset.evictManagedEndPlayers();
        if(!evacuated) plugin.getLogger().severe("Fail-closed: nie udało się ewakuować wszystkich graczy z Endu podczas utraty HexEvents.");

        String active=runtime.activeEventId();
        if(active!=null&&!active.isBlank()) runtime.lastFinishedEventId(active);
        runtime.activeEventId("");
        runtime.activeUntil("");
        runtime.preparedEventId("");
        runtime.resetRequired(true);
        runtimeRepository.save(runtime);
        openSlot=null;
        if(evacuated) worldReset.unloadManagedEndAfterClose();
        setError(reason);
    }

    public void reload(EndEventConfig newConfig){
        if(state==EndEventState.PREPARING||state==EndEventState.CLOSING)throw new IllegalStateException("Nie można reloadować podczas PREPARING/CLOSING");
        if(state==EndEventState.OPEN&&(!config.endWorld().equals(newConfig.endWorld())||!config.returnWorld().equals(newConfig.returnWorld())||!config.runtimeStateFile().equals(newConfig.runtimeStateFile())))throw new IllegalStateException("Nie można zmienić world/runtime podczas aktywnego eventu");
        this.config=newConfig;worldReset.reload(newConfig);bossBar.reload(newConfig);runtimeRepository.setFileName(newConfig.runtimeStateFile());
        if(!newConfig.enabled()){state=EndEventState.DISABLED;enforceOnlinePlayers();}else if(state==EndEventState.DISABLED)state=EndEventState.CLOSED;
    }

    public CompletableFuture<Boolean> prepareExternal(UUID instanceId, Instant startAt, Instant endAt){
        CompletableFuture<Boolean> out=new CompletableFuture<>();
        if(!config.enabled()){out.complete(false);return out;}
        if(instanceId.toString().equals(runtime.preparedEventId())
                && instanceId.toString().equals(runtime.generationEventId())
                && !runtime.resetRequired()
                && worldReset.ensurePreparedWorldLoaded(runtime.generationSeed())) {
            state=EndEventState.READY;out.complete(true);return out;
        }
        state=EndEventState.PREPARING;bossBar.hideAll();long seed=config.seedMode()==EndEventConfig.SeedMode.FIXED?config.fixedSeed():ThreadLocalRandom.current().nextLong();
        worldReset.prepare(seed).whenComplete((result,error)->Bukkit.getScheduler().runTask(plugin,()->{
            if(gateway==null){
                runtime.preparedEventId("");runtime.resetRequired(true);runtimeRepository.save(runtime);
                setError("HexEvents unavailable during End preparation");out.complete(false);return;
            }
            if(error!=null||result==null||!result.success()){setError(error!=null?rootMessage(error):(result==null?"null reset result":result.error()));out.complete(false);return;}
            runtime.preparedEventId(instanceId.toString());runtime.generationEventId(instanceId.toString());runtime.generationSeed(seed);runtime.resetRequired(false);runtime.activeEventId("");runtime.activeUntil("");runtimeRepository.save(runtime);state=EndEventState.READY;out.complete(true);
        }));return out;
    }

    public boolean startExternal(UUID instanceId, Instant startAt, Instant endAt){
        if(!config.enabled())return false;
        boolean recovering=instanceId.toString().equals(runtime.activeEventId());
        if(!recovering && (runtime.resetRequired()
                || !instanceId.toString().equals(runtime.preparedEventId())
                || !instanceId.toString().equals(runtime.generationEventId()))) return false;
        if(!worldReset.ensurePreparedWorldLoaded(runtime.generationSeed()))return false;
        this.openSlot=new EndEventSlot(ZonedDateTime.ofInstant(startAt,config.zoneId()),ZonedDateTime.ofInstant(endAt,config.zoneId()),instanceId.toString());
        state=EndEventState.OPEN;runtime.activeEventId(instanceId.toString());runtime.activeUntil(endAt.toString());runtimeRepository.save(runtime);bossBarAccumulatedTicks=0;bossBar.start(openSlot);
        if(!recovering)hex.ui().broadcast("endevent.broadcast.open",UiTokens.of("duration",TimeTextFormatter.duration(Duration.between(startAt,endAt))));
        return true;
    }

    public CompletableFuture<Boolean> stopExternal(UUID instanceId){CompletableFuture<Boolean> out=new CompletableFuture<>();state=EndEventState.CLOSING;bossBar.hideAll();attemptClose(instanceId,out,0);return out;}
    private void attemptClose(UUID instanceId,CompletableFuture<Boolean> out,int attempt){
        if(worldReset.evictManagedEndPlayers()){
            String finished=runtime.activeEventId();runtime.lastFinishedEventId(finished);runtime.activeEventId("");runtime.activeUntil("");runtime.resetRequired(true);runtime.preparedEventId("");runtimeRepository.save(runtime);participants.clear();authorizedTeleports.clear();openSlot=null;worldReset.unloadManagedEndAfterClose();state=config.enabled()?EndEventState.CLOSED:EndEventState.DISABLED;if(!finished.isBlank())hex.ui().broadcast("endevent.broadcast.closed");out.complete(true);return;
        }
        if(attempt>=15){setError("Nie udało się ewakuować graczy z Endu");out.complete(false);return;}
        Bukkit.getScheduler().runTaskLater(plugin,()->attemptClose(instanceId,out,attempt+1),20L);
    }

    public boolean joinExternal(Player player){
        if(state!=EndEventState.OPEN||openSlot==null)return false;World end=Bukkit.getWorld(config.endWorld());if(end==null||end.getEnvironment()!=World.Environment.THE_END)return false;
        participants.add(player.getUniqueId());
        authorizedTeleports.add(player.getUniqueId());
        boolean ok=player.teleport(end.getSpawnLocation());
        // PlayerTeleportEvent jest synchroniczny i normalnie konsumuje token. Usuwamy go także tutaj,
        // aby żaden token nie pozostał przypadkiem na przyszły teleport, gdy inny plugin zmieni flow.
        authorizedTeleports.remove(player.getUniqueId());
        if(!ok) participants.remove(player.getUniqueId());
        return ok;
    }
    public void leaveParticipant(UUID playerId){participants.remove(playerId);}
    public boolean consumeAuthorizedTeleport(Player player){return authorizedTeleports.remove(player.getUniqueId());}
    public boolean isParticipant(UUID playerId){return participants.contains(playerId)||(gateway!=null&&gateway.isParticipant(playerId));}
    public void requestJoin(Player player,String source){if(gateway==null){notifyBlocked(player);return;}gateway.requestJoin(player,source);}

    private void maintenanceTick(){
        if(state==EndEventState.OPEN&&openSlot!=null){bossBarAccumulatedTicks+=20;if(bossBarAccumulatedTicks>=config.bossBar().updateIntervalTicks()){bossBarAccumulatedTicks=0;bossBar.tick(ZonedDateTime.now(config.zoneId()));}}
        if(state!=EndEventState.OPEN)enforceOnlinePlayers();
    }
    public boolean canEnter(Player player,World target){if(target==null||target.getEnvironment()!=World.Environment.THE_END)return true;return canEnterEnd(player);}
    public boolean canEnterEnd(Player player){if(state==EndEventState.OPEN&&isParticipant(player.getUniqueId()))return true;boolean resetting=state==EndEventState.PREPARING||state==EndEventState.CLOSING;return !resetting&&player.hasPermission(config.bypassPermission());}
    public boolean shouldProtectTarget(World target){if(target==null||target.getEnvironment()!=World.Environment.THE_END)return false;return config.blockAllEndEnvironments()||target.getName().equals(config.endWorld());}
    public void notifyBlocked(Player player){if(gateway==null)hex.ui().send(player,"endevent.error.unavailable");else hex.ui().send(player,"endevent.access.closed",UiTokens.of("next",nextOpenText()));}
    public void enforcePlayer(Player player,boolean notify){if(player.getWorld().getEnvironment()!=World.Environment.THE_END)return;if(canEnter(player,player.getWorld()))return;if(worldReset.evictPlayer(player)&&notify)notifyBlocked(player);bossBar.hide(player);}
    public void enforceOnlinePlayers(){for(Player player:Bukkit.getOnlinePlayers())enforcePlayer(player,false);}
    public void refreshBossBar(Player player){if(state==EndEventState.OPEN)bossBar.refreshPlayer(player);else bossBar.hide(player);} public void hideBossBar(Player p){bossBar.hide(p);}
    public void forceErrorClosed(String reason){setError(reason);} private void setError(String reason){bossBar.hideAll();state=EndEventState.ERROR_CLOSED;plugin.getLogger().severe("HexEndEvent ERROR_CLOSED: "+reason);enforceOnlinePlayers();}

    public EndEventState state(){return state;}public EndEventConfig config(){return config;}public EndEventRuntimeState runtime(){return runtime;}public int playersInEnd(){return worldReset.playersInManagedEnd();}public boolean managedEndLoaded(){return worldReset.isManagedEndLoaded();}public boolean isOpen(){return state==EndEventState.OPEN;}
    public String nextOpenText(){return gateway==null?"HexEvents niedostępny":gateway.next().map(w->TimeTextFormatter.friendly(ZonedDateTime.ofInstant(w.startAt(),config.zoneId()))).orElse("brak zaplanowanego eventu");}
    public String nextOpenPlaceholder(){return gateway==null?"-":gateway.next().map(w->TimeTextFormatter.dateTime(ZonedDateTime.ofInstant(w.startAt(),config.zoneId()))).orElse("-");}
    public String nextOpenDate(){return gateway==null?"-":gateway.next().map(w->TimeTextFormatter.date(ZonedDateTime.ofInstant(w.startAt(),config.zoneId()))).orElse("-");}
    public String nextOpenTime(){return gateway==null?"-":gateway.next().map(w->TimeTextFormatter.time(ZonedDateTime.ofInstant(w.startAt(),config.zoneId()))).orElse("-");}
    public String nextOpenRelative(){return gateway==null?"-":gateway.next().map(w->TimeTextFormatter.relative(ZonedDateTime.now(config.zoneId()),ZonedDateTime.ofInstant(w.startAt(),config.zoneId()))).orElse("-");}
    public String remainingText(){return state==EndEventState.OPEN&&openSlot!=null?TimeTextFormatter.remaining(ZonedDateTime.now(config.zoneId()),openSlot.end()):"-";}public String closesAtText(){return state==EndEventState.OPEN&&openSlot!=null?TimeTextFormatter.time(openSlot.end()):"-";}
    public String statusText(){return switch(state){case DISABLED->"WYŁĄCZONY";case CLOSED->"ZAMKNIĘTY";case PREPARING->"PRZYGOTOWANIE";case READY->"GOTOWY";case OPEN->"OTWARTY";case CLOSING->"ZAMYKANIE";case ERROR_CLOSED->"BŁĄD/ZAMKNIĘTY";};}
    public void sendStatus(org.bukkit.command.CommandSender sender){switch(state){case DISABLED->hex.ui().send(sender,"endevent.status.disabled");case OPEN->hex.ui().send(sender,"endevent.status.open",UiTokens.of("remaining",remainingText()).put("closes",closesAtText()));case PREPARING->hex.ui().send(sender,"endevent.status.preparing",UiTokens.of("next",nextOpenText()));case ERROR_CLOSED->hex.ui().send(sender,"endevent.error.unavailable");default->hex.ui().send(sender,"endevent.status.closed",UiTokens.of("next",nextOpenText()));}}
    private static String rootMessage(Throwable t){Throwable c=t;while(c.getCause()!=null)c=c.getCause();return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage();}
}
