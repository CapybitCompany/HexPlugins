package hex.minigames.game.breezetower;

import org.bukkit.entity.Breeze;
import org.bukkit.event.*;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.Plugin;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Allows only the NPCs explicitly created in the current spawn operation, never natural mobs. */
final class BreezeNpcSpawnGuard implements Listener, AutoCloseable {
    private final Set<UUID> spawning = new HashSet<>();
    BreezeNpcSpawnGuard(Plugin plugin) { plugin.getServer().getPluginManager().registerEvents(this, plugin); }
    void include(Breeze npc) { spawning.add(npc.getUniqueId()); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof Breeze && spawning.contains(event.getEntity().getUniqueId())) event.setCancelled(false);
    }
    @Override public void close() {
        HandlerList.unregisterAll(this);
        spawning.clear();
    }
}
