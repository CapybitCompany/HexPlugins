package hex.bossfight;

import hex.bossfight.fight.ActiveBossFight;
import hex.bossfight.fight.ActiveFightRegistry;
import hex.bossfight.integration.BossFightEventModule;
import hex.bossfight.listener.BossCombatListener;
import hex.bossfight.storm.StormBossV1Adapter;
import hex.events.api.HexEventsApi;
import hex.events.api.ModuleRegistration;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class HexBossFightPlugin extends JavaPlugin implements Listener {
    private ModuleRegistration registration;
    private StormBossV1Adapter adapter;
    private ActiveFightRegistry fights;

    @Override public void onEnable(){
        saveDefaultConfig();
        var apiReg=Bukkit.getServicesManager().getRegistration(HexEventsApi.class);
        if(apiReg==null){getLogger().severe("HexEventsApi unavailable; disabling HexBossFight.");getServer().getPluginManager().disablePlugin(this);return;}
        Plugin storm=Bukkit.getPluginManager().getPlugin("STORMBOSSY");
        if(storm==null||!storm.isEnabled()){getLogger().severe("STORMBOSSY unavailable; disabling HexBossFight.");getServer().getPluginManager().disablePlugin(this);return;}
        String supported=getConfig().getString("stormbossy.supported-version","1.0");
        adapter=new StormBossV1Adapter(storm,supported);
        if(!adapter.health().ready())getLogger().warning("STORM adapter not ready: "+adapter.health().status()+" / "+adapter.health().message());
        fights=new ActiveFightRegistry();
        boolean strictSchedules=getConfig().getBoolean("stormbossy.require-native-schedules-disabled",true);
        boolean strictRewards=getConfig().getBoolean("stormbossy.require-native-rewards-disabled",true);
        BossFightEventModule module=new BossFightEventModule(adapter,fights,strictSchedules,strictRewards);
        registration=apiReg.getProvider().registerModule(module);
        getServer().getPluginManager().registerEvents(new BossCombatListener(fights,apiReg.getProvider(),getConfig().getBoolean("tracking.include-projectiles",true)),this);
        getServer().getPluginManager().registerEvents(this,this);
        getLogger().info("HexBossFight enabled. STORM="+adapter.health().status()+", bosses="+adapter.bossIds().size());
    }

    @EventHandler
    public void onDependencyDisable(PluginDisableEvent event) {
        String name = event.getPlugin().getName();
        if (!name.equalsIgnoreCase("HexEvents") && !name.equalsIgnoreCase("STORMBOSSY")) return;
        getLogger().severe(name + " disabled while HexBossFight is active; stopping all tracked bosses fail-closed.");
        cleanupActiveBosses();
        if (registration != null) { registration.close(); registration = null; }
    }

    @Override public void onDisable(){
        cleanupActiveBosses();
        if(registration!=null){registration.close();registration=null;}
        fights=null;
        adapter=null;
    }

    private void cleanupActiveBosses() {
        ActiveFightRegistry registry = fights;
        StormBossV1Adapter currentAdapter = adapter;
        if (registry == null || currentAdapter == null) return;
        for (ActiveBossFight fight : registry.all()) {
            if (fight.bossEntityId == null) continue;
            try {
                var result = currentAdapter.stop(fight.bossEntityId);
                if (!result.success()) getLogger().warning("Could not stop boss " + fight.bossEntityId + " during fail-closed cleanup: " + result.message());
            } catch (Throwable error) {
                getLogger().warning("Error stopping boss " + fight.bossEntityId + " during fail-closed cleanup: " + error.getMessage());
            }
        }
        registry.clear();
    }
}
