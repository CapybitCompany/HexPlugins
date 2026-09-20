package hex.minigames.game.sumo;
import org.bukkit.Location;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SumoRuntimeTest {
    @Test void timeCountsOnlyArenaAndPreservesAcrossTimeOutside() {
        var runtime = new SumoRuntime(); UUID id = UUID.randomUUID();
        for (int i=0;i<399;i++) runtime.tick(id,true);
        runtime.tick(id,false); assertEquals(0,runtime.points(id));
        runtime.tick(id,true); assertEquals(1,runtime.points(id));
        for (int i=0;i<800;i++) runtime.tick(id,true);
        assertEquals(3,runtime.points(id));
        assertFalse(SumoRuntime.onArena(448,49,324));
        assertTrue(SumoRuntime.onArena(438.5,49,349.5));
        assertFalse(SumoRuntime.onArena(447.5,48,358.5));
    }
    @Test void bothPadsLaunchFastWithLowNaturalArcs() {
        for (var start : new Location[]{new Location(null,448,49,329),new Location(null,434,47,370)}) {
            var velocity = SumoMinigame.launchVelocity(start);
            double x = start.getX(), y = start.getY(), z = start.getZ(), peak = y;
            for (int tick = 0; tick < 18; tick++) {
                x += velocity.getX(); y += velocity.getY(); z += velocity.getZ();
                peak = Math.max(peak, y);
                velocity.setX(velocity.getX() * 0.91).setZ(velocity.getZ() * 0.91);
                velocity.setY((velocity.getY() - 0.08) * 0.98);
            }
            assertTrue(peak - start.getY() < 3);
            assertTrue(Math.hypot(x - 438.5, z - 349.5) < 1);
            assertTrue(velocity.getY() < -0.6);
        }
    }
    @Test void clockDisplaysSecondsAndHundredths() {
        assertEquals("00:00", SumoRuntime.formatTime(0));
        assertEquals("00:05", SumoRuntime.formatTime(1));
        assertEquals("00:95", SumoRuntime.formatTime(19));
        assertEquals("01:00", SumoRuntime.formatTime(20));
        assertEquals("20:00", SumoRuntime.formatTime(400));
    }
}
