package hex.towns.visual;

import hex.towns.config.TownsConfig;
import hex.towns.service.TownsService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VisualCheckService implements Listener {
    private static final AxisAngle4f NO_ROTATION = new AxisAngle4f(0.0f, 0.0f, 1.0f, 0.0f);

    private final Plugin plugin;
    private final TownsService service;
    private final TownsConfig config;
    private final Map<UUID, VisualSession> active = new ConcurrentHashMap<>();
    private final BlockData visualData;
    private final BukkitTask refreshTask;
    private long ticksElapsed;

    public VisualCheckService(Plugin plugin, TownsService service, TownsConfig config) {
        this.plugin = plugin;
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
            } else {
                VisualSession session = active.remove(id);
                if (session != null) {
                    removeDisplays(session.displays);
                }
            }
        }
        refreshTask.cancel();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // BlockDisplay encje sa realnymi encjami w swiecie. Ukrywamy aktualne wizualizacje
        // przed dolaczajacym graczem, bo /town check ma byc widoczne tylko dla wlasciciela sesji.
        Player joining = event.getPlayer();
        for (Map.Entry<UUID, VisualSession> entry : active.entrySet()) {
            if (entry.getKey().equals(joining.getUniqueId())) {
                continue;
            }
            for (UUID displayId : entry.getValue().displays) {
                Entity entity = Bukkit.getEntity(displayId);
                if (entity != null) {
                    joining.hideEntity(plugin, entity);
                }
            }
        }
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

        removeDisplays(session.displays);
        session.displays.clear();
        session.remember(worldName, centerX, centerZ);

        int radius = config.visualRadiusChunks();

        for (int cx = centerX - radius; cx <= centerX + radius; cx++) {
            for (int cz = centerZ - radius; cz <= centerZ + radius; cz++) {
                if (!budget.hasRemaining()) {
                    return;
                }
                if (player.getWorld().isChunkLoaded(cx, cz) && service.townAt(worldName, cx, cz).isPresent()) {
                    addChunkDisplayFrame(player, session.displays, cx, cz, budget);
                }
            }
        }
    }

    private void addChunkDisplayFrame(Player player, Set<UUID> displays, int chunkX, int chunkZ, RenderBudget budget) {
        World world = player.getWorld();
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int maxX = minX + 16;
        int maxZ = minZ + 16;
        int highestBase = highestCornerBase(world, minX, minZ, maxX - 1, maxZ - 1);
        int topY = Math.min(world.getMaxHeight() - 2, highestBase + config.visualPillarHeight());
        int bottomY = visualBottomY(world);

        addCornerDisplay(player, displays, minX, minZ, topY, bottomY, budget);
        addCornerDisplay(player, displays, minX, maxZ, topY, bottomY, budget);
        addCornerDisplay(player, displays, maxX, minZ, topY, bottomY, budget);
        addCornerDisplay(player, displays, maxX, maxZ, topY, bottomY, budget);
        if (config.visualVerticalEdgeWalls()) {
            addVerticalEdgeDisplays(player, displays, minX, minZ, maxX, maxZ, topY, bottomY, budget);
        }
        addTopFrameDisplays(player, displays, minX, minZ, maxX, maxZ, topY, budget);
        addCenterSpark(player, chunkX, chunkZ, topY);
    }

    private int highestCornerBase(World world, int minX, int minZ, int maxX, int maxZ) {
        return Math.max(
                Math.max(surfaceBase(world, minX, minZ), surfaceBase(world, minX, maxZ)),
                Math.max(surfaceBase(world, maxX, minZ), surfaceBase(world, maxX, maxZ))
        );
    }

    private void addCornerDisplay(Player player, Set<UUID> displays, int x, int z, int topY, int bottomY, RenderBudget budget) {
        int baseY = config.visualExtendToWorldMin() ? bottomY : surfaceBase(player.getWorld(), x, z);
        float width = config.visualDisplayWidth();
        float height = Math.max(1.0f, topY - baseY + config.visualEdgeThickness());
        Location location = new Location(player.getWorld(), x + 0.5 - width / 2.0, baseY, z + 0.5 - width / 2.0);
        spawnDisplay(player, displays, location, new Vector3f(width, height, width), budget);
        spawnSpark(player, x + 0.5, topY + 0.5, z + 0.5);
    }

    private void addVerticalEdgeDisplays(Player player, Set<UUID> displays, int minX, int minZ, int maxX, int maxZ, int topY, int bottomY, RenderBudget budget) {
        int baseY = config.visualExtendToWorldMin() ? bottomY : highestCornerBase(player.getWorld(), minX, minZ, maxX - 1, maxZ - 1);
        float width = config.visualDisplayWidth();
        float thickness = config.visualEdgeThickness();
        float height = Math.max(1.0f, topY - baseY + thickness);

        // Wczesniej byly to cztery dlugie BlockDisplay o skali 16xH, czyli w praktyce pelne sciany.
        // /town check ma pokazywac granice jako waskie slupki 0.1x0.1 rozmieszczone na krawedziach chunka.
        for (int x = minX + 1; x < maxX; x++) {
            addThinEdgePost(player, displays, x, minZ, baseY, height, width, budget);
            addThinEdgePost(player, displays, x, maxZ, baseY, height, width, budget);
        }
        for (int z = minZ + 1; z < maxZ; z++) {
            addThinEdgePost(player, displays, minX, z, baseY, height, width, budget);
            addThinEdgePost(player, displays, maxX, z, baseY, height, width, budget);
        }
    }

    private void addThinEdgePost(Player player, Set<UUID> displays, int x, int z, int baseY, float height, float width, RenderBudget budget) {
        if (!budget.hasRemaining()) {
            return;
        }
        Location location = new Location(player.getWorld(), x + 0.5 - width / 2.0, baseY, z + 0.5 - width / 2.0);
        spawnDisplay(player, displays, location, new Vector3f(width, height, width), budget);
    }

    private void addTopFrameDisplays(Player player, Set<UUID> displays, int minX, int minZ, int maxX, int maxZ, int topY, RenderBudget budget) {
        float width = config.visualDisplayWidth();
        float thickness = config.visualEdgeThickness();
        float length = 16.0f;
        double y = topY + 0.5 - thickness / 2.0;

        spawnDisplay(player, displays, new Location(player.getWorld(), minX + 0.5, y, minZ + 0.5 - width / 2.0), new Vector3f(length, thickness, width), budget);
        spawnDisplay(player, displays, new Location(player.getWorld(), minX + 0.5, y, maxZ + 0.5 - width / 2.0), new Vector3f(length, thickness, width), budget);
        spawnDisplay(player, displays, new Location(player.getWorld(), minX + 0.5 - width / 2.0, y, minZ + 0.5), new Vector3f(width, thickness, length), budget);
        spawnDisplay(player, displays, new Location(player.getWorld(), maxX + 0.5 - width / 2.0, y, minZ + 0.5), new Vector3f(width, thickness, length), budget);
    }

    private boolean spawnDisplay(Player viewer, Set<UUID> displays, Location location, Vector3f scale, RenderBudget budget) {
        if (!budget.tryConsume() || location.getWorld() == null || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return false;
        }

        BlockDisplay display = location.getWorld().spawn(location, BlockDisplay.class, entity -> {
            entity.setBlock(visualData);
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setGravity(false);
            entity.setSilent(true);
            entity.setViewRange(config.visualDisplayViewRange());
            entity.setTransformation(new Transformation(
                    new Vector3f(0.0f, 0.0f, 0.0f),
                    NO_ROTATION,
                    scale,
                    NO_ROTATION
            ));
        });

        displays.add(display.getUniqueId());
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getUniqueId().equals(viewer.getUniqueId())) {
                other.showEntity(plugin, display);
            } else {
                other.hideEntity(plugin, display);
            }
        }
        return true;
    }

    private int surfaceBase(World world, int x, int z) {
        int minY = world.getMinHeight() + 1;
        int maxY = world.getMaxHeight() - 2;
        return Math.max(minY, Math.min(world.getHighestBlockYAt(x, z) + 1, maxY));
    }

    private int visualBottomY(World world) {
        return world.getMinHeight();
    }

    private void addCenterSpark(Player player, int chunkX, int chunkZ, int topY) {
        double x = (chunkX << 4) + 8.0;
        double z = (chunkZ << 4) + 8.0;
        player.spawnParticle(Particle.END_ROD, x, topY + 1.25, z, 10, 5.0, 0.6, 5.0, 0.01);
    }

    private void spawnSpark(Player player, double x, double y, double z) {
        player.spawnParticle(Particle.END_ROD, x, y, z, 1, 0.03, 0.08, 0.03, 0.0);
    }

    private void clear(Player player) {
        VisualSession session = active.remove(player.getUniqueId());
        if (session != null) {
            removeDisplays(session.displays);
        }
    }

    private void removeDisplays(Set<UUID> displays) {
        for (UUID displayId : displays) {
            Entity entity = Bukkit.getEntity(displayId);
            if (entity != null) {
                entity.remove();
            }
        }
    }

    private static final class VisualSession {
        private final Set<UUID> displays = ConcurrentHashMap.newKeySet();
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
