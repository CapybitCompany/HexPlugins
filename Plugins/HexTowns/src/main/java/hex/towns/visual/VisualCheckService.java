package hex.towns.visual;

import hex.towns.config.TownsConfig;
import hex.towns.service.TownsService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VisualCheckService implements Listener {
    private final TownsService service;
    private final TownsConfig config;
    private final Map<UUID, VisualSession> active = new ConcurrentHashMap<>();
    private final BlockData visualData;
    private final BukkitTask refreshTask;
    private long ticksElapsed;

    public VisualCheckService(Plugin plugin, TownsService service, TownsConfig config) {
        this.service = service;
        this.config = config;
        this.visualData = config.visualBlock().createBlockData();
        this.refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, config.visualRefreshTicks(), config.visualRefreshTicks());
    }

    public boolean toggle(Player player) {
        UUID id = player.getUniqueId();
        if (active.containsKey(id)) {
            clear(player);
            return false;
        }
        active.put(id, new VisualSession());
        refresh(player, true, new RenderBudget(config.visualMaxBlocksPerTickGlobal()));
        return true;
    }

    public void shutdown() {
        for (UUID id : Set.copyOf(active.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                clear(player);
            }
        }
        refreshTask.cancel();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        active.remove(event.getPlayer().getUniqueId());
    }

    private void refreshAll() {
        ticksElapsed += config.visualRefreshTicks();
        boolean forceState = shouldForceStateRefresh();

        RenderBudget budget = new RenderBudget(config.visualMaxBlocksPerTickGlobal());
        for (UUID id : active.keySet()) {
            if (!budget.hasRemaining()) {
                return;
            }
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                refresh(player, forceState, budget);
            }
        }
    }

    private boolean shouldForceStateRefresh() {
        int stateInterval = config.visualStateRefreshTicks();
        if (stateInterval <= 0) {
            return false;
        }
        if (ticksElapsed >= stateInterval) {
            ticksElapsed = 0L;
            return true;
        }
        return false;
    }

    private void refresh(Player player, boolean force, RenderBudget budget) {
        VisualSession session = active.get(player.getUniqueId());
        if (session == null || budget == null || !budget.hasRemaining()) {
            return;
        }
        int centerX = player.getChunk().getX();
        int centerZ = player.getChunk().getZ();
        String worldName = player.getWorld().getName();

        if (!force && session.matches(worldName, centerX, centerZ)) {
            return;
        }

        clearMarkers(player, session.markers);
        session.markers.clear();
        session.remember(worldName, centerX, centerZ);

        int radius = config.visualRadiusChunks();

        for (int cx = centerX - radius; cx <= centerX + radius; cx++) {
            for (int cz = centerZ - radius; cz <= centerZ + radius; cz++) {
                if (!budget.hasRemaining()) {
                    return;
                }
                if (player.getWorld().isChunkLoaded(cx, cz) && service.townAt(worldName, cx, cz).isPresent()) {
                    addChunkBeaconFrame(player, session.markers, cx, cz, budget);
                }
            }
        }
    }

    private void addChunkBeaconFrame(Player player, Set<BlockMarker> markers, int chunkX, int chunkZ, RenderBudget budget) {
        World world = player.getWorld();
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int maxX = minX + 15;
        int maxZ = minZ + 15;
        int topY = Math.min(world.getMaxHeight() - 2, highestCornerBase(world, minX, minZ, maxX, maxZ) + config.visualPillarHeight());

        addCornerPillar(player, markers, minX, minZ, topY, budget);
        addCornerPillar(player, markers, minX, maxZ, topY, budget);
        addCornerPillar(player, markers, maxX, minZ, topY, budget);
        addCornerPillar(player, markers, maxX, maxZ, topY, budget);
        addTopFrame(player, markers, minX, minZ, maxX, maxZ, topY, budget);
        addCenterSpark(player, chunkX, chunkZ, topY);
    }

    private int highestCornerBase(World world, int minX, int minZ, int maxX, int maxZ) {
        return Math.max(
                Math.max(surfaceBase(world, minX, minZ), surfaceBase(world, minX, maxZ)),
                Math.max(surfaceBase(world, maxX, minZ), surfaceBase(world, maxX, maxZ))
        );
    }

    private void addCornerPillar(Player player, Set<BlockMarker> markers, int x, int z, int topY, RenderBudget budget) {
        int baseY = surfaceBase(player.getWorld(), x, z);
        int step = config.visualPillarStep();
        for (int y = baseY; y <= topY; y += step) {
            if (!sendFake(player, markers, x, y, z, budget)) {
                return;
            }
            spawnSpark(player, x + 0.5, y + 0.5, z + 0.5);
        }
        sendFake(player, markers, x, topY, z, budget);
    }

    private void addTopFrame(Player player, Set<BlockMarker> markers, int minX, int minZ, int maxX, int maxZ, int topY, RenderBudget budget) {
        int step = config.visualEdgeStep();
        for (int offset = 0; offset < 16; offset += step) {
            if (!sendFake(player, markers, minX + offset, topY, minZ, budget)) return;
            if (!sendFake(player, markers, minX + offset, topY, maxZ, budget)) return;
            if (!sendFake(player, markers, minX, topY, minZ + offset, budget)) return;
            if (!sendFake(player, markers, maxX, topY, minZ + offset, budget)) return;
        }
        sendFake(player, markers, maxX, topY, minZ, budget);
        sendFake(player, markers, maxX, topY, maxZ, budget);
        sendFake(player, markers, minX, topY, maxZ, budget);
    }

    private int surfaceBase(World world, int x, int z) {
        int minY = world.getMinHeight() + 1;
        int maxY = world.getMaxHeight() - 2;
        return Math.max(minY, Math.min(world.getHighestBlockYAt(x, z) + 1, maxY));
    }

    private void addCenterSpark(Player player, int chunkX, int chunkZ, int topY) {
        double x = (chunkX << 4) + 8.0;
        double z = (chunkZ << 4) + 8.0;
        player.spawnParticle(Particle.END_ROD, x, topY + 1.25, z, 10, 5.0, 0.6, 5.0, 0.01);
    }

    private void spawnSpark(Player player, double x, double y, double z) {
        player.spawnParticle(Particle.END_ROD, x, y, z, 1, 0.03, 0.08, 0.03, 0.0);
    }

    private boolean sendFake(Player player, Set<BlockMarker> markers, int x, int y, int z, RenderBudget budget) {
        if (!budget.tryConsume()) {
            return false;
        }
        BlockMarker marker = new BlockMarker(player.getWorld().getName(), x, y, z);
        if (markers.add(marker)) {
            player.sendBlockChange(new Location(player.getWorld(), x, y, z), visualData);
        }
        return true;
    }

    private void clear(Player player) {
        VisualSession session = active.remove(player.getUniqueId());
        if (session != null) {
            clearMarkers(player, session.markers);
        }
    }

    private void clearMarkers(Player player, Set<BlockMarker> markers) {
        for (BlockMarker marker : markers) {
            World world = Bukkit.getWorld(marker.world());
            if (world != null && world.equals(player.getWorld()) && world.isChunkLoaded(marker.x() >> 4, marker.z() >> 4)) {
                Location location = new Location(world, marker.x(), marker.y(), marker.z());
                player.sendBlockChange(location, world.getBlockAt(location).getBlockData());
            }
        }
    }

    private record BlockMarker(String world, int x, int y, int z) {
    }

    private static final class VisualSession {
        private final Set<BlockMarker> markers = ConcurrentHashMap.newKeySet();
        private String world;
        private int chunkX;
        private int chunkZ;
        private boolean initialized;

        private boolean matches(String world, int chunkX, int chunkZ) {
            return initialized && this.chunkX == chunkX && this.chunkZ == chunkZ && this.world.equals(world);
        }

        private void remember(String world, int chunkX, int chunkZ) {
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.initialized = true;
        }
    }

    private static final class RenderBudget {
        private int remaining;

        private RenderBudget(int remaining) {
            this.remaining = Math.max(0, remaining);
        }

        private boolean hasRemaining() {
            return remaining > 0;
        }

        private boolean tryConsume() {
            if (remaining <= 0) {
                return false;
            }
            remaining--;
            return true;
        }
    }
}