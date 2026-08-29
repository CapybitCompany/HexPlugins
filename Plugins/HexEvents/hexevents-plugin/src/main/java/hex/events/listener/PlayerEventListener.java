package hex.events.listener;

import hex.events.api.LeaveReason;
import hex.events.lifecycle.EventLifecycleService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerEventListener implements Listener {
    private final EventLifecycleService lifecycle;

    public PlayerEventListener(EventLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        lifecycle.onPlayerJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        for (var instance : lifecycle.allInstances()) {
            if (instance.participants().contains(player.getUniqueId())) {
                lifecycle.leave(player, instance.id(), LeaveReason.DISCONNECT);
            }
        }
    }
}
