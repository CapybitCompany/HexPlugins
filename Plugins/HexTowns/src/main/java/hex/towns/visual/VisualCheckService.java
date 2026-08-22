package hex.towns.visual;

import hex.towns.config.TownsConfig;
import hex.towns.api.event.TownDestroyedEvent;
import hex.towns.model.Town;
import hex.towns.service.TownsService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
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
import org.bukkit.persistence.PersistentDataType;
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
    private volatile TownsConfig config;
    private final Map<UUID, VisualSession> active = new ConcurrentHashMap<>();
    private volatile BlockData visualData;
    private final NamespacedKey townUuidKey;
    private final NamespacedKey objectTypeKey;
    private final NamespacedKey objectIdKey;
    private volatile BukkitTask refreshTask;
    private long ticksElapsed;

    public VisualCheckService(Plugin plugin, TownsService service, TownsConfig config) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.visualData = config.visualBlock().createBlockData();
        this.townUuidKey = new NamespacedKey(plugin, "town_uuid");
        this.objectTypeKey = new NamespacedKey(plugin, "object_type");
        this.objectIdKey = new NamespacedKey(plugin, "object_id");
        this.refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, config.visualRefreshTicks(), config.visualRefreshTicks());
    }

    public void reloadConfig(TownsConfig config) {
        shutdown();
        this.config = config;
        this.visualData = config.visualBlock().createBlockData();
        this.ticksElapsed = 0L;
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
        BukkitTask task = refreshTask;
        if (task != null) {
            task.cancel();
            refreshTask = null;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
    }

    @EventHandler
    public void onTownDestroyed(TownDestroyedEvent event) {
        if (event == null || event.town() == null) return;
        UUID destroyedTown = event.town().id();
        String expected = destroyedTown.toString();
        for (VisualSession session : active.values()) {
            for (UUID displayId : Set.copyOf(session.displays)) {
                Entity entity = Bukkit.getEntity(displayId);
                if (entity == null) {
                    session.displays.remove(displayId);
                    continue;
                }
                String owner = entity.getPersistentDataContainer().get(townUuidKey, PersistentDataType.STRING);
                if (!expected.equals(owner)) continue;
                entity.remove();
                session.displays.remove(displayId);
            }
        }
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
                if (!player.getWorld().isChunkLoaded(cx, cz)) continue;
                java.util.Optional<Town> town = service.townAt(worldName, cx, cz);
                if (town.isEmpty()) continue;
                UUID townId = town.get().id();
                boolean north = !sameTown(worldName, cx, cz - 1, townId);
                boolean south = !sameTown(worldName, cx, cz + 1, townId);
                boolean west = !sameTown(worldName, cx - 1, cz, townId);
                boolean east = !sameTown(worldName, cx + 1, cz, townId);
                if (north || south || west || east) {
                    addChunkDisplayFrame(player, session.displays, townId, cx, cz, north, south, west, east, budget);
                }
            }
        }
    }

    private boolean sameTown(String world, int chunkX, int chunkZ, UUID townId) {
        return service.townAt(world, chunkX, chunkZ).map(town -> town.id().equals(townId)).orElse(false);
    }

    private void addChunkDisplayFrame(Player player, Set<UUID> displays, UUID townId,
                                      int chunkX, int chunkZ,
                                      boolean north, boolean south, boolean west, boolean east,
                                      RenderBudget budget) {
        World world = player.getWorld();
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int maxX = minX + 16;
        int maxZ = minZ + 16;
        int highestBase = highestCornerBase(world, minX, minZ, maxX - 1, maxZ - 1);
        int topY = Math.min(world.getMaxHeight() - 2, highestBase + config.visualPillarHeight());
        int bottomY = visualBottomY(world);

        // Render only the outer edge of the union of claims. Internal borders between
        // chunks of the same town are intentionally omitted.
        if (north || west) addCornerDisplay(player, displays, townId, minX, minZ, topY, bottomY, budget);
        if (south || west) addCornerDisplay(player, displays, townId, minX, maxZ, topY, bottomY, budget);
        if (north || east) addCornerDisplay(player, displays, townId, maxX, minZ, topY, bottomY, budget);
        if (south || east) addCornerDisplay(player, displays, townId, maxX, maxZ, topY, bottomY, budget);
        if (config.visualVerticalEdgeWalls()) {
            addVerticalEdgeDisplays(player, displays, townId, minX, minZ, maxX, maxZ, topY, bottomY,
                    north, south, west, east, budget);
        }
        if (config.visualShowTopFrame()) {
            addHorizontalFrameDisplays(player, displays, townId, minX, minZ, maxX, maxZ, topY + 0.5,
                    north, south, west, east, budget);
        }
        if (config.visualExtendToWorldMin()) {
            addHorizontalFrameDisplays(player, displays, townId, minX, minZ, maxX, maxZ, bottomY + 0.5,
                    north, south, west, east, budget);
        }
        addCenterSpark(player, chunkX, chunkZ, topY);
    }

    private int highestCornerBase(World world, int minX, int minZ, int maxX, int maxZ) {
        return Math.max(
                Math.max(surfaceBase(world, minX, minZ), surfaceBase(world, minX, maxZ)),
                Math.max(surfaceBase(world, maxX, minZ), surfaceBase(world, maxX, maxZ))
        );
    }

    private void addCornerDisplay(Player player, Set<UUID> displays, UUID townId, int x, int z, int topY, int bottomY, RenderBudget budget) {
        int baseY = config.visualExtendToWorldMin() ? bottomY : surfaceBase(player.getWorld(), x, z);
        float width = config.visualDisplayWidth();
        float height = Math.max(1.0f, topY - baseY + config.visualEdgeThickness());
        Location location = new Location(player.getWorld(), x + 0.5 - width / 2.0, baseY, z + 0.5 - width / 2.0);
        spawnDisplay(player, displays, townId, location, new Vector3f(width, height, width), budget);
        spawnSpark(player, x + 0.5, topY + 0.5, z + 0.5);
    }

    private void addVerticalEdgeDisplays(Player player, Set<UUID> displays, UUID townId,
                                         int minX, int minZ, int maxX, int maxZ, int topY, int bottomY,
                                         boolean north, boolean south, boolean west, boolean east,
                                         RenderBudget budget) {
        int baseY = config.visualExtendToWorldMin() ? bottomY : highestCornerBase(player.getWorld(), minX, minZ, maxX - 1, maxZ - 1);
        float width = config.visualDisplayWidth();
        float height = Math.max(1.0f, topY - baseY + config.visualEdgeThickness());
        int step = Math.max(1, config.visualEdgeStep());

        if (north) {
            for (int x = minX; x <= maxX; x += step) addThinEdgePost(player, displays, townId, x, minZ, baseY, height, width, budget);
            if ((maxX - minX) % step != 0) addThinEdgePost(player, displays, townId, maxX, minZ, baseY, height, width, budget);
        }
        if (south) {
            for (int x = minX; x <= maxX; x += step) addThinEdgePost(player, displays, townId, x, maxZ, baseY, height, width, budget);
            if ((maxX - minX) % step != 0) addThinEdgePost(player, displays, townId, maxX, maxZ, baseY, height, width, budget);
        }
        if (west) {
            for (int z = minZ; z <= maxZ; z += step) addThinEdgePost(player, displays, townId, minX, z, baseY, height, width, budget);
            if ((maxZ - minZ) % step != 0) addThinEdgePost(player, displays, townId, minX, maxZ, baseY, height, width, budget);
        }
        if (east) {
            for (int z = minZ; z <= maxZ; z += step) addThinEdgePost(player, displays, townId, maxX, z, baseY, height, width, budget);
            if ((maxZ - minZ) % step != 0) addThinEdgePost(player, displays, townId, maxX, maxZ, baseY, height, width, budget);
        }
    }

    private void addThinEdgePost(Player player, Set<UUID> displays, UUID townId, int x, int z, int baseY, float height, float width, RenderBudget budget) {
        if (!budget.hasRemaining()) return;
        float remaining = height;
        float y = baseY;
        final float segmentHeight = 24.0f;
        while (remaining > 0.01f && budget.hasRemaining()) {
            float part = Math.min(segmentHeight, remaining);
            Location location = new Location(player.getWorld(), x + 0.5 - width / 2.0, y, z + 0.5 - width / 2.0);
            spawnDisplay(player, displays, townId, location, new Vector3f(width, part, width), budget);
            y += part;
            remaining -= part;
        }
    }

    private void addHorizontalFrameDisplays(Player player, Set<UUID> displays, UUID townId,
                                            int minX, int minZ, int maxX, int maxZ, double centerY,
                                            boolean north, boolean south, boolean west, boolean east,
                                            RenderBudget budget) {
        float width = config.visualDisplayWidth();
        float thickness = config.visualEdgeThickness();
        float length = 16.0f;
        double y = centerY - thickness / 2.0;

        if (north) spawnDisplay(player, displays, townId, new Location(player.getWorld(), minX + 0.5, y, minZ + 0.5 - width / 2.0), new Vector3f(length, thickness, width), budget);
        if (south) spawnDisplay(player, displays, townId, new Location(player.getWorld(), minX + 0.5, y, maxZ + 0.5 - width / 2.0), new Vector3f(length, thickness, width), budget);
        if (west) spawnDisplay(player, displays, townId, new Location(player.getWorld(), minX + 0.5 - width / 2.0, y, minZ + 0.5), new Vector3f(width, thickness, length), budget);
        if (east) spawnDisplay(player, displays, townId, new Location(player.getWorld(), maxX + 0.5 - width / 2.0, y, minZ + 0.5), new Vector3f(width, thickness, length), budget);
    }

    private boolean spawnDisplay(Player viewer, Set<UUID> displays, UUID townId, Location location, Vector3f scale, RenderBudget budget) {
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
        display.getPersistentDataContainer().set(townUuidKey, PersistentDataType.STRING, townId.toString());
        display.getPersistentDataContainer().set(objectTypeKey, PersistentDataType.STRING, "border_visual");
        display.getPersistentDataContainer().set(objectIdKey, PersistentDataType.STRING, display.getUniqueId().toString());

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
