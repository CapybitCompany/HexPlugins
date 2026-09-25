package hexposterunki.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Death, leaving the region, a normal logout, a world change and a teleport out all funnel into
 * {@link KillLedger#reset(UUID)}, so one test covers every one of those triggers.
 */
class KillLedgerTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Test
    void killsAccumulateAndCarryTheMomentTheyWereReached() {
        KillLedger ledger = new KillLedger();
        ledger.recordKill(PLAYER, TOWN, 100L);
        KillEntry entry = ledger.recordKill(PLAYER, TOWN, 250L);

        assertEquals(2, entry.kills());
        assertEquals(250L, entry.achievedAtMillis());
        assertEquals(2, ledger.kills(PLAYER));
    }

    @Test
    void resetDropsTheWholeProgressOfOnePlayerOnly() {
        KillLedger ledger = new KillLedger();
        UUID other = UUID.randomUUID();
        ledger.recordKill(PLAYER, TOWN, 100L);
        ledger.recordKill(other, TOWN, 100L);

        assertTrue(ledger.reset(PLAYER));
        assertEquals(0, ledger.kills(PLAYER));
        assertEquals(1, ledger.kills(other));
    }

    @Test
    void resettingAPlayerWithoutProgressReportsNothingToAnnounce() {
        assertFalse(new KillLedger().reset(PLAYER));
    }

    @Test
    void progressStartsFromZeroAfterAReset() {
        KillLedger ledger = new KillLedger();
        ledger.recordKill(PLAYER, TOWN, 100L);
        ledger.recordKill(PLAYER, TOWN, 110L);
        ledger.reset(PLAYER);

        KillEntry entry = ledger.recordKill(PLAYER, TOWN, 400L);
        assertEquals(1, entry.kills());
        assertEquals(400L, entry.achievedAtMillis());
    }

    @Test
    void loadReplacesTheLedgerForRecovery() {
        KillLedger ledger = new KillLedger();
        ledger.recordKill(PLAYER, TOWN, 100L);
        UUID restored = UUID.randomUUID();

        ledger.load(List.of(new KillEntry(restored, TOWN, 4, 900L)));

        assertEquals(0, ledger.kills(PLAYER));
        assertEquals(4, ledger.kills(restored));
        assertEquals(1, ledger.entries().size());
    }
}
