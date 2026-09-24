package hex.minigames.game.popcorn;

import hex.minigames.game.MinigameDefinition;
import hex.minigames.model.BlockPosition;
import hex.minigames.model.CuboidRegion;
import hex.minigames.model.LocationSpec;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PopcornRuntimeTest {
    @Test
    void legacyConfigGivesPlayersTimeToReactToEachColor() {
        PopcornRuntime runtime = runtime();

        runtime.tick(10L);

        assertEquals(Material.YELLOW_CONCRETE, runtime.tick(18L).get(0).material());
        assertEquals(Material.ORANGE_CONCRETE, runtime.tick(26L).get(0).material());
        assertEquals(Material.RED_CONCRETE, runtime.tick(34L).get(0).material());
        assertEquals(Material.AIR, runtime.tick(42L).get(0).material());
    }

    @Test
    void blockCannotEnterPipelineTwice() {
        PopcornRuntime runtime = runtime();
        BlockPosition block = config().platformBlocks().get(0);

        runtime.tick(0L);
        runtime.tick(240L);
        runtime.tick(500L);

        assertTrue(runtime.inPipeline(block));
        assertTrue(runtime.tick(560L).isEmpty());
    }

    @Test
    void activationRateIncreasesOverTime() {
        PopcornRuntime runtime = runtime();

        assertTrue(runtime.activationRateAt(0.0) < runtime.activationRateAt(0.5));
        assertTrue(runtime.activationRateAt(0.5) < runtime.activationRateAt(1.0));
    }

    @Test
    void fullPlatformLastsThirtyFiveSecondsAndLeavesTwelveBlocks() {
        var runtime = new PopcornRuntime(config(35, Map.of(
                "platform", Map.of("pos1", Map.of("x", 580, "y", -26, "z", -153),
                        "pos2", Map.of("x", 608, "y", -26, "z", -110))
        )), new Random(5L));
        for (int tick = 0; tick <= 700; tick++) {
            runtime.tick(tick);
            if (tick == 450) assertTrue(runtime.remainingSolidBlocks() < 650);
            if (tick == 680) assertTrue(runtime.remainingSolidBlocks() > 12);
        }
        assertEquals(12, runtime.remainingSolidBlocks());
        assertTrue(runtime.tick(1400).isEmpty());
    }

    @Test
    void survivorRewardIsTwoPoints() {
        assertEquals(2, config().survivorPoints());
    }

    private PopcornRuntime runtime() {
        return new PopcornRuntime(config(), new Random(5L));
    }

    private PopcornConfig config() {
        return config(90, Map.of(
                "platform", Map.of("pos1", Map.of("x", 0, "y", 0, "z", 0), "pos2", Map.of("x", 0, "y", 0, "z", 0)),
                "hazard", Map.of("stage-duration-ticks", 60, "activation-rate-start", 20.0, "activation-rate-end", 40.0, "target-remaining-blocks", 0)
        ));
    }

    private PopcornConfig config(int seconds, Map<String, Object> settings) {
        return PopcornConfig.fromDefinition(new MinigameDefinition(
                PopcornConfig.ID,
                "Popcorn",
                true,
                true,
                false,
                1,
                14,
                1,
                Optional.of(new CuboidRegion("world", new BlockPosition(0, -10, 0), new BlockPosition(10, 10, 10))),
                List.of(new LocationSpec(0, 0, 0, 0, 0, true)),
                Optional.of(new LocationSpec(0, 0, 0, 0, 0, true)),
                seconds,
                settings,
                "test"
        ), new java.util.ArrayList<>());
    }
}
