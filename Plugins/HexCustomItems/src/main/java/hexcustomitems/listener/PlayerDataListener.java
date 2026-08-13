package hexcustomitems.listener;

import hexcustomitems.service.PlayerDataService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class PlayerDataListener implements Listener {

    private final PlayerDataService playerDataService;

    public PlayerDataListener(PlayerDataService playerDataService) {
        this.playerDataService = playerDataService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        playerDataService.apply(event.getPlayer());
    }
}
