package hex.limbo.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IpHasherTest {

    @Test
    void sameIpAndPepperHashesIdentically() {
        IpHasher a = new IpHasher("pepper");
        IpHasher b = new IpHasher("pepper");
        assertEquals(a.hash("1.2.3.4"), b.hash("1.2.3.4"));
    }

    @Test
    void differentPepperChangesHash() {
        assertNotEquals(new IpHasher("pepper-1").hash("1.2.3.4"), new IpHasher("pepper-2").hash("1.2.3.4"));
    }

    @Test
    void blankInputReturnsNull() {
        IpHasher hasher = new IpHasher("pepper");
        assertNull(hasher.hash(null));
        assertNull(hasher.hash(""));
        assertNull(hasher.hash("   "));
    }
}
