package hex.limbo.limbo.server;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Low-level Minecraft protocol primitives: VarInt/VarLong, length-prefixed strings, UUIDs, and
 * NBT tag writers.
 *
 * <p>HexLimbo targets a single Minecraft version: protocol {@code 769} (Minecraft 1.21.4). The
 * encoded packet IDs in {@link Packets} are hand-picked for that version. Players connecting via
 * a Velocity proxy with ViaVersion will be translated to/from 1.21.4 by ViaVersion.
 *
 * <p>Compression is not negotiated by the limbo. Packets always use the uncompressed framing:
 * VarInt packetLength | VarInt packetId | payload.
 */
public final class Protocol {

    private Protocol() {}

    public static final int MINECRAFT_PROTOCOL_VERSION = 769;
    public static final String MINECRAFT_VERSION_LABEL = "1.21.4";

    /**
     * Packet IDs for protocol 769 (Minecraft 1.21.4).
     *
     * <p>The internal limbo speaks ONLY this protocol version. Clients on other versions must be
     * translated by ViaVersion / ViaBackwards on the proxy. A handshake announcing any other
     * protocol version is rejected with a clean disconnect message – we never "proceed anyway".
     *
     * <p>Source of truth: <code>minecraft-data</code> repository, file
     * {@code data/pc/1.21.4/protocol.json}. When retargeting a different Minecraft version,
     * every constant below must be reviewed against that file for the new version – chunk and
     * play-state packet IDs in particular drift across versions.
     */
    public static final class Packets {
        // ----- SERVERBOUND -----
        public static final int HANDSHAKE_INTENTION = 0x00;
        public static final int STATUS_REQUEST = 0x00;
        public static final int STATUS_PING = 0x01;
        public static final int LOGIN_START = 0x00;
        public static final int LOGIN_PLUGIN_RESPONSE = 0x02;
        public static final int LOGIN_ACKNOWLEDGED = 0x03;
        public static final int CONFIG_CLIENT_INFORMATION = 0x00;
        public static final int CONFIG_PLUGIN_MESSAGE = 0x02;
        public static final int CONFIG_FINISH_ACK = 0x03;
        public static final int CONFIG_KEEPALIVE = 0x04;
        public static final int CONFIG_SELECT_KNOWN_PACKS_RESPONSE = 0x07;
        /** Serverbound Keep Alive response (protocol 769). */
        public static final int PLAY_KEEPALIVE_RESPONSE = 0x1A;
        /** Serverbound Set Player Position (protocol 769). */
        public static final int PLAY_PLAYER_POSITION = 0x1C;
        /** Serverbound Set Player Position and Rotation (protocol 769). */
        public static final int PLAY_PLAYER_POSITION_ROTATION = 0x1D;
        /** Serverbound Set Player Rotation (protocol 769). */
        public static final int PLAY_PLAYER_ROTATION = 0x1E;
        public static final int PLAY_CONFIRM_TELEPORT = 0x00;
        public static final int PLAY_CHAT_COMMAND = 0x05;
        public static final int PLAY_CHAT_MESSAGE = 0x07;

