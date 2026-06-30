package hex.limbo.limbo;

import hex.limbo.config.ForwardingMode;
import hex.limbo.config.PluginConfig;
import hex.limbo.limbo.server.MinecraftLimboServer;
import hex.limbo.limbo.server.Protocol;
import hex.limbo.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-level tests for the Velocity {@code velocity:player_info} modern-forwarding handshake
 * implemented by {@link hex.limbo.limbo.server.LimboConnection}. Each test stands up a real
 * {@link MinecraftLimboServer}, drives the four-state Minecraft login flow over a raw TCP
 * socket, and asserts the expected packet sequence.
 */
class VelocityForwardingHandshakeTest {

    private int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private MinecraftLimboServer startServer(int port, ForwardingMode mode, String secret) {
        PluginConfig.Limbo base = TestConfigs.limboWithForwarding(mode, secret);
        PluginConfig.Limbo limbo = new PluginConfig.Limbo(
                base.serverName(), base.bindHost(), port,
                base.spawnX(), base.spawnY(), base.spawnZ(), base.spawnYaw(), base.spawnPitch(),
                base.actionbarEnabled(), base.actionbarText(),
                base.forwarding(),
                base.debugProtocol()
        );
        MinecraftLimboServer server = new MinecraftLimboServer(limbo,
                LoggerFactory.getLogger(VelocityForwardingHandshakeTest.class));
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

    private static byte[] handshakePayload(int nextState) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        Protocol.writeVarInt(out, Protocol.MINECRAFT_PROTOCOL_VERSION);
        Protocol.writeString(out, "localhost");
        out.writeShort(25580);
        Protocol.writeVarInt(out, nextState);
        return baos.toByteArray();
    }

