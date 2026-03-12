package hex.panel2.service;

import hex.panel2.model.Panel;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class PanelService {

    private final Plugin plugin;
    private final List<Panel> panels = new ArrayList<>();
    private final Map<UUID, Panel> ownerPanels = new HashMap<>();

    public PanelService(Plugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void scanPanels() {
        World world = resolveWorld();
        if (world == null) {
            plugin.getLogger().severe("No world found for panel scan.");
            return;
        }

        List<Location> markers = collectRedConcreteMarkers(world);
        if (markers.isEmpty()) {
            plugin.getLogger().warning("No RED_CONCRETE markers found in loaded chunks for world " + world.getName() + ".");
            return;
        }

        Map<String, UUID> previousOwners = snapshotOwners();
        List<Panel> detectedPanels = detectPanels(world, markers, previousOwners);
        detectedPanels.sort(Comparator
                .comparingInt((Panel panel) -> panel.getMin().getBlockX())
                .thenComparingInt(panel -> panel.getMin().getBlockY())
                .thenComparingInt(panel -> panel.getMin().getBlockZ()));

        Map<UUID, Panel> detectedOwnerPanels = new HashMap<>();
        for (Panel panel : detectedPanels) {
            UUID owner = panel.getOwner();
            if (owner != null) {
                detectedOwnerPanels.putIfAbsent(owner, panel);
            }
        }

        panels.clear();
        panels.addAll(detectedPanels);
        ownerPanels.clear();
        ownerPanels.putAll(detectedOwnerPanels);

        plugin.getLogger().info("Panel scan complete. Markers=" + markers.size() + ", panels=" + panels.size() + " in world " + world.getName());
    }

    public synchronized Optional<Panel> getOwnedPanel(UUID owner) {
        return Optional.ofNullable(ownerPanels.get(owner));
    }

    public synchronized void unassignPanel(UUID owner) {
        Panel panel = ownerPanels.remove(owner);
        if (panel != null && panel.isOwner(owner)) {
            panel.setOwner(null);
        }
    }

    public synchronized Optional<Panel> assignFreePanel(UUID owner) {
        Panel existing = ownerPanels.get(owner);
        if (existing != null) {
            return Optional.of(existing);
        }

        for (Panel panel : panels) {
            if (!panel.isOwned()) {
                panel.setOwner(owner);
                ownerPanels.put(owner, panel);
                return Optional.of(panel);
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<Panel> assignRandomFreePanel(UUID owner) {
        Panel existing = ownerPanels.get(owner);
        if (existing != null) {
            return Optional.of(existing);
        }

        List<Panel> freePanels = new ArrayList<>();
        for (Panel panel : panels) {
            if (!panel.isOwned()) {
                freePanels.add(panel);
            }
        }
        if (freePanels.isEmpty()) {
            return Optional.empty();
        }

        Panel selected = freePanels.get(ThreadLocalRandom.current().nextInt(freePanels.size()));
        selected.setOwner(owner);
        ownerPanels.put(owner, selected);
        return Optional.of(selected);
    }

    public synchronized Optional<Panel> findPanelContaining(Location location) {
        return Optional.ofNullable(findPanelContainingInternal(location));
    }

    public synchronized boolean canBuild(UUID owner, Location location) {
        for (Panel panel : panels) {
            if (panel.contains(location) && panel.isOwner(owner)) {
                return true;
            }
        }
        return false;
    }

    public synchronized int getPanelCount() {
        return panels.size();
    }

    public synchronized int getFreePanelCount() {
        int free = 0;
        for (Panel panel : panels) {
            if (!panel.isOwned()) {
                free++;
            }
        }
        return free;
    }

    public synchronized int unassignPlayersNotIn(Set<UUID> activePlayers) {
        List<UUID> owners = new ArrayList<>(ownerPanels.keySet());
        int released = 0;
        for (UUID owner : owners) {
            if (!activePlayers.contains(owner)) {
                unassignPanel(owner);
                released++;
            }
        }
        return released;
    }

    private List<Panel> detectPanels(World world, List<Location> markers, Map<String, UUID> previousOwners) {
        Map<Integer, Set<Pos>> byY = new HashMap<>();
        for (Location marker : markers) {
            byY.computeIfAbsent(marker.getBlockY(), ignored -> new HashSet<>())
                    .add(new Pos(marker.getBlockX(), marker.getBlockZ()));
        }

        List<Panel> detectedPanels = new ArrayList<>();
        for (Map.Entry<Integer, Set<Pos>> entry : byY.entrySet()) {
            int y = entry.getKey();
            Set<Pos> allMarkersOnY = entry.getValue();
            Set<Pos> remaining = new HashSet<>(allMarkersOnY);

            while (!remaining.isEmpty()) {
                Pos seed = remaining.iterator().next();
                Set<Pos> component = collectComponent(seed, allMarkersOnY, remaining);

                Panel panel = tryCreatePanel(world, y, component, previousOwners);
                if (panel != null) {
                    detectedPanels.add(panel);
                }
            }
        }
        return detectedPanels;
    }

    private Set<Pos> collectComponent(Pos seed, Set<Pos> allMarkers, Set<Pos> remaining) {
        Set<Pos> component = new HashSet<>();
        ArrayDeque<Pos> queue = new ArrayDeque<>();

        queue.add(seed);
        remaining.remove(seed);

        while (!queue.isEmpty()) {
            Pos current = queue.poll();
            component.add(current);

            for (Pos neighbor : neighbors(current)) {
                if (!allMarkers.contains(neighbor) || !remaining.remove(neighbor)) {
                    continue;
                }
                queue.add(neighbor);
            }
        }

        return component;
    }

    private Panel tryCreatePanel(World world, int y, Set<Pos> component, Map<String, UUID> previousOwners) {
        if (component.size() < 8) {
            return null;
        }

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Pos pos : component) {
            minX = Math.min(minX, pos.x());
            maxX = Math.max(maxX, pos.x());
            minZ = Math.min(minZ, pos.z());
            maxZ = Math.max(maxZ, pos.z());
        }

        int width = maxX - minX;
        int depth = maxZ - minZ;
        if (width < 2 || depth < 2) {
            return null;
        }

        if (width != depth) {
            return null;
        }

        if (!hasFullPerimeter(component, minX, maxX, minZ, maxZ)) {
            return null;
        }

        if (!containsOnlyPerimeterBlocks(component, minX, maxX, minZ, maxZ)) {
            return null;
        }

        if (!hasCleanLoopDegree(component)) {
            return null;
        }

        String key = panelKey(world.getName(), minX, y, minZ, maxX, y, maxZ);
        UUID owner = previousOwners.get(key);

        return new Panel(
                owner,
                new Location(world, minX, y, minZ),
                new Location(world, maxX, y, maxZ)
        );
    }

    private boolean hasFullPerimeter(Set<Pos> component, int minX, int maxX, int minZ, int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            if (!component.contains(new Pos(x, minZ))) {
                return false;
            }
            if (!component.contains(new Pos(x, maxZ))) {
                return false;
            }
        }
        for (int z = minZ; z <= maxZ; z++) {
            if (!component.contains(new Pos(minX, z))) {
                return false;
            }
            if (!component.contains(new Pos(maxX, z))) {
                return false;
            }
        }
        return true;
    }

    private boolean containsOnlyPerimeterBlocks(Set<Pos> component, int minX, int maxX, int minZ, int maxZ) {
        for (Pos pos : component) {
            boolean onPerimeter = pos.x() == minX || pos.x() == maxX || pos.z() == minZ || pos.z() == maxZ;
            if (!onPerimeter) {
                return false;
            }
        }
        return true;
    }

    private boolean hasCleanLoopDegree(Set<Pos> component) {
        for (Pos pos : component) {
            int neighbors = 0;
            for (Pos neighbor : neighbors(pos)) {
                if (component.contains(neighbor)) {
                    neighbors++;
                }
            }
            if (neighbors != 2) {
                return false;
            }
        }
        return true;
    }

    private List<Pos> neighbors(Pos pos) {
        List<Pos> result = new ArrayList<>(4);
        result.add(new Pos(pos.x() + 1, pos.z()));
        result.add(new Pos(pos.x() - 1, pos.z()));
        result.add(new Pos(pos.x(), pos.z() + 1));
        result.add(new Pos(pos.x(), pos.z() - 1));
        return result;
    }

    private Panel findPanelContainingInternal(Location location) {
        for (Panel panel : panels) {
            if (panel.contains(location)) {
                return panel;
            }
        }
        return null;
    }

    private World resolveWorld() {
        if (Bukkit.getWorlds().isEmpty()) {
            return null;
        }
        return Bukkit.getWorlds().get(0);
    }

    private List<Location> collectRedConcreteMarkers(World world) {
        List<Location> markers = new ArrayList<>();
        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();

        for (Chunk chunk : world.getLoadedChunks()) {
            int baseX = chunk.getX() << 4;
            int baseZ = chunk.getZ() << 4;

            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    int worldX = baseX + localX;
                    int worldZ = baseZ + localZ;

                    for (int y = minHeight; y < maxHeight; y++) {
                        if (world.getBlockAt(worldX, y, worldZ).getType() == Material.RED_CONCRETE) {
                            markers.add(new Location(world, worldX, y, worldZ));
                        }
                    }
                }
            }
        }
        return markers;
    }

    private Map<String, UUID> snapshotOwners() {
        Map<String, UUID> snapshot = new HashMap<>();
        for (Panel panel : panels) {
            UUID owner = panel.getOwner();
            if (owner == null) {
                continue;
            }
            Location min = panel.getMin();
            Location max = panel.getMax();
            World world = min.getWorld();
            if (world == null) {
                continue;
            }
            String key = panelKey(
                    world.getName(),
                    min.getBlockX(), min.getBlockY(), min.getBlockZ(),
                    max.getBlockX(), max.getBlockY(), max.getBlockZ()
            );
            snapshot.put(key, owner);
        }
        return snapshot;
    }

    private static String panelKey(String worldName, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return worldName + "|" + minX + ":" + minY + ":" + minZ + "|" + maxX + ":" + maxY + ":" + maxZ;
    }

    private record Pos(int x, int z) {
    }
}
