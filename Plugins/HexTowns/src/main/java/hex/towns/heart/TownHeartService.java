package hex.towns.heart;

import hex.core.api.HexApi;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class TownHeartService {
    private static final String NS = "heart";
    private static final String X = "x";
    private static final String Y = "y";
    private static final String Z = "z";
    private static final String WORLD = "world";
    private static final String ACTIVE = "active";

    private final Plugin plugin;
    private final HexApi api;
    private final TownRepository repository;
    private final TownsService townsService;
    private final TownHeartRenderer renderer;
    private final Map<UUID, TownHeartLocation> hearts = new ConcurrentHashMap<>();

    public TownHeartService(Plugin plugin, HexApi api, TownRepository repository, TownsService townsService, TownHeartRenderer renderer) {
        this.plugin = plugin;
        this.api = api;
        this.repository = repository;
        this.townsService = townsService;
        this.renderer = renderer;
    }

    public void loadAndRenderExistingHearts() {
        hearts.clear();
        townsService.forEachTown(town -> loadHeart(town).ifPresent(heart -> {
            hearts.put(town.id(), heart);
            removePhysicalHeartBlock(heart);
            registerLegacyFoundationIfNeeded(town.id(), heart);
            ensureBedrockFoundation(heart);
            renderer.render(town, heart);
        }), 100);
    }

    public Optional<TownHeartLocation> loadHeart(Town town) {
        String active = repository.getMeta(town.internalId(), NS, ACTIVE, "false");
        if (!Boolean.parseBoolean(active)) return Optional.empty();
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

    /**
     * Installs the heart and records the exact 9x9 foundation footprint before replacing it.
     * The footprint makes destroy ownership-aware and allows retry-safe restoration.
     */
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

        List<TownRepository.FoundationBlock> footprint = captureFoundation(heart);
        // Persist ownership before mutating the foundation. If this fails, the world remains untouched.
        repository.replaceHeartFoundation(town.id(), world.getName(), footprint);

        try {
            clearHeartChunk(heart);
            ensureBedrockFoundation(heart);
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
        } catch (Throwable error) {
            renderer.remove(town.id(), heart.toLocation());
            hearts.remove(town.id());
            restoreFoundation(town.id(), heart, footprint);
            repository.deleteHeartFoundation(town.id());
            repository.setMeta(town.internalId(), NS, ACTIVE, "false");
            throw error;
        }
    }

    public boolean rerender(UUID townId) {
        Town town = townsService.findTown(townId).orElse(null);
        if (town == null) return false;
        TownHeartLocation heart = heartOf(townId).orElse(null);
        if (heart == null) return false;
        removePhysicalHeartBlock(heart);
        ensureBedrockFoundation(heart);
        renderer.render(town, heart);
        return true;
    }

    public void updateName(Town town) {
        heartOf(town.id()).ifPresent(heart -> {
            removePhysicalHeartBlock(heart);
            ensureBedrockFoundation(heart);
            renderer.render(town, heart);
        });
    }

    public void removeVisuals(UUID townId) {
        TownHeartLocation heart = hearts.remove(townId);
        renderer.remove(townId, heart == null ? null : heart.toLocation());
    }

    public void removeHeartCompletely(Town town) {
        if (town == null) return;
        TownHeartLocation cached = hearts.get(town.id());
        Optional<TownHeartLocation> heart = cached == null ? loadHeart(town) : Optional.of(cached);
        TownHeartLocation location = heart.orElse(null);
        renderer.remove(town.id(), location == null ? null : location.toLocation());
        hearts.remove(town.id());
        if (location != null) {
            removePhysicalHeartBlock(location);
            List<TownRepository.FoundationBlock> footprint = repository.loadHeartFoundation(town.id());
            restoreFoundation(town.id(), location, footprint);
        }
        repository.deleteHeartFoundation(town.id());
    }

    /**
     * Persistent destroy-job world cleanup. It does not depend on the town still existing
     * in the core tables and therefore works after CORE_DELETED and after a restart.
     */
    public CompletableFuture<Void> cleanupJob(TownRepository.CleanupJob job) {
        if (job == null || job.town() == null) return CompletableFuture.completedFuture(null);
        UUID townId = job.town().id();
        return api.db().async(() -> repository.loadHeartFoundation(townId)).thenCompose(footprint ->
                runOnMainThread(() -> {
                    TownHeartLocation heart = heartFromJob(job, footprint);
                    hearts.remove(townId);
                    renderer.remove(townId, heart == null ? null : heart.toLocation());
                    if (heart != null) {
                        removePhysicalHeartBlock(heart);
                        restoreFoundation(townId, heart, footprint);
                    }
                    return null;
                })).thenCompose(ignored -> api.db().asyncRun(() -> repository.deleteHeartFoundation(townId)));
    }

    private TownHeartLocation heartFromJob(TownRepository.CleanupJob job, List<TownRepository.FoundationBlock> footprint) {
        if (job.heartX() != null && job.heartY() != null && job.heartZ() != null) {
            String world = job.heartWorld() == null || job.heartWorld().isBlank() ? job.town().world() : job.heartWorld();
            return new TownHeartLocation(job.town().id(), world, job.heartX(), job.heartY(), job.heartZ(), job.heartX() >> 4, job.heartZ() >> 4);
        }
        if (footprint != null && !footprint.isEmpty()) {
            TownRepository.FoundationBlock first = footprint.get(0);
            int x = (job.town().heart().x() << 4) + 8;
            int z = (job.town().heart().z() << 4) + 8;
            return new TownHeartLocation(job.town().id(), job.town().world(), x, first.y() + 2, z, job.town().heart().x(), job.town().heart().z());
        }
        return null;
    }

    private List<TownRepository.FoundationBlock> captureFoundation(TownHeartLocation heart) {
        World world = Bukkit.getWorld(heart.world());
        if (world == null) return List.of();
        int foundationY = Math.max(world.getMinHeight(), heart.y() - 2);
        List<TownRepository.FoundationBlock> blocks = new ArrayList<>(81);
        for (int x = heart.x() - 4; x <= heart.x() + 4; x++) {
            for (int z = heart.z() - 4; z <= heart.z() + 4; z++) {
                blocks.add(new TownRepository.FoundationBlock(x, foundationY, z, world.getBlockAt(x, foundationY, z).getType().name()));
            }
        }
        return blocks;
    }

    private void registerLegacyFoundationIfNeeded(UUID townId, TownHeartLocation heart) {
        if (!repository.loadHeartFoundation(townId).isEmpty()) return;
        World world = Bukkit.getWorld(heart.world());
        if (world == null) return;
        int foundationY = Math.max(world.getMinHeight(), heart.y() - 2);
        List<TownRepository.FoundationBlock> legacy = new ArrayList<>(81);
        for (int x = heart.x() - 4; x <= heart.x() + 4; x++) {
            for (int z = heart.z() - 4; z <= heart.z() + 4; z++) {
                // Existing active-town metadata is the ownership proof for this deterministic
                // legacy footprint. We intentionally restore AIR on legacy destroy rather than
                // pretending to know what existed before older plugin versions placed bedrock.
                legacy.add(new TownRepository.FoundationBlock(x, foundationY, z, Material.AIR.name()));
            }
        }
        repository.replaceHeartFoundation(townId, heart.world(), legacy);
    }

    private void ensureBedrockFoundation(TownHeartLocation heart) {
        World world = Bukkit.getWorld(heart.world());
        if (world == null) return;
        int foundationY = Math.max(world.getMinHeight(), heart.y() - 2);
        for (int x = heart.x() - 4; x <= heart.x() + 4; x++) {
            for (int z = heart.z() - 4; z <= heart.z() + 4; z++) {
                world.getBlockAt(x, foundationY, z).setType(Material.BEDROCK, false);
            }
        }
    }

    private void restoreFoundation(UUID townId, TownHeartLocation heart, List<TownRepository.FoundationBlock> footprint) {
        if (footprint == null || footprint.isEmpty()) return;
        World world = Bukkit.getWorld(heart.world());
        if (world == null) return;
        for (TownRepository.FoundationBlock saved : footprint) {
            Block block = world.getBlockAt(saved.x(), saved.y(), saved.z());
            // Do not overwrite a block changed by another system. Only our registered bedrock
            // footprint is eligible for restoration.
            if (block.getType() != Material.BEDROCK) continue;
            Material previous = Material.matchMaterial(saved.previousMaterial());
            if (previous == null) previous = Material.AIR;
            block.setType(previous, false);
        }
    }

    private void removePhysicalHeartBlock(TownHeartLocation heart) {
        World world = Bukkit.getWorld(heart.world());
        if (world == null) return;
        Block block = world.getBlockAt(heart.x(), heart.y(), heart.z());
        // Current hearts are display-based and keep this carrier block as AIR.
        // RED_CONCRETE is the only legacy physical carrier we recognize, so never
        // erase an arbitrary player/plugin block merely because it occupies the old heart coordinate.
        if (block.getType() == Material.RED_CONCRETE) block.setType(Material.AIR, false);
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

    private <T> CompletableFuture<T> runOnMainThread(Supplier<T> action) {
        if (Bukkit.isPrimaryThread()) {
            try { return CompletableFuture.completedFuture(action.get()); }
            catch (Throwable error) { return CompletableFuture.failedFuture(error); }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try { future.complete(action.get()); }
            catch (Throwable error) { future.completeExceptionally(error); }
        });
        return future;
    }

    private int parseInt(String raw, int fallback) {
        try { return Integer.parseInt(raw); }
        catch (NumberFormatException ex) { return fallback; }
    }
}
