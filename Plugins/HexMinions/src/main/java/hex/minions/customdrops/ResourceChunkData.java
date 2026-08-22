package hex.minions.customdrops;

import java.util.HashSet;
import java.util.Set;

public final class ResourceChunkData {
    private final Set<BlockPos> ghostCopperPositions = new HashSet<>();
    private final Set<BlockPos> playerPlacedRelevantBlocks = new HashSet<>();
    private boolean loaded;
    private boolean dirty;

    public ResourceChunkData(boolean loaded) {
        this.loaded = loaded;
    }

    public synchronized Set<BlockPos> ghostCopperSnapshot() {
        return new HashSet<>(ghostCopperPositions);
    }

    public synchronized Set<BlockPos> playerPlacedSnapshot() {
        return new HashSet<>(playerPlacedRelevantBlocks);
    }

    public synchronized boolean loaded() {
        return loaded;
    }

    public synchronized boolean dirty() {
        return dirty;
    }

    public synchronized boolean empty() {
        return ghostCopperPositions.isEmpty() && playerPlacedRelevantBlocks.isEmpty();
    }

    public synchronized boolean hasGhostCopper(BlockPos pos) {
        return ghostCopperPositions.contains(pos);
    }

    public synchronized boolean hasPlayerPlaced(BlockPos pos) {
        return playerPlacedRelevantBlocks.contains(pos);
    }

    public synchronized void addGhostCopper(BlockPos pos) {
        ghostCopperPositions.add(pos);
        dirty = true;
    }

    public synchronized void removeGhostCopper(BlockPos pos) {
        if (ghostCopperPositions.remove(pos)) dirty = true;
    }

    public synchronized void addPlayerPlaced(BlockPos pos) {
        playerPlacedRelevantBlocks.add(pos);
        ghostCopperPositions.remove(pos);
        dirty = true;
    }

    public synchronized boolean removePlayerPlaced(BlockPos pos) {
        boolean removed = playerPlacedRelevantBlocks.remove(pos);
        if (removed) dirty = true;
        return removed;
    }

    public synchronized void mergeLoaded(Set<BlockPos> ghosts, Set<BlockPos> placed) {
        ghostCopperPositions.addAll(ghosts);
        playerPlacedRelevantBlocks.addAll(placed);
        ghostCopperPositions.removeAll(playerPlacedRelevantBlocks);
        loaded = true;
    }

    public synchronized Snapshot snapshotForSave(ChunkKey key) {
        Snapshot snapshot = new Snapshot(key, new HashSet<>(ghostCopperPositions), new HashSet<>(playerPlacedRelevantBlocks));
        dirty = false;
        return snapshot;
    }

    public record Snapshot(ChunkKey key, Set<BlockPos> ghostCopper, Set<BlockPos> playerPlaced) {
        public boolean empty() {
            return ghostCopper.isEmpty() && playerPlaced.isEmpty();
        }
    }
}
