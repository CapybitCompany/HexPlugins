package hex.minions.customdrops;

public record ChunkKey(String world, int chunkX, int chunkZ) {
    public static ChunkKey of(BlockPos pos) {
        return new ChunkKey(pos.world(), Math.floorDiv(pos.x(), 16), Math.floorDiv(pos.z(), 16));
    }
}
