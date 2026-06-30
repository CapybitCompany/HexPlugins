package hex.limbo.limbo.server;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Builds the payload of a "Chunk Data and Update Light" packet for a totally empty void chunk.
 *
 * <p>Targets Minecraft 1.21.4 (protocol 769) with the vanilla overworld dimension type: 24
 * sections (Y from -64 to 320 in 16-block sections). Every section is a single-value palette of
 * air; every biome is a single-value palette of id 0 ({@code minecraft:plains} in the default
 * client registry, but the value is functionally irrelevant for a void).
 *
 * <p>Light data is intentionally empty – all four light masks have zero longs, so the client
 * applies default lighting (full bright on the sky side, dark below). The proxy doesn't need
 * real lighting for an authentication limbo.
 */
final class EmptyChunk {

    /** Vanilla 1.21.4 overworld height range maps to this many 16-block sections. */
    static final int SECTION_COUNT = 24;

    /** 256 heightmap cells × 9 bits per cell, packed seven cells per long → 37 longs. */
    private static final int HEIGHTMAP_LONGS = 37;

    private EmptyChunk() {}

    /**
     * Encode an empty Chunk Data and Update Light packet payload for chunk coordinates
     * {@code (chunkX, chunkZ)}. Caller is responsible for sending it with the right packet id.
     */
    static byte[] buildPayload(int chunkX, int chunkZ) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeInt(chunkX);
            out.writeInt(chunkZ);

            writeHeightmaps(out);

            // Section data: serialise into a temporary buffer so we can prefix it with the length.
            ByteArrayOutputStream sectionsBuf = new ByteArrayOutputStream();
            try (DataOutputStream sectionsOut = new DataOutputStream(sectionsBuf)) {
                for (int i = 0; i < SECTION_COUNT; i++) {
                    writeEmptySection(sectionsOut);
                }
            }
            byte[] sectionBytes = sectionsBuf.toByteArray();
            Protocol.writeVarInt(out, sectionBytes.length);
            out.write(sectionBytes);

            // No block entities.
            Protocol.writeVarInt(out, 0);

            // Light data: four empty BitSets followed by two empty arrays-of-arrays.
            // BitSet encoding = VarInt (long count) + that many longs. Zero longs means no bits set.
            Protocol.writeVarInt(out, 0); // sky light mask
            Protocol.writeVarInt(out, 0); // block light mask
            Protocol.writeVarInt(out, 0); // empty sky light mask
            Protocol.writeVarInt(out, 0); // empty block light mask
            Protocol.writeVarInt(out, 0); // sky light array count
            Protocol.writeVarInt(out, 0); // block light array count
        } catch (IOException ex) {
            throw new IllegalStateException("In-memory chunk encode failed", ex);
        }
        return baos.toByteArray();
    }

    private static void writeEmptySection(DataOutputStream out) throws IOException {
        out.writeShort(0); // non-air block count

        // Block states: single-value palette, value = air (block state id 0).
        out.writeByte(0); // bits per entry = 0 → single value
        Protocol.writeVarInt(out, 0); // palette value
        Protocol.writeVarInt(out, 0); // data array length (no data when single-value)

        // Biomes: single-value palette, value = biome id 0.
        out.writeByte(0); // bits per entry = 0 → single value
        Protocol.writeVarInt(out, 0); // palette value
        Protocol.writeVarInt(out, 0); // data array length
    }

    private static void writeHeightmaps(DataOutputStream out) throws IOException {
        // Network NBT: outer compound has no name.
        NbtWriter.startRootCompound(out);
        long[] zeros = new long[HEIGHTMAP_LONGS];
        NbtWriter.writeNamedLongArray(out, "MOTION_BLOCKING", zeros);
        NbtWriter.writeNamedLongArray(out, "WORLD_SURFACE", zeros);
        NbtWriter.endCompound(out);
    }
}
