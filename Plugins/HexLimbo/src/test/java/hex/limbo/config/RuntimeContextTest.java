package hex.limbo.config;

import hex.limbo.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeContextTest {

    @Test
    void initialValuesReadable() {
        RuntimeContext context = new RuntimeContext(TestConfigs.defaultConfig(), new MessagesConfig(Map.of("hello", "world")));
        assertEquals("hexlimbo-limbo", context.config().limboServer());
        assertEquals("world", context.messages().raw("hello"));
    }

    @Test
    void updateSwapsLiveReferences() {
        RuntimeContext context = new RuntimeContext(TestConfigs.defaultConfig(), new MessagesConfig(Map.of()));
        PluginConfig changed = TestConfigs.withServers("waiting", "main");
        MessagesConfig newMessages = new MessagesConfig(Map.of("msg.key", "value-after-reload"));
        context.update(changed, newMessages);
        assertEquals("waiting", context.config().limboServer());
        assertEquals("main", context.config().targetServer());
        assertEquals("value-after-reload", context.messages().raw("msg.key"));
    }

    @Test
    void listenersReadThroughLiveContextRatherThanCachedRefs() {
        // Simulates how a listener should consume the context: read through context.config() each
        // call rather than capturing a config snapshot at construction time.
        RuntimeContext context = new RuntimeContext(TestConfigs.defaultConfig(), new MessagesConfig(Map.of()));
        Object captureA = context.config();
        context.update(TestConfigs.withServers("a", "b"), new MessagesConfig(Map.of()));
        Object captureB = context.config();
        assertTrue(captureA != captureB, "Updating the context must replace the held reference.");
        assertSame(context.config(), captureB);
    }
}
