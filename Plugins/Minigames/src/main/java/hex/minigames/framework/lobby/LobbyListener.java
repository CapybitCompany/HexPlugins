package hex.minigames.framework.lobby;

import hex.minigames.framework.InstanceManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.function.Supplier;

public final class LobbyListener implements Listener {

    private final Supplier<InstanceManager> managerSupplier;

    public LobbyListener(Supplier<InstanceManager> managerSupplier) {
        this.managerSupplier = managerSupplier;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        managerSupplier.get().leave(event.getPlayer().getUniqueId());
    }
}
