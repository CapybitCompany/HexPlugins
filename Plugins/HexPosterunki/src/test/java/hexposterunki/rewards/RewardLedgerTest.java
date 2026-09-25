package hexposterunki.rewards;

import hexposterunki.persistence.RewardClaim;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review finding 4, in-memory part: freezing remembers what was frozen instead of acting as a lock
 * that a failed write could leave behind.
 */
class RewardLedgerTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static FrozenRewards frozen(String payload) {
        return new FrozenRewards("run-1", List.of(), List.of(
                new RewardClaim("run-1", "fort", PLAYER, 1, RewardClaim.Status.CLAIMED, 0, 0, payload, null)));
    }

    @Test
    void aRepeatedFreezeReturnsTheOriginallyFrozenClaims() {
        RewardLedger ledger = new RewardLedger();
        FrozenRewards first = ledger.freeze("run-1", () -> frozen("DIAMOND"));
        FrozenRewards second = ledger.freeze("run-1", () -> frozen("DIRT"));

        assertSame(first, second, "ponowne zamrożenie zwraca te same roszczenia");
        assertEquals("DIAMOND", second.claims().get(0).payload());
        assertTrue(ledger.isFrozen("run-1"));
    }

    @Test
    void freezingIsNotALockOnTheClaims() {
        RewardLedger ledger = new RewardLedger();
        ledger.freeze("run-1", () -> frozen("DIAMOND"));

        // Nothing here blocks a later write: the frozen claims stay available for every retry.
        assertEquals(1, ledger.frozen("run-1").orElseThrow().claims().size());
        ledger.clear();
        assertFalse(ledger.isFrozen("run-1"));
    }
}
