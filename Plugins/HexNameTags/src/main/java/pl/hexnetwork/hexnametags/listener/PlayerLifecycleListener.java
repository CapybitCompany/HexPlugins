package pl.hexnetwork.hexnametags.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import pl.hexnetwork.hexnametags.NameTagManager;

public final class PlayerLifecycleListener implements Listener {
    private final NameTagManager manager;

    public PlayerLifecycleListener(NameTagManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        manager.loadPersistentPlayerTag(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.removeViewer(event.getPlayer());
        // Nie kasujemy DB na quit. Zdejmujemy tylko runtime target z pamięci/renderu.
        manager.unloadTarget(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        manager.refreshViewer(event.getPlayer());
    }
}
