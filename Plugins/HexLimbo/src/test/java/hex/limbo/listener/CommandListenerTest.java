package hex.limbo.listener;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandListenerTest {

    @Test
    void extractsHeadCommand() {
        assertEquals("login", CommandListener.headOf("/login mypassword"));
        assertEquals("login", CommandListener.headOf("login"));
        assertEquals("register", CommandListener.headOf("/REGISTER a b"));
        assertEquals("", CommandListener.headOf(""));
        assertEquals("", CommandListener.headOf(null));
    }
}
