package hexdailyrewards.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlRewardStorageTest {

    @TempDir
    File tempDir;

    @Test
    void shouldPersistClaimDate() throws Exception {
        UUID playerId = UUID.randomUUID();
        File file = new File(tempDir, "claims.yml");

        YamlRewardStorage storage = new YamlRewardStorage(file);
        storage.load();
        storage.markClaimed(playerId, "Tester", LocalDate.of(2026, 7, 20),
                Instant.parse("2026-07-20T10:15:30Z"));

        YamlRewardStorage reloaded = new YamlRewardStorage(file);
        reloaded.load();

        assertTrue(reloaded.lastClaimDate(playerId).isPresent());
        assertEquals(LocalDate.of(2026, 7, 20), reloaded.lastClaimDate(playerId).get());
    }

    @Test
    void shouldPersistClaimDatesPerRewardGroup() throws Exception {
        UUID playerId = UUID.randomUUID();
        File file = new File(tempDir, "claims.yml");

        YamlRewardStorage storage = new YamlRewardStorage(file);
        storage.load();
        storage.markClaimed(playerId, "Tester", "default", LocalDate.of(2026, 7, 20),
                Instant.parse("2026-07-20T10:15:30Z"));
        storage.markClaimed(playerId, "Tester", "vip", LocalDate.of(2026, 7, 21),
                Instant.parse("2026-07-21T10:15:30Z"));

        YamlRewardStorage reloaded = new YamlRewardStorage(file);
        reloaded.load();

        assertEquals(LocalDate.of(2026, 7, 20), reloaded.lastClaimDate(playerId, "default").orElseThrow());
        assertEquals(LocalDate.of(2026, 7, 21), reloaded.lastClaimDate(playerId, "vip").orElseThrow());
    }
}
