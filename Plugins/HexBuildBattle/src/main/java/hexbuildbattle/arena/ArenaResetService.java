package hexbuildbattle.arena;

import hexbuildbattle.config.ConfigService;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public final class ArenaResetService {

    private final JavaPlugin plugin;
    private final ConfigService configService;
    private final ArenaManager arenaManager;
    private final Logger logger;
    private BukkitTask activeChunkedTask;

    public ArenaResetService(
            JavaPlugin plugin,
            ConfigService configService,
            ArenaManager arenaManager,
            Logger logger
    ) {
        this.plugin = plugin;
        this.configService = configService;
        this.arenaManager = arenaManager;
        this.logger = logger;
    }

    public void resetArenas(Collection<Arena> arenas, Runnable onComplete) {
        cancel();
        if (arenas.isEmpty()) {
            onComplete.run();
            return;
        }

        List<Arena> snapshot = new ArrayList<>(arenas);
        Material defaultFloor = configService.config().materials().defaultFloor();
        arenaManager.resetVisualSettings(snapshot);

        if (configService.config().reset().preferFawe() && hasWorldEditLikePlugin()) {
            resetWithWorldEdit(snapshot, defaultFloor)
                    .whenComplete((ignored, failure) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (failure == null) {
                            onComplete.run();
                        } else {
                            logger.warning("WorldEdit/FAWE reset failed, falling back to chunked Bukkit reset: "
                                    + failure.getMessage());
                            resetChunked(snapshot, defaultFloor, onComplete);
                        }
                    }));
            return;
        }

        resetChunked(snapshot, defaultFloor, onComplete);
    }

    public void cancel() {
        if (activeChunkedTask != null) {
            activeChunkedTask.cancel();
            activeChunkedTask = null;
        }
    }

    private boolean hasWorldEditLikePlugin() {
        Plugin fawe = plugin.getServer().getPluginManager().getPlugin("FastAsyncWorldEdit");
        Plugin worldEdit = plugin.getServer().getPluginManager().getPlugin("WorldEdit");
        return (fawe != null && fawe.isEnabled()) || (worldEdit != null && worldEdit.isEnabled());
    }

    private CompletableFuture<Void> resetWithWorldEdit(List<Arena> arenas, Material defaultFloor) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                WorldEditResetBridge bridge = new WorldEditResetBridge();
                for (Arena arena : arenas) {
                    bridge.setRegion(arena.buildRegion(), Material.AIR);
                    bridge.setRegion(arena.floorRegion(), defaultFloor);
                }
                future.complete(null);
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    private void resetChunked(List<Arena> arenas, Material defaultFloor, Runnable onComplete) {
        Queue<BlockFillCursor> changes = new ArrayDeque<>();
        for (Arena arena : arenas) {
            changes.add(new BlockFillCursor(arena.buildRegion(), Material.AIR));
            changes.add(new BlockFillCursor(arena.floorRegion(), defaultFloor));
        }

        int maxPerTick = configService.config().reset().maxBlocksPerMainThreadTick();
        activeChunkedTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int processed = 0;
            while (processed < maxPerTick && !changes.isEmpty()) {
                BlockFillCursor cursor = changes.peek();
                Block block = cursor.currentBlock();
                if (block.getType() != cursor.material()) {
                    block.setType(cursor.material(), false);
                }
                cursor.advance();
                if (cursor.done()) {
                    changes.poll();
                }
                processed++;
            }
            if (changes.isEmpty()) {
                cancel();
                onComplete.run();
            }
        }, 1L, 1L);
    }

    private static final class BlockFillCursor {
        private final CuboidRegion region;
        private final Material material;
        private int x;
        private int y;
        private int z;
        private boolean done;

        private BlockFillCursor(CuboidRegion region, Material material) {
            this.region = region;
            this.material = material;
            this.x = region.minX();
            this.y = region.minY();
            this.z = region.minZ();
        }

        private Block currentBlock() {
            return region.world().getBlockAt(x, y, z);
        }

        private Material material() {
            return material;
        }

        private void advance() {
            if (done) {
                return;
            }
            z++;
            if (z <= region.maxZ()) {
                return;
            }
            z = region.minZ();
            y++;
            if (y <= region.maxY()) {
                return;
            }
            y = region.minY();
            x++;
            if (x > region.maxX()) {
                done = true;
            }
        }

        private boolean done() {
            return done;
        }
    }
}
