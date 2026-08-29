package hex.limbo.limbo;

import hex.limbo.config.PluginConfig;
import hex.limbo.limbo.server.MinecraftLimboServer;
import hex.limbo.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Black-box lifecycle tests for {@link MinecraftLimboServer}. We deliberately don't speak the
 * Minecraft protocol here – just verify the server binds the port, exposes the right
 * {@link LimboServer} fields, and handles port-conflict by refusing to start.
 */
class MinecraftLimboServerLifecycleTest {

    /**
     * A port that was free a moment ago.
     *
     * <p>Only safe for a test that hands the port straight to the server it is about to start:
     * between this socket closing and that bind there is a window nothing can close from the test
     * side. A test that wants to <em>hold</em> a port must bind it and keep it, never ask here and
     * bind again later - see {@link #portConflictReportsLastStartError()}.
     */
    private int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void startsAndStops() throws IOException {
        PluginConfig.Limbo limbo = TestConfigs.withLimboPort(freePort()).limbo();
        MinecraftLimboServer server = new MinecraftLimboServer(limbo, LoggerFactory.getLogger(MinecraftLimboServerLifecycleTest.class));
        try {
            server.start();
            server.awaitReady(2_000L);
            assertTrue(server.isReady(), "Server should be ready after start.");
            assertEquals(0, server.activeConnectionCount());
            assertEquals(limbo.serverName(), server.serverName());
            assertEquals(limbo.bindHost(), server.bindHost());
            assertEquals(limbo.bindPort(), server.bindPort());
        } finally {
            server.stop();
        }
        assertFalse(server.isReady(), "Server should not be ready after stop.");
    }

    /**
     * The port has to be occupied for the whole of {@code start()}, so it is <b>bound first and
     * read afterwards</b>: the blocking socket asks the OS for an ephemeral port on the very
     * address the server will use, and {@code getLocalPort()} reports what it actually got.
     *
     * <p>Asking {@link #freePort()} for a number, closing that socket and binding the port again
     * here is what this test used to do, and it is a time-of-check/time-of-use race: any other test
     * or process on the machine may take the port in between, and the conflict this test wants to
     * stage turns into a {@code BindException} from the test itself. Binding once removes the
     * window entirely - there is no moment in which the port is unowned.
     */
    @Test
    void portConflictReportsLastStartError() throws IOException {
        try (ServerSocket hog = new ServerSocket()) {
            hog.bind(new InetSocketAddress("127.0.0.1", 0));
            assertTrue(hog.isBound());
            PluginConfig.Limbo limbo = TestConfigs.withLimboPort(hog.getLocalPort()).limbo();
            MinecraftLimboServer server = new MinecraftLimboServer(limbo, LoggerFactory.getLogger(MinecraftLimboServerLifecycleTest.class));
            try {
                server.start();
                assertFalse(server.isReady(), "Server must not report ready when port bind fails.");
                assertTrue(server.lastStartError().isPresent(), "lastStartError must be populated.");
            } finally {
                server.stop();
            }
        }
    }

    @Test
    void idempotentStartAndStop() throws IOException {
        PluginConfig.Limbo limbo = TestConfigs.withLimboPort(freePort()).limbo();
        MinecraftLimboServer server = new MinecraftLimboServer(limbo, LoggerFactory.getLogger(MinecraftLimboServerLifecycleTest.class));
        try {
            server.start();
            server.start();
            server.awaitReady(2_000L);
            assertTrue(server.isReady());
        } finally {
            server.stop();
            server.stop();
        }
    }
}
