package hex.towns.util;

public final class ChunkKeys {
    private ChunkKeys() {
    }

    public static long chunkKey(int worldId, int chunkX, int chunkZ) {
        return ((long) (worldId & 0xFFFF) << 48)
                | ((long) (chunkX & 0xFFFFFF) << 24)
                | (chunkZ & 0xFFFFFFL);
    }

    public static long bucketKey(int worldId, int bucketX, int bucketZ) {
        return ((long) (worldId & 0xFFFF) << 48)
                | ((long) (bucketX & 0xFFFFFF) << 24)
                | (bucketZ & 0xFFFFFFL);
    }

    public static int bucket(int chunkCoord, int bucketSize) {
        return Math.floorDiv(chunkCoord, bucketSize);
    }
}