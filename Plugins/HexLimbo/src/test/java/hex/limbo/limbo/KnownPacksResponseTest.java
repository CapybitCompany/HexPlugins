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
 * Verifies that the limbo parses the Select Known Packs response payload and then replays the
 * complete captured 1.21.11 registry set (23 registries, data inline / {@code hasData=true}) →
 * Update Tags → Finish Configuration, regardless of which packs the client echoed – including the
 * live {@code count=0} answer ViaVersion / ViaFabric give. The sequence must never disconnect.
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

    /** What {@link #assertSequenceCompletes} observed on the wire for one Registry Data run. */
    private record Sequence(java.util.Map<String, Boolean> hasData,
                            java.util.Map<String, Integer> entryCounts) {}

    /**
     * Walks Registry Data → Update Tags → Finish Configuration and returns, for each registry
     * sent, the {@code hasData} byte on its FIRST entry and the declared entry count. Caller
     * asserts on the maps to verify the right encoding mode AND a complete registry set were sent.
     */
    private Sequence assertSequenceCompletes(
            DataOutputStream out, DataInputStream in, byte[] knownPacksResponsePayload) throws IOException {
        send(out, Protocol.Packets.CONFIG_SELECT_KNOWN_PACKS_RESPONSE, knownPacksResponsePayload);

        java.util.Map<String, Boolean> registryHasData = new java.util.LinkedHashMap<>();
        java.util.Map<String, Integer> registryEntryCounts = new java.util.LinkedHashMap<>();
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
                registryEntryCounts.put(registryName, entryCount);
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
        return new Sequence(registryHasData, registryEntryCounts);
    }

    /** The registries a void 1.21.11 session must not omit, plus a few new-in-1.21.5+ ones. */
    private static void assertShipsFullDump(Sequence seq) {
        java.util.Map<String, Boolean> hasData = seq.hasData();
        // The complete captured set is 23 registries; every one is sent with data inline.
        assertEquals(23, hasData.size(), "Must replay the complete 1.21.11 registry set (got " + hasData.keySet() + ")");
        for (var e : hasData.entrySet()) {
            assertEquals(true, e.getValue(), e.getKey() + " must be sent hasData=true (data inline)");
        }
        // Hard-required registries and a couple that only exist post-1.21.4 (proving it is the real
        // 1.21.11 dump, not the old hand-rolled four).
        for (String required : new String[]{
                "minecraft:dimension_type", "minecraft:worldgen/biome", "minecraft:damage_type",
                "minecraft:chat_type", "minecraft:painting_variant", "minecraft:wolf_variant",
                "minecraft:cow_variant", "minecraft:pig_variant", "minecraft:dialog"}) {
            assertTrue(hasData.containsKey(required), "Registry set must include " + required);
        }
        // damage_type is the full vanilla set (50 entries), not a hand-rolled subset.
        assertTrue(seq.entryCounts().get("minecraft:damage_type") >= 40,
                "damage_type must be the full vanilla set, got " + seq.entryCounts().get("minecraft:damage_type"));
    }

    @Test
    void coreKnownPackAckStillShipsFullRegistryData() throws IOException {
        int port = freePort();
        MinecraftLimboServer server = start(port);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(3_000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            walkToKnownPacks(out, in);

            // Even when the client echoes the core pack, we send the data inline (always valid).
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            DataOutputStream r = new DataOutputStream(resp);
            Protocol.writeVarInt(r, 1);
            Protocol.writeString(r, "minecraft");
            Protocol.writeString(r, "core");
            Protocol.writeString(r, Protocol.MINECRAFT_VERSION_LABEL);
            assertShipsFullDump(assertSequenceCompletes(out, in, resp.toByteArray()));
        } finally {
            server.stop();
        }
    }

    @Test
    void countZeroResponseShipsFullRegistryData() throws IOException {
        int port = freePort();
        MinecraftLimboServer server = start(port);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(3_000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            walkToKnownPacks(out, in);

            // Live scenario: ViaVersion / ViaFabric answer Select Known Packs with count=0. We must
            // still ship the complete registry set inline and reach Finish Configuration.
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            DataOutputStream r = new DataOutputStream(resp);
            Protocol.writeVarInt(r, 0);
            assertShipsFullDump(assertSequenceCompletes(out, in, resp.toByteArray()));
        } finally {
            server.stop();
        }
    }
}
