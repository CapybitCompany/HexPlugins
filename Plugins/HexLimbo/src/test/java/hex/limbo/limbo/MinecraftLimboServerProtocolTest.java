package hex.limbo.limbo;

import hex.limbo.config.PluginConfig;
import hex.limbo.limbo.server.MinecraftLimboServer;
import hex.limbo.limbo.server.Protocol;
import hex.limbo.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-level smoke test for {@link MinecraftLimboServer}. Verifies that the server actually
 * performs the four-state Minecraft 1.21.11 handshake instead of merely binding a TCP port.
 *
 * <p>The test does not replace a real Minecraft client – framing nuances, NBT correctness or
 * client-side expectations beyond packet ids must still be validated with an actual client. But
 * it catches the common regressions: missing packets, wrong packet ids, wrong state transitions.
 */
class MinecraftLimboServerProtocolTest {

    private int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private MinecraftLimboServer start(int port) {
        PluginConfig.Limbo limbo = TestConfigs.withLimboPort(port).limbo();
        MinecraftLimboServer server = new MinecraftLimboServer(limbo,
                LoggerFactory.getLogger(MinecraftLimboServerProtocolTest.class));
        server.start();
        server.awaitReady(2_000L);
        return server;
    }

    /** Frame writer: VarInt length + VarInt packet id + payload. */
    private static void send(DataOutputStream out, int packetId, byte[] payload) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        DataOutputStream framed = new DataOutputStream(frame);
        Protocol.writeVarInt(framed, packetId);
        framed.write(payload);
        byte[] body = frame.toByteArray();
        Protocol.writeVarInt(out, body.length);
        out.write(body);
        out.flush();
    }

    /** Frame reader: returns (packetId, remainingPayloadStream). */
    private record Frame(int packetId, DataInputStream payload) {}

    private static Frame readFrame(DataInputStream in) throws IOException {
        int length = Protocol.readVarInt(in);
        byte[] body = new byte[length];
        in.readFully(body);
        DataInputStream payload = new DataInputStream(new java.io.ByteArrayInputStream(body));
        int packetId = Protocol.readVarInt(payload);
        return new Frame(packetId, payload);
    }

    private static byte[] handshakePayload(int nextState) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        Protocol.writeVarInt(out, Protocol.MINECRAFT_PROTOCOL_VERSION);
        Protocol.writeString(out, "localhost");
        out.writeShort(25580);
        Protocol.writeVarInt(out, nextState);
        return baos.toByteArray();
    }

    @Test
    void statusHandshakeRespondsWithVersionedJson() throws IOException {
        int port = freePort();
        MinecraftLimboServer server = start(port);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(2_000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            send(out, Protocol.Packets.HANDSHAKE_INTENTION, handshakePayload(1));
            send(out, Protocol.Packets.STATUS_REQUEST, new byte[0]);

            Frame response = readFrame(in);
            assertEquals(Protocol.Packets.STATUS_RESPONSE_OUT, response.packetId());
            String json = Protocol.readString(response.payload(), 32_767);
            assertTrue(json.contains("\"name\":\"HexLimbo " + Protocol.MINECRAFT_VERSION_LABEL + "\""),
                    "Status JSON should announce the limbo version label: " + json);
            assertTrue(json.contains("\"protocol\":" + Protocol.MINECRAFT_PROTOCOL_VERSION),
                    "Status JSON should announce the protocol id: " + json);
        } finally {
            server.stop();
        }
    }

    @Test
    void nonPremiumJoinReachesPlayWithChunks() throws IOException {
        // Hard-coded packet ids from minecraft-data 1.21.11 protocol.json. These intentionally do
        // NOT reuse Protocol.Packets constants – if the constants regress, this test catches it.
        int loginSuccessOut = 0x02;
        int knownPacksOut = 0x0E;
        int finishConfigOut = 0x03;
        int playLoginOut = 0x30;
        int playerAbilitiesOut = 0x3E;
        int setCenterChunkOut = 0x5C;
        int chunkDataOut = 0x2C;
        int syncPlayerPositionOut = 0x46;
        int gameEventOut = 0x26;

        int port = freePort();
        MinecraftLimboServer server = start(port);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(3_000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            send(out, Protocol.Packets.HANDSHAKE_INTENTION, handshakePayload(2));

            // Login Start: username + UUID (1.20.2+ format).
            ByteArrayOutputStream loginStart = new ByteArrayOutputStream();
            DataOutputStream ls = new DataOutputStream(loginStart);
            Protocol.writeString(ls, "ProtocolTester");
            Protocol.writeUuid(ls, UUID.nameUUIDFromBytes("OfflinePlayer:ProtocolTester".getBytes(StandardCharsets.UTF_8)));
            send(out, Protocol.Packets.LOGIN_START, loginStart.toByteArray());

            // Expect Login Success (server is in NONE forwarding mode by default).
            Frame loginSuccess = readFrame(in);
            assertEquals(loginSuccessOut, loginSuccess.packetId(),
                    "1.21.11 Login Success clientbound id must be 0x02");
            UUID returnedUuid = Protocol.readUuid(loginSuccess.payload());
            String returnedName = Protocol.readString(loginSuccess.payload(), 16);
            assertEquals("ProtocolTester", returnedName);
            assertNotEquals(new UUID(0, 0), returnedUuid);
            // 1.21.11 Login Success body ends after the properties array – no strict_error_handling.
            assertEquals(0, Protocol.readVarInt(loginSuccess.payload()), "properties array must be empty");

            // Client confirms login → server transitions to CONFIGURATION.
            send(out, Protocol.Packets.LOGIN_ACKNOWLEDGED, new byte[0]);

            // Vanilla 1.21.11 sends Feature Flags BEFORE Select Known Packs.
            int featureFlagsOut = 0x0C;
            Frame featureFlags = readFrame(in);
            assertEquals(featureFlagsOut, featureFlags.packetId(),
                    "1.21.11 Feature Flags clientbound id must be 0x0C");
            int flagCount = Protocol.readVarInt(featureFlags.payload());
            assertTrue(flagCount >= 1, "Feature Flags must list at least minecraft:vanilla");

            Frame knownPacks = readFrame(in);
            assertEquals(knownPacksOut, knownPacks.packetId(),
                    "1.21.11 Select Known Packs clientbound id must be 0x0E");

            // Echo the known-packs handshake back with the matching pack.
            ByteArrayOutputStream packAck = new ByteArrayOutputStream();
            DataOutputStream pa = new DataOutputStream(packAck);
            Protocol.writeVarInt(pa, 1);
            Protocol.writeString(pa, "minecraft");
            Protocol.writeString(pa, "core");
            Protocol.writeString(pa, Protocol.MINECRAFT_VERSION_LABEL);
            send(out, Protocol.Packets.CONFIG_SELECT_KNOWN_PACKS_RESPONSE, packAck.toByteArray());

            // After Known Packs response 1.21.11 expects: Registry Data (multiple) → Update Tags
            // → Finish Configuration. Tag references rely on registry entries existing, so tags
            // MUST come after registries.
            int registryDataOut = 0x07;
            int updateTagsOut = 0x0D;
            java.util.Set<String> registriesSent = new java.util.HashSet<>();
            int peekedAfterRegistries = -1;
            while (true) {
                Frame frame = readFrame(in);
                if (frame.packetId() == registryDataOut) {
                    String registryName = Protocol.readString(frame.payload(), 256);
                    registriesSent.add(registryName);
                    continue;
                }
                peekedAfterRegistries = frame.packetId();
                break;
            }
            assertTrue(registriesSent.contains("minecraft:dimension_type"),
                    "Server must send Registry Data for minecraft:dimension_type (used by Play Login)");
            assertTrue(registriesSent.contains("minecraft:worldgen/biome"),
                    "Server must send Registry Data for minecraft:worldgen/biome (used by chunk data)");
            assertEquals(updateTagsOut, peekedAfterRegistries,
                    "1.21.11 Update Tags clientbound id 0x0D must come after Registry Data and before Finish Configuration");

            Frame finishConfig = readFrame(in);
            assertEquals(finishConfigOut, finishConfig.packetId(),
                    "1.21.11 Finish Configuration clientbound id must be 0x03 after Update Tags");

            // Client ACK → PLAY.
            send(out, Protocol.Packets.CONFIG_FINISH_ACK, new byte[0]);

            // Concrete-id verification of the PLAY opening sequence. Catches packet-id drift.
            assertEquals(playLoginOut, readFrame(in).packetId(),
                    "1.21.11 Play Login clientbound id must be 0x30");
            assertEquals(playerAbilitiesOut, readFrame(in).packetId(),
                    "1.21.11 Player Abilities clientbound id must be 0x3E");
            assertEquals(setCenterChunkOut, readFrame(in).packetId(),
                    "1.21.11 Set Center Chunk clientbound id must be 0x5C");

            int chunksSeen = 0;
            int peekedNonChunkPacketId = -1;
            while (true) {
                Frame frame = readFrame(in);
                if (frame.packetId() == chunkDataOut) {
                    chunksSeen++;
                } else {
                    peekedNonChunkPacketId = frame.packetId();
                    break;
                }
            }
            assertTrue(chunksSeen > 0,
                    "1.21.11 Chunk Data clientbound id must be 0x2C and at least one must be sent");
            assertEquals(syncPlayerPositionOut, peekedNonChunkPacketId,
                    "1.21.11 Sync Player Position clientbound id must be 0x46");

            Frame gameEvent = readFrame(in);
            assertEquals(gameEventOut, gameEvent.packetId(),
                    "1.21.11 Game Event clientbound id must be 0x26");
            // Verify Game Event payload: byte event id (13 = "start waiting for level chunks") + float.
            byte gameEventCode = gameEvent.payload().readByte();
            assertEquals(13, gameEventCode);
        } finally {
            server.stop();
        }
    }

    @Test
    void mismatchedProtocolVersionIsRejectedCleanly() throws IOException {
        int port = freePort();
        MinecraftLimboServer server = start(port);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(2_000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            // Send handshake with deliberately wrong protocol version.
            ByteArrayOutputStream bad = new ByteArrayOutputStream();
            DataOutputStream ds = new DataOutputStream(bad);
            Protocol.writeVarInt(ds, Protocol.MINECRAFT_PROTOCOL_VERSION + 999);
            Protocol.writeString(ds, "localhost");
            ds.writeShort(25580);
            Protocol.writeVarInt(ds, 2);
            send(out, Protocol.Packets.HANDSHAKE_INTENTION, bad.toByteArray());

            Frame disconnect = readFrame(in);
            assertEquals(Protocol.Packets.LOGIN_DISCONNECT_OUT, disconnect.packetId(),
                    "Mismatched protocol must produce a Login Disconnect, not proceed silently.");
            String reason = Protocol.readString(disconnect.payload(), 32_767);
            assertTrue(reason.contains("HexLimbo"), "Disconnect message should be self-identifying: " + reason);
        } finally {
            server.stop();
        }
    }

    @Test
    void shutdownClosesAcceptedConnections() throws IOException, InterruptedException {
        int port = freePort();
        MinecraftLimboServer server = start(port);
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
        socket.setSoTimeout(2_000);
        // Briefly handshake + login so the connection moves past the immediate accept.
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        send(out, Protocol.Packets.HANDSHAKE_INTENTION, handshakePayload(1));

        server.stop();

        // After stop(), reads from the accepted socket must see end-of-stream within a moment.
        try {
            socket.getInputStream().read();
        } catch (IOException ignored) {
            // Expected – server closed the channel.
        }
        // active count drains.
        long deadline = System.currentTimeMillis() + 1_500L;
        while (server.activeConnectionCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, server.activeConnectionCount(), "Shutdown must drain the active connection count.");
        socket.close();
    }
}
