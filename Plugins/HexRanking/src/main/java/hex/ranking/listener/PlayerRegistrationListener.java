package hex.ranking.listener;

import hex.ranking.service.RankingService;
import hex.ranking.util.MessageUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

public final class PlayerRegistrationListener implements Listener {

    private final Plugin plugin;
    private final RankingService rankingService;

    public PlayerRegistrationListener(Plugin plugin, RankingService rankingService) {
        this.plugin = plugin;
        this.rankingService = rankingService;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        syncPlayer(event.getPlayer());
    }

    public void syncPlayer(Player player) {
        rankingService.ensurePlayerExists(player.getUniqueId(), player.getName())
                .exceptionally(ex -> {
                    plugin.getLogger().warning(
                            "Could not sync ranking row for player '" + player.getName() + "': " + MessageUtil.causeMessage(ex)
                    );
                    return null;
                });
    }
}
