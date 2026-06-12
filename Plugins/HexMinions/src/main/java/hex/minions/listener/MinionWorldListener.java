package hex.minions.listener;

import hex.minions.service.MinionService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.Bukkit;

public final class MinionWorldListener implements Listener {
    private final Plugin plugin;
    private final MinionService service;

    public MinionWorldListener(Plugin plugin, MinionService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> service.observeChunk(event.getChunk()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Jeżeli gracz dołącza do świata, w którym chunki z minionami są już załadowane,
        // krótkie opóźnienie pozwala odświeżyć obserwowany chunk i rozliczyć zaległą produkcję.
        Bukkit.getScheduler().runTaskLater(plugin, () -> service.observeChunk(event.getPlayer().getLocation().getChunk()), 20L);
    }
}
