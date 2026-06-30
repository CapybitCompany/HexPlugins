package hex.limbo.limbo.server;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Minimal NBT encoder used by the limbo for the few packets that need NBT bodies:
 * chunk-data heightmaps and Registry Data entries.
 *
 * <p>"Network NBT" semantics (1.20.2+): the outermost {@code TAG_Compound} is written without a
 * name (just the tag byte). Compound entries inside still carry names. List elements carry no
 * tag and no name – just the raw value, since the list header announces the element type.
 */
public final class NbtWriter {

    public static final byte TAG_END = 0x00;
    public static final byte TAG_BYTE = 0x01;
    public static final byte TAG_SHORT = 0x02;
    public static final byte TAG_INT = 0x03;
    public static final byte TAG_LONG = 0x04;
    public static final byte TAG_FLOAT = 0x05;
    public static final byte TAG_DOUBLE = 0x06;
    public static final byte TAG_BYTE_ARRAY = 0x07;
    public static final byte TAG_STRING = 0x08;
    public static final byte TAG_LIST = 0x09;
    public static final byte TAG_COMPOUND = 0x0A;
    public static final byte TAG_INT_ARRAY = 0x0B;
    public static final byte TAG_LONG_ARRAY = 0x0C;

    private NbtWriter() {}

    // ---------- structural ----------

    /** Start a network-NBT root compound (single byte: tag id, no name). */
    public static void startRootCompound(DataOutputStream out) throws IOException {
        out.writeByte(TAG_COMPOUND);
    }

    public static void startNamedCompound(DataOutputStream out, String name) throws IOException {
        out.writeByte(TAG_COMPOUND);
        writeUtfShort(out, name);
    }

    public static void endCompound(DataOutputStream out) throws IOException {
        out.writeByte(TAG_END);
    }

    // ---------- named scalars ----------

    public static void writeNamedByte(DataOutputStream out, String name, byte value) throws IOException {
        out.writeByte(TAG_BYTE);
        writeUtfShort(out, name);
        out.writeByte(value);
    }

    /** NBT has no boolean tag; convention is a byte with value 0 or 1. */
    public static void writeNamedBoolean(DataOutputStream out, String name, boolean value) throws IOException {
        writeNamedByte(out, name, (byte) (value ? 1 : 0));
    }

    public static void writeNamedInt(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(TAG_INT);
        writeUtfShort(out, name);
        out.writeInt(value);
    }

    public static void writeNamedLong(DataOutputStream out, String name, long value) throws IOException {
        out.writeByte(TAG_LONG);
        writeUtfShort(out, name);
        out.writeLong(value);
    }

    public static void writeNamedFloat(DataOutputStream out, String name, float value) throws IOException {
        out.writeByte(TAG_FLOAT);
        writeUtfShort(out, name);
        out.writeFloat(value);
    }

    public static void writeNamedDouble(DataOutputStream out, String name, double value) throws IOException {
        out.writeByte(TAG_DOUBLE);
        writeUtfShort(out, name);
        out.writeDouble(value);
    }

    public static void writeNamedString(DataOutputStream out, String name, String value) throws IOException {
        out.writeByte(TAG_STRING);
        writeUtfShort(out, name);
        writeUtfShort(out, value);
    }

    // ---------- arrays ----------

    public static void writeNamedLongArray(DataOutputStream out, String name, long[] values) throws IOException {
        out.writeByte(TAG_LONG_ARRAY);
        writeUtfShort(out, name);
        out.writeInt(values.length);
        for (long v : values) {
            out.writeLong(v);
        }
    }

    // ---------- lists ----------

    /**
     * Start a named list of strings. Caller must follow with {@code count} calls to
     * {@link #writeListString(DataOutputStream, String)} and nothing else.
     */
    public static void startNamedStringList(DataOutputStream out, String name, int count) throws IOException {
        out.writeByte(TAG_LIST);
        writeUtfShort(out, name);
        out.writeByte(TAG_STRING);
        out.writeInt(count);
    }

    /** Raw string for a TAG_LIST element. No tag byte, no name – list header already declared them. */
    public static void writeListString(DataOutputStream out, String value) throws IOException {
        writeUtfShort(out, value);
    }

    // ---------- low-level ----------

    private static void writeUtfShort(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }
}
