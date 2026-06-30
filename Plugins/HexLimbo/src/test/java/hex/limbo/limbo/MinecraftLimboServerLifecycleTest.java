package hex.limbo.limbo;

import hex.limbo.config.PluginConfig;
import hex.limbo.limbo.server.MinecraftLimboServer;
import hex.limbo.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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

    @Test
    void portConflictReportsLastStartError() throws IOException {
        int port = freePort();
        ServerSocket hog = new ServerSocket();
        try {
            hog.bind(new java.net.InetSocketAddress("127.0.0.1", port));
            assertTrue(hog.isBound());
            PluginConfig.Limbo limbo = TestConfigs.withLimboPort(port).limbo();
            MinecraftLimboServer server = new MinecraftLimboServer(limbo, LoggerFactory.getLogger(MinecraftLimboServerLifecycleTest.class));
            try {
                server.start();
                assertFalse(server.isReady(), "Server must not report ready when port bind fails.");
                assertTrue(server.lastStartError().isPresent(), "lastStartError must be populated.");
            } finally {
                server.stop();
            }
        } finally {
            hog.close();
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
