package hex.limbo.limbo;

import hex.limbo.config.ForwardingMode;
import hex.limbo.config.PluginConfig;
import hex.limbo.limbo.server.MinecraftLimboServer;
import hex.limbo.limbo.server.Protocol;
import hex.limbo.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the live regression where non-premium players disconnect right after joining
 * {@code hexlimbo-limbo}. The script walks the complete login → modern forwarding handshake →
 * configuration → play opening flow against hard-coded 1.21.11 packet ids and asserts the client
 * never receives a Login or Configuration Disconnect.
 */
class NonPremiumModernForwardingJoinTest {

    // Hard-coded 1.21.11 ids; do NOT replace with Protocol.Packets references in this file.
    private static final int LOGIN_PLUGIN_REQUEST_OUT = 0x04;
    private static final int LOGIN_SUCCESS_OUT = 0x02;
    private static final int CONFIG_FEATURE_FLAGS_OUT = 0x0C;
    private static final int CONFIG_SELECT_KNOWN_PACKS_OUT = 0x0E;
    private static final int CONFIG_REGISTRY_DATA_OUT = 0x07;
    private static final int CONFIG_UPDATE_TAGS_OUT = 0x0D;
    private static final int CONFIG_FINISH_OUT = 0x03;
    private static final int PLAY_LOGIN_OUT = 0x30;
    private static final int PLAY_PLAYER_ABILITIES_OUT = 0x3E;
    private static final int PLAY_SET_CENTER_CHUNK_OUT = 0x5C;
    private static final int PLAY_CHUNK_DATA_OUT = 0x2C;
    private static final int PLAY_SYNC_PLAYER_POSITION_OUT = 0x46;
    private static final int PLAY_GAME_EVENT_OUT = 0x26;
    private static final int LOGIN_DISCONNECT_OUT = 0x00;
    private static final int CONFIG_DISCONNECT_OUT = 0x02;

    private int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private MinecraftLimboServer start(int port) {
        PluginConfig.Limbo base = TestConfigs.limboWithForwarding(ForwardingMode.MODERN, "");
        PluginConfig.Limbo limbo = new PluginConfig.Limbo(
                base.serverName(), base.bindHost(), port,
                base.spawnX(), base.spawnY(), base.spawnZ(), base.spawnYaw(), base.spawnPitch(),
                base.actionbarEnabled(), base.actionbarText(),
                base.forwarding(),
                true   // debug-protocol on so trace output is visible if a future regression hits
        );
        MinecraftLimboServer server = new MinecraftLimboServer(limbo,
                LoggerFactory.getLogger(NonPremiumModernForwardingJoinTest.class));
        server.start();
        server.awaitReady(2_000L);
        return server;
    }

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

    private record Frame(int packetId, DataInputStream payload) {}

    private static Frame readFrame(DataInputStream in) throws IOException {
        int length = Protocol.readVarInt(in);
        byte[] body = new byte[length];
        in.readFully(body);
        DataInputStream payload = new DataInputStream(new ByteArrayInputStream(body));
        int packetId = Protocol.readVarInt(payload);
        return new Frame(packetId, payload);
    }

