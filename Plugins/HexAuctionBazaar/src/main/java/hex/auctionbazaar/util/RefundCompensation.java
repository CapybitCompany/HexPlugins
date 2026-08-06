package hex.auctionbazaar.util;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Kolejność kompensacji pieniężnej po nieudanym kroku (np. insert aukcji po
 * pobraniu opłaty+podatku). Nie jest to fire-and-forget: cała ścieżka jest
 * śledzona jako {@link CompletableFuture} i kończy się dopiero po ustaleniu wyniku.
 *
 * Kolejność (punkt #2):
 *  1. spróbuj zwrócić całą kwotę przez Economy (deposit),
 *  2. jeśli deposit się nie uda -> utwórz trwały money-claim,
 *  3. jeśli i to zawiedzie -> {@link Status#FAILED} (caller loguje SEVERE).
 *
 * Funkcja jest czysta względem efektów (efekty wstrzykiwane jako suppliery
 * zwracające {@code CompletableFuture<Boolean>} = sukces), więc jest testowalna.
 */
public final class RefundCompensation {

    public enum Status { REFUNDED, PENDING_CLAIM, FAILED }

    /**
     * Rozszerzony wynik kompensacji używany tam, gdzie identyfikator trwałego
     * money-claimu musi trafić do audytu. {@code claimId} jest ustawione wyłącznie
     * dla {@link Status#PENDING_CLAIM}.
     */
    public record TrackedOutcome(Status status, Long claimId) {
        public TrackedOutcome {
            if (status == null) status = Status.FAILED;
            if (status != Status.PENDING_CLAIM) claimId = null;
        }
    }

    private RefundCompensation() {
    }

    public static CompletableFuture<Status> compensate(
            Supplier<CompletableFuture<Boolean>> deposit,
            Supplier<CompletableFuture<Boolean>> moneyClaim) {
        return safe(deposit).thenCompose(depositOk -> {
            if (Boolean.TRUE.equals(depositOk)) {
                return CompletableFuture.completedFuture(Status.REFUNDED);
            }
            return safe(moneyClaim).thenApply(claimOk ->
                    Boolean.TRUE.equals(claimOk) ? Status.PENDING_CLAIM : Status.FAILED);
        });
    }

    /**
     * Wariant zachowujący identyfikator money-claimu do audytu. Deposit jest
     * uznany za sukces tylko dla wartości {@code true}; claim tylko dla ID >= 0.
     * Supplier, null-future i future zakończony wyjątkiem dają bezpieczny status
     * FAILED. Każdy efekt jest uruchamiany najwyżej raz.
     */
    public static CompletableFuture<TrackedOutcome> compensateTracked(
            Supplier<CompletableFuture<Boolean>> deposit,
            Supplier<CompletableFuture<Long>> moneyClaim) {
        return safe(deposit).thenCompose(depositOk -> {
            if (Boolean.TRUE.equals(depositOk)) {
                return CompletableFuture.completedFuture(new TrackedOutcome(Status.REFUNDED, null));
            }
            return safeClaim(moneyClaim).thenApply(claimId ->
                    claimId != null && claimId >= 0
                            ? new TrackedOutcome(Status.PENDING_CLAIM, claimId)
                            : new TrackedOutcome(Status.FAILED, null));
        });
    }

    /** Każdy wyjątek/synchroniczny błąd traktujemy jak niepowodzenie kroku. */
    private static CompletableFuture<Boolean> safe(Supplier<CompletableFuture<Boolean>> step) {
        try {
            CompletableFuture<Boolean> f = step.get();
            return f == null ? CompletableFuture.completedFuture(false)
                    : f.exceptionally(ex -> false);
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(false);
        }
    }

    private static CompletableFuture<Long> safeClaim(Supplier<CompletableFuture<Long>> step) {
        try {
            CompletableFuture<Long> f = step.get();
            return f == null ? CompletableFuture.completedFuture(null)
                    : f.exceptionally(ex -> null);
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
