package hexposterunki.rewards;

import hexposterunki.config.RewardsConfig;
import hexposterunki.persistence.PosterunkiRepository;
import hexposterunki.persistence.RewardClaim;
import hexposterunki.persistence.SqlTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding 4 regression against real SQL: entitlements are frozen, survive restarts, resume for
 * offline players, and an interrupted delivery is parked instead of silently replayed or dropped.
 */
class RewardClaimSqlTest {

    private static final String RUN = "run-1";
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private Connection connection;
    private PosterunkiRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connection = SqlTestSupport.open();
        repository = SqlTestSupport.freshRepository(connection);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    private RewardClaim claim(int place, String payload) {
        return new RewardClaim(RUN, "fort", PLAYER, place, RewardClaim.Status.CLAIMED, 0, 0, payload, null);
    }

    private static String payloadOf(RewardsConfig.PlaceReward reward) {
        return RewardPayload.from(reward).serialize();
    }

    @Test
    void claimsAreInsertedOnceAndNeverOverwritten() {
        String payload = payloadOf(new RewardsConfig.PlaceReward(
                List.of(new RewardsConfig.ItemReward("DIAMOND", 3, null, List.of())), List.of()));
        assertEquals(1, repository.insertClaims(List.of(claim(1, payload)), 10L));

        // A replayed completion or a repeated recovery must not change what was earned.
        String changed = payloadOf(new RewardsConfig.PlaceReward(
                List.of(new RewardsConfig.ItemReward("DIRT", 1, null, List.of())), List.of()));
        assertEquals(0, repository.insertClaims(List.of(claim(1, changed)), 20L));

        RewardClaim stored = repository.loadClaim(RewardClaim.key(RUN, PLAYER, 1)).orElseThrow();
        assertTrue(RewardPayload.deserialize(stored.payload()).components().get(0).value().contains("DIAMOND"),
                "zamrożona treść nagrody nie zmienia się po edycji konfiguracji");
    }

    @Test
    void deliveryIsHandedOverToExactlyOneCaller() {
        repository.insertClaims(List.of(claim(1, payloadOf(new RewardsConfig.PlaceReward(
                List.of(), List.of("eco give <player> 100"))))), 10L);
        String key = RewardClaim.key(RUN, PLAYER, 1);

        Optional<RewardClaim> first = repository.beginDelivery(key, 20L);
        Optional<RewardClaim> second = repository.beginDelivery(key, 21L);

        assertTrue(first.isPresent());
        assertTrue(second.isEmpty(), "drugie przejęcie tego samego roszczenia musi zostać odrzucone");
        assertEquals(1, first.get().attempts());
        assertEquals(RewardClaim.Status.DELIVERING, first.get().status());
    }

    @Test
    void aCrashDuringDeliveryParksTheClaimForAnAdministrator() {
        repository.insertClaims(List.of(claim(1, payloadOf(new RewardsConfig.PlaceReward(
                List.of(), List.of("eco give <player> 100"))))), 10L);
        String key = RewardClaim.key(RUN, PLAYER, 1);
        repository.beginDelivery(key, 20L);

        // Server dies here. Startup quarantines everything still marked DELIVERING.
        assertEquals(1, repository.quarantineInterruptedDeliveries(30L));

        RewardClaim stored = repository.loadClaim(key).orElseThrow();
        assertEquals(RewardClaim.Status.NEEDS_REVIEW, stored.status());
        assertFalse(stored.lastError() == null || stored.lastError().isBlank(),
                "powód musi być widoczny dla administratora");
        assertTrue(repository.loadClaims(RewardClaim.Status.CLAIMED).isEmpty(),
                "niepewna nagroda nie wraca automatycznie do wydania");
    }

    @Test
    void anOfflinePlayerKeepsTheClaimUntilTheyReturn() {
        repository.insertClaims(List.of(claim(1, payloadOf(new RewardsConfig.PlaceReward(
                List.of(new RewardsConfig.ItemReward("DIAMOND", 1, null, List.of())), List.of())))), 10L);
        String key = RewardClaim.key(RUN, PLAYER, 1);

        // Delivery started while the player was gone, so it is handed straight back.
        repository.beginDelivery(key, 20L);
        repository.finishDelivery(key, RewardClaim.Status.CLAIMED, 0, "gracz offline", 21L);

        List<RewardClaim> outstanding = repository.loadClaimsForPlayer(PLAYER, RewardClaim.Status.CLAIMED);
        assertEquals(1, outstanding.size(), "roszczenie czeka na powrót gracza");
        assertEquals(0, outstanding.get(0).progress());
        assertEquals(1, outstanding.get(0).attempts());
    }

