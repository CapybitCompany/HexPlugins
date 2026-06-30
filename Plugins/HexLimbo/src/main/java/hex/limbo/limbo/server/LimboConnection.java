package hex.limbo.limbo.server;

import hex.limbo.config.ForwardingMode;
import hex.limbo.config.PluginConfig;
import hex.limbo.limbo.LimboSession;
import hex.limbo.limbo.LimboSessionRegistry;
import org.slf4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Per-connection state machine for one Velocity backend connection. Runs on its own worker
 * thread; the I/O is plain blocking sockets.
 *
 * <p>State transitions follow the Minecraft 1.21.4 protocol:
 * <pre>
 *   HANDSHAKE → STATUS (server list ping)        → terminate
 *             → LOGIN  → CONFIGURATION → PLAY    → keep alive loop
 * </pre>
 *
 * <p>Within PLAY the connection reads packets in a loop, discarding everything except
 * keep-alive responses. The void position is enforced via a single Sync Player Position right
 * after entering PLAY; we do not try to fight client physics every tick.
 */
final class LimboConnection {

    private enum State { HANDSHAKE, STATUS, LOGIN, CONFIGURATION, PLAY }

    private static final int MAX_USERNAME_LENGTH = 16;
    private static final long KEEPALIVE_INTERVAL_MS = 15_000L;
    private static final long ACTIONBAR_INTERVAL_MS = 2_000L;
    /** Square radius of empty chunks sent around spawn. View distance 2 → 5×5 = 25 chunks. */
    private static final int VIEW_DISTANCE = 2;

    /** Maximum forwarding-protocol version this limbo supports. Velocity will cap to its own max. */
    private static final int MAX_FORWARDING_VERSION = 1;

    /**
     * Arbitrary, connection-local id we put into the Login Plugin Request so we can correlate the
     * client's Login Plugin Response. Velocity echoes this value back to us verbatim.
     */
    private static final int VELOCITY_FORWARDING_MESSAGE_ID = 1;
    private static final String VELOCITY_FORWARDING_CHANNEL = "velocity:player_info";

    private final Socket socket;
    private final PluginConfig.Limbo config;
    private final LimboSessionRegistry sessions;
    private final ScheduledExecutorService scheduler;
    private final Supplier<String> forwardingFailedMessage;
    private final Logger logger;

    private final AtomicReference<State> state = new AtomicReference<>(State.HANDSHAKE);
    private final AtomicLong nextKeepAlive = new AtomicLong(0L);
    private LimboSession session;
    private ScheduledFuture<?> keepAliveTask;
    private ScheduledFuture<?> actionbarTask;
    /** Username/UUID from Login Start, held until Login Success can be sent. */
    private String pendingUsername;
    private UUID pendingUuid;
    /** True between sending the velocity:player_info request and receiving its response. */
    private boolean awaitingForwardingResponse;
    /** Cached so debug logs include the remote endpoint without another syscall. */
    private final String remoteLabel;
    /** Last CONFIGURATION-state packet we successfully sent; surfaced in IOException logs. */
    private volatile String lastConfigStep = "(none)";

    LimboConnection(Socket socket, PluginConfig.Limbo config, LimboSessionRegistry sessions,
                    ScheduledExecutorService scheduler,
                    Supplier<String> forwardingFailedMessage, Logger logger) {
        this.socket = socket;
        this.config = config;
        this.sessions = sessions;
        this.scheduler = scheduler;
        this.forwardingFailedMessage = forwardingFailedMessage;
        this.logger = logger;
        this.remoteLabel = socket.getRemoteSocketAddress() == null
                ? "?" : socket.getRemoteSocketAddress().toString();
    }

    /** INFO-level structured log when {@code limbo.debug-protocol=true}, DEBUG otherwise. */
    private void trace(String stage, String details) {
        if (config.debugProtocol()) {
            logger.info("HexLimbo[{} user={}]: {} {}", remoteLabel, pendingUsername == null ? "?" : pendingUsername, stage, details);
        } else if (logger.isDebugEnabled()) {
            logger.debug("HexLimbo[{} user={}]: {} {}", remoteLabel, pendingUsername == null ? "?" : pendingUsername, stage, details);
        }
    }

