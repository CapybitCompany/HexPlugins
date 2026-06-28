package hex.minions.energy;

import org.bukkit.Axis;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public record CableSegment(UUID id, String world, BlockPos start, BlockPos end, Axis axis, CableType type, int length) {
    public Set<Long> chunkKeys() {
        Set<Long> keys = new LinkedHashSet<>();
        int minX = Math.min(start.x(), end.x());
        int maxX = Math.max(start.x(), end.x());
        int minZ = Math.min(start.z(), end.z());
        int maxZ = Math.max(start.z(), end.z());
        int minChunkX = Math.floorDiv(minX, 16);
        int maxChunkX = Math.floorDiv(maxX, 16);
        int minChunkZ = Math.floorDiv(minZ, 16);
        int maxChunkZ = Math.floorDiv(maxZ, 16);
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) keys.add(chunkKey(cx, cz));
        }
        return keys;
    }

    public boolean touchesChunk(int chunkX, int chunkZ) {
        return chunkKeys().contains(chunkKey(chunkX, chunkZ));
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }
}
