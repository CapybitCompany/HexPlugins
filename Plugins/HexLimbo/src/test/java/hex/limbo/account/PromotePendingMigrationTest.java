package hex.limbo.account;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ensures {@link InMemoryAccountRepository#promotePendingMigrationToPremium} flips every field at
 * once, leaving no observable half-migrated state. The SQL implementation makes the same change
 * in a single UPDATE so it has the same row-atomic guarantees.
 */
class PromotePendingMigrationTest {

    private Account pendingMigration(String name) {
        return new Account(
                0L,
                name.toLowerCase(),
                name,
                AccountType.PENDING_MIGRATION,
                UUID.nameUUIDFromBytes(("offline:" + name).getBytes()),
                null,
                "old-hash",
                100L,
                100L,
                "ip-hash-old",
                3,
                999L
        );
    }

    @Test
    void migrationReturnsTrueAndSwapsAllFields() {
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        Account stored = repo.create(pendingMigration("Alice"));
        UUID realUuid = UUID.fromString("11111111-2222-3333-4444-555555555555");

        boolean promoted = repo.promotePendingMigrationToPremium(stored.id(), realUuid, 5_000L, "ip-hash-new", "Alice");
        assertTrue(promoted, "Successful promotion must return true.");

        Account refreshed = repo.findByUsername("alice").orElseThrow();
        assertEquals(AccountType.PREMIUM, refreshed.accountType());
        assertEquals(realUuid, refreshed.uuid());
        assertEquals(realUuid, refreshed.premiumUuid());
        assertEquals(5_000L, refreshed.lastLoginAt());
        assertEquals("ip-hash-new", refreshed.lastIpHash());
        assertEquals("Alice", refreshed.lastUsername());
        assertEquals(0, refreshed.failedAttempts());
        assertNull(refreshed.lockedUntil());
        assertTrue(repo.findByUuid(realUuid).isPresent());
        assertTrue(repo.findByUuid(UUID.nameUUIDFromBytes("offline:Alice".getBytes())).isEmpty(),
                "Old offline UUID must no longer map to this row.");
    }

    @Test
    void migrationReturnsFalseForNonPendingAccounts() {
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        Account cracked = new Account(
                0L, "bob", "Bob",
                AccountType.CRACKED,
                UUID.nameUUIDFromBytes("offline:Bob".getBytes()),
                null, "hash", 1L, 1L, "ip", 0, null
        );
        Account stored = repo.create(cracked);
        UUID realUuid = UUID.fromString("11111111-2222-3333-4444-666666666666");

        boolean promoted = repo.promotePendingMigrationToPremium(stored.id(), realUuid, 5_000L, "ip-new", "Bob");
        assertFalse(promoted, "Promotion of a non-PENDING_MIGRATION row must return false.");

        Account refreshed = repo.findByUsername("bob").orElseThrow();
        assertEquals(AccountType.CRACKED, refreshed.accountType(),
                "Migration must only act on PENDING_MIGRATION rows.");
        assertTrue(repo.findByUuid(realUuid).isEmpty());
    }

    @Test
    void migrationReturnsFalseForUnknownId() {
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        boolean promoted = repo.promotePendingMigrationToPremium(9_999L, UUID.randomUUID(), 1L, "ip", "Ghost");
        assertFalse(promoted, "Promotion of an unknown id must return false.");
    }

    @Test
    void migrationLosesRaceWhenAnotherCallerAlreadyPromoted() {
        // Simulates the race the LoginListener guards against: between findByUsername and the
        // promote call, a concurrent login on the same name has already flipped the row to
        // PREMIUM. Our promote must return false so the listener denies the join.
        InMemoryAccountRepository repo = new InMemoryAccountRepository();
        Account stored = repo.create(pendingMigration("Carol"));
        repo.updateAccountType(stored.id(), AccountType.PREMIUM);

        UUID realUuid = UUID.fromString("11111111-2222-3333-4444-777777777777");
        boolean promoted = repo.promotePendingMigrationToPremium(stored.id(), realUuid, 5_000L, "ip", "Carol");
        assertFalse(promoted);
    }
}
