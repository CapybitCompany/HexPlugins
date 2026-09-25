package hexposterunki.integration;

import hexposterunki.persistence.RewardClaim;
import hexposterunki.rewards.RewardComponent;
import hexposterunki.rewards.RewardPayload;
import hexposterunki.rewards.RewardService;
import hexposterunki.support.OutpostTestHarness;
import hexposterunki.support.ScriptedRewardExecutor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Final review, finding 2: the outcome of a delivery attempt survives a failed status write. The write
 * fails for real (a renamed column inside H2), is repeated without a restart, and never causes an item
 * to be handed over twice. Items are given out by the production {@code ItemRewardExecutor} behind a
 * scripted test double at the executor boundary.
 */
class DeliveryStatusRetryIntegrationTest {

    private OutpostTestHarness harness;
    private ScriptedRewardExecutor executor;
    private RewardService rewards;
    private Player player;
    private RewardClaim claim;

    @BeforeEach
    void setUp() throws Exception {
        harness = new OutpostTestHarness();
        executor = new ScriptedRewardExecutor();
        rewards = new RewardService(harness.plugin, harness.persistence, harness.ui, executor);
        player = harness.server.addPlayer("Zwyciezca");
    }

    @AfterEach
    void tearDown() throws Exception {
        harness.close();
    }

    /** Stores a CLAIMED claim with the given number of item components. */
    private void storeClaim(int components) {
        RewardPayload payload = new RewardPayload(java.util.stream.IntStream.range(0, components)
                .mapToObj(index -> RewardComponent.item("DIAMOND", 1, null, List.of())).toList());
        claim = new RewardClaim(UUID.randomUUID().toString(), "fort", player.getUniqueId(), 1,
                RewardClaim.Status.CLAIMED, 0, 0, payload.serialize(), null);
        harness.persistence.repository().insertClaims(List.of(claim), 10L);
    }

    private final java.util.concurrent.atomic.AtomicBoolean broken = new java.util.concurrent.atomic.AtomicBoolean();

    /** Breaks the status write exactly once, right after a component was handed over. */
    private Runnable breakStatusWritesOnce() {
        return () -> {
            if (broken.compareAndSet(false, true)) {
                try {
                    harness.sql("ALTER TABLE posterunki_reward_claims RENAME COLUMN last_error TO ukryty_blad");
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }
        };
    }

    private void repairStatusWrites() throws Exception {
        harness.sql("ALTER TABLE posterunki_reward_claims RENAME COLUMN ukryty_blad TO last_error");
    }

    /** Reads only the status column, so it also works while the error column is renamed away. */
    private String statusInDatabase() throws Exception {
        try (java.sql.PreparedStatement statement = harness.database().prepareStatement(
                "SELECT status FROM posterunki_reward_claims WHERE idempotency_key = ?")) {
            statement.setString(1, claim.key());
            try (java.sql.ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private void cycles(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            harness.tick();
            Thread.sleep(5L);
        }
        harness.settle();
    }

    private RewardClaim stored() {
        return harness.persistence.repository().loadClaim(claim.key()).orElseThrow();
    }

    private int diamonds() {
        return java.util.Arrays.stream(player.getInventory().getContents())
                .filter(stack -> stack != null && stack.getType() == Material.DIAMOND)
                .mapToInt(stack -> stack.getAmount()).sum();
    }

    @Test
    void aFailedFinalStatusWriteIsRepeatedWithoutDeliveringAnythingTwice() throws Exception {
        storeClaim(2);
        executor.afterDelivery(breakStatusWritesOnce());

        rewards.deliverFor(player);
        cycles(10);
        assertEquals(2, diamonds(), "obie części zostały wydane");
        assertEquals(RewardClaim.Status.DELIVERING.name(), statusInDatabase(), "status jeszcze nie zapisany");
        assertTrue(harness.persistence.hasPendingSideWrites(), "ustalony wynik czeka na zapis");

        executor.afterDelivery(() -> {
        });
        repairStatusWrites();
        for (int i = 0; i < 3; i++) {
            rewards.deliverOutstanding();     // a parallel attempt must not pay out again
            cycles(10);
        }

        RewardClaim persisted = stored();
        assertEquals(RewardClaim.Status.DELIVERED, persisted.status(), "zapis statusu został powtórzony");
        assertEquals(2, persisted.progress());
        assertEquals(2, diamonds(), "żaden przedmiot nie został wydany drugi raz");
        assertEquals(2, executor.delivered().size());
        assertFalse(harness.persistence.hasPendingSideWrites());
    }

    @Test
    void aPartialDeliveryKeepsItsProgressAndResumesWithTheRemainingComponent() throws Exception {
        storeClaim(3);
        executor.failAt(2, true);          // the third component provably did not run
        executor.afterDelivery(breakStatusWritesOnce());

        rewards.deliverFor(player);
        cycles(10);
        assertEquals(2, diamonds());

        executor.afterDelivery(() -> {
        });
        repairStatusWrites();
        cycles(10);

        RewardClaim afterRetry = stored();
        assertEquals(RewardClaim.Status.CLAIMED, afterRetry.status(), "sprawdzalny błąd pozwala na ponowienie");
        assertEquals(2, afterRetry.progress(), "wydane części są zapamiętane");

        executor.failAt(-1, true);
        rewards.deliverOutstanding();
        cycles(10);

        assertEquals(3, diamonds(), "brakująca część została wydana");
        assertEquals(List.of(claim.key() + "#0", claim.key() + "#1", claim.key() + "#2"), executor.delivered(),
                "już wydane części nie są powtarzane");
        assertEquals(RewardClaim.Status.DELIVERED, stored().status());
    }

    @Test
    void anUncertainOutcomeBecomesAdministrableAfterTheRepairWithoutARestart() throws Exception {
        storeClaim(2);
        executor.failAt(1, false);         // unknown whether the second component took effect
        executor.afterDelivery(breakStatusWritesOnce());

        rewards.deliverFor(player);
        cycles(10);
        assertEquals(RewardClaim.Status.DELIVERING.name(), statusInDatabase());

        executor.afterDelivery(() -> {
        });
        repairStatusWrites();
        cycles(10);

        assertEquals(RewardClaim.Status.NEEDS_REVIEW, stored().status(), "niepewny wynik trafia do decyzji admina");
        assertEquals(1, harness.persistence.repository().loadClaims(RewardClaim.Status.NEEDS_REVIEW).size(),
                "roszczenie jest widoczne na liście administratora bez restartu");
        assertEquals(1, diamonds());
        assertTrue(harness.persistence.repository().loadClaims(RewardClaim.Status.CLAIMED).isEmpty(),
                "niepewny wynik nie wraca automatycznie do wydania");
    }

    @Test
    void anAdminDecisionIsNeverOverwrittenByAnOlderPendingStatus() throws Exception {
        storeClaim(1);
        harness.persistence.repository().beginDelivery(claim.key(), 20L);

        Runnable release = harness.holdDatabase();
        try {
            harness.persistence.finishDelivery(claim.key(), RewardClaim.Status.NEEDS_REVIEW, 0, "nieznany wynik");
            harness.persistence.finishDelivery(claim.key(), RewardClaim.Status.CLAIMED, 0, "decyzja administratora");
        } finally {
            release.run();
        }
        cycles(10);

        RewardClaim persisted = stored();
        assertEquals(RewardClaim.Status.CLAIMED, persisted.status(), "decyzja administratora jest ostateczna");
        assertEquals("decyzja administratora", persisted.lastError());
        assertFalse(harness.persistence.hasPendingSideWrites());
    }
}
