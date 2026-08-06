package hex.quests.tracking;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persists player-placed block coordinates in the owning chunk PDC.
 * This prevents place/break loops even across restarts without filling the SQL quest tables.
 */
public final class PlayerPlacedBlockTracker implements Listener {
    private final NamespacedKey key;
    private final NamespacedKey movingBlockKey;
    private final Map<ChunkKey, Set<Integer>> cache = new HashMap<>();
    private final Set<ChunkKey> dirty = new HashSet<>();

    public PlayerPlacedBlockTracker(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "player_placed_blocks");
        this.movingBlockKey = new NamespacedKey(plugin, "moving_player_placed_block");
    }

    public boolean isPlayerPlaced(Block block) {
        return values(block.getChunk()).contains(pack(block));
    }

    public void remove(Block block) {
        Chunk chunk = block.getChunk();
        ChunkKey chunkKey = ChunkKey.of(chunk);
        if (values(chunk).remove(pack(block))) dirty.add(chunkKey);
    }

    public void flushDirty() {
        for (ChunkKey chunkKey : new ArrayList<>(dirty)) {
            World world = Bukkit.getWorld(chunkKey.worldUuid);
            if (world != null && world.isChunkLoaded(chunkKey.x, chunkKey.z)) {
                write(world.getChunkAt(chunkKey.x, chunkKey.z));
            }
        }
    }

    public void flushAll() {
        for (ChunkKey chunkKey : new ArrayList<>(cache.keySet())) {
            World world = Bukkit.getWorld(chunkKey.worldUuid);
            if (world != null && world.isChunkLoaded(chunkKey.x, chunkKey.z)) {
                write(world.getChunkAt(chunkKey.x, chunkKey.z));
            }
        }
        cache.clear();
        dirty.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        mark(event.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMultiPlace(BlockMultiPlaceEvent event) {
        event.getReplacedBlockStates().forEach(state -> mark(state.getBlock()));
    }


    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        // Stone/cobblestone generators and similar runtime-created blocks are not natural deposits.
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        if (event.getTo().isAir()) {
            if (isPlayerPlaced(block)) {
                event.getEntity().getPersistentDataContainer().set(
                        movingBlockKey, PersistentDataType.BYTE, (byte) 1);
                remove(block);
            }
            return;
        }
        Byte moved = event.getEntity().getPersistentDataContainer().get(movingBlockKey, PersistentDataType.BYTE);
        if (moved != null && moved == (byte) 1) {
            mark(block);
            event.getEntity().getPersistentDataContainer().remove(movingBlockKey);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        move(event.getBlocks(), event.getDirection().getModX(), event.getDirection().getModY(), event.getDirection().getModZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        move(event.getBlocks(), -event.getDirection().getModX(), -event.getDirection().getModY(), -event.getDirection().getModZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(this::remove);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(this::remove);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        remove(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        write(event.getChunk());
        ChunkKey chunkKey = ChunkKey.of(event.getChunk());
        cache.remove(chunkKey);
        dirty.remove(chunkKey);
    }

    private void mark(Block block) {
        Chunk chunk = block.getChunk();
        if (values(chunk).add(pack(block))) dirty.add(ChunkKey.of(chunk));
    }

    private void move(List<Block> blocks, int dx, int dy, int dz) {
        record Move(Block source, Block destination, boolean marked) {}
        List<Move> moves = new ArrayList<>(blocks.size());
        for (Block source : blocks) {
            Block destination = source.getRelative(dx, dy, dz);
            moves.add(new Move(source, destination, isPlayerPlaced(source)));
        }
        for (Move move : moves) remove(move.source());
        for (Move move : moves) if (move.marked()) mark(move.destination());
    }

    private Set<Integer> values(Chunk chunk) {
        ChunkKey chunkKey = ChunkKey.of(chunk);
        return cache.computeIfAbsent(chunkKey, ignored -> {
            int[] stored = chunk.getPersistentDataContainer().get(key, PersistentDataType.INTEGER_ARRAY);
            Set<Integer> result = new HashSet<>();
            if (stored != null) for (int value : stored) result.add(value);
            return result;
        });
    }

    private void write(Chunk chunk) {
        ChunkKey chunkKey = ChunkKey.of(chunk);
        Set<Integer> values = cache.get(chunkKey);
        if (values == null || !dirty.contains(chunkKey)) return;
        if (values.isEmpty()) {
            chunk.getPersistentDataContainer().remove(key);
        } else {
            int[] encoded = values.stream().mapToInt(Integer::intValue).toArray();
            chunk.getPersistentDataContainer().set(key, PersistentDataType.INTEGER_ARRAY, encoded);
        }
        dirty.remove(chunkKey);
    }

    private static int pack(Block block) {
        int y = block.getY() + 2048;
        return ((y & 0xFFF) << 8) | ((block.getX() & 0xF) << 4) | (block.getZ() & 0xF);
    }

    private record ChunkKey(UUID worldUuid, int x, int z) {
        static ChunkKey of(Chunk chunk) {
            return new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        }
    }
}
