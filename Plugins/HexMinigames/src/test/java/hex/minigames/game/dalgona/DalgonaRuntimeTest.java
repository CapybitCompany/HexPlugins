package hex.minigames.game.dalgona;

import hex.minigames.game.MinigameDefinition;
import hex.minigames.model.BlockPosition;
import hex.minigames.model.CuboidRegion;
import hex.minigames.model.LocationSpec;
import hex.minigames.runtime.RoundPlayerState;
import org.bukkit.GameMode;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DalgonaRuntimeTest {
    @Test
    void allPlayersReceiveSamePattern() {
        DalgonaRuntime runtime = runtime(List.of(UUID.randomUUID(), UUID.randomUUID()));

        assertEquals("single", runtime.pattern().id());
    }

    @Test
    void correctBlockIncreasesProgressAndCompletes() {
        UUID player = UUID.randomUUID();
        DalgonaRuntime runtime = runtime(List.of(player));
        BlockPosition required = runtime.requiredBlocks(player).iterator().next();

        DalgonaRuntime.BreakResult result = runtime.breakBlock(player, required);

        assertEquals(DalgonaRuntime.BreakOutcome.COMPLETE, result.outcome());
        assertEquals(1, runtime.progress(player));
        assertTrue(runtime.finished(player));
        assertEquals(1, config().completePoints());
    }

    @Test
    void wrongBlockEliminatesPlayer() {
        UUID player = UUID.randomUUID();
        DalgonaRuntime runtime = runtime(List.of(player));
        DalgonaConfig.Station station = runtime.station(player);
        BlockPosition wrong = new BlockPosition(station.arena().maxX(), station.arena().minY(), station.arena().maxZ());
        assertNotEquals(runtime.requiredBlocks(player).iterator().next(), wrong);

        DalgonaRuntime.BreakResult result = runtime.breakBlock(player, wrong);

        assertEquals(DalgonaRuntime.BreakOutcome.WRONG, result.outcome());
        assertTrue(runtime.eliminated(player));
    }

    @Test
    void completeIsIdempotent() {
        UUID player = UUID.randomUUID();
        DalgonaRuntime runtime = runtime(List.of(player));
        BlockPosition required = runtime.requiredBlocks(player).iterator().next();

        runtime.breakBlock(player, required);
        DalgonaRuntime.BreakResult second = runtime.breakBlock(player, required);

        assertEquals(DalgonaRuntime.BreakOutcome.IGNORED, second.outcome());
        assertEquals(1, runtime.progress(player));
    }

    @Test
    void defaultConfigLoadsSixDeterministicPatterns() {
        DalgonaConfig config = defaultConfig();

        assertEquals(List.of("triangle", "square", "diamond", "circle", "heart", "star"), config.patterns().stream().map(DalgonaConfig.Pattern::id).toList());
    }

    @Test
    void defaultGameplayModeIsSurvivalAfterTutorial() {
        assertEquals(GameMode.SURVIVAL, defaultConfig().gameplayGameMode());
    }

    @Test
    void gameplayCutDoesNotRunDuringTutorialOrForGhosts() {
        assertFalse(DalgonaMinigame.shouldProcessCut(false, RoundPlayerState.ACTIVE, Action.LEFT_CLICK_BLOCK, EquipmentSlot.HAND));
        assertFalse(DalgonaMinigame.shouldProcessCut(true, RoundPlayerState.GHOST, Action.LEFT_CLICK_BLOCK, EquipmentSlot.HAND));
        assertTrue(DalgonaMinigame.shouldProcessCut(true, RoundPlayerState.ACTIVE, Action.LEFT_CLICK_BLOCK, EquipmentSlot.HAND));
    }

    @Test
    void everyRenderedBoardPixelHasExactlyOneArenaTargetForAllTemplates() {
        DalgonaConfig config = defaultConfig();
        DalgonaConfig.Station station = config.stations().get(0);

        for (DalgonaConfig.Pattern pattern : config.patterns()) {
            List<DalgonaRuntime.LogicalPixel> pixels = DalgonaRuntime.logicalPixels(station, pattern);
            var mapping = DalgonaRuntime.templateMapping(station, pattern);

            assertEquals(pixels.size(), mapping.size(), pattern.id());
            assertEquals(pixels.size(), new LinkedHashSet<>(mapping.stream().map(DalgonaRuntime.TemplateMapping::pixel).toList()).size(), pattern.id());
            assertEquals(pixels.size(), new LinkedHashSet<>(mapping.stream().map(DalgonaRuntime.TemplateMapping::boardBlock).toList()).size(), pattern.id());
            assertEquals(pixels.size(), new LinkedHashSet<>(mapping.stream().map(DalgonaRuntime.TemplateMapping::arenaBlock).toList()).size(), pattern.id());
            assertEquals(DalgonaRuntime.boardBlocks(station, pattern), new LinkedHashSet<>(mapping.stream().map(DalgonaRuntime.TemplateMapping::boardBlock).toList()), pattern.id());
            assertEquals(DalgonaRuntime.arenaBlocks(station, pattern), new LinkedHashSet<>(mapping.stream().map(DalgonaRuntime.TemplateMapping::arenaBlock).toList()), pattern.id());
        }
    }

    @Test
    void boardTopRowMapsToFarEdgeWhenLookingTowardBoard() {
        DalgonaConfig config = defaultConfig();
        DalgonaConfig.Station station = config.stations().get(0);

        BlockPosition topArena = DalgonaRuntime.arenaBlock(station, new DalgonaRuntime.LogicalPixel(0, 0));
        BlockPosition bottomArena = DalgonaRuntime.arenaBlock(station, new DalgonaRuntime.LogicalPixel(0, station.arena().maxZ() - station.arena().minZ()));

        assertEquals(station.arena().minZ(), topArena.z());
        assertEquals(station.arena().maxZ(), bottomArena.z());
    }

    @Test
    void triangleVisiblePixelIsCorrectOnEveryStation() {
        var config = defaultConfig();
        var triangle = config.patterns().get(0);
        var players = java.util.stream.IntStream.range(0, config.stations().size()).mapToObj(i -> UUID.randomUUID()).toList();
        var runtime = new DalgonaRuntime(config, players, triangle);
        for (UUID player : players) {
            var arena = runtime.station(player).arena();
            var bottomLeft = DalgonaRuntime.arenaBlock(runtime.station(player), new DalgonaRuntime.LogicalPixel(3, 4));
            assertEquals(DalgonaRuntime.BreakOutcome.CORRECT, runtime.breakBlock(player, bottomLeft).outcome());
            assertFalse(runtime.eliminated(player));
        }
    }

    @Test
    void everyVisibleRedCellCanBeCutUsingOnlyItsOffsetFromTheBoardBottom() {
        var config = defaultConfig();
        var players = java.util.stream.IntStream.range(0, config.stations().size()).mapToObj(i -> UUID.randomUUID()).toList();
        for (var pattern : config.patterns()) {
            var runtime = new DalgonaRuntime(config, players, pattern);
            for (UUID player : players) {
                var station = runtime.station(player);
                var display = DalgonaRuntime.displayRegion(station);
                assertEquals(station.arena().maxZ() - station.arena().minZ(), display.maxY() - display.minY());
                for (BlockPosition visible : runtime.boardBlocks(station)) {
                    var floor = new BlockPosition(station.arena().minX() + visible.x() - display.minX(),
                            station.arena().minY(), station.arena().maxZ() - (visible.y() - display.minY()));
                    assertNotEquals(DalgonaRuntime.BreakOutcome.WRONG, runtime.breakBlock(player, floor).outcome(),
                            pattern.id() + " station=" + station.id() + " visible=" + visible + " floor=" + floor);
                }
                assertTrue(runtime.finished(player));
            }
        }
    }

    @Test
    void squareHasFiveByFivePixelsAndMargins() {
        var square = defaultConfig().patterns().stream().filter(p -> p.id().equals("square")).findFirst().orElseThrow();
        var pixels = DalgonaRuntime.logicalPixels(square);
        assertEquals(25, pixels.size());
        assertTrue(pixels.stream().allMatch(p -> p.u() > 0 && p.u() < 10 && p.v() > 0 && p.v() < 9));
    }

    @Test
    void airClicksCannotCutAndBlocksOutsideFloorAreIgnored() {
        assertFalse(DalgonaMinigame.isDalgonaCutAction(Action.LEFT_CLICK_AIR, EquipmentSlot.HAND));
        UUID player = UUID.randomUUID();
        var runtime = runtime(List.of(player));
        assertEquals(DalgonaRuntime.BreakOutcome.IGNORED,
                runtime.breakBlock(player, new BlockPosition(435, -33, -229)).outcome());
        assertFalse(runtime.eliminated(player));
    }

    @Test
    void everyCorrectBlockCanBeCutWithoutEliminatingForAllTemplates() {
        UUID player = UUID.randomUUID();
        DalgonaConfig config = defaultConfig();

        for (DalgonaConfig.Pattern pattern : config.patterns()) {
            DalgonaRuntime runtime = new DalgonaRuntime(config, List.of(player), pattern);
            for (BlockPosition block : runtime.requiredBlocks(player)) {
                DalgonaRuntime.BreakResult result = runtime.breakBlock(player, block);
                assertNotEquals(DalgonaRuntime.BreakOutcome.WRONG, result.outcome(), pattern.id() + " " + block);
                assertFalse(runtime.eliminated(player), pattern.id());
            }
            assertTrue(runtime.finished(player), pattern.id());
        }
    }

    @Test
    void customBreakInputIsMainHandLeftClickBlockOnly() {
        assertTrue(DalgonaMinigame.isDalgonaCutAction(Action.LEFT_CLICK_BLOCK, EquipmentSlot.HAND));
        assertEquals(false, DalgonaMinigame.isDalgonaCutAction(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND));
        assertEquals(false, DalgonaMinigame.isDalgonaCutAction(Action.LEFT_CLICK_BLOCK, EquipmentSlot.OFF_HAND));
    }

    private DalgonaRuntime runtime(List<UUID> players) {
        return new DalgonaRuntime(config(), players, new Random(3L));
    }

    private DalgonaConfig config() {
        List<String> grid = List.of(
                "#..........",
                "...........",
                "...........",
                "...........",
                "...........",
                "...........",
                "...........",
                "...........",
                "...........",
                "..........."
        );
        return DalgonaConfig.fromDefinition(new MinigameDefinition(
                DalgonaConfig.ID,
                "Dalgona",
                true,
                true,
                false,
                1,
                14,
                1,
                Optional.of(new CuboidRegion("world", new BlockPosition(400, -33, -313), new BlockPosition(520, 7, -193))),
                List.of(new LocationSpec(435, -31, -229, 180, 0, true)),
                Optional.of(new LocationSpec(466, -25, -250, 180, 0, true)),
                60,
                Map.of("patterns", List.of(Map.of("id", "single", "grid", grid))),
                "test"
        ), new java.util.ArrayList<>());
    }

    private DalgonaConfig defaultConfig() {
        return DalgonaConfig.fromDefinition(new MinigameDefinition(
                DalgonaConfig.ID,
                "Dalgona",
                true,
                true,
                false,
                1,
                14,
                1,
                Optional.of(new CuboidRegion("world", new BlockPosition(400, -33, -313), new BlockPosition(520, 7, -193))),
                List.of(new LocationSpec(435, -31, -229, 180, 0, true)),
                Optional.of(new LocationSpec(466, -25, -250, 180, 0, true)),
                60,
                Map.of(),
                "test"
        ), new java.util.ArrayList<>());
    }
}
