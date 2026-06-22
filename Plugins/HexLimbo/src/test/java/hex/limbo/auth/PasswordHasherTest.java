package hex.limbo.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher(4);

    @Test
    void hashIsNotPlaintext() {
        String hashed = hasher.hash("hunter2");
        assertNotEquals("hunter2", hashed);
        assertTrue(hashed.startsWith("$2"));
    }

    @Test
    void verifyAcceptsCorrectPassword() {
        String hashed = hasher.hash("supersecret");
        assertTrue(hasher.verify("supersecret", hashed));
    }

    @Test
    void verifyRejectsWrongPassword() {
        String hashed = hasher.hash("supersecret");
        assertFalse(hasher.verify("nope", hashed));
    }

    @Test
    void verifyHandlesNulls() {
        assertFalse(hasher.verify(null, "anything"));
        assertFalse(hasher.verify("x", null));
        assertFalse(hasher.verify("x", ""));
    }

    @Test
    void differentSaltsProduceDifferentHashes() {
        String first = hasher.hash("same");
        String second = hasher.hash("same");
        assertNotEquals(first, second);
        assertTrue(hasher.verify("same", first));
        assertTrue(hasher.verify("same", second));
    }
}
