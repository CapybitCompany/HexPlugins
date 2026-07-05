package hex.limbo.limbo.server;

import hex.limbo.config.PluginConfig;
import hex.limbo.limbo.LimboServer;
import hex.limbo.limbo.LimboSession;
import hex.limbo.limbo.LimboSessionRegistry;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Hand-rolled minimal Minecraft backend server. Targets protocol {@code 774} (Minecraft 1.21.11).
 *
 * <p>The server opens a plain TCP {@link ServerSocket} on the configured bind address and spawns
 * a thread per incoming connection. Each connection runs the state machine in
 * {@link LimboConnection}: handshake → login (with Velocity modern forwarding when enabled) →
 * configuration → play. Compression and encryption are intentionally not negotiated – the limbo
 * is an internal backend behind a Velocity proxy on loopback.
 *
 * <p>v1 scope:
 * <ul>
 *     <li>Status pings show a placeholder MOTD.</li>
 *     <li>Login uses either the username from Login Start (mode NONE/LEGACY) or the UUID/name
 *     forwarded by Velocity via the {@code velocity:player_info} Login Plugin Request handshake
 *     (mode MODERN).</li>
 *     <li>Configuration sends the Known Packs handshake, then replays the complete captured
 *     1.21.11 registry set from {@link RegistryData} (23 registries, full NBT inline). Shipping
 *     the exact registry data a real server sends is the only thing the native client and
 *     ViaVersion/ViaFabric reliably accept; an incomplete or hand-rolled set kicks the client at
 *     the CONFIGURATION → PLAY transition.</li>
 *     <li>Play sends Login → Player Abilities → Set Center Chunk → a 5×5 patch of empty chunks →
 *     Synchronize Player Position → Game Event 13 ("start waiting for level chunks"). Keep-alives
 *     run every 15 s.</li>
 * </ul>
 * Anything beyond that (block updates, inventory, signed chat, real biomes) is intentionally
 * absent – the void is enough for /login and /register via Velocity command handlers.
 */
public final class MinecraftLimboServer implements LimboServer {

    private final PluginConfig.Limbo config;
    private final Supplier<String> forwardingFailedMessage;
    private final Logger logger;
    private final LimboSessionRegistry sessions = new LimboSessionRegistry();
    /** Live connections; needed so {@link #stop()} can close client sockets explicitly. */
    private final Set<LimboConnection> activeConnections = Collections.synchronizedSet(new HashSet<>());

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> lastStartError = new AtomicReference<>(null);

    private ServerSocket serverSocket;
    private ExecutorService acceptor;
    private ExecutorService workers;
    private ScheduledExecutorService scheduler;

    public MinecraftLimboServer(PluginConfig.Limbo config, Logger logger) {
        // Default supplier produces a stable English fallback if the caller didn't wire messages.yml.
        this(config, () -> "Could not complete Velocity forwarding handshake.", logger);
    }

    public MinecraftLimboServer(PluginConfig.Limbo config, Supplier<String> forwardingFailedMessage, Logger logger) {
        this.config = config;
        this.forwardingFailedMessage = forwardingFailedMessage;
        this.logger = logger;
    }

    @Override
    public synchronized void start() {
        if (running.get()) {
            return;
        }
        try {
            ServerSocket socket = new ServerSocket();
            // No SO_REUSEADDR: we WANT a hard failure if the port is already in use so the plugin
            // surfaces the conflict in lastStartError instead of silently sharing the port.
            socket.bind(new InetSocketAddress(config.bindHost(), config.bindPort()));
            this.serverSocket = socket;

            AtomicInteger acceptorIndex = new AtomicInteger();
            this.acceptor = Executors.newSingleThreadExecutor(r -> daemon(r, "HexLimbo-Accept-" + acceptorIndex.incrementAndGet()));
            AtomicInteger workerIndex = new AtomicInteger();
            this.workers = Executors.newCachedThreadPool(r -> daemon(r, "HexLimbo-Conn-" + workerIndex.incrementAndGet()));
            AtomicInteger schedulerIndex = new AtomicInteger();
            this.scheduler = Executors.newScheduledThreadPool(2, r -> daemon(r, "HexLimbo-Sched-" + schedulerIndex.incrementAndGet()));

            running.set(true);
            lastStartError.set(null);
            acceptor.execute(this::acceptLoop);

            logger.info("HexLimbo internal void backend ready on {}:{} (protocol {} / Minecraft {}).",
                    config.bindHost(), config.bindPort(),
                    Protocol.MINECRAFT_PROTOCOL_VERSION, Protocol.MINECRAFT_VERSION_LABEL);
        } catch (IOException ex) {
            lastStartError.set(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            running.set(false);
            logger.error("HexLimbo internal limbo failed to bind {}:{} – {}",
                    config.bindHost(), config.bindPort(), ex.getMessage());
            closeQuietly();
        }
    }

    @Override
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        closeQuietly();
        logger.info("HexLimbo internal void backend stopped.");
    }

    private void closeQuietly() {
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            serverSocket = null;
        }
        // Drop every live client connection. Each LimboConnection is in a blocking read; closing
        // its socket lets the worker thread fall through its IOException handler and clean up.
        synchronized (activeConnections) {
            for (LimboConnection conn : activeConnections) {
                conn.closeQuietly();
            }
            activeConnections.clear();
        }
        if (acceptor != null) {
            acceptor.shutdownNow();
            acceptor = null;
        }
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void acceptLoop() {
        ServerSocket socket = this.serverSocket;
        while (running.get() && socket != null && !socket.isClosed()) {
            try {
                Socket client = socket.accept();
                client.setTcpNoDelay(true);
                client.setSoTimeout(60_000);
                LimboConnection conn = new LimboConnection(client, config, sessions, scheduler, forwardingFailedMessage, logger);
                activeConnections.add(conn);
                workers.execute(() -> {
                    try {
                        conn.run();
                    } finally {
                        activeConnections.remove(conn);
                    }
                });
            } catch (IOException ex) {
                if (running.get()) {
                    logger.warn("HexLimbo accept() failed: {}", ex.getMessage());
                }
            }
        }
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread t = new Thread(runnable, name);
        t.setDaemon(true);
        return t;
    }

    @Override public boolean isReady() { return running.get() && serverSocket != null && !serverSocket.isClosed(); }
    @Override public String serverName() { return config.serverName(); }
    @Override public String bindHost() { return config.bindHost(); }
    @Override public int bindPort() { return config.bindPort(); }
    @Override public int activeConnectionCount() { return sessions.activeCount(); }
    @Override public int tcpConnectionCount() { return activeConnections.size(); }
    @Override public Optional<String> lastStartError() { return Optional.ofNullable(lastStartError.get()); }
    @Override public LimboSessionRegistry sessionRegistry() { return sessions; }

    /** Visible for tests: wait briefly for a fresh start so timing-dependent checks settle. */
    public void awaitReady(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (!isReady() && System.currentTimeMillis() < deadline) {
            try { TimeUnit.MILLISECONDS.sleep(10); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
        }
    }
}
