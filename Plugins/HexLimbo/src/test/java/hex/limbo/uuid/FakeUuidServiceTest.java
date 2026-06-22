package hex.limbo.uuid;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FakeUuidServiceTest {

    private final FakeUuidService service = new FakeUuidService();

    @Test
    void sameNameProducesSameUuid() {
        UUID first = service.forName("Alice");
        UUID second = service.forName("Alice");
        assertEquals(first, second);
    }

    @Test
    void differentNamesProduceDifferentUuids() {
        UUID alice = service.forName("Alice");
        UUID bob = service.forName("Bob");
        assertNotEquals(alice, bob);
    }

    @Test
    void caseDifferenceProducesDifferentUuids() {
        // Mirrors Spigot offline-mode behaviour where capitalization matters.
        UUID lower = service.forName("alice");
        UUID upper = service.forName("Alice");
        assertNotEquals(lower, upper);
    }

    @Test
    void emptyNameRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.forName(""));
    }

    @Test
    void matchesSpigotOfflineConvention() {
        // Mirrors the bukkit offline-UUID derivation: UUID.nameUUIDFromBytes("OfflinePlayer:" + name).
        UUID expected = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:Notch").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(expected, service.forName("Notch"));
    }
}
