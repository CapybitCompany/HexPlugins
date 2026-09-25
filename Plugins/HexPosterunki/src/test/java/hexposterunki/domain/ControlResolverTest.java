package hexposterunki.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlResolverTest {

    private static final UUID TOWN_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID TOWN_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID TOWN_C = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    private static Presence presence(UUID town, long enteredAt, String playerSuffix) {
        return new Presence(UUID.fromString("00000000-0000-0000-0000-0000000000" + playerSuffix), town, enteredAt);
    }

    @Test
    void emptyRegionResolvesToNobody() {
        assertTrue(ControlResolver.resolve(TOWN_A, List.of()).isEmpty());
        assertTrue(ControlResolver.resolve(null, List.of()).isEmpty());
    }

    @Test
    void firstTownInsideClaimsAnUnclaimedOutpost() {
        Optional<UUID> resolved = ControlResolver.resolve(null, List.of(
                presence(TOWN_B, 500L, "02"),
                presence(TOWN_A, 100L, "01")));
        assertEquals(TOWN_A, resolved.orElseThrow());
    }

    @Test
    void controllerKeepsControlWhileAnyMemberRemains() {
        List<Presence> present = List.of(
                presence(TOWN_A, 900L, "01"),
                presence(TOWN_B, 100L, "02"));
        // Town B entered earlier, but the controller still has someone inside.
        assertEquals(TOWN_A, ControlResolver.resolve(TOWN_A, present).orElseThrow());
        assertFalse(ControlResolver.isTakeover(TOWN_A, present));
    }

    @Test
    void foreignTownTakesOverOnceTheControllerIsPushedOut() {
        List<Presence> present = List.of(presence(TOWN_B, 100L, "02"));
        assertEquals(TOWN_B, ControlResolver.resolve(TOWN_A, present).orElseThrow());
        assertTrue(ControlResolver.isTakeover(TOWN_A, present));
    }

    @Test
    void earliestPresentPlayerDecidesBetweenSeveralCandidates() {
        List<Presence> present = List.of(
                presence(TOWN_C, 300L, "03"),
                presence(TOWN_B, 150L, "02"),
                presence(TOWN_C, 120L, "04"));
        // Town C's earliest currently present player entered at 120 - earlier than Town B's 150.
        assertEquals(TOWN_C, ControlResolver.resolve(TOWN_A, present).orElseThrow());
    }

    @Test
    void equalEntryTimesFallBackToTheStablePlayerUuid() {
        List<Presence> present = List.of(
                presence(TOWN_C, 200L, "0c"),
                presence(TOWN_B, 200L, "0b"));
        UUID first = ControlResolver.resolve(null, present).orElseThrow();
        UUID second = ControlResolver.resolve(null, List.of(present.get(1), present.get(0))).orElseThrow();
        assertEquals(first, second, "kolejność wejściowa nie może zmieniać wyniku");
        assertEquals(TOWN_B, first);
    }
}
