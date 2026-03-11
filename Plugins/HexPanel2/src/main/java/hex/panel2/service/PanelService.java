package hex.panel2.service;

import hex.panel2.model.Panel;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

public final class PanelService {

    private final Plugin plugin;
    private final List<Panel> panels = new ArrayList<>();
    private final Map<UUID, Panel> ownerPanels = new HashMap<>();

    public PanelService(Plugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void scanPanels() {
        panels.clear();
        ownerPanels.clear();

        World world = resolveWorld();
        if (world == null) {
            plugin.getLogger().severe("No world found for panel scan.");
            return;
        }

        List<Location> markers = collectRedConcreteMarkers(world);
        if (markers.isEmpty()) {
            plugin.getLogger().warning("No RED_CONCRETE markers found in loaded chunks.");
            return;
        }

        Set<String> markerSet = new HashSet<>();
        for (Location marker : markers) {
            markerSet.add(markerKey(marker.getBlockX(), marker.getBlockY(), marker.getBlockZ()));
        }

        Set<String> addedPanels = new HashSet<>();
        for (int i = 0; i < markers.size(); i++) {
            Location first = markers.get(i);
            for (int j = i + 1; j < markers.size(); j++) {
                Location second = markers.get(j);

                if (first.getBlockY() != second.getBlockY()) {
                    continue;
                }
                if (first.getBlockX() == second.getBlockX() || first.getBlockZ() == second.getBlockZ()) {
                    continue;
                }

                int y = first.getBlockY();
                int minX = Math.min(first.getBlockX(), second.getBlockX());
                int maxX = Math.max(first.getBlockX(), second.getBlockX());
                int minZ = Math.min(first.getBlockZ(), second.getBlockZ());
                int maxZ = Math.max(first.getBlockZ(), second.getBlockZ());

                if (!markerSet.contains(markerKey(minX, y, maxZ))) {
                    continue;
                }
                if (!markerSet.contains(markerKey(maxX, y, minZ))) {
                    continue;
                }
                if (hasIntermediateMarkers(markers, y, minX, maxX, minZ, maxZ)) {
                    continue;
                }

                String panelKey = panelKey(minX, y, minZ, maxX, y, maxZ);
                if (addedPanels.add(panelKey)) {
                    panels.add(new Panel(
                            null,
                            new Location(world, minX, y, minZ),
                            new Location(world, maxX, y, maxZ)
                    ));
                }
            }
        }

        panels.sort(Comparator
                .comparingInt((Panel panel) -> panel.getMin().getBlockX())
                .thenComparingInt(panel -> panel.getMin().getBlockY())
                .thenComparingInt(panel -> panel.getMin().getBlockZ()));

        plugin.getLogger().info("Panel scan complete. Panels found: " + panels.size());
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
        Panel panel = findPanelContainingInternal(location);
        return panel != null && panel.isOwner(owner);
    }

    public synchronized int getPanelCount() {
        return panels.size();
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

    private boolean hasIntermediateMarkers(List<Location> markers, int y, int minX, int maxX, int minZ, int maxZ) {
        for (Location marker : markers) {
            if (marker.getBlockY() != y) {
                continue;
            }

            int x = marker.getBlockX();
            int z = marker.getBlockZ();
            if (x < minX || x > maxX || z < minZ || z > maxZ) {
                continue;
            }

            boolean corner = (x == minX || x == maxX) && (z == minZ || z == maxZ);
            if (!corner) {
                return true;
            }
        }
        return false;
    }

    private static String markerKey(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    private static String panelKey(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return minX + ":" + minY + ":" + minZ + "|" + maxX + ":" + maxY + ":" + maxZ;
    }
}