    void run() {
        try (Socket s = socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
             BufferedOutputStream out = new BufferedOutputStream(s.getOutputStream())) {
            while (!s.isClosed()) {
                int packetLength = Protocol.readVarInt(in);
                if (packetLength <= 0 || packetLength > 2_097_151) {
                    return;
                }
                byte[] frame = new byte[packetLength];
                in.readFully(frame);
                handleFrame(out, frame);
                if (state.get() == State.PLAY && session == null) {
                    return;
                }
            }
        } catch (EOFException | SocketTimeoutException | SocketException eof) {
            // Normal disconnect / proxy closed the channel.
            if (config.debugProtocol()) {
                logger.info("HexLimbo[{} user={}]: connection closed during {} (last config step: {}) ({}: {})",
                        remoteLabel, pendingUsername == null ? "?" : pendingUsername,
                        state.get(), lastConfigStep, eof.getClass().getSimpleName(),
                        eof.getMessage() == null ? "" : eof.getMessage());
            }
        } catch (IOException ex) {
            if (config.debugProtocol()) {
                logger.warn("HexLimbo[{} user={}]: I/O during {} (last config step: {}): {}: {}",
                        remoteLabel, pendingUsername == null ? "?" : pendingUsername,
                        state.get(), lastConfigStep, ex.getClass().getSimpleName(), ex.getMessage());
            } else {
                logger.debug("HexLimbo connection failed: {}", ex.getMessage());
            }
        } finally {
            cancelTasks();
            if (session != null) {
                sessions.remove(session.uuid());
                session.setStage(LimboSession.Stage.DISCONNECTED);
            }
        }
    }

    private void handleFrame(OutputStream out, byte[] frame) throws IOException {
        DataInputStream payload = new DataInputStream(new ByteArrayInputStream(frame));
        int packetId = Protocol.readVarInt(payload);
        switch (state.get()) {
            case HANDSHAKE -> handleHandshake(out, packetId, payload);
            case STATUS -> handleStatus(out, packetId, payload);
            case LOGIN -> handleLogin(out, packetId, payload);
            case CONFIGURATION -> handleConfiguration(out, packetId, payload);
            case PLAY -> handlePlay(out, packetId, payload);
        }
    }

    // ---------- HANDSHAKE ----------

    private void handleHandshake(OutputStream out, int packetId, DataInputStream in) throws IOException {
        if (packetId != Protocol.Packets.HANDSHAKE_INTENTION) {
            return;
        }
        int protocolVersion = Protocol.readVarInt(in);
        Protocol.readString(in, 255);                    // host discarded; Velocity sends arbitrary value
        in.readUnsignedShort();                          // port discarded
        int nextState = Protocol.readVarInt(in);
        if (nextState == 1) {
            state.set(State.STATUS);
            // Status replies always advertise the version we support; the client decides what to
            // do with a mismatch (server-list ping shows the version string).
            return;
        }
        if (nextState != 2 && nextState != 3) {
            // 2 = login, 3 = transfer (1.20.5+). Anything else is invalid.
            logger.debug("HexLimbo backend got unknown intention next-state {}; closing.", nextState);
            socket.close();
            return;
        }
        if (protocolVersion != Protocol.MINECRAFT_PROTOCOL_VERSION) {
            // Hard fail: we will not "proceed anyway" on a protocol the limbo wasn't built for.
            String message = "{\"text\":\"HexLimbo requires Minecraft " + Protocol.MINECRAFT_VERSION_LABEL
                    + " (protocol " + Protocol.MINECRAFT_PROTOCOL_VERSION + "). "
                    + "Use ViaVersion on the proxy to translate to this version.\"}";
            // Login disconnect packet is valid in the LOGIN state even before we transition.
            Protocol.writePacket(out, Protocol.Packets.LOGIN_DISCONNECT_OUT,
                    Protocol.payload(o -> Protocol.writeString(o, message)));
            out.flush();
            logger.info("HexLimbo refused login from protocol {} (we speak {}).", protocolVersion, Protocol.MINECRAFT_PROTOCOL_VERSION);
            socket.close();
            return;
        }
        trace("HANDSHAKE", "protocol=" + protocolVersion + " next-state=LOGIN");
        state.set(State.LOGIN);
    }

    // ---------- STATUS ----------

