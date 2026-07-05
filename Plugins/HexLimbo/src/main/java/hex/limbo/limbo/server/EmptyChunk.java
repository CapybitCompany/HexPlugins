package hex.limbo.limbo.server;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Builds the payload of a "Chunk Data and Update Light" packet for a totally empty void chunk.
 *
 * <p>Targets Minecraft 1.21.11 (protocol 774) with the vanilla {@code minecraft:the_end}
 * dimension type: 16 sections (Y from 0 to 256 in 16-block sections). The section count MUST match
 * the dimension height (the_end is 256 tall = 16 sections) or the client rejects the chunk. Every
 * section is a single-value palette of air; every biome is a single-value palette of
 * {@link #END_BIOME_ID} ({@code minecraft:the_end}) so the void renders with End fog under the End
 * sky.
 *
 * <p>Two 1.21.5+ format changes relative to 1.21.4 are baked in here:
 * <ul>
 *     <li>Heightmaps are a prefixed array of {@code (type VarInt, packed long[])} entries, NOT an
 *     NBT compound. A void needs no height information, so we send an empty array (count 0).</li>
 *     <li>A paletted container no longer carries a "Data Array Length" VarInt – the client derives
 *     it from bits-per-entry. For a single-valued palette (0 bits) that means we write only the
 *     bits-per-entry byte and the palette value; no length, no data.</li>
 * </ul>
 *
 * <p>Light data is intentionally empty – all four light masks have zero longs, so the client
 * applies default lighting (full bright on the sky side, dark below). The proxy doesn't need
 * real lighting for an authentication limbo.
 */
final class EmptyChunk {

    /** Vanilla 1.21.11 {@code the_end} height range (0..256) maps to this many 16-block sections. */
    static final int SECTION_COUNT = 16;

    /**
     * Biome palette value for every section: index of {@code minecraft:the_end} in the captured
     * 1.21.11 biome registry we ship (see {@code registries-1.21.11.nbt}). Keep this in sync if the
     * biome registry order ever changes.
     */
    static final int END_BIOME_ID = 56;

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

            // Heightmaps (1.21.5+): prefixed array of (type VarInt, packed long[]). A void needs
            // none, so send an empty array.
            Protocol.writeVarInt(out, 0);

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

        // Block states: single-value palette, value = air (block state id 0). 1.21.5+ omits the
        // trailing data-array-length VarInt for single-valued palettes.
        out.writeByte(0); // bits per entry = 0 → single value
        Protocol.writeVarInt(out, 0); // palette value

        // Biomes: single-value palette, value = the_end biome index.
        out.writeByte(0); // bits per entry = 0 → single value
        Protocol.writeVarInt(out, END_BIOME_ID); // palette value
    }
}
