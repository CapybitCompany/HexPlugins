package hex.towns.model;

public record ChunkPos(int x, int z) {
    public boolean touchesSide(ChunkPos other) {
        int dx = Math.abs(x - other.x);
        int dz = Math.abs(z - other.z);
        return dx + dz == 1;
    }
}