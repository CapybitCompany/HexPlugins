package hex.minions.listener;

import hex.minions.machine.MachineService;
import hex.minions.service.MinionService;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;

public final class MinionWorldListener implements Listener {
    private final Plugin plugin;
    private final MinionService service;
    private final MachineService machines;

    public MinionWorldListener(Plugin plugin, MinionService service, MachineService machines) {
        this.plugin = plugin;
        this.service = service;
        this.machines = machines;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            service.observeChunk(event.getChunk());
            machines.observeChunk(event.getChunk());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        machines.observeChunkUnload(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Migracja itemów wygenerowanych przez starszy bug: wyłącznie dokładne, pluginowe nazwy
        // vanilla outputów są usuwane, dzięki czemu ponownie stackują się z normalnymi surowcami.
        machines.normalizeLegacyMachineOutputs(event.getPlayer());
        // Jeżeli gracz dołącza do świata, w którym chunki z minionami/maszynami są już załadowane,
        // krótkie opóźnienie pozwala odświeżyć obserwowany chunk i rozliczyć zaległą produkcję.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            service.observeChunk(event.getPlayer().getLocation().getChunk());
            machines.observeChunk(event.getPlayer().getLocation().getChunk());
        }, 20L);
    }
}
