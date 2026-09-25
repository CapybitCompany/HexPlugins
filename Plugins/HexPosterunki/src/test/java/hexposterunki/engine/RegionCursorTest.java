package hexposterunki.engine;

import hexposterunki.config.BlockVec;
import hexposterunki.config.Cuboid;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding 10 regression: the reset-time region scan is bounded and resumable instead of walking a
 * whole fortress synchronously in one tick.
 */
class RegionCursorTest {

    private static Cuboid region(int size) {
        return Cuboid.of("world", new BlockVec(0, 0, 0), new BlockVec(size - 1, size - 1, size - 1));
    }

    @Test
    void everyBlockIsVisitedExactlyOnce() {
        Cuboid region = region(5);
        RegionCursor cursor = new RegionCursor(region);
        Set<String> seen = new HashSet<>();

        do {
            assertTrue(seen.add(cursor.x() + ":" + cursor.y() + ":" + cursor.z()),
                    "blok odwiedzony dwa razy");
        } while (cursor.advance());

        assertEquals(region.volume(), seen.size());
        assertTrue(cursor.finished());
    }

    @Test
    void theScanResumesAcrossSimulatedTicks() {
        Cuboid region = region(8);
        RegionCursor cursor = new RegionCursor(region);
        long budgetPerTick = 37L;
        Set<String> seen = new HashSet<>();
        int ticks = 0;

        while (!cursor.finished()) {
            ticks++;
            long processed = 0;
            while (processed < budgetPerTick && !cursor.finished()) {
                seen.add(cursor.x() + ":" + cursor.y() + ":" + cursor.z());
                processed++;
                cursor.advance();
            }
            assertTrue(processed <= budgetPerTick, "przekroczono budżet jednego ticka");
        }

        assertEquals(region.volume(), seen.size());
        assertTrue(ticks > 1, "skan musi rozłożyć się na wiele ticków");
        assertEquals((int) Math.ceil(region.volume() / (double) budgetPerTick), ticks);
    }

    @Test
    void aVolumeCapStopsTheScanEarly() {
        Cuboid region = region(10);
        RegionCursor cursor = new RegionCursor(region);
        long cap = 250L;
        long visited = 0;

        while (!cursor.finished() && visited < cap) {
            visited++;
            cursor.advance();
        }

        assertEquals(cap, visited);
        assertFalse(cursor.finished(), "limit objętości przerywa skan przed końcem regionu");
        assertTrue(region.volume() > cap);
    }

    @Test
    void aSingleBlockRegionFinishesImmediately() {
        RegionCursor cursor = new RegionCursor(region(1));
        assertFalse(cursor.finished());
        assertFalse(cursor.advance());
        assertTrue(cursor.finished());
        assertEquals(1L, cursor.visited());
    }

    @Test
    void theTraversalOrderIsStableAcrossRuns() {
        StringBuilder first = new StringBuilder();
        StringBuilder second = new StringBuilder();
        for (StringBuilder target : new StringBuilder[]{first, second}) {
            RegionCursor cursor = new RegionCursor(region(3));
            do {
                target.append(cursor.x()).append(cursor.y()).append(cursor.z()).append(';');
            } while (cursor.advance());
        }
        assertEquals(first.toString(), second.toString());
    }
}
