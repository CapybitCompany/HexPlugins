package hex.minigames.game.redlight;

import hex.minigames.config.ConfiguredSound;
import hex.minigames.game.common.BossBarSettings;
import hex.minigames.game.common.TutorialSettings;
import hex.minigames.model.BlockPosition;
import hex.minigames.model.CuboidRegion;
import hex.minigames.model.LocationSpec;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RedLightGreenLightRuntimeTest {
    @Test
    void playersHaveFifteenTicksToReactThenRealMovementIsPenalized() {
        RedLightGreenLightRuntime runtime = runtime();
        runtime.forceRed(100L);

        assertFalse(runtime.movementViolation(114L, move(0, -0.1, 0, 0, 0)));
        assertTrue(runtime.movementViolation(115L, move(0, -0.1, 0, 0, 0)));
        assertFalse(runtime.movementViolation(115L, move(0.005, 0, 0, 0.2f, 0)));
    }

    @Test
    void movementAfterGracePeriodIsViolation() {
        RedLightGreenLightRuntime runtime = runtime();
        runtime.forceRed(100L);

        assertTrue(runtime.movementViolation(115L, move(0, 0, 1, 0, 0)));
    }

    @Test
    void rotationAfterGracePeriodIsViolation() {
        RedLightGreenLightRuntime runtime = runtime();
        runtime.forceRed(100L);

        assertTrue(runtime.movementViolation(115L, move(0, 0, 0, 10, 0)));
    }

    @Test
    void finishIsStoredOnlyOnce() {
        RedLightGreenLightRuntime runtime = runtime();
        UUID player = UUID.randomUUID();

        assertTrue(runtime.markFinished(player, 10L));
        assertFalse(runtime.markFinished(player, 20L));
        assertTrue(runtime.finishTick(player).isPresent());
        assertTrue(runtime.finishTick(player).orElseThrow() == 10L);
    }

    @Test
    void randomLightDurationsStayBetweenOnePointFiveAndThreePointFiveSeconds() {
        for (int seed = 0; seed < 50; seed++) {
            RedLightGreenLightRuntime runtime = new RedLightGreenLightRuntime(config(), new Random(seed));
            runtime.start(0L);
            long firstDelay = runtime.ticksUntilSwitch(0L);
            assertTrue(firstDelay >= 30);
            assertTrue(firstDelay <= 70);
            runtime.tick(firstDelay);
            long secondDelay = runtime.ticksUntilSwitch(firstDelay);
            assertTrue(secondDelay >= 30);
            assertTrue(secondDelay <= 70);
        }
    }

    @Test
    void defaultsUseShortGraceAndResetSpawn() {
        RedLightGreenLightConfig config = config();

        assertEquals(15, config.redGraceTicks());
        assertEquals(30, config.greenMinTicks());
        assertEquals(70, config.greenMaxTicks());
        assertEquals(30, config.redMinTicks());
        assertEquals(70, config.redMaxTicks());
        assertEquals(173.0, config.resetSpawn().x());
        assertEquals(-30.0, config.resetSpawn().y());
        assertEquals(-274.0, config.resetSpawn().z());
    }

    private RedLightGreenLightRuntime runtime() {
        return new RedLightGreenLightRuntime(config(), new Random(1L));
    }

    private RedLightGreenLightRuntime.MovementSample move(double dx, double dy, double dz, float yaw, float pitch) {
        return new RedLightGreenLightRuntime.MovementSample(0, 0, 0, 0, 0, dx, dy, dz, yaw, pitch);
    }

    private RedLightGreenLightConfig config() {
        CuboidRegion region = new CuboidRegion("world", new BlockPosition(0, 0, 0), new BlockPosition(10, 10, 10));
        TutorialSettings tutorial = new TutorialSettings(30, "&6ZASADY", "&f{seconds}", 25, new ConfiguredSound(false, "", 1, 1), 5, List.of());
        return new RedLightGreenLightConfig(
                region,
                new LocationSpec(173, -30, -274, 0, 0, true),
                region,
                region,
                75,
                tutorial,
                new BossBarSettings("&dCZERWONE-ZIELONE", BarColor.WHITE, BarStyle.SOLID),
                30,
                70,
                30,
                70,
                15,
                0.015,
                0.5,
                Material.LIME_CONCRETE,
                Material.RED_CONCRETE,
                new ConfiguredSound(false, "", 1, 1),
                new ConfiguredSound(false, "", 1, 1),
                "",
                "",
                "",
                3,
                2,
                1
        );
    }
}
