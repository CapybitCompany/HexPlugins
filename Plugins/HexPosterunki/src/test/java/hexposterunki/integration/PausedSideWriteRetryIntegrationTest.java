package hexposterunki.integration;

import hexposterunki.domain.RunPhase;
import hexposterunki.engine.EngineMode;
import hexposterunki.persistence.RewardClaim;
import hexposterunki.rewards.RewardComponent;
import hexposterunki.rewards.RewardPayload;
import hexposterunki.rewards.RewardService;
import hexposterunki.support.OutpostTestHarness;
import hexposterunki.support.ScriptedRewardExecutor;
import org.bukkit.entity.Player;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Side review: an outstanding reward status write is repeated even while the current run is stopped.
 *
 * <p>A reward earned in an earlier run is still handed over when its player joins, so its result has to
 * reach the database no matter what the current run does. The retry is driven by the persistence
 * lifecycle, so the test only lets time pass - it never calls {@code retryPendingSideWrites()} or a
 * sweep itself. The running case is the control; the failed one reproduced the defect.
 */
class PausedSideWriteRetryIntegrationTest {

    private static void cycles(OutpostTestHarness harness, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            harness.tick();
            Thread.sleep(5L);
        }
        harness.settle();
    }

    @ParameterizedTest(name = "nieudany run: {0}")
    @ValueSource(booleans = {false, true})
    void anEarnedRewardStatusSettlesEvenWhileTheCurrentRunIsStopped(boolean failedRun) throws Exception {
        try (OutpostTestHarness harness = new OutpostTestHarness()) {
            Player player = harness.server.addPlayer("Zwyciezca");
            RewardPayload payload = new RewardPayload(List.of(
                    RewardComponent.item("DIAMOND", 1, null, List.of()),
                    RewardComponent.item("DIAMOND", 1, null, List.of())));
            RewardClaim claim = new RewardClaim("poprzednia-wygrana", "fort", player.getUniqueId(), 1,
                    RewardClaim.Status.CLAIMED, 0, 0, payload.serialize(), null);
            harness.persistence.repository().insertClaims(List.of(claim), 10L);

            assertTrue(harness.engine.beginNewRun("fort", System.currentTimeMillis(), "test"));
            cycles(harness, 15);
            if (failedRun) {
                harness.engine.failRun("techniczny błąd bieżącego runu");
                cycles(harness, 5);
                assertEquals(RunPhase.FAILED, harness.engine.phase());
                assertFalse(harness.engine.operational());
                assertEquals(EngineMode.STOPPED, harness.engine.mode());
            }

            // The second component ends with an unknown outcome, and storing that result fails.
            ScriptedRewardExecutor executor = new ScriptedRewardExecutor();
            executor.failAt(1, false);
            AtomicBoolean broken = new AtomicBoolean();
            executor.afterDelivery(() -> {
                if (broken.compareAndSet(false, true)) {
                    try {
                        harness.sql("ALTER TABLE posterunki_reward_claims RENAME COLUMN last_error TO ukryty_blad");
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }
            });
            RewardService rewards = new RewardService(harness.plugin, harness.persistence, harness.ui, executor);

            rewards.deliverFor(player);   // joining still hands over claims of earlier runs
            cycles(harness, 20);
            assertTrue(harness.persistence.hasPendingSideWrites(), "ustalony wynik czeka na zapis");
            assertEquals(1, executor.delivered().size());

            executor.afterDelivery(() -> {
            });
            harness.sql("ALTER TABLE posterunki_reward_claims RENAME COLUMN ukryty_blad TO last_error");
            cycles(harness, 60);          // only time passes - no manual retry, no sweep call

            RewardClaim stored = harness.persistence.repository().loadClaim(claim.key()).orElseThrow();
            assertEquals(RewardClaim.Status.NEEDS_REVIEW, stored.status(),
                    "niepewna wypłata musi stać się administrowalna bez restartu");
            assertEquals(1, stored.progress(), "wydana część jest zapamiętana");
            assertEquals(1, harness.persistence.repository().loadClaims(RewardClaim.Status.NEEDS_REVIEW).size(),
                    "roszczenie jest widoczne dla administratora");
            assertFalse(harness.persistence.hasPendingSideWrites());
            assertEquals(1, executor.delivered().size(),
                    "ponawiany jest wyłącznie zapis statusu, nigdy sama wypłata");

            if (failedRun) {
                assertEquals(RunPhase.FAILED, harness.engine.phase(), "ponawianie zapisów nie wznawia eventu");
                assertFalse(harness.engine.operational());
                assertEquals(EngineMode.STOPPED, harness.engine.mode());
                assertTrue(harness.engine.liveMobs().isEmpty(), "nie powstały nowe fale ani bossowie");
            }
        }
    }
}
