package hex.minigames;

import hex.core.api.HexApi;
import hex.minigames.framework.DefaultGameInstanceFactory;
import hex.minigames.framework.InstanceManager;
import hex.minigames.framework.ServerStatusSnapshot;
import hex.minigames.framework.command.ArenaCommand;
import hex.minigames.framework.command.JoinCommand;
import hex.minigames.framework.command.LeaveCommand;
import hex.minigames.framework.command.MinigamesCommand;
import hex.minigames.framework.config.MinigamesConfig;
import hex.minigames.framework.config.MinigamesConfigLoader;
import hex.minigames.framework.lobby.LobbyListener;
import hex.minigames.framework.map.MapProvider;
import hex.minigames.framework.map.SingleWorldMapProvider;
import hex.minigames.framework.status.ReflectiveStatusPublisher;
import hex.minigames.framework.status.StatusPublisher;
import hex.minigames.games.skywars.SkyWarsBehaviour;
import hex.minigames.games.spleef.SpleefBehaviour;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

public final class MinigamesPlugin extends JavaPlugin {

    private HexApi api;
    private InstanceManager instanceManager;
    private StatusPublisher statusPublisher;

    @Override
    public void onEnable() {
        var provider = Bukkit.getServicesManager().getRegistration(HexApi.class);
        if (provider == null) {
            getLogger().severe("HexCore not found.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.api = provider.getProvider();

        saveResource("minigames.yml", false);

        if (!reloadFramework()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(new LobbyListener(() -> instanceManager), this);

        JoinCommand joinCmd = new JoinCommand(api, () -> instanceManager);
        reg("join", joinCmd, joinCmd);

        reg("leave", new LeaveCommand(api, () -> instanceManager), null);

        ArenaCommand arenaCmd = new ArenaCommand(() -> instanceManager);
        reg("arena", arenaCmd, arenaCmd);

        reg("minigames", new MinigamesCommand(() -> {
            if (!reloadFramework()) {
                getLogger().warning("Reload failed. Previous runtime remains active.");
            }
        }), null);

        Bukkit.getScheduler().runTaskTimer(this, () -> instanceManager.tick(), 20L, 20L);

        statusPublisher = new ReflectiveStatusPublisher(this, api);
        Bukkit.getScheduler().runTaskTimer(this, () -> statusPublisher.publish(
                instanceManager.collectModeStatus(),
                new ServerStatusSnapshot(Bukkit.getOnlinePlayers().size(), getServer().getMotd(), "ONLINE")
        ), 40L, 40L);

        getLogger().info("Minigames enabled.");
    }

    private boolean reloadFramework() {
        try {
            MinigamesConfig config = new MinigamesConfigLoader().load(this);

            MapProvider mapProvider = new SingleWorldMapProvider();
            InstanceManager newManager = new InstanceManager(this, mapProvider);

            if (config.asMap().containsKey("skywars")) {
                newManager.registerGameType(
                        "skywars",
                        config.requireGameType("skywars"),
                        new DefaultGameInstanceFactory(this, api, new SkyWarsBehaviour())
                );
            }

            if (config.asMap().containsKey("spleef")) {
                newManager.registerGameType(
                        "spleef",
                        config.requireGameType("spleef"),
                        new DefaultGameInstanceFactory(this, api, new SpleefBehaviour())
                );
            }

            this.instanceManager = newManager;
            getLogger().info("Minigames framework loaded. Types: " + instanceManager.gameTypes());
            return true;
        } catch (Exception ex) {
            getLogger().severe("Failed to load minigames.yml: " + ex.getMessage());
            return false;
        }
    }

    private void reg(String commandName, CommandExecutor exec, TabCompleter tab) {
        var cmd = getCommand(commandName);
        if (cmd == null) {
            getLogger().severe("Command missing in plugin.yml: " + commandName);
            return;
        }
        cmd.setExecutor(exec);
        if (tab != null) {
            cmd.setTabCompleter(tab);
        }
    }
}

