package hex.collections.service;

import hex.collections.model.CollectionDefinition;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.logging.Logger;

public final class CollectionRewardService {
    private final Plugin plugin;
    private final Logger logger;

    public CollectionRewardService(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void unlock(UUID townId, UUID playerUuid, CollectionDefinition definition, int level) {
        Bukkit.getScheduler().runTask(plugin, () -> logger.info("Collection reward unlocked: town=" + townId
                + ", player=" + playerUuid + ", collection=" + definition.id() + ", level=" + level));
    }
}

