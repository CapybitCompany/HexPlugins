package hex.minigames.framework;

import hex.core.api.HexApi;
import hex.minigames.framework.config.GameTypeConfig;
import org.bukkit.plugin.java.JavaPlugin;

public final class DefaultGameInstanceFactory implements GameInstanceFactory {

    private final JavaPlugin plugin;
    private final HexApi api;
    private final GameBehaviour behaviour;

    public DefaultGameInstanceFactory(JavaPlugin plugin, HexApi api, GameBehaviour behaviour) {
        this.plugin = plugin;
        this.api = api;
        this.behaviour = behaviour;
    }

    @Override
    public GameInstance create(String instanceId, String gameTypeId, String worldName, GameTypeConfig config) {
        return new GameInstance(plugin, api, behaviour, instanceId, gameTypeId, worldName, config);
    }
}