        // ----- CLIENTBOUND -----
        public static final int STATUS_RESPONSE_OUT = 0x00;
        public static final int STATUS_PONG_OUT = 0x01;
        public static final int LOGIN_DISCONNECT_OUT = 0x00;
        public static final int LOGIN_SUCCESS_OUT = 0x02;
        public static final int LOGIN_PLUGIN_REQUEST_OUT = 0x04;
        public static final int CONFIG_DISCONNECT_OUT = 0x02;
        public static final int CONFIG_FINISH_OUT = 0x03;
        public static final int CONFIG_KEEPALIVE_OUT = 0x04;
        public static final int CONFIG_REGISTRY_DATA_OUT = 0x07;
        /** Update Enabled Features ("feature_flags") clientbound (protocol 769). */
        public static final int CONFIG_FEATURE_FLAGS_OUT = 0x0C;
        public static final int CONFIG_UPDATE_TAGS_OUT = 0x0D;
        public static final int CONFIG_SELECT_KNOWN_PACKS_OUT = 0x0E;
        public static final int PLAY_DISCONNECT_OUT = 0x1D;
        /** Clientbound Game Event (protocol 769). Vanilla "game_state_change". */
        public static final int PLAY_GAME_EVENT_OUT = 0x23;
        /** Clientbound Keep Alive (protocol 769). */
        public static final int PLAY_KEEPALIVE_OUT = 0x27;
        /** Clientbound Chunk Data and Update Light (protocol 769). Vanilla "map_chunk". */
        public static final int PLAY_CHUNK_DATA_OUT = 0x28;
        /** Clientbound Login (play) (protocol 769). Vanilla "login". */
        public static final int PLAY_LOGIN_OUT = 0x2C;
        /** Clientbound Set Action Bar Text (protocol 769). Vanilla "action_bar". */
        public static final int PLAY_ACTIONBAR_OUT = 0x51;
        /** Clientbound Synchronize Player Position (protocol 769). Vanilla "position". */
        public static final int PLAY_SYNC_PLAYER_POSITION_OUT = 0x42;
        /** Clientbound Set Default Spawn Position (protocol 769). Vanilla "spawn_position". */
        public static final int PLAY_SET_DEFAULT_SPAWN_POSITION_OUT = 0x5B;
        /** Clientbound Player Abilities (protocol 769). Vanilla "abilities". */
        public static final int PLAY_PLAYER_ABILITIES_OUT = 0x3A;
        /** Clientbound Set Center Chunk (protocol 769). Vanilla "update_view_position". */
        public static final int PLAY_SET_CENTER_CHUNK_OUT = 0x58;

        private Packets() {}
    }

    // ----- VarInt -----

    public static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.writeByte(value);
                return;
            }
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    public static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            int currentByte = in.read();
            if (currentByte == -1) {
                throw new EOFException("Stream closed while reading VarInt");
            }
            value |= (currentByte & 0x7F) << position;
            if ((currentByte & 0x80) == 0) {
                return value;
            }
            position += 7;
            if (position >= 32) {
                throw new IOException("VarInt is too big");
            }
        }
    }

    public static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            int currentByte = in.read();
            if (currentByte == -1) {
                throw new EOFException("Stream closed while reading VarInt");
            }
            value |= (currentByte & 0x7F) << position;
            if ((currentByte & 0x80) == 0) {
                return value;
            }
            position += 7;
            if (position >= 32) {
                throw new IOException("VarInt is too big");
            }
        }
    }

    public static int varIntSize(int value) {
        for (int i = 1; i < 5; i++) {
            if ((value & -1 << i * 7) == 0) {
                return i;
            }
        }
        return 5;
    }

    // ----- VarLong -----

    public static void writeVarLong(DataOutputStream out, long value) throws IOException {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                out.writeByte((int) value);
                return;
            }
            out.writeByte((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
    }

    // ----- String -----

    public static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    public static String readString(DataInputStream in, int maxLength) throws IOException {
        int length = readVarInt(in);
        if (length < 0 || length > maxLength * 4) {
            throw new IOException("String length out of bounds: " + length);
        }
        byte[] buf = new byte[length];
        in.readFully(buf);
        return new String(buf, StandardCharsets.UTF_8);
    }

    // ----- UUID -----

    public static void writeUuid(DataOutputStream out, UUID uuid) throws IOException {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    public static UUID readUuid(DataInputStream in) throws IOException {
        long msb = in.readLong();
        long lsb = in.readLong();
        return new UUID(msb, lsb);
    }

    // ----- Boolean / Byte / Int / Long / Float / Double -----

    public static void writeBoolean(DataOutputStream out, boolean value) throws IOException {
        out.writeByte(value ? 1 : 0);
    }

    // ----- Packet framing -----

    /**
     * Write a complete packet: VarInt total length, VarInt packet id, payload.
     */
    public static void writePacket(OutputStream out, int packetId, byte[] payload) throws IOException {
        int idSize = varIntSize(packetId);
        int total = idSize + payload.length;
        DataOutputStream dos = new DataOutputStream(out);
        writeVarInt(dos, total);
        writeVarInt(dos, packetId);
        dos.write(payload);
        dos.flush();
    }

    /** Convenience: build a payload via a {@link PayloadWriter}. */
    public static byte[] payload(PayloadWriter writer) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            writer.write(dos);
        } catch (IOException ex) {
            throw new IllegalStateException("Payload writer failed (in-memory)", ex);
        }
        return baos.toByteArray();
    }

    @FunctionalInterface
    public interface PayloadWriter {
        void write(DataOutputStream out) throws IOException;
    }
}
