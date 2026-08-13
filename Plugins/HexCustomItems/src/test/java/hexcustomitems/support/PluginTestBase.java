package hexcustomitems.support;

import hexcustomitems.HexCustomItemsPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Basis für Tests, die das vollständige Plugin unter MockBukkit laden.
 */
public abstract class PluginTestBase {

    protected ServerMock server;
    protected HexCustomItemsPlugin plugin;

    @BeforeEach
    void baseSetUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(HexCustomItemsPlugin.class);
    }

    @AfterEach
    void baseTearDown() {
        MockBukkit.unmock();
    }
}
