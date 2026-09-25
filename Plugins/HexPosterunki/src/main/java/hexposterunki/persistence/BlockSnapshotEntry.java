package hexposterunki.persistence;

/** One captured mutable block state inside the outpost region. */
public record BlockSnapshotEntry(String world, int x, int y, int z, String blockData) {
}
