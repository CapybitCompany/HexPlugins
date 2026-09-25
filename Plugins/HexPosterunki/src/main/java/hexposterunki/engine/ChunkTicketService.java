package hexposterunki.engine;

import hexposterunki.config.Cuboid;
import hexposterunki.config.OutpostDefinition;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Keeps only the chunks of the currently active outpost loaded, via plugin chunk tickets.
 *
 * <p>Tickets are strictly scoped: they are taken when a run starts (or is recovered) and dropped
 * on reset/cooldown, so the plugin never pins chunks of inactive fortresses.
 */
public final class ChunkTicketService {

    private final Plugin plugin;
    private final Logger logger;
    private final List<long[]> held = new ArrayList<>();
    private String heldWorld;

    public ChunkTicketService(Plugin plugin, Logger logger) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = logger;
    }

    /** Takes tickets for the outpost region, capped by {@code limit} chunks. */
    public void acquire(OutpostDefinition outpost, int limit) {
        release();
        if (outpost == null || limit <= 0) {
            return;
        }
        World world = Bukkit.getWorld(outpost.world());
        if (world == null) {
            return;
        }
        Cuboid region = outpost.region();
        int total = region.chunkCount();
        if (total > limit) {
            logger.warning("[chunks] Region posterunku '" + outpost.id() + "' obejmuje " + total
                    + " chunków, limit to " + limit + ". Ładuję tylko " + limit + " chunków wokół środka.");
        }
        int centerChunkX = (int) Math.floor(region.centerX()) >> 4;
        int centerChunkZ = (int) Math.floor(region.centerZ()) >> 4;

        List<long[]> candidates = new ArrayList<>(total);
        for (int cx = region.chunkMinX(); cx <= region.chunkMaxX(); cx++) {
            for (int cz = region.chunkMinZ(); cz <= region.chunkMaxZ(); cz++) {
                candidates.add(new long[]{cx, cz});
            }
        }
        // Closest to the centre first, so a capped region still keeps the fight area loaded.
        candidates.sort((a, b) -> Long.compare(
                squaredChunkDistance(a, centerChunkX, centerChunkZ),
                squaredChunkDistance(b, centerChunkX, centerChunkZ)));

        int taken = 0;
        for (long[] chunk : candidates) {
            if (taken >= limit) {
                break;
            }
            try {
                if (world.addPluginChunkTicket((int) chunk[0], (int) chunk[1], plugin)) {
                    held.add(chunk);
                }
            } catch (RuntimeException exception) {
                // A server implementation without plugin chunk tickets must not kill the event;
                // the encounter then relies on normal chunk loading instead.
                logger.warning("[chunks] Ten serwer nie obsługuje ticketów chunków: "
                        + exception.getMessage() + ". Kontynuuję bez wymuszonego ładowania.");
                heldWorld = outpost.world();
                return;
            }
            taken++;
        }
        heldWorld = outpost.world();
    }

    public void release() {
        if (held.isEmpty()) {
            heldWorld = null;
            return;
        }
        World world = heldWorld == null ? null : Bukkit.getWorld(heldWorld);
        if (world != null) {
            try {
                for (long[] chunk : held) {
                    world.removePluginChunkTicket((int) chunk[0], (int) chunk[1], plugin);
                }
            } catch (RuntimeException exception) {
                logger.warning("[chunks] Nie udało się zwolnić ticketów: " + exception.getMessage());
            }
        }
        held.clear();
        heldWorld = null;
    }

    public int heldChunks() {
        return held.size();
    }

    private static long squaredChunkDistance(long[] chunk, int centerX, int centerZ) {
        long dx = chunk[0] - centerX;
        long dz = chunk[1] - centerZ;
        return dx * dx + dz * dz;
    }
}