    @Test
    void aPartialDeliveryResumesWithTheRemainingComponents() {
        String payload = payloadOf(new RewardsConfig.PlaceReward(
                List.of(new RewardsConfig.ItemReward("DIAMOND", 1, null, List.of())),
                List.of("eco give <player> 100", "broadcast <player> wygral")));
        repository.insertClaims(List.of(claim(1, payload)), 10L);
        String key = RewardClaim.key(RUN, PLAYER, 1);

        repository.beginDelivery(key, 20L);
        // Item plus first command went through, the second command failed provably.
        repository.finishDelivery(key, RewardClaim.Status.CLAIMED, 2, "komenda nieobsłużona", 21L);

        RewardClaim resumed = repository.beginDelivery(key, 30L).orElseThrow();
        assertEquals(2, resumed.progress(), "już wydane części nie są powtarzane");
        assertEquals(3, RewardPayload.deserialize(resumed.payload()).size());
        assertEquals(2, resumed.attempts());
    }

    @Test
    void repeatedRecoveryDoesNotDuplicateAConfirmedPayout() {
        repository.insertClaims(List.of(claim(1, payloadOf(new RewardsConfig.PlaceReward(
                List.of(new RewardsConfig.ItemReward("DIAMOND", 1, null, List.of())), List.of())))), 10L);
        String key = RewardClaim.key(RUN, PLAYER, 1);
        repository.beginDelivery(key, 20L);
        repository.finishDelivery(key, RewardClaim.Status.DELIVERED, 1, null, 21L);

        // Two further recovery passes.
        assertEquals(0, repository.quarantineInterruptedDeliveries(30L));
        assertTrue(repository.beginDelivery(key, 31L).isEmpty());
        assertTrue(repository.loadClaims(RewardClaim.Status.CLAIMED).isEmpty());
        assertEquals(RewardClaim.Status.DELIVERED, repository.loadClaim(key).orElseThrow().status());
    }

    @Test
    void anAdministratorCanReleaseOrCloseAParkedClaim() {
        repository.insertClaims(List.of(claim(1, payloadOf(new RewardsConfig.PlaceReward(
                List.of(), List.of("eco give <player> 100"))))), 10L);
        String key = RewardClaim.key(RUN, PLAYER, 1);
        repository.beginDelivery(key, 20L);
        repository.quarantineInterruptedDeliveries(30L);

        assertEquals(1, repository.loadClaims(RewardClaim.Status.NEEDS_REVIEW).size());

        // "retry": back into the normal delivery queue.
        repository.finishDelivery(key, RewardClaim.Status.CLAIMED, 0, "decyzja administratora", 40L);
        assertEquals(1, repository.loadClaims(RewardClaim.Status.CLAIMED).size());

        // "resolve": closed without another payout.
        repository.finishDelivery(key, RewardClaim.Status.DELIVERED, 0, "decyzja administratora", 50L);
        assertTrue(repository.loadClaims(RewardClaim.Status.NEEDS_REVIEW).isEmpty());
        assertTrue(repository.loadClaims(RewardClaim.Status.CLAIMED).isEmpty());
    }

    @Test
    void differentPlacesAndRunsAreIndependentClaims() {
        String payload = payloadOf(new RewardsConfig.PlaceReward(
                List.of(new RewardsConfig.ItemReward("DIAMOND", 1, null, List.of())), List.of()));
        assertEquals(2, repository.insertClaims(List.of(claim(1, payload), claim(2, payload)), 10L));
        assertEquals(1, repository.insertClaims(List.of(
                new RewardClaim("run-2", "fort", PLAYER, 1, RewardClaim.Status.CLAIMED, 0, 0, payload, null)), 11L));
        assertEquals(3, repository.loadClaims(RewardClaim.Status.CLAIMED).size());
    }
}