    private void handleStatus(OutputStream out, int packetId, DataInputStream in) throws IOException {
        if (packetId == Protocol.Packets.STATUS_REQUEST) {
            String json = "{"
                    + "\"version\":{\"name\":\"HexLimbo " + Protocol.MINECRAFT_VERSION_LABEL + "\","
                    + "\"protocol\":" + Protocol.MINECRAFT_PROTOCOL_VERSION + "},"
                    + "\"players\":{\"max\":0,\"online\":" + sessions.activeCount() + "},"
                    + "\"description\":{\"text\":\"HexLimbo void backend\"}"
                    + "}";
            Protocol.writePacket(out, Protocol.Packets.STATUS_RESPONSE_OUT,
                    Protocol.payload(o -> Protocol.writeString(o, json)));
            out.flush();
        } else if (packetId == Protocol.Packets.STATUS_PING) {
            long ping = in.readLong();
            Protocol.writePacket(out, Protocol.Packets.STATUS_PONG_OUT,
                    Protocol.payload(o -> o.writeLong(ping)));
            out.flush();
        }
    }

    // ---------- LOGIN ----------

    private void handleLogin(OutputStream out, int packetId, DataInputStream in) throws IOException {
        if (packetId == Protocol.Packets.LOGIN_START) {
            String username = Protocol.readString(in, MAX_USERNAME_LENGTH);
            UUID uuid;
            // 1.20.2+ login start carries the UUID directly; for safety fall back to offline UUID.
            try {
                uuid = Protocol.readUuid(in);
            } catch (IOException ex) {
                uuid = offlineUuid(username);
            }
            if (uuid.getMostSignificantBits() == 0 && uuid.getLeastSignificantBits() == 0) {
                uuid = offlineUuid(username);
            }
            pendingUsername = username;
            pendingUuid = uuid;
            trace("LOGIN_START", "username=" + username + " uuid=" + uuid);

            ForwardingMode mode = config.forwarding().mode();
            if (mode == ForwardingMode.MODERN) {
                sendVelocityForwardingRequest(out);
                awaitingForwardingResponse = true;
                trace("FORWARDING", "sent velocity:player_info request (mode=MODERN)");
                // Login Success is deferred until the Login Plugin Response arrives.
            } else {
                trace("FORWARDING", "skipped (mode=" + mode + ")");
                // NONE / LEGACY: trust the username in Login Start.
                completeLogin(out, uuid, username);
            }
        } else if (packetId == Protocol.Packets.LOGIN_PLUGIN_RESPONSE) {
            handleVelocityForwardingResponse(out, in);
        } else if (packetId == Protocol.Packets.LOGIN_ACKNOWLEDGED) {
            trace("LOGIN_ACKNOWLEDGED", "→ CONFIGURATION");
            state.set(State.CONFIGURATION);
            // Vanilla 1.21.4 server order: Feature Flags first so the client knows we're a vanilla
            // network, THEN the Select Known Packs handshake. The client refuses to participate in
            // hasData=false registries if it hasn't first learned which feature set we run.
            sendFeatureFlags(out);
            lastConfigStep = "Feature Flags";
            trace("CONFIG", "sent Feature Flags minecraft:vanilla");
            sendKnownPacks(out);
            lastConfigStep = "Select Known Packs";
            trace("CONFIG", "sent Select Known Packs");
        }
    }

    private void sendVelocityForwardingRequest(OutputStream out) throws IOException {
        Protocol.writePacket(out, Protocol.Packets.LOGIN_PLUGIN_REQUEST_OUT, Protocol.payload(o -> {
            Protocol.writeVarInt(o, VELOCITY_FORWARDING_MESSAGE_ID);
            Protocol.writeString(o, VELOCITY_FORWARDING_CHANNEL);
            // Data payload of the plugin request: a single VarInt with the highest forwarding
            // version the backend can handle. Velocity caps to its own MAX and writes the chosen
            // version into the response.
            Protocol.writeVarInt(o, MAX_FORWARDING_VERSION);
        }));
        out.flush();
    }

