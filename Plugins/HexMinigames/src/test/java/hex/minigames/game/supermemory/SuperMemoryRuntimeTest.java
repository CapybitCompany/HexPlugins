package hex.minigames.game.supermemory;

import hex.minigames.config.ConfiguredSound;
import hex.minigames.game.RoundResult;
import hex.minigames.model.BlockPosition;
import hex.minigames.model.CuboidRegion;
import hex.minigames.model.LocationSpec;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SuperMemoryRuntimeTest {
    @Test
    void playerReceivesEightBlockPermutation() {
        UUID player = UUID.randomUUID();
        SuperMemoryRuntime runtime = runtime(List.of(player), new FakeBoard());

        List<BlockPosition> sequence = runtime.sequence(player);

        assertEquals(8, sequence.size());
        assertEquals(8, new LinkedHashSet<>(sequence).size());
        assertTrue(runtime.station(player).clickBlocks().containsAll(sequence));
    }

    @Test
    void twoPlayersHaveIndependentRuntimeState() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        FakeBoard board = new FakeBoard();
        SuperMemoryRuntime runtime = runtime(List.of(first, second), board);
        runtime.start(0L);

        runtime.click(first, runtime.sequence(first).get(0), 1L, 1_000_000L, board);

        assertEquals(1, runtime.progress(first));
        assertEquals(0, runtime.progress(second));
        assertNotEquals(runtime.station(first).id(), runtime.station(second).id());
    }

    @Test
    void correctClickIncrementsProgressAndTurnsBlockLime() {
        UUID player = UUID.randomUUID();
        FakeBoard board = new FakeBoard();
        SuperMemoryRuntime runtime = runtime(List.of(player), board);
        runtime.start(0L);
        BlockPosition expected = runtime.sequence(player).get(0);

        SuperMemoryRuntime.ClickResult result = runtime.click(player, expected, 1L, 1_000_000L, board);

        assertEquals(SuperMemoryRuntime.ClickOutcome.CORRECT, result.outcome());
        assertEquals(1, runtime.progress(player));
        assertEquals(Material.LIME_CONCRETE, board.material(expected));
    }

    @Test
    void wrongClickResetsProgressWithoutChangingSequenceAndDelayedResetReturnsWhite() {
        UUID player = UUID.randomUUID();
        FakeBoard board = new FakeBoard();
        SuperMemoryRuntime runtime = runtime(List.of(player), board);
        runtime.start(0L);
        List<BlockPosition> sequence = runtime.sequence(player);
        runtime.click(player, sequence.get(0), 1L, 1_000_000L, board);
        BlockPosition wrong = runtime.station(player).clickBlocks().stream()
                .filter(block -> !block.equals(sequence.get(1)))
                .findFirst()
                .orElseThrow();

        SuperMemoryRuntime.ClickResult result = runtime.click(player, wrong, 2L, 2_000_000L, board);

        assertEquals(SuperMemoryRuntime.ClickOutcome.WRONG, result.outcome());
        assertEquals(0, runtime.progress(player));
        assertEquals(sequence, runtime.sequence(player));
        assertEquals(Material.RED_CONCRETE, board.material(wrong));

        runtime.resetWrong(player, board);

        for (BlockPosition block : runtime.station(player).clickBlocks()) {
            assertEquals(Material.WHITE_CONCRETE, board.material(block));
        }
    }

    @Test
    void eightCorrectClicksFinishPlayerAndStoreCompletionTime() {
        UUID player = UUID.randomUUID();
        FakeBoard board = new FakeBoard();
        SuperMemoryRuntime runtime = runtime(List.of(player), board);
        runtime.start(0L);

        long tick = 1L;
        for (BlockPosition block : runtime.sequence(player)) {
            runtime.click(player, block, tick, tick * 1_000_000L, board);
            tick++;
        }

        assertTrue(runtime.finished(player));
        assertEquals(8, runtime.progress(player));
        assertTrue(runtime.completionNanos(player) > 0L);
    }

    @Test
    void timeoutMarksUnfinishedPlayerAsDnfWithZeroPoints() {
        UUID player = UUID.randomUUID();
        SuperMemoryRuntime runtime = runtime(List.of(player), new FakeBoard());
        runtime.start(0L);

        runtime.markTimeouts();
        RoundResult result = runtime.result();

        assertTrue(runtime.dnf(player));
        assertEquals(0, result.players().get(player).points());
        assertTrue(result.players().get(player).failed());
    }

    @Test
    void rankingSortsByCompletionTimeAndAppliesConfiguredScoring() {
        List<UUID> players = java.util.stream.IntStream.range(0, 8)
                .mapToObj(ignored -> UUID.randomUUID())
                .toList();
        FakeBoard board = new FakeBoard();
        SuperMemoryRuntime runtime = runtime(players, board);
        runtime.start(0L);
        long[] completionNanos = {
                40_000_000L,
                10_000_000L,
                80_000_000L,
                20_000_000L,
                30_000_000L,
                50_000_000L,
                60_000_000L,
                70_000_000L
        };

        long tick = 1L;
        for (int i = 0; i < players.size(); i++) {
            UUID player = players.get(i);
            List<BlockPosition> sequence = runtime.sequence(player);
            for (int blockIndex = 0; blockIndex < sequence.size(); blockIndex++) {
                long now = blockIndex == sequence.size() - 1 ? completionNanos[i] : completionNanos[i] - 1_000_000L;
                runtime.click(player, sequence.get(blockIndex), tick++, now, board);
            }
        }

        RoundResult result = runtime.result();

        assertEquals(4, result.players().get(players.get(1)).points());
        assertEquals(3, result.players().get(players.get(3)).points());
        assertEquals(2, result.players().get(players.get(4)).points());
        assertEquals(1, result.players().get(players.get(0)).points());
        assertEquals(1, result.players().get(players.get(5)).points());
        assertEquals(1, result.players().get(players.get(6)).points());
        assertEquals(1, result.players().get(players.get(7)).points());
        assertEquals(0, result.players().get(players.get(2)).points());
    }

    @Test
    void clickingOtherPlayersStationDoesNotProgress() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        FakeBoard board = new FakeBoard();
        SuperMemoryRuntime runtime = runtime(List.of(first, second), board);
        runtime.start(0L);
        BlockPosition otherStationBlock = runtime.station(second).clickBlocks().get(0);

        SuperMemoryRuntime.ClickResult result = runtime.click(first, otherStationBlock, 1L, 1_000_000L, board);

        assertEquals(SuperMemoryRuntime.ClickOutcome.IGNORED, result.outcome());
        assertEquals(0, runtime.progress(first));
    }

    @Test
    void stationBoundaryDetectsLeavingStationRegion() {
        UUID player = UUID.randomUUID();
        SuperMemoryRuntime runtime = runtime(List.of(player), new FakeBoard());
        SuperMemoryConfig.StationConfig station = runtime.station(player);

        assertTrue(runtime.stationContains(player, station.clickBlocks().get(0)));
        assertFalse(runtime.stationContains(player, new BlockPosition(999, 1, 1)));
    }

    @Test
    void cleanupRestoresAllBlocksToWhite() {
        UUID player = UUID.randomUUID();
        FakeBoard board = new FakeBoard();
        SuperMemoryRuntime runtime = runtime(List.of(player), board);
        runtime.start(0L);
        runtime.click(player, runtime.sequence(player).get(0), 1L, 1_000_000L, board);

        runtime.cleanup(board);

        for (SuperMemoryConfig.StationConfig station : config().stations()) {
            for (BlockPosition block : station.clickBlocks()) {
                assertEquals(Material.WHITE_CONCRETE, board.material(block));
            }
        }
    }

    @Test
    void temporaryMuteRegistryClearsOnlyTrackedPlayers() {
        TemporaryMuteRegistry registry = new TemporaryMuteRegistry();
        UUID player = UUID.randomUUID();

        assertTrue(registry.markMuted(player, "Quezo"));
        assertFalse(registry.markMuted(player, "Quezo"));
        assertEquals("Quezo", registry.remove(player));
        assertFalse(registry.contains(player));
    }

    @Test
    void roundResultIsIdempotent() {
        UUID player = UUID.randomUUID();
        FakeBoard board = new FakeBoard();
        SuperMemoryRuntime runtime = runtime(List.of(player), board);
        runtime.start(0L);
        long tick = 1L;
        for (BlockPosition block : runtime.sequence(player)) {
            runtime.click(player, block, tick, tick * 1_000_000L, board);
            tick++;
        }

        RoundResult first = runtime.result();
        RoundResult second = runtime.result();

        assertSame(first, second);
        assertEquals(4, first.players().get(player).points());
    }

    private SuperMemoryRuntime runtime(List<UUID> players, FakeBoard board) {
        return new SuperMemoryRuntime(config(), players, new Random(4L), board);
    }

    private SuperMemoryConfig config() {
        CuboidRegion gameRegion = new CuboidRegion("Hex_Minigames", new BlockPosition(0, 0, 0), new BlockPosition(200, 5, 20));
        List<SuperMemoryConfig.StationConfig> stations = java.util.stream.IntStream.range(0, 14)
                .mapToObj(index -> station(index + 1, index * 10))
                .toList();
        return new SuperMemoryConfig(
                "&6SUPER-PAMIEC",
                gameRegion,
                90,
                15,
                "&6&lZASADY",
                "&e{seconds}",
                25,
                new ConfiguredSound(false, "", 1.0f, 1.0f),
                List.of(),
                new SuperMemoryConfig.BossBarSettings("&6SUPER-PAMIEC", BarColor.YELLOW, BarStyle.SOLID),
                "{progress}/{remaining}",
                "{completion_time}",
                "done",
                "{time}",
                30,
                Material.WHITE_CONCRETE,
                Material.LIME_CONCRETE,
                Material.RED_CONCRETE,
                1,
                new ConfiguredSound(false, "", 1.0f, 1.0f),
                new ConfiguredSound(false, "", 1.0f, 1.0f),
                new ConfiguredSound(false, "", 1.0f, 1.0f),
                new ConfiguredSound(false, "", 1.0f, 1.0f),
                new ConfiguredSound(false, "", 1.0f, 1.0f),
                List.of(4, 3, 2, 1, 1, 1, 1, 0),
                new SuperMemoryConfig.ResultsFormat(List.of(), "", "", ""),
                stations
        );
    }

    private SuperMemoryConfig.StationConfig station(int id, int baseX) {
        CuboidRegion region = new CuboidRegion("Hex_Minigames", new BlockPosition(baseX, 0, 0), new BlockPosition(baseX + 7, 2, 2));
        List<BlockPosition> clickBlocks = java.util.stream.IntStream.range(0, 8)
                .mapToObj(offset -> new BlockPosition(baseX + offset, 1, 1))
                .toList();
        return new SuperMemoryConfig.StationConfig(id, region, new LocationSpec(baseX + 0.5, 1.0, 1.0, 0.0f, 0.0f, true), clickBlocks);
    }

    private static final class FakeBoard implements SuperMemoryRuntime.Board {
        private final Map<BlockPosition, Material> materials = new LinkedHashMap<>();

        @Override
        public void setBlock(BlockPosition position, Material material) {
            materials.put(position, material);
        }

        private Material material(BlockPosition position) {
            return materials.get(position);
        }
    }
}
