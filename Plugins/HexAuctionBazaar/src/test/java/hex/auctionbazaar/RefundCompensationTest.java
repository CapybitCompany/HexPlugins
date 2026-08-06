package hex.auctionbazaar;

import hex.auctionbazaar.util.RefundCompensation;
import hex.auctionbazaar.util.RefundCompensation.Status;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Punkt #2: kolejność kompensacji pieniężnej po nieudanym wystawieniu.
 * deposit OK -> REFUNDED; deposit fail + claim OK -> PENDING_CLAIM; oba fail -> FAILED.
 * Żadnego fire-and-forget: wynik jest deterministyczny i śledzony jako Future.
 */
class RefundCompensationTest {

    private static java.util.function.Supplier<CompletableFuture<Boolean>> ok(boolean v) {
        return () -> CompletableFuture.completedFuture(v);
    }

    private static java.util.function.Supplier<CompletableFuture<Boolean>> boom() {
        return () -> CompletableFuture.failedFuture(new RuntimeException("db down"));
    }

    @Test
    void depositSuccessMeansRefunded() {
        Status s = RefundCompensation.compensate(ok(true), ok(true)).join();
        assertEquals(Status.REFUNDED, s);
    }

    @Test
    void depositFailButClaimOkMeansPendingClaim() {
        Status s = RefundCompensation.compensate(ok(false), ok(true)).join();
        assertEquals(Status.PENDING_CLAIM, s);
    }

    @Test
    void depositAndClaimFailMeansFailed() {
        Status s = RefundCompensation.compensate(ok(false), ok(false)).join();
        assertEquals(Status.FAILED, s);
    }

    @Test
    void exceptionsAreTreatedAsFailure() {
        assertEquals(Status.PENDING_CLAIM, RefundCompensation.compensate(boom(), ok(true)).join());
        assertEquals(Status.FAILED, RefundCompensation.compensate(boom(), boom()).join());
    }

    @Test
    void nullFuturesAreTreatedAsFailure() {
        // Supplier zwracający null-future = niepowodzenie kroku (nigdy udawany sukces).
        java.util.function.Supplier<CompletableFuture<Boolean>> nul = () -> null;
        assertEquals(Status.FAILED, RefundCompensation.compensate(nul, nul).join());
        assertEquals(Status.PENDING_CLAIM, RefundCompensation.compensate(nul, ok(true)).join(),
                "null deposit -> próba money-claim; claim OK -> PENDING_CLAIM");
        assertEquals(Status.FAILED, RefundCompensation.compensate(ok(false), nul).join(),
                "deposit false + null money-claim -> FAILED");
    }

    @Test
    void throwingSupplierIsTreatedAsFailure() {
        java.util.function.Supplier<CompletableFuture<Boolean>> thrower = () -> {
            throw new RuntimeException("sync boom");
        };
        assertEquals(Status.FAILED, RefundCompensation.compensate(thrower, thrower).join());
        assertEquals(Status.REFUNDED, RefundCompensation.compensate(ok(true), thrower).join(),
                "deposit OK -> money-claim nie jest wołany (nie rzuca)");
    }

    @Test
    void resultStaysPendingUntilDepositCompletes() {
        // Punkt #1: dopóki zwrot (deposit) jest w toku, wynik pozostaje pending.
        CompletableFuture<Boolean> pendingDeposit = new CompletableFuture<>();
        var f = RefundCompensation.compensate(() -> pendingDeposit, ok(true));
        org.junit.jupiter.api.Assertions.assertFalse(f.isDone(),
                "wynik zwrotu czeka na zakończenie deposit");
        pendingDeposit.complete(true);
        org.junit.jupiter.api.Assertions.assertTrue(f.isDone());
        assertEquals(Status.REFUNDED, f.join());
    }

    @Test
    void claimNotAttemptedWhenDepositSucceeds() {
        AtomicInteger claimCalls = new AtomicInteger();
        RefundCompensation.compensate(ok(true), () -> {
            claimCalls.incrementAndGet();
            return CompletableFuture.completedFuture(true);
        }).join();
        assertEquals(0, claimCalls.get(), "gdy deposit OK, nie wołamy claim (brak podwójnej wypłaty)");
    }

    @Test
    void trackedOutcomeKeepsPendingMoneyClaimId() {
        var outcome = RefundCompensation.compensateTracked(ok(false),
                () -> CompletableFuture.completedFuture(42L)).join();
        assertEquals(Status.PENDING_CLAIM, outcome.status());
        assertEquals(42L, outcome.claimId());
    }

    @Test
    void trackedDirectRefundHasNoClaimIdAndDoesNotInsertClaim() {
        AtomicInteger claimCalls = new AtomicInteger();
        var outcome = RefundCompensation.compensateTracked(ok(true), () -> {
            claimCalls.incrementAndGet();
            return CompletableFuture.completedFuture(42L);
        }).join();
        assertEquals(Status.REFUNDED, outcome.status());
        assertNull(outcome.claimId());
        assertEquals(0, claimCalls.get());
    }

    @Test
    void trackedInvalidOrExceptionalClaimIsFailed() {
        var invalid = RefundCompensation.compensateTracked(ok(false),
                () -> CompletableFuture.completedFuture(-1L)).join();
        var exceptional = RefundCompensation.compensateTracked(ok(false),
                () -> CompletableFuture.failedFuture(new RuntimeException("db down"))).join();
        assertEquals(Status.FAILED, invalid.status());
        assertEquals(Status.FAILED, exceptional.status());
        assertNull(invalid.claimId());
        assertNull(exceptional.claimId());
    }
}