    private void handleVelocityForwardingResponse(OutputStream out, DataInputStream in) throws IOException {
        if (!awaitingForwardingResponse) {
            // Unsolicited plugin response: nothing to do.
            return;
        }
        int messageId = Protocol.readVarInt(in);
        boolean successful = in.readBoolean();
        if (messageId != VELOCITY_FORWARDING_MESSAGE_ID || !successful) {
            failForwarding(out, "non-matching/unsuccessful Login Plugin Response (id=" + messageId
                    + ", successful=" + successful + ")");
            return;
        }
        byte[] payload = in.readAllBytes();
        if (payload.length < 32 + 1) {
            failForwarding(out, "Login Plugin Response payload too short (" + payload.length + " bytes)");
            return;
        }

        byte[] hmac = new byte[32];
        System.arraycopy(payload, 0, hmac, 0, 32);
        byte[] signed = new byte[payload.length - 32];
        System.arraycopy(payload, 32, signed, 0, signed.length);

        String secret = config.forwarding().secret();
        if (secret != null && !secret.isEmpty()) {
            byte[] expected = hmacSha256(secret, signed);
            if (expected == null || !MessageDigest.isEqual(expected, hmac)) {
                failForwarding(out, "HMAC validation failed");
                return;
            }
        }

        // Parse the signed payload: VarInt version, String ip, UUID, String name, properties.
        try (DataInputStream signedIn = new DataInputStream(new ByteArrayInputStream(signed))) {
            int version = Protocol.readVarInt(signedIn);
            String forwardedIp = Protocol.readString(signedIn, 256);
            UUID forwardedUuid = Protocol.readUuid(signedIn);
            String forwardedName = Protocol.readString(signedIn, MAX_USERNAME_LENGTH);
            int propertyCount = Protocol.readVarInt(signedIn);
            for (int i = 0; i < propertyCount; i++) {
                Protocol.readString(signedIn, 32767);              // property name
                Protocol.readString(signedIn, 32767);              // property value
                if (signedIn.readBoolean()) {                      // has signature
                    Protocol.readString(signedIn, 32767);          // signature
                }
            }
            trace("FORWARDING", "response ok version=" + version + " ip=" + forwardedIp
                    + " uuid=" + forwardedUuid + " name=" + forwardedName);

            awaitingForwardingResponse = false;
            completeLogin(out, forwardedUuid, forwardedName);
        } catch (IOException ex) {
            failForwarding(out, "could not parse forwarded payload: " + ex.getMessage());
        }
    }

    private void completeLogin(OutputStream out, UUID uuid, String username) throws IOException {
        session = sessions.add(new LimboSession(uuid, username));
        Protocol.writePacket(out, Protocol.Packets.LOGIN_SUCCESS_OUT, Protocol.payload(o -> {
            // Protocol 769 Login Success: UUID, username, properties array. The strict_error_handling
            // boolean from 1.20.5 was removed in 1.21.2; do NOT append it here for 1.21.4.
            Protocol.writeUuid(o, uuid);
            Protocol.writeString(o, username);
            Protocol.writeVarInt(o, 0); // no profile properties
        }));
        out.flush();
        trace("LOGIN_SUCCESS", "sent uuid=" + uuid + " name=" + username);
        // Wait for Login Acknowledged before switching state.
    }

    private void failForwarding(OutputStream out, String reason) throws IOException {
        logger.warn("HexLimbo forwarding handshake failed for {}: {}", pendingUsername, reason);
        // Disconnect cleanly in the LOGIN state without creating a session. The player-facing
        // text comes from messages.yml (disconnect.forwarding-failed); the technical reason is
        // logged but not shown.
        String userVisible = forwardingFailedMessage == null ? "Forwarding failed." : forwardingFailedMessage.get();
        String json = "{\"text\":\"" + escapeJson(userVisible) + "\"}";
        Protocol.writePacket(out, Protocol.Packets.LOGIN_DISCONNECT_OUT,
                Protocol.payload(o -> Protocol.writeString(o, json)));
        out.flush();
        awaitingForwardingResponse = false;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static byte[] hmacSha256(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception ex) {
            return null;
        }
    }

    private void sendKnownPacks(OutputStream out) throws IOException {
        // Tell the client we share the vanilla "minecraft:core" pack for our target version.
        // The client then uses its built-in registries and we never have to ship them.
        Protocol.writePacket(out, Protocol.Packets.CONFIG_SELECT_KNOWN_PACKS_OUT, Protocol.payload(o -> {
            Protocol.writeVarInt(o, 1);
            Protocol.writeString(o, "minecraft");
            Protocol.writeString(o, "core");
            Protocol.writeString(o, Protocol.MINECRAFT_VERSION_LABEL);
        }));
        out.flush();
    }

    // ---------- CONFIGURATION ----------

