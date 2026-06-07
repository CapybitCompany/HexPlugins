package hex.towns.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class Town {
    private final long internalId;
    private final UUID id;
    private final UUID ownerId;
    private final String name;
    private final String world;
    private final int worldId;
    private final ChunkPos heart;
    private final Instant createdAt;
    private final AtomicInteger growthPoints;
    private volatile TownStatus status;

    public Town(long internalId, UUID id, UUID ownerId, String name, String world, int worldId,
                ChunkPos heart, int growthPoints, Instant createdAt, TownStatus status) {
        this.internalId = internalId;
        this.id = Objects.requireNonNull(id, "id");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.name = Objects.requireNonNull(name, "name");
        this.world = Objects.requireNonNull(world, "world");
        this.worldId = worldId;
        this.heart = Objects.requireNonNull(heart, "heart");
        this.growthPoints = new AtomicInteger(growthPoints);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.status = status == null ? TownStatus.ACTIVE : status;
    }

    public long internalId() { return internalId; }
    public UUID id() { return id; }
    public UUID ownerId() { return ownerId; }
    public String name() { return name; }
    public String world() { return world; }
    public int worldId() { return worldId; }
    public ChunkPos heart() { return heart; }
    public int growthPoints() { return growthPoints.get(); }
    public Instant createdAt() { return createdAt; }
    public TownStatus status() { return status; }

    public void setStatus(TownStatus status) {
        this.status = status;
    }

    public void setGrowthPoints(int value) {
        growthPoints.set(value);
    }

    public int addGrowthPoints(int delta) {
        return growthPoints.addAndGet(delta);
    }

    public boolean tryConsumeGrowthPoint() {
        while (true) {
            int current = growthPoints.get();
            if (current <= 0) {
                return false;
            }
            if (growthPoints.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }
}