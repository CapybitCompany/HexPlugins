package hex.limbo.limbo;

import hex.limbo.limbo.server.MinimalRegistries;
import hex.limbo.limbo.server.NbtWriter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-level smoke tests for the registry NBT bodies produced by {@code RegistryNbtWriters}.
 * Walks the encoded bytes against the NBT type table and verifies the field names that
 * 1.21.4 clients actually look for during PLAY init. A typo in a field name would not be
 * caught by a "does it compile" test but would kick the player live, so this is the safety net.
 */
class RegistryNbtSmokeTest {

    private byte[] encode(MinimalRegistries.NbtPayload payload) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            payload.write(out);
        }
        return baos.toByteArray();
    }

    @Test
    void overworldDimensionTypeStartsWithRootCompound() throws IOException {
        byte[] bytes = encode(findEntry("minecraft:dimension_type", "minecraft:overworld").nbt());
        assertTrue(bytes.length > 0);
        assertEquals(NbtWriter.TAG_COMPOUND, bytes[0],
                "Registry NBT must begin with a network-NBT root compound (no name byte)");
    }

    @Test
    void overworldDimensionTypeContainsRequiredFields() throws IOException {
        Set<String> names = collectTopLevelNames(encode(findEntry("minecraft:dimension_type", "minecraft:overworld").nbt()));
        // Fields the 1.21.4 client reads from data/minecraft/dimension_type/overworld.json.
        assertTrue(names.contains("min_y"), names::toString);
        assertTrue(names.contains("height"), names::toString);
        assertTrue(names.contains("logical_height"), names::toString);
        assertTrue(names.contains("effects"), names::toString);
        assertTrue(names.contains("coordinate_scale"), names::toString);
        assertTrue(names.contains("has_skylight"), names::toString);
        assertTrue(names.contains("has_ceiling"), names::toString);
        assertTrue(names.contains("infiniburn"), names::toString);
        assertTrue(names.contains("ambient_light"), names::toString);
        assertTrue(names.contains("monster_spawn_light_level"), names::toString);
    }

    @Test
    void plainsBiomeContainsRequiredFields() throws IOException {
        Set<String> names = collectTopLevelNames(encode(findEntry("minecraft:worldgen/biome", "minecraft:plains").nbt()));
        assertTrue(names.contains("temperature"), names::toString);
        assertTrue(names.contains("downfall"), names::toString);
        assertTrue(names.contains("has_precipitation"), names::toString);
        assertTrue(names.contains("effects"), names::toString);
    }

    @Test
    void chatChatTypeHasTwoSubCompounds() throws IOException {
        Set<String> names = collectTopLevelNames(encode(findEntry("minecraft:chat_type", "minecraft:chat").nbt()));
        assertTrue(names.contains("chat"), names::toString);
        assertTrue(names.contains("narration"), names::toString);
    }

    @Test
    void damageTypeGenericKillCarriesMessageId() throws IOException {
        Set<String> names = collectTopLevelNames(encode(findEntry("minecraft:damage_type", "minecraft:generic_kill").nbt()));
        assertTrue(names.contains("message_id"), names::toString);
        assertTrue(names.contains("scaling"), names::toString);
        assertTrue(names.contains("exhaustion"), names::toString);
    }

    /**
     * Walks the top-level fields of a root compound and returns their names. Stops on TAG_End.
     * Skips over the value of each entry so nested compounds don't contribute to the result set.
     */
    private Set<String> collectTopLevelNames(byte[] body) throws IOException {
        java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(body));
        Set<String> names = new HashSet<>();
        byte rootType = in.readByte();
        assertEquals(NbtWriter.TAG_COMPOUND, rootType, "Root must be TAG_Compound");
        // Network NBT root has no name; descend directly.
        readCompoundEntries(in, names);
        return names;
    }

    private void readCompoundEntries(java.io.DataInputStream in, Set<String> namesOut) throws IOException {
        while (true) {
            byte tag = in.readByte();
            if (tag == NbtWriter.TAG_END) {
                return;
            }
            String name = readNamedHeaderName(in);
            namesOut.add(name);
            skipValue(in, tag);
        }
    }

    private String readNamedHeaderName(java.io.DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] buf = new byte[len];
        in.readFully(buf);
        return new String(buf, StandardCharsets.UTF_8);
    }

    private void skipValue(java.io.DataInputStream in, byte tag) throws IOException {
        switch (tag) {
            case NbtWriter.TAG_BYTE -> in.readByte();
            case NbtWriter.TAG_SHORT -> in.readShort();
            case NbtWriter.TAG_INT -> in.readInt();
            case NbtWriter.TAG_LONG -> in.readLong();
            case NbtWriter.TAG_FLOAT -> in.readFloat();
            case NbtWriter.TAG_DOUBLE -> in.readDouble();
            case NbtWriter.TAG_BYTE_ARRAY -> in.skipBytes(in.readInt());
            case NbtWriter.TAG_STRING -> in.skipBytes(in.readUnsignedShort());
            case NbtWriter.TAG_LIST -> {
                byte elemType = in.readByte();
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    skipListElement(in, elemType);
                }
            }
            case NbtWriter.TAG_COMPOUND -> {
                // Recurse but discard nested names so collectTopLevelNames stays top-level only.
                Set<String> sink = new HashSet<>();
                readCompoundEntries(in, sink);
            }
            case NbtWriter.TAG_INT_ARRAY -> in.skipBytes(in.readInt() * 4);
            case NbtWriter.TAG_LONG_ARRAY -> in.skipBytes(in.readInt() * 8);
            default -> throw new IOException("Unsupported NBT tag in test: " + tag);
        }
    }

    private void skipListElement(java.io.DataInputStream in, byte elemType) throws IOException {
        switch (elemType) {
            case NbtWriter.TAG_STRING -> in.skipBytes(in.readUnsignedShort());
            case NbtWriter.TAG_COMPOUND -> {
                Set<String> sink = new HashSet<>();
                readCompoundEntries(in, sink);
            }
            default -> skipValue(in, elemType);
        }
    }

    private MinimalRegistries.Entry findEntry(String registry, String id) {
        for (MinimalRegistries.Registry r : MinimalRegistries.ALL) {
            if (!r.name().equals(registry)) continue;
            for (MinimalRegistries.Entry e : r.entries()) {
                if (e.id().equals(id)) return e;
            }
        }
        throw new AssertionError("Missing registry " + registry + " entry " + id);
    }
}
