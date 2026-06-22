package hex.limbo.account;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryAccountRepositoryTest {

    private final InMemoryAccountRepository repo = new InMemoryAccountRepository();

    private Account candidate(String name) {
        return new Account(
                0L,
                name.toLowerCase(),
                name,
                AccountType.CRACKED,
                UUID.nameUUIDFromBytes(name.getBytes()),
                null,
                "hash",
                123L,
                null,
                "iphash-1",
                0,
                null
        );
    }

    @Test
    void createAssignsId() {
        Account stored = repo.create(candidate("Alice"));
        assertTrue(stored.id() > 0);
    }

    @Test
    void cannotInsertDuplicateUsername() {
        repo.create(candidate("Alice"));
        assertThrows(IllegalStateException.class, () -> repo.create(candidate("Alice")));
    }

    @Test
    void findByUsernameIsCaseInsensitive() {
        repo.create(candidate("Alice"));
        Optional<Account> hit = repo.findByUsername("ALICE");
        assertTrue(hit.isPresent());
        assertEquals("alice", hit.get().usernameLower());
    }

    @Test
    void recordSuccessfulLoginResetsFailures() {
        Account stored = repo.create(candidate("Alice"));
        repo.updateFailedAttempts(stored.id(), 4, 1_000L);
        repo.recordSuccessfulLogin(stored.id(), 9999L, "iphash-2", "Alice");
        Account refreshed = repo.findByUsername("alice").orElseThrow();
        assertEquals(0, refreshed.failedAttempts());
        assertNull(refreshed.lockedUntil());
        assertEquals(9999L, refreshed.lastLoginAt());
        assertEquals("iphash-2", refreshed.lastIpHash());
    }

    @Test
    void countByIpAggregates() {
        repo.create(candidate("Alice"));
        repo.create(candidate("Bob"));
        Account third = candidate("Carl");
        third.setLastIpHash("iphash-2");
        repo.create(third);
        assertEquals(2, repo.countByIp("iphash-1"));
        assertEquals(1, repo.countByIp("iphash-2"));
    }
}
