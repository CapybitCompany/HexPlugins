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
 * <p>HexLimbo targets a single Minecraft version <b>natively</b>: protocol {@code 774}
 * (Minecraft 1.21.11). The limbo advertises this version in its status ping so ViaVersion on the
 * proxy detects the backend as 1.21.11 and passes a 1.21.11 client straight through WITHOUT
 * translation. This is the key to the design: an earlier iteration spoke 1.21.4 (769) behind
 * ViaVersion, which forced ViaVersion to translate the limbo's 1.21.4 registry data up to the
 * client's version. That failed – the newer client requires registries that never existed in
 * 1.21.4 (cow_variant, pig_variant, chicken_variant, frog_variant, wolf_sound_variant, …), which
 * cannot be carried over a 1.21.4 connection, so the translated client always disconnected at the
 * CONFIGURATION → PLAY transition. Speaking the client's native version removes the translation
 * layer entirely, exactly like standalone limbo servers do.
 *
 * <p>Compression is not negotiated by the limbo. Packets always use the uncompressed framing:
 * VarInt packetLength | VarInt packetId | payload.
 */
public final class Protocol {

    private Protocol() {}

    public static final int MINECRAFT_PROTOCOL_VERSION = 774;
    public static final String MINECRAFT_VERSION_LABEL = "1.21.11";

    /**
     * Packet IDs for protocol 774 (Minecraft 1.21.11).
     *
     * <p>The internal limbo speaks ONLY this protocol version. A native 1.21.11 client connects
     * without ViaVersion translation; clients on other versions are translated DOWN to 1.21.11 by
     * ViaBackwards on the proxy (the well-supported direction). A handshake announcing any other
     * protocol version is rejected with a clean disconnect message – we never "proceed anyway".
     *
     * <p>Source of truth: <code>minecraft-data</code> repository, file
     * {@code data/pc/1.21.11/protocol.json}. The HANDSHAKE / STATUS / LOGIN / CONFIGURATION packet
     * ids are byte-for-byte identical to 1.21.4; only the PLAY-state ids shifted (verified against
     * that file). When retargeting again, re-review every constant below – chunk and play-state
     * ids in particular drift across versions.
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
        /** Serverbound Keep Alive response (protocol 774). */
        public static final int PLAY_KEEPALIVE_RESPONSE = 0x1B;
        /** Serverbound Set Player Position (protocol 774). */
        public static final int PLAY_PLAYER_POSITION = 0x1D;
        /** Serverbound Set Player Position and Rotation (protocol 774). */
        public static final int PLAY_PLAYER_POSITION_ROTATION = 0x1E;
        /** Serverbound Set Player Rotation (protocol 774). */
        public static final int PLAY_PLAYER_ROTATION = 0x1F;
        public static final int PLAY_CONFIRM_TELEPORT = 0x00;
        public static final int PLAY_CHAT_COMMAND = 0x06;
        public static final int PLAY_CHAT_MESSAGE = 0x08;

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
        /** Update Enabled Features ("feature_flags") clientbound (protocol 774). */
        public static final int CONFIG_FEATURE_FLAGS_OUT = 0x0C;
        public static final int CONFIG_UPDATE_TAGS_OUT = 0x0D;
        public static final int CONFIG_SELECT_KNOWN_PACKS_OUT = 0x0E;
        /** Clientbound Disconnect (play) (protocol 774). Vanilla "kick_disconnect". */
        public static final int PLAY_DISCONNECT_OUT = 0x20;
        /** Clientbound Game Event (protocol 774). Vanilla "game_state_change". */
        public static final int PLAY_GAME_EVENT_OUT = 0x26;
        /** Clientbound Keep Alive (protocol 774). */
        public static final int PLAY_KEEPALIVE_OUT = 0x2B;
        /** Clientbound Chunk Data and Update Light (protocol 774). Vanilla "map_chunk". */
        public static final int PLAY_CHUNK_DATA_OUT = 0x2C;
        /** Clientbound Login (play) (protocol 774). Vanilla "login". */
        public static final int PLAY_LOGIN_OUT = 0x30;
        /** Clientbound Set Action Bar Text (protocol 774). Vanilla "action_bar". */
        public static final int PLAY_ACTIONBAR_OUT = 0x55;
        /** Clientbound Synchronize Player Position (protocol 774). Vanilla "position". */
        public static final int PLAY_SYNC_PLAYER_POSITION_OUT = 0x46;
        /** Clientbound Set Default Spawn Position (protocol 774). Vanilla "spawn_position". */
        public static final int PLAY_SET_DEFAULT_SPAWN_POSITION_OUT = 0x5F;
        /** Clientbound Player Abilities (protocol 774). Vanilla "abilities". */
        public static final int PLAY_PLAYER_ABILITIES_OUT = 0x3E;
        /** Clientbound Set Center Chunk (protocol 774). Vanilla "update_view_position". */
        public static final int PLAY_SET_CENTER_CHUNK_OUT = 0x5C;

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