    private void handleConfiguration(OutputStream out, int packetId, DataInputStream in) throws IOException {
        if (packetId == Protocol.Packets.CONFIG_CLIENT_INFORMATION
                || packetId == Protocol.Packets.CONFIG_PLUGIN_MESSAGE
                || packetId == Protocol.Packets.CONFIG_KEEPALIVE) {
            // Ignored: we have nothing to negotiate. Client may send these in any order before
            // accepting the Finish Configuration handshake.
            return;
        }
        if (packetId == Protocol.Packets.CONFIG_SELECT_KNOWN_PACKS_RESPONSE) {
            boolean knownCoreAccepted = parseAndLogKnownPacksResponse(in);
            // useFullNbt=true is the SAFE fallback: when the client didn't acknowledge a known
            // pack, hasData=false would leave it without resolved NBT and it disconnects after
            // Finish Configuration. We then ship full NBT for the registries we have writers for
            // and skip the rest entirely.
            boolean useFullNbt = !knownCoreAccepted;
            trace("CONFIG", "Registry Data mode=" + (useFullNbt ? "full-nbt fallback" : "hasData=false via known pack"));
            sendRegistryData(out, useFullNbt);
            sendUpdateTags(out);
            Protocol.writePacket(out, Protocol.Packets.CONFIG_FINISH_OUT, new byte[0]);
            out.flush();
            lastConfigStep = "Finish Configuration";
            trace("CONFIG", "sent Finish Configuration");
            return;
        }
        if (packetId == Protocol.Packets.CONFIG_FINISH_ACK) {
            trace("CONFIG", "received Finish Configuration ACK → PLAY");
            state.set(State.PLAY);
            enterPlay(out);
            trace("PLAY", "opening sequence sent");
            return;
        }
        trace("CONFIG", "ignored unknown packet id 0x" + Integer.toHexString(packetId));
    }

    private void sendFeatureFlags(OutputStream out) throws IOException {
        Protocol.writePacket(out, Protocol.Packets.CONFIG_FEATURE_FLAGS_OUT, Protocol.payload(o -> {
            Protocol.writeVarInt(o, 1);
            Protocol.writeString(o, "minecraft:vanilla");
        }));
    }

    private void sendUpdateTags(OutputStream out) throws IOException {
        // Empty Update Tags packet: VarInt(0) = zero registries with tags. The vanilla 1.21.4
        // client treats this as "no datapack tags to load". Some interactions reference tags
        // (e.g. block-set lookups) but our void never triggers them.
        Protocol.writePacket(out, Protocol.Packets.CONFIG_UPDATE_TAGS_OUT, Protocol.payload(o -> {
            Protocol.writeVarInt(o, 0);
        }));
        lastConfigStep = "Update Tags";
        trace("CONFIG", "sent Update Tags (0 registries)");
    }

    /**
     * Parse the Select Known Packs response and return whether the client acknowledged
     * {@code minecraft:core 1.21.4}. Caller uses this to pick the Registry Data encoding mode.
     */
    private boolean parseAndLogKnownPacksResponse(DataInputStream in) throws IOException {
        // Format: VarInt count, then for each pack: String namespace, String id, String version.
        int count = Protocol.readVarInt(in);
        java.util.List<String> packs = new java.util.ArrayList<>(count);
        boolean acknowledgedCore = false;
        for (int i = 0; i < count; i++) {
            String namespace = Protocol.readString(in, 256);
            String id = Protocol.readString(in, 256);
            String version = Protocol.readString(in, 256);
            packs.add(namespace + ":" + id + "/" + version);
            if ("minecraft".equals(namespace) && "core".equals(id)
                    && Protocol.MINECRAFT_VERSION_LABEL.equals(version)) {
                acknowledgedCore = true;
            }
        }
        trace("CONFIG", "received Select Known Packs response count=" + count + " packs=" + packs);
        if (acknowledgedCore) {
            trace("CONFIG", "knownCoreAccepted=true – will use hasData=false");
        } else {
            // Velocity / ViaVersion has been observed to answer with count=0 in production. We
            // fall back to shipping full NBT inline for the registries we have writers for.
            trace("CONFIG", "knownCoreAccepted=false – will use hasData=true full-NBT fallback");
        }
        return acknowledgedCore;
    }