    @Test
    void crackedClientWithModernForwardingReachesPlayWithoutDisconnect() throws IOException {
        int port = freePort();
        MinecraftLimboServer server = start(port);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(3_000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            // 1) Handshake: protocol 774, next state 2 (login).
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            DataOutputStream hsOut = new DataOutputStream(hs);
            Protocol.writeVarInt(hsOut, Protocol.MINECRAFT_PROTOCOL_VERSION);
            Protocol.writeString(hsOut, "localhost");
            hsOut.writeShort(25580);
            Protocol.writeVarInt(hsOut, 2);
            send(out, Protocol.Packets.HANDSHAKE_INTENTION, hs.toByteArray());

            // 2) Login Start (cracked – Velocity always sends username + arbitrary UUID).
            ByteArrayOutputStream loginStart = new ByteArrayOutputStream();
            DataOutputStream ls = new DataOutputStream(loginStart);
            Protocol.writeString(ls, "NonPremiumJoin");
            Protocol.writeUuid(ls, UUID.randomUUID());
            send(out, Protocol.Packets.LOGIN_START, loginStart.toByteArray());

            // 3) Server must FIRST send a Login Plugin Request for velocity:player_info.
            //    The historic bug was: server sent Login Success here, Velocity disconnected.
            Frame request = readFrame(in);
            assertNotEquals(LOGIN_DISCONNECT_OUT, request.packetId(),
                    "Server must not Login-Disconnect a cracked client in modern-forwarding mode.");
            assertEquals(LOGIN_PLUGIN_REQUEST_OUT, request.packetId(),
                    "1.21.11 Login Plugin Request clientbound id must be 0x04");
            int messageId = Protocol.readVarInt(request.payload());
            String channel = Protocol.readString(request.payload(), 256);
            assertEquals("velocity:player_info", channel);

            // 4) Send back a valid response (empty secret → HMAC not validated).
            UUID forwardedUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
            ByteArrayOutputStream signedBaos = new ByteArrayOutputStream();
            DataOutputStream signed = new DataOutputStream(signedBaos);
            Protocol.writeVarInt(signed, 1);                       // version
            Protocol.writeString(signed, "1.2.3.4");               // ip
            Protocol.writeUuid(signed, forwardedUuid);             // uuid
            Protocol.writeString(signed, "NonPremiumJoin");        // username
            Protocol.writeVarInt(signed, 0);                       // no properties
            byte[] signedBytes = signedBaos.toByteArray();
            byte[] response = new byte[32 + signedBytes.length];
            System.arraycopy(signedBytes, 0, response, 32, signedBytes.length);

            ByteArrayOutputStream pluginRespBaos = new ByteArrayOutputStream();
            DataOutputStream pluginResp = new DataOutputStream(pluginRespBaos);
            Protocol.writeVarInt(pluginResp, messageId);
            pluginResp.writeBoolean(true);
            pluginResp.write(response);
            send(out, Protocol.Packets.LOGIN_PLUGIN_RESPONSE, pluginRespBaos.toByteArray());

            // 5) Now Login Success.
            Frame success = readFrame(in);
            assertEquals(LOGIN_SUCCESS_OUT, success.packetId());
            UUID receivedUuid = Protocol.readUuid(success.payload());
            String receivedName = Protocol.readString(success.payload(), 16);
            assertEquals(forwardedUuid, receivedUuid,
                    "Login Success must reuse the UUID forwarded by Velocity.");
            assertEquals("NonPremiumJoin", receivedName);

            // 6) Login Acknowledged → CONFIGURATION.
            send(out, Protocol.Packets.LOGIN_ACKNOWLEDGED, new byte[0]);

            // Vanilla 1.21.11 server order: Feature Flags first, THEN Select Known Packs.
            Frame featureFlags = readFrame(in);
            assertNotEquals(CONFIG_DISCONNECT_OUT, featureFlags.packetId(),
                    "Server must not Config-Disconnect before Feature Flags.");
            assertEquals(CONFIG_FEATURE_FLAGS_OUT, featureFlags.packetId(),
                    "1.21.11 Feature Flags clientbound id must be 0x0C");

            Frame knownPacks = readFrame(in);
            assertEquals(CONFIG_SELECT_KNOWN_PACKS_OUT, knownPacks.packetId());

            // LIVE REGRESSION: Velocity/ViaVersion has been observed to answer Select Known
            // Packs with count=0. The limbo must NOT then send hasData=false registry entries
            // (the client has no NBT to resolve them against); it must fall back to hasData=true
            // with inline NBT for the primary registries.
            ByteArrayOutputStream packAck = new ByteArrayOutputStream();
            DataOutputStream pa = new DataOutputStream(packAck);
            Protocol.writeVarInt(pa, 0);
            send(out, Protocol.Packets.CONFIG_SELECT_KNOWN_PACKS_RESPONSE, packAck.toByteArray());

            // 1.21.11 sequence after Known Packs response: Registry Data (multiple) → Update Tags
            // → Finish Configuration. None of these may be a Config Disconnect.
            java.util.Set<String> registriesSent = new java.util.HashSet<>();
            int peekedAfterRegistries = -1;
            while (true) {
                Frame frame = readFrame(in);
                assertNotEquals(CONFIG_DISCONNECT_OUT, frame.packetId(),
                        "Server must not Config-Disconnect between known-packs handshake and PLAY.");
                if (frame.packetId() == CONFIG_REGISTRY_DATA_OUT) {
                    String registry = Protocol.readString(frame.payload(), 256);
                    int entryCount = Protocol.readVarInt(frame.payload());
                    assertTrue(entryCount >= 1, "Registry " + registry + " must have at least one entry");
                    Protocol.readString(frame.payload(), 256);  // first entry id
                    boolean hasData = frame.payload().readBoolean();
                    assertTrue(hasData, "Fallback mode must use hasData=true; registry " + registry + " sent hasData=false");
                    registriesSent.add(registry);
                    continue;
                }
                peekedAfterRegistries = frame.packetId();
                break;
            }
            assertTrue(registriesSent.contains("minecraft:dimension_type"),
                    "Server must send Registry Data for minecraft:dimension_type");
            assertTrue(registriesSent.contains("minecraft:worldgen/biome"),
                    "Server must send Registry Data for minecraft:worldgen/biome");
            assertTrue(registriesSent.contains("minecraft:chat_type"));
            assertTrue(registriesSent.contains("minecraft:damage_type"));
            assertEquals(CONFIG_UPDATE_TAGS_OUT, peekedAfterRegistries,
                    "1.21.11 Update Tags (0x0D) must come after Registry Data and before Finish Configuration.");

            Frame finish = readFrame(in);
            assertEquals(CONFIG_FINISH_OUT, finish.packetId(),
                    "After Update Tags, Finish Configuration must follow.");

            // 7) Finish Configuration ACK → PLAY opening sequence.
            send(out, Protocol.Packets.CONFIG_FINISH_ACK, new byte[0]);

            assertEquals(PLAY_LOGIN_OUT, readFrame(in).packetId());
            assertEquals(PLAY_PLAYER_ABILITIES_OUT, readFrame(in).packetId());
            assertEquals(PLAY_SET_CENTER_CHUNK_OUT, readFrame(in).packetId());

            int chunksSeen = 0;
            int peekedNonChunkId = -1;
            while (true) {
                Frame frame = readFrame(in);
                if (frame.packetId() == PLAY_CHUNK_DATA_OUT) {
                    chunksSeen++;
                } else {
                    peekedNonChunkId = frame.packetId();
                    break;
                }
            }
            assertTrue(chunksSeen > 0);
            assertEquals(PLAY_SYNC_PLAYER_POSITION_OUT, peekedNonChunkId);
            assertEquals(PLAY_GAME_EVENT_OUT, readFrame(in).packetId());
        } finally {
            server.stop();
        }
    }
}
