package hex.parkour.model;

public record ParkourCheckpoint(String id, int order, CuboidRegion region, LocationSpec respawn) {
}
