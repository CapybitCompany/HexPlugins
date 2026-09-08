package hexbuildbattle.arena;

import hexbuildbattle.config.ConfigService;
import hexbuildbattle.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

public final class ArenaManager {

    private static final int EXTRA_BUILD_HEIGHT = 5;

    private final ConfigService configService;
    private final Logger logger;
    private List<Arena> arenas = List.of();
    private final Map<Integer, ArenaVisualSettings> visualSettings = new HashMap<>();

    public ArenaManager(ConfigService configService, Logger logger) {
        this.configService = configService;
        this.logger = logger;
    }

    public void reload() {
        PluginConfig config = configService.config();
        World world = Bukkit.getWorld(config.map().worldName());
        if (world == null) {
            if (Bukkit.getWorlds().isEmpty()) {
                logger.severe("Cannot generate arenas: no world is loaded.");
                arenas = List.of();
                return;
            }
            world = Bukkit.getWorlds().get(0);
            logger.warning("World '" + config.map().worldName()
                    + "' from config.yml is not loaded. Using '" + world.getName() + "' for arenas.");
        }

        PluginConfig.ArenaGeometry geometry = config.map().arenaGeometry();
        List<Arena> generated = new ArrayList<>(geometry.count());
        for (int zeroBased = 0; zeroBased < geometry.count(); zeroBased++) {
            int row = zeroBased / geometry.columns();
            int column = zeroBased % geometry.columns();
            int offsetX = column * geometry.columnOffsetX();
            int offsetZ = row * geometry.rowOffsetZ();
            generated.add(createArena(world, zeroBased + 1, geometry, offsetX, offsetZ));
        }
        this.arenas = Collections.unmodifiableList(generated);
        logger.info("Generated " + arenas.size() + " Build Battle arenas.");
    }

    public Map<UUID, Arena> assignArenas(List<UUID> participants) {
        if (participants.size() > arenas.size()) {
            throw new IllegalStateException("Not enough arenas for " + participants.size() + " participants.");
        }
        Map<UUID, Arena> assignments = new LinkedHashMap<>();
        for (int i = 0; i < participants.size(); i++) {
            Arena arena = arenas.get(i);
            assignments.put(participants.get(i), arena);
            visualSettings.put(arena.index(), ArenaVisualSettings.defaults(configService.config().materials().defaultFloor()));
        }
        return assignments;
    }

    public List<Arena> allArenas() {
        return arenas;
    }

    public Optional<Arena> byIndex(int index) {
        return arenas.stream().filter(arena -> arena.index() == index).findFirst();
    }

    public Optional<Arena> findByBlock(Block block, Collection<Arena> searchArenas) {
        return searchArenas.stream()
                .filter(arena -> arena.moduleRegion().contains(block)
                        || arena.buildRegion().contains(block)
                        || arena.floorRegion().contains(block))
                .findFirst();
    }

    public Optional<Arena> findByBuildRegion(Block block, Collection<Arena> searchArenas) {
        return searchArenas.stream()
                .filter(arena -> arena.buildRegion().contains(block) || arena.floorRegion().contains(block))
                .findFirst();
    }

    public ArenaVisualSettings visualSettings(Arena arena) {
        return visualSettings.computeIfAbsent(
                arena.index(),
                ignored -> ArenaVisualSettings.defaults(configService.config().materials().defaultFloor())
        );
    }

    public void resetVisualSettings(Collection<Arena> arenas) {
        for (Arena arena : arenas) {
            visualSettings.put(arena.index(), ArenaVisualSettings.defaults(configService.config().materials().defaultFloor()));
        }
    }

    private Arena createArena(
            World world,
            int index,
            PluginConfig.ArenaGeometry geometry,
            int offsetX,
            int offsetZ
    ) {
        CuboidRegion module = shift(world, geometry.module(), offsetX, offsetZ);
        CuboidRegion build = shiftBuildRegion(world, geometry.buildRegion(), offsetX, offsetZ);
        PluginConfig.FloorSpec floor = geometry.floor();
        CuboidRegion floorRegion = new CuboidRegion(
                world,
                floor.minX() + offsetX,
                floor.y(),
                floor.minZ() + offsetZ,
                floor.maxX() + offsetX,
                floor.y(),
                floor.maxZ() + offsetZ
        );
        return new Arena(
                index,
                module,
                build,
                floorRegion,
                shift(world, geometry.ownerSpawn(), offsetX, offsetZ),
                shift(world, geometry.judgingCenter(), offsetX, offsetZ)
        );
    }

    private CuboidRegion shift(World world, PluginConfig.CuboidSpec spec, int offsetX, int offsetZ) {
        return new CuboidRegion(
                world,
                spec.minX() + offsetX,
                spec.minY(),
                spec.minZ() + offsetZ,
                spec.maxX() + offsetX,
                spec.maxY(),
                spec.maxZ() + offsetZ
        );
    }

    private CuboidRegion shiftBuildRegion(World world, PluginConfig.CuboidSpec spec, int offsetX, int offsetZ) {
        return new CuboidRegion(
                world,
                spec.minX() + offsetX,
                spec.minY(),
                spec.minZ() + offsetZ,
                spec.maxX() + offsetX,
                spec.maxY() + EXTRA_BUILD_HEIGHT,
                spec.maxZ() + offsetZ
        );
    }

    private Location shift(World world, PluginConfig.Point point, int offsetX, int offsetZ) {
        return new Location(
                world,
                point.x() + offsetX,
                point.y(),
                point.z() + offsetZ,
                point.yaw(),
                point.pitch()
        );
    }
}
