package hexposterunki.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankingCalculatorTest {

    private static final UUID TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID OTHER_TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private static UUID player(String suffix) {
        return UUID.fromString("00000000-0000-0000-0000-0000000000" + suffix);
    }

    private static KillEntry entry(String suffix, UUID town, int kills, long achievedAt) {
        return new KillEntry(player(suffix), town, kills, achievedAt);
    }

    @Test
    void moreKillsWinsBeforeAnythingElse() {
        List<KillEntry> top = RankingCalculator.top(List.of(
                entry("01", TOWN, 5, 100L),
                entry("02", TOWN, 9, 900L)), TOWN, 1, 5);
        assertEquals(player("02"), top.get(0).playerId());
    }

    @Test
    void equalKillsAreOrderedByWhoReachedThemFirst() {
        List<KillEntry> top = RankingCalculator.top(List.of(
                entry("01", TOWN, 7, 900L),
                entry("02", TOWN, 7, 100L)), TOWN, 1, 5);
        assertEquals(player("02"), top.get(0).playerId());
        assertEquals(player("01"), top.get(1).playerId());
    }

    @Test
    void uuidIsTheStableFinalTieBreaker() {
        List<KillEntry> forward = RankingCalculator.top(List.of(
                entry("0b", TOWN, 7, 500L),
                entry("0a", TOWN, 7, 500L)), TOWN, 1, 5);
        List<KillEntry> reversed = RankingCalculator.top(List.of(
                entry("0a", TOWN, 7, 500L),
                entry("0b", TOWN, 7, 500L)), TOWN, 1, 5);
        assertEquals(forward.get(0).playerId(), reversed.get(0).playerId());
        assertEquals(player("0a"), forward.get(0).playerId());
    }

    @Test
    void playersBelowTheMinimumAreExcludedEntirely() {
        List<KillEntry> top = RankingCalculator.top(List.of(
                entry("01", TOWN, 9, 100L),
                entry("02", TOWN, 3, 100L)), TOWN, 5, 5);
        assertEquals(1, top.size());
        assertEquals(player("01"), top.get(0).playerId());
    }

    @Test
    void onlyMembersOfTheFinalControllingTownAreRanked() {
        List<KillEntry> top = RankingCalculator.top(List.of(
                entry("01", OTHER_TOWN, 50, 100L),
                entry("02", TOWN, 6, 100L)), TOWN, 1, 5);
        assertEquals(1, top.size());
        assertEquals(player("02"), top.get(0).playerId());
    }

    @Test
    void rankingIsCappedAtFivePlaces() {
        List<KillEntry> entries = List.of(
                entry("01", TOWN, 10, 100L),
                entry("02", TOWN, 9, 100L),
                entry("03", TOWN, 8, 100L),
                entry("04", TOWN, 7, 100L),
                entry("05", TOWN, 6, 100L),
                entry("06", TOWN, 5, 100L));
        assertEquals(5, RankingCalculator.top(entries, TOWN, 1, 5).size());
    }

    @Test
    void rankOfMatchesTheRankingOrderAndIsZeroForIneligiblePlayers() {
        List<KillEntry> entries = List.of(
                entry("01", TOWN, 10, 100L),
                entry("02", TOWN, 9, 100L),
                entry("03", OTHER_TOWN, 99, 100L),
                entry("04", TOWN, 2, 100L));

        assertEquals(1, RankingCalculator.rankOf(entries, TOWN, 5, player("01")));
        assertEquals(2, RankingCalculator.rankOf(entries, TOWN, 5, player("02")));
        assertEquals(0, RankingCalculator.rankOf(entries, TOWN, 5, player("03")), "obca drużyna");
        assertEquals(0, RankingCalculator.rankOf(entries, TOWN, 5, player("04")), "poniżej minimum");
    }

    @Test
    void noControllingTownMeansNobodyIsRanked() {
        assertTrue(RankingCalculator.top(List.of(entry("01", TOWN, 10, 100L)), null, 1, 5).isEmpty());
    }
}
