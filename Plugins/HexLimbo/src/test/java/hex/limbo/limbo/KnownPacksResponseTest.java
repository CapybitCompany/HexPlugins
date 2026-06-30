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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the limbo parses the Select Known Packs response payload and proceeds with the
 * full Registry Data → Update Tags → Finish Configuration sequence regardless of which packs the
 * client echoes back. Today v1 always proceeds with {@code hasData=false} entries and only logs
 * a warning when {@code minecraft:core 1.21.4} is absent; this test ensures the sequence does
 * not hang or disconnect even on a non-vanilla pack list.
 */
class KnownPacksResponseTest {

    private static final int CONFIG_FEATURE_FLAGS_OUT = 0x0C;
    private static final int CONFIG_SELECT_KNOWN_PACKS_OUT = 0x0E;
    private static final int CONFIG_REGISTRY_DATA_OUT = 0x07;
    private static final int CONFIG_UPDATE_TAGS_OUT = 0x0D;
    private static final int CONFIG_FINISH_OUT = 0x03;
    private static final int CONFIG_DISCONNECT_OUT = 0x02;

    private int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private MinecraftLimboServer start(int port) {
        PluginConfig.Limbo base = TestConfigs.limboWithForwarding(ForwardingMode.NONE, "");
        PluginConfig.Limbo limbo = new PluginConfig.Limbo(
                base.serverName(), base.bindHost(), port,
                base.spawnX(), base.spawnY(), base.spawnZ(), base.spawnYaw(), base.spawnPitch(),
                base.actionbarEnabled(), base.actionbarText(),
                base.forwarding(),
                true   // debug-protocol on so warnings about missing core pack are surfaced in logs
        );
        MinecraftLimboServer server = new MinecraftLimboServer(limbo,
                LoggerFactory.getLogger(KnownPacksResponseTest.class));
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

    private void walkToKnownPacks(DataOutputStream out, DataInputStream in) throws IOException {
        // Handshake
        ByteArrayOutputStream hs = new ByteArrayOutputStream();
        DataOutputStream hsOut = new DataOutputStream(hs);
        Protocol.writeVarInt(hsOut, Protocol.MINECRAFT_PROTOCOL_VERSION);
        Protocol.writeString(hsOut, "localhost");
        hsOut.writeShort(25580);
        Protocol.writeVarInt(hsOut, 2);
        send(out, Protocol.Packets.HANDSHAKE_INTENTION, hs.toByteArray());

        // Login Start
        ByteArrayOutputStream ls = new ByteArrayOutputStream();
        DataOutputStream lsOut = new DataOutputStream(ls);
        Protocol.writeString(lsOut, "PackTester");
        Protocol.writeUuid(lsOut, UUID.randomUUID());
        send(out, Protocol.Packets.LOGIN_START, ls.toByteArray());

        // Login Success (NONE forwarding mode, sent immediately).
        readFrame(in);

        // Acknowledge → CONFIGURATION
        send(out, Protocol.Packets.LOGIN_ACKNOWLEDGED, new byte[0]);

        // Server now sends Feature Flags + Select Known Packs in that order.
        Frame featureFlags = readFrame(in);
        assertEquals(CONFIG_FEATURE_FLAGS_OUT, featureFlags.packetId());

        Frame knownPacks = readFrame(in);
        assertEquals(CONFIG_SELECT_KNOWN_PACKS_OUT, knownPacks.packetId());
    }

    /**
     * Walks Registry Data → Update Tags → Finish Configuration and returns, for each registry
     * sent, the value of the {@code hasData} byte on its FIRST entry. Caller asserts on the map
     * to verify the right encoding mode was chosen.
     */
    private java.util.Map<String, Boolean> assertSequenceCompletes(
            DataOutputStream out, DataInputStream in, byte[] knownPacksResponsePayload) throws IOException {
        send(out, Protocol.Packets.CONFIG_SELECT_KNOWN_PACKS_RESPONSE, knownPacksResponsePayload);

        java.util.Map<String, Boolean> registryHasData = new java.util.LinkedHashMap<>();
        int afterRegistries = -1;
        while (true) {
            Frame frame = readFrame(in);
            assertNotEquals(CONFIG_DISCONNECT_OUT, frame.packetId(),
                    "Server must not disconnect during Registry Data / Tags / Finish.");
            if (frame.packetId() == CONFIG_REGISTRY_DATA_OUT) {
                String registryName = Protocol.readString(frame.payload(), 256);
                int entryCount = Protocol.readVarInt(frame.payload());
                assertTrue(entryCount >= 1, "Registry " + registryName + " must have at least one entry");
                Protocol.readString(frame.payload(), 256);   // first entry id
                boolean hasData = frame.payload().readBoolean();
                registryHasData.put(registryName, hasData);
                continue;
            }
            afterRegistries = frame.packetId();
            break;
        }
        assertTrue(registryHasData.containsKey("minecraft:dimension_type"));
        assertTrue(registryHasData.containsKey("minecraft:worldgen/biome"));
        assertEquals(CONFIG_UPDATE_TAGS_OUT, afterRegistries);

        Frame finish = readFrame(in);
        assertEquals(CONFIG_FINISH_OUT, finish.packetId());
        return registryHasData;
    }

    @Test
    void responseWithCorePackUsesHasDataFalse() throws IOException {
        int port = freePort();
        MinecraftLimboServer server = start(port);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(3_000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            walkToKnownPacks(out, in);

            // Echo back exactly the Known Pack we announced.
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            DataOutputStream r = new DataOutputStream(resp);
            Protocol.writeVarInt(r, 1);
            Protocol.writeString(r, "minecraft");
            Protocol.writeString(r, "core");
            Protocol.writeString(r, Protocol.MINECRAFT_VERSION_LABEL);
            java.util.Map<String, Boolean> hasData = assertSequenceCompletes(out, in, resp.toByteArray());

            assertEquals(false, hasData.get("minecraft:dimension_type"),
                    "Known-pack mode must use hasData=false");
            assertEquals(false, hasData.get("minecraft:worldgen/biome"),
                    "Known-pack mode must use hasData=false");
            // Token registries also participate in known-pack mode.
            assertTrue(hasData.containsKey("minecraft:trim_pattern"),
                    "Known-pack mode includes token registries");
        } finally {
            server.stop();
        }
    }

    @Test
    void responseWithoutCorePackUsesFullNbtFallback() throws IOException {
        int port = freePort();
        MinecraftLimboServer server = start(port);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(3_000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            walkToKnownPacks(out, in);

            // Live regression: Velocity/ViaVersion replies count=0.
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            DataOutputStream r = new DataOutputStream(resp);
            Protocol.writeVarInt(r, 0);
            java.util.Map<String, Boolean> hasData = assertSequenceCompletes(out, in, resp.toByteArray());

            // Fallback mode: primary registries must ship full NBT (hasData=true) ...
            assertEquals(true, hasData.get("minecraft:dimension_type"),
                    "Fallback mode must ship full NBT with hasData=true");
            assertEquals(true, hasData.get("minecraft:worldgen/biome"));
            assertEquals(true, hasData.get("minecraft:chat_type"));
            assertEquals(true, hasData.get("minecraft:damage_type"));
            // ... and token-only registries must be skipped entirely.
            assertTrue(!hasData.containsKey("minecraft:trim_pattern"),
                    "Fallback mode must skip token-only registries (got " + hasData.keySet() + ")");
            assertTrue(!hasData.containsKey("minecraft:wolf_variant"));
            assertTrue(!hasData.containsKey("minecraft:painting_variant"));
        } finally {
            server.stop();
        }
    }
}