    private static byte[] loginStartPayload(String username, UUID uuid) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        Protocol.writeString(out, username);
        Protocol.writeUuid(out, uuid);
        return baos.toByteArray();
    }

    /** Build the signed body of a velocity:player_info response (everything after the HMAC). */
    private static byte[] buildSignedBody(int version, String ip, UUID uuid, String username) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        Protocol.writeVarInt(out, version);
        Protocol.writeString(out, ip);
        Protocol.writeUuid(out, uuid);
        Protocol.writeString(out, username);
        Protocol.writeVarInt(out, 0); // no properties
        return baos.toByteArray();
    }

    private static byte[] hmacSha256(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(body);
    }

    /** Assemble Login Plugin Response payload: VarInt messageId, boolean successful, then data. */
    private static byte[] pluginResponsePayload(int messageId, boolean successful, byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        Protocol.writeVarInt(out, messageId);
        out.writeBoolean(successful);
        if (successful && data != null) {
            out.write(data);
        }
        return baos.toByteArray();
    }

    @Test
    void modernForwardingRequestIsSentAfterLoginStart() throws Exception {
        int port = freePort();
        MinecraftLimboServer server = startServer(port, ForwardingMode.MODERN, "");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(3_000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            send(out, Protocol.Packets.HANDSHAKE_INTENTION, handshakePayload(2));
            UUID offlineUuid = UUID.nameUUIDFromBytes("OfflinePlayer:ForwardTester".getBytes(StandardCharsets.UTF_8));
            send(out, Protocol.Packets.LOGIN_START, loginStartPayload("ForwardTester", offlineUuid));

            // Expect Login Plugin Request, NOT Login Success.
            Frame request = readFrame(in);
            assertEquals(Protocol.Packets.LOGIN_PLUGIN_REQUEST_OUT, request.packetId(),
                    "Server must send a Login Plugin Request before Login Success in MODERN mode.");
            int messageId = Protocol.readVarInt(request.payload());
            String channel = Protocol.readString(request.payload(), 256);
            assertEquals("velocity:player_info", channel,
                    "Login Plugin Request channel must be velocity:player_info.");
            int requestedVersion = Protocol.readVarInt(request.payload());
            assertTrue(requestedVersion >= 1, "Backend must request at least forwarding version 1.");

            // Forge a valid Velocity response with arbitrary HMAC (no validation, empty secret).
            UUID forwardedUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
            byte[] signed = buildSignedBody(1, "1.2.3.4", forwardedUuid, "ForwardTester");
            byte[] response = new byte[32 + signed.length];
            // 32 bytes of zero HMAC are accepted when secret == "".
            System.arraycopy(signed, 0, response, 32, signed.length);

            send(out, Protocol.Packets.LOGIN_PLUGIN_RESPONSE,
                    pluginResponsePayload(messageId, true, response));

            // Now Login Success.
            Frame success = readFrame(in);
            assertEquals(Protocol.Packets.LOGIN_SUCCESS_OUT, success.packetId());
            UUID returnedUuid = Protocol.readUuid(success.payload());
            String returnedName = Protocol.readString(success.payload(), 16);
            assertEquals(forwardedUuid, returnedUuid,
                    "Login Success must use the UUID forwarded by Velocity, not the client's claim.");
            assertEquals("ForwardTester", returnedName);
        } finally {
            server.stop();
        }
    }

    @Test
    void invalidMessageIdProducesLoginDisconnect() throws Exception {
        int port = freePort();
        MinecraftLimboServer server = startServer(port, ForwardingMode.MODERN, "");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(3_000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            send(out, Protocol.Packets.HANDSHAKE_INTENTION, handshakePayload(2));
            send(out, Protocol.Packets.LOGIN_START,
                    loginStartPayload("WrongIdTester", UUID.randomUUID()));
            readFrame(in); // discard plugin request

            // Respond with wrong message id.
            byte[] signed = buildSignedBody(1, "1.2.3.4", UUID.randomUUID(), "WrongIdTester");
            byte[] response = new byte[32 + signed.length];
            System.arraycopy(signed, 0, response, 32, signed.length);
            send(out, Protocol.Packets.LOGIN_PLUGIN_RESPONSE,
                    pluginResponsePayload(99, true, response));

            Frame disconnect = readFrame(in);
            assertEquals(Protocol.Packets.LOGIN_DISCONNECT_OUT, disconnect.packetId());
        } finally {
            server.stop();
        }
    }

    @Test
    void unsuccessfulResponseProducesLoginDisconnect() throws Exception {
        int port = freePort();
        MinecraftLimboServer server = startServer(port, ForwardingMode.MODERN, "");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(3_000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            send(out, Protocol.Packets.HANDSHAKE_INTENTION, handshakePayload(2));
            send(out, Protocol.Packets.LOGIN_START,
                    loginStartPayload("UnsuccessfulTester", UUID.randomUUID()));
            Frame req = readFrame(in);
            int messageId = Protocol.readVarInt(req.payload());

            // successful=false signals "client refused / channel unknown".
            send(out, Protocol.Packets.LOGIN_PLUGIN_RESPONSE,
                    pluginResponsePayload(messageId, false, null));

            Frame disconnect = readFrame(in);
            assertEquals(Protocol.Packets.LOGIN_DISCONNECT_OUT, disconnect.packetId());
        } finally {
            server.stop();
        }
    }

    @Test
    void correctHmacIsAcceptedWhenSecretConfigured() throws Exception {
        String secret = "shared-test-secret";
        int port = freePort();
        MinecraftLimboServer server = startServer(port, ForwardingMode.MODERN, secret);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(3_000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            send(out, Protocol.Packets.HANDSHAKE_INTENTION, handshakePayload(2));
            send(out, Protocol.Packets.LOGIN_START,
                    loginStartPayload("SecretTester", UUID.randomUUID()));
            Frame req = readFrame(in);
            int messageId = Protocol.readVarInt(req.payload());

            UUID forwardedUuid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
            byte[] signed = buildSignedBody(1, "1.2.3.4", forwardedUuid, "SecretTester");
            byte[] hmac = hmacSha256(secret, signed);
            byte[] response = new byte[32 + signed.length];
            System.arraycopy(hmac, 0, response, 0, 32);
            System.arraycopy(signed, 0, response, 32, signed.length);

            send(out, Protocol.Packets.LOGIN_PLUGIN_RESPONSE,
                    pluginResponsePayload(messageId, true, response));

            Frame success = readFrame(in);
            assertEquals(Protocol.Packets.LOGIN_SUCCESS_OUT, success.packetId());
        } finally {
            server.stop();
        }
    }

    @Test
    void wrongHmacProducesLoginDisconnect() throws Exception {
        int port = freePort();
        MinecraftLimboServer server = startServer(port, ForwardingMode.MODERN, "shared-test-secret");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(3_000);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            send(out, Protocol.Packets.HANDSHAKE_INTENTION, handshakePayload(2));
            send(out, Protocol.Packets.LOGIN_START,
                    loginStartPayload("WrongMacTester", UUID.randomUUID()));
            Frame req = readFrame(in);
            int messageId = Protocol.readVarInt(req.payload());

            byte[] signed = buildSignedBody(1, "1.2.3.4", UUID.randomUUID(), "WrongMacTester");
            byte[] hmac = hmacSha256("DIFFERENT-SECRET", signed);
            byte[] response = new byte[32 + signed.length];
            System.arraycopy(hmac, 0, response, 0, 32);
            System.arraycopy(signed, 0, response, 32, signed.length);

            send(out, Protocol.Packets.LOGIN_PLUGIN_RESPONSE,
                    pluginResponsePayload(messageId, true, response));

            Frame disconnect = readFrame(in);
            assertEquals(Protocol.Packets.LOGIN_DISCONNECT_OUT, disconnect.packetId());
        } finally {
            server.stop();
        }
    }
}
