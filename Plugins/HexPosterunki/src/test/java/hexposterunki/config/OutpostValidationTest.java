package hexposterunki.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A broken location must be skipped with a readable reason, never silently accepted - and never
 * take the whole plugin down with it.
 */
class OutpostValidationTest {

    private static final OutpostsLoader LOADER = new OutpostsLoader(
            name -> "world".equals(name),
            world -> new int[]{-64, 319});

    private static OutpostDefinition definition(String world, Cuboid region, PointDef center,
                                                PointDef bossSpawn, Map<String, PointDef> spawnPoints,
                                                Map<String, BlockVec> loot) {
        return new OutpostDefinition("fort", "Fort Północny", world, region, center, bossSpawn,
                spawnPoints, loot, 5);
    }

    private static Cuboid region() {
        return Cuboid.of("world", new BlockVec(0, 60, 0), new BlockVec(50, 100, 50));
    }

    private static PointDef inside() {
        return new PointDef(25, 65, 25, 0, 0);
    }

    @Test
    void aWellFormedOutpostPassesValidation() {
        assertDoesNotThrow(() -> LOADER.validate(definition("world", region(), inside(), inside(),
                Map.of("brama", inside()), Map.of("skrzynia", new BlockVec(25, 66, 25)))));
    }

    @Test
    void anUnknownWorldIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LOADER.validate(definition("nieistniejacy", region(), inside(), inside(),
                        Map.of("brama", inside()), Map.of())));
        assertTrue(error.getMessage().contains("nie istnieje"));
    }

    @Test
    void aCenterOutsideTheRegionIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LOADER.validate(definition("world", region(), new PointDef(500, 65, 500, 0, 0),
                        inside(), Map.of("brama", inside()), Map.of())));
        assertTrue(error.getMessage().contains("center"));
    }

    @Test
    void aBossSpawnOutsideTheRegionIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LOADER.validate(definition("world", region(), inside(),
                        new PointDef(-10, 65, 25, 0, 0), Map.of("brama", inside()), Map.of())));
        assertTrue(error.getMessage().contains("boss-spawn"));
    }

    @Test
    void anOutpostWithoutSpawnPointsIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LOADER.validate(definition("world", region(), inside(), inside(), Map.of(), Map.of())));
        assertTrue(error.getMessage().contains("spawn-points"));
    }

    @Test
    void aSpawnPointOutsideTheRegionIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LOADER.validate(definition("world", region(), inside(), inside(),
                        Map.of("brama", new PointDef(999, 65, 25, 0, 0)), Map.of())));
        assertTrue(error.getMessage().contains("brama"));
    }

    @Test
    void aLootContainerOutsideTheRegionIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LOADER.validate(definition("world", region(), inside(), inside(),
                        Map.of("brama", inside()), Map.of("skrzynia", new BlockVec(900, 66, 25)))));
        assertTrue(error.getMessage().contains("skrzynia"));
    }

    @Test
    void aRegionOutsideWorldHeightIsRejected() {
        Cuboid tooHigh = Cuboid.of("world", new BlockVec(0, 300, 0), new BlockVec(50, 400, 50));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LOADER.validate(definition("world", tooHigh, new PointDef(25, 350, 25, 0, 0),
                        new PointDef(25, 350, 25, 0, 0),
                        Map.of("brama", new PointDef(25, 350, 25, 0, 0)), Map.of())));
        assertTrue(error.getMessage().contains("wysokość"));
    }
}