    private void sendRegistryData(OutputStream out, boolean useFullNbt) throws IOException {
        int registriesSent = 0;
        for (MinimalRegistries.Registry registry : MinimalRegistries.ALL) {
            // Pick the entries we can send safely in this mode.
            //   known-pack mode: send everything with hasData=false (resolved via the pack).
            //   fallback mode: only entries that ship full NBT; never send hasData=true with
            //                  a missing body – the client would try to deserialise garbage.
            java.util.List<MinimalRegistries.Entry> sendable = useFullNbt
                    ? registry.entries().stream().filter(MinimalRegistries.Entry::hasNbt).toList()
                    : registry.entries();
            if (sendable.isEmpty()) {
                trace("CONFIG", "skip Registry Data " + registry.name() + " (no NBT writers in fallback mode)");
                continue;
            }
            Protocol.writePacket(out, Protocol.Packets.CONFIG_REGISTRY_DATA_OUT, Protocol.payload(o -> {
                Protocol.writeString(o, registry.name());
                Protocol.writeVarInt(o, sendable.size());
                for (MinimalRegistries.Entry entry : sendable) {
                    Protocol.writeString(o, entry.id());
                    if (useFullNbt) {
                        Protocol.writeBoolean(o, true);
                        entry.nbt().write(o);
                    } else {
                        Protocol.writeBoolean(o, false);
                    }
                }
            }));
            trace("CONFIG", "sent Registry Data " + registry.name() + " entries=" + sendable.size()
                    + " hasData=" + useFullNbt);
            registriesSent++;
        }
        lastConfigStep = "Registry Data (" + registriesSent + " registries, useFullNbt=" + useFullNbt + ")";
    }

    // ---------- PLAY ----------

    private void enterPlay(OutputStream out) throws IOException {
        // 1) Login (Play) – minimal void overworld.
        Protocol.writePacket(out, Protocol.Packets.PLAY_LOGIN_OUT, Protocol.payload(o -> {
            o.writeInt(1);                                    // entity ID
            Protocol.writeBoolean(o, false);                  // hardcore
            Protocol.writeVarInt(o, 1);                       // dimension count
            Protocol.writeString(o, "minecraft:overworld");   // dimension names[0]
            Protocol.writeVarInt(o, 0);                       // max players (ignored by client)
            Protocol.writeVarInt(o, VIEW_DISTANCE);           // view distance
            Protocol.writeVarInt(o, VIEW_DISTANCE);           // simulation distance
            Protocol.writeBoolean(o, false);                  // reduced debug info
            Protocol.writeBoolean(o, true);                   // enable respawn screen
            Protocol.writeBoolean(o, false);                  // do limited crafting
            Protocol.writeVarInt(o, 0);                       // dimension type id (overworld at registry index 0)
            Protocol.writeString(o, "minecraft:overworld");   // dimension name
            o.writeLong(0L);                                  // hashed seed
            o.writeByte(2);                                   // game mode (2 = adventure)
            o.writeByte(-1);                                  // previous game mode
            Protocol.writeBoolean(o, false);                  // is debug
            Protocol.writeBoolean(o, true);                   // is flat
            Protocol.writeBoolean(o, false);                  // has death location
            Protocol.writeVarInt(o, 0);                       // portal cooldown
            Protocol.writeVarInt(o, 64);                      // sea level
            Protocol.writeBoolean(o, true);                   // enforces secure chat
        }));

        // 2) Player Abilities BEFORE the chunks so the void can't insta-kill the player while
        //    they wait for the loading screen to clear.
        Protocol.writePacket(out, Protocol.Packets.PLAY_PLAYER_ABILITIES_OUT, Protocol.payload(o -> {
            o.writeByte(0x0F);    // invulnerable + flying + allow flying + creative
            o.writeFloat(0.05f);  // flying speed
            o.writeFloat(0.10f);  // walking speed
        }));

        // 3) Tell the client which chunk we'll spawn in.
        int centerChunkX = (int) Math.floor(config.spawnX() / 16.0);
        int centerChunkZ = (int) Math.floor(config.spawnZ() / 16.0);
        Protocol.writePacket(out, Protocol.Packets.PLAY_SET_CENTER_CHUNK_OUT, Protocol.payload(o -> {
            Protocol.writeVarInt(o, centerChunkX);
            Protocol.writeVarInt(o, centerChunkZ);
        }));

        // 4) Send empty chunks around the spawn so the "Loading terrain" overlay can clear.
        for (int dx = -VIEW_DISTANCE; dx <= VIEW_DISTANCE; dx++) {
            for (int dz = -VIEW_DISTANCE; dz <= VIEW_DISTANCE; dz++) {
                byte[] payload = EmptyChunk.buildPayload(centerChunkX + dx, centerChunkZ + dz);
                Protocol.writePacket(out, Protocol.Packets.PLAY_CHUNK_DATA_OUT, payload);
            }
        }

        // 5) Now teleport the player to the void spawn. Sending this *after* the chunks means the
        //    client renders into the empty world instead of treating the position as nonsense.
        Protocol.writePacket(out, Protocol.Packets.PLAY_SYNC_PLAYER_POSITION_OUT, Protocol.payload(o -> {
            Protocol.writeVarInt(o, 1);             // teleport id
            o.writeDouble(config.spawnX());
            o.writeDouble(config.spawnY());
            o.writeDouble(config.spawnZ());
            o.writeDouble(0.0);                     // delta x
            o.writeDouble(0.0);                     // delta y
            o.writeDouble(0.0);                     // delta z
            o.writeFloat(config.spawnYaw());
            o.writeFloat(config.spawnPitch());
            o.writeInt(0);                          // relatives bitmask (absolute)
        }));

        // 6) Game Event 13 = "start waiting for level chunks" – instructs the client to dismiss
        //    the loading screen once it has received enough chunks (we already sent them above).
        Protocol.writePacket(out, Protocol.Packets.PLAY_GAME_EVENT_OUT, Protocol.payload(o -> {
            o.writeByte(13);
            o.writeFloat(0.0f);
        }));

        out.flush();

        if (session != null) {
            session.setStage(LimboSession.Stage.IN_VOID);
        }

        scheduleKeepalive(out);
        scheduleActionbar(out);
    }

