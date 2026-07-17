package hex.towns.heart;

import hex.towns.database.TownRepository;
import hex.towns.model.Town;
import hex.towns.service.TownsService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TownHeartService {
    private static final String NS = "heart";
    private static final String X = "x";
    private static final String Y = "y";
    private static final String Z = "z";
    private static final String WORLD = "world";
    private static final String ACTIVE = "active";

    private final Plugin plugin;
    private final TownRepository repository;
    private final TownsService townsService;
    private final TownHeartRenderer renderer;
    private final Map<UUID, TownHeartLocation> hearts = new ConcurrentHashMap<>();

    public TownHeartService(Plugin plugin, TownRepository repository, TownsService townsService, TownHeartRenderer renderer) {
        this.plugin = plugin;
        this.repository = repository;
        this.townsService = townsService;
        this.renderer = renderer;
    }

    public void loadAndRenderExistingHearts() {
        hearts.clear();
        townsService.forEachTown(town -> loadHeart(town).ifPresent(heart -> {
            hearts.put(town.id(), heart);
            removePhysicalHeartBlock(heart);
            ensureBedrockFoundation(heart);
            renderer.render(town, heart);
        }), 100);
    }

    public Optional<TownHeartLocation> loadHeart(Town town) {
        String active = repository.getMeta(town.internalId(), NS, ACTIVE, "false");
        if (!Boolean.parseBoolean(active)) {
            return Optional.empty();
        }
        String world = repository.getMeta(town.internalId(), NS, WORLD, town.world());
        int x = parseInt(repository.getMeta(town.internalId(), NS, X, String.valueOf((town.heart().x() << 4) + 8)), (town.heart().x() << 4) + 8);
        int y = parseInt(repository.getMeta(town.internalId(), NS, Y, "64"), 64);
        int z = parseInt(repository.getMeta(town.internalId(), NS, Z, String.valueOf((town.heart().z() << 4) + 8)), (town.heart().z() << 4) + 8);
        return Optional.of(new TownHeartLocation(town.id(), world, x, y, z, x >> 4, z >> 4));
    }

    public Optional<TownHeartLocation> heartOf(UUID townId) {
        TownHeartLocation cached = hearts.get(townId);
        if (cached != null) return Optional.of(cached);
        return townsService.findTown(townId).flatMap(this::loadHeart).map(heart -> {
            hearts.put(townId, heart);
            return heart;
        });
    }

    public Optional<TownHeartLocation> heartAt(Location location) {
        return hearts.values().stream().filter(heart -> heart.sameBlock(location)).findFirst();
    }

    public Optional<TownHeartLocation> protectedHeartAt(Location location) {
        return hearts.values().stream().filter(heart -> heart.inProtectedBuildZone(location)).findFirst();
    }

    public Optional<TownHeartLocation> heartChunkAt(Location location) {
        return hearts.values().stream().filter(heart -> heart.inHeartChunk(location)).findFirst();
    }

    public TownHeartLocation installHeart(Town town, Location placementHint) {
        World world = placementHint.getWorld();
        if (world == null) throw new IllegalArgumentException("World is missing for town heart placement.");
        int chunkX = town.heart().x();
        int chunkZ = town.heart().z();
        int x = (chunkX << 4) + 8;
        int z = (chunkZ << 4) + 8;
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        chunk.load(true);
        int y = Math.max(world.getMinHeight() + 2, Math.min(world.getMaxHeight() - 3, placementHint.getBlockY() + 1));
        TownHeartLocation heart = new TownHeartLocation(town.id(), world.getName(), x, y, z, chunkX, chunkZ);
        clearHeartChunk(heart);
        ensureBedrockFoundation(heart);
        // Nie stawiamy już widocznego bloku czerwonego betonu w środku serca.
        // Interakcję obsługuje niewidzialna encja Interaction renderowana razem z modelem,
        // a ochrona/damage w przyszłości mogą bazować na zapisanej lokalizacji serca.
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.AIR, false);
        repository.setMeta(town.internalId(), NS, ACTIVE, "true");
        repository.setMeta(town.internalId(), NS, WORLD, world.getName());
        repository.setMeta(town.internalId(), NS, X, String.valueOf(x));
        repository.setMeta(town.internalId(), NS, Y, String.valueOf(y));
        repository.setMeta(town.internalId(), NS, Z, String.valueOf(z));
        hearts.put(town.id(), heart);
        renderer.render(town, heart);
        return heart;
    }

    public void updateName(Town town) {
        heartOf(town.id()).ifPresent(heart -> {
            removePhysicalHeartBlock(heart);
            ensureBedrockFoundation(heart);
            renderer.render(town, heart);
        });
    }

    public void removeVisuals(UUID townId) {
        hearts.remove(townId);
        renderer.remove(townId);
    }

    public void removeHeartCompletely(Town town) {
        if (town == null) {
            return;
        }
        TownHeartLocation cached = hearts.get(town.id());
        Optional<TownHeartLocation> heart = cached == null ? loadHeart(town) : Optional.of(cached);
        renderer.remove(town.id());
        hearts.remove(town.id());
        heart.ifPresent(location -> {
            removePhysicalHeartBlock(location);
            removeBedrockFoundation(location);
        });
    }

    private void ensureBedrockFoundation(TownHeartLocation heart) {
        World world = Bukkit.getWorld(heart.world());
        if (world == null) return;
        int foundationY = Math.max(world.getMinHeight(), heart.y() - 2);
        int minX = heart.x() - 4;
        int maxX = heart.x() + 4;
        int minZ = heart.z() - 4;
        int maxZ = heart.z() + 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                world.getBlockAt(x, foundationY, z).setType(Material.BEDROCK, false);
            }
        }
    }

    private void removeBedrockFoundation(TownHeartLocation heart) {
        World world = Bukkit.getWorld(heart.world());
        if (world == null) return;
        int foundationY = Math.max(world.getMinHeight(), heart.y() - 2);
        int minX = heart.x() - 4;
        int maxX = heart.x() + 4;
        int minZ = heart.z() - 4;
        int maxZ = heart.z() + 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Block block = world.getBlockAt(x, foundationY, z);
                if (block.getType() == Material.BEDROCK) {
                    block.setType(Material.AIR, false);
                }
            }
        }
    }

    private void removePhysicalHeartBlock(TownHeartLocation heart) {
        World world = Bukkit.getWorld(heart.world());
        if (world == null) return;
        Block block = world.getBlockAt(heart.x(), heart.y(), heart.z());
        if (block.getType() != Material.AIR) {
            block.setType(Material.AIR, false);
        }
    }

    private void clearHeartChunk(TownHeartLocation heart) {
        World world = Bukkit.getWorld(heart.world());
        if (world == null) return;
        int minX = heart.chunkX() << 4;
        int minZ = heart.chunkZ() << 4;
        int fromY = Math.max(world.getMinHeight(), heart.y() - 1);
        int toY = world.getMaxHeight() - 1;
        for (int x = minX; x < minX + 16; x++) {
            for (int z = minZ; z < minZ + 16; z++) {
                for (int y = fromY; y <= toY; y++) {
                    if (x == heart.x() && y == heart.y() && z == heart.z()) continue;
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
