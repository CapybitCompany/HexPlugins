package hex.rankexpiry.listener;

import hex.rankexpiry.config.RankExpirySettings;
import hex.rankexpiry.model.RankExpiry;
import hex.rankexpiry.service.RankExpiryService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;

public final class PlayerJoinListener implements Listener {
    private final Plugin plugin;
    private final RankExpiryService service;

    public PlayerJoinListener(Plugin plugin, RankExpiryService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        service.refreshNow(uuid).thenAccept(rank -> sendJoinMessageLater(uuid, rank));
    }

    private void sendJoinMessageLater(UUID uuid, Optional<RankExpiry> rank) {
        RankExpirySettings settings = service.settings();
        if (!settings.joinMessageEnabled() || rank.isEmpty()) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                return;
            }
            RankExpiry activeRank = rank.get();
            if (!activeRank.activeAt(service.nowEpochSeconds())) {
                return;
            }
            for (String line : settings.joinMessageLines()) {
                player.sendMessage(RankExpirySettings.color(service.format(line, activeRank)));
            }
        }, settings.joinMessageDelayTicks());
    }
}
