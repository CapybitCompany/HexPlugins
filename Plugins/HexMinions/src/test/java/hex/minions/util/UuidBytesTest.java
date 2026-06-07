package hex.minions.util;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UuidBytesTest {
    @Test
    void uuidRoundTripsThroughBytes() {
        UUID uuid = UUID.randomUUID();
        assertEquals(uuid, UuidBytes.fromBytes(UuidBytes.toBytes(uuid)));
    }
}