    private void scheduleKeepalive(OutputStream out) {
        keepAliveTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                long id = System.currentTimeMillis();
                nextKeepAlive.set(id);
                if (session != null) {
                    session.recordKeepAliveSent(id);
                }
                synchronized (out) {
                    Protocol.writePacket(out, Protocol.Packets.PLAY_KEEPALIVE_OUT,
                            Protocol.payload(o -> o.writeLong(id)));
                    out.flush();
                }
            } catch (IOException ex) {
                cancelTasks();
            }
        }, KEEPALIVE_INTERVAL_MS, KEEPALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void scheduleActionbar(OutputStream out) {
        if (!config.actionbarEnabled()) {
            return;
        }
        actionbarTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                String json = "{\"text\":\"" + escapeJson(config.actionbarText()) + "\"}";
                synchronized (out) {
                    Protocol.writePacket(out, Protocol.Packets.PLAY_ACTIONBAR_OUT, Protocol.payload(o -> {
                        // Action Bar Text in 1.21.4: TextComponent (NBT). For broad compatibility
                        // through ViaVersion we fall back to JSON over the legacy System Chat
                        // path; if the client rejects this packet shape it is silently ignored
                        // and v1 still works without the action bar.
                        o.writeByte(0x0A); // TAG_Compound start
                        // empty name
                        o.writeByte(0x08); // TAG_String
                        writeNbtString(o, "text");
                        writeNbtString(o, config.actionbarText());
                        o.writeByte(0x00); // TAG_End
                    }));
                    out.flush();
                }
            } catch (IOException ex) {
                cancelTasks();
            }
        }, 500L, ACTIONBAR_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void handlePlay(OutputStream out, int packetId, DataInputStream in) throws IOException {
        if (packetId == Protocol.Packets.PLAY_KEEPALIVE_RESPONSE) {
            long id = in.readLong();
            if (session != null && id == nextKeepAlive.get()) {
                session.recordKeepAliveAck(System.currentTimeMillis());
            }
            return;
        }
        // Movement and other PLAY packets are intentionally ignored – the void has no physics.
    }

    /** Called from {@link MinecraftLimboServer#stop()} to forcibly drop this connection. */
    void closeQuietly() {
        cancelTasks();
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void cancelTasks() {
        if (keepAliveTask != null) {
            keepAliveTask.cancel(false);
            keepAliveTask = null;
        }
        if (actionbarTask != null) {
            actionbarTask.cancel(false);
            actionbarTask = null;
        }
    }

    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void writeNbtString(java.io.DataOutputStream o, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        o.writeShort(bytes.length);
        o.write(bytes);
    }
}
