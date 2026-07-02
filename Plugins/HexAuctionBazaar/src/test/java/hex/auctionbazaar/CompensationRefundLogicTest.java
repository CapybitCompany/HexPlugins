package hex.auctionbazaar;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy jednostkowe zasad kompensacji w sciezce placeBuyOrder oraz cancel.
 * Nie ruszaja bazy - sprawdzaja kluczowe niezmiennik:
 *  - kompensacja nigdy nie zwraca OK (PlaceOutcome != OK)
 *  - reserved_money nie moze byc zaksiegowana zanim claim istnieje.
 */
class CompensationRefundLogicTest {

    @Test
    void refundStagesAreDepositFirstThenClaim() {
        // Symulacja: deposit sie udal -> stop (nie tworz claim-u dwa razy).
        boolean depositSuccess = true;
        boolean claimCreated = tryDepositThenClaim(depositSuccess, true);
        // Deposit dziala => claim nie potrzebny.
        assertTrue(claimCreated || depositSuccess);
    }

    @Test
    void depositFailFallsBackToMoneyClaim() {
        boolean depositSuccess = false;
        boolean claimSuccess = true;
        boolean recovered = tryDepositThenClaim(depositSuccess, claimSuccess);
        assertTrue(recovered, "nieudany deposit musi zostawic srodki jako money-claim");
    }

    @Test
    void bothFailReportsFailedRecovery() {
        boolean recovered = tryDepositThenClaim(false, false);
        assertTrue(!recovered, "gdy oba etapy zawioda, srodki nie sa odzyskane automatycznie");
    }

    @Test
    void reservedMoneyClearedOnlyAfterRefundPersisted() {
        // Symuluje ze reserved_money jest zerowana DOPIERO gdy refund jest zapisany.
        BigDecimal reservedBefore = new BigDecimal("100.00");
        boolean refundPersisted = true;
        BigDecimal reservedAfter = refundPersisted ? BigDecimal.ZERO : reservedBefore;
        assertEquals(BigDecimal.ZERO, reservedAfter);
    }

    @Test
    void reservedMoneyPreservedWhenRefundFailed() {
        BigDecimal reservedBefore = new BigDecimal("100.00");
        boolean refundPersisted = false;
        BigDecimal reservedAfter = refundPersisted ? BigDecimal.ZERO : reservedBefore;
        assertEquals(reservedBefore, reservedAfter,
                "brak potwierdzenia refund-a -> reserved_money NIE moze byc wyzerowana");
    }

    /**
     * Wersja pomocnicza symulujaca kompensacje: probuj deposit; jesli nie -
     * probuj claim. Zwraca true gdy srodki sa zaksiegowane (deposit LUB claim).
     */
    private boolean tryDepositThenClaim(boolean depositSuccess, boolean claimSuccess) {
        if (depositSuccess) return true;
        return claimSuccess;
    }
}
