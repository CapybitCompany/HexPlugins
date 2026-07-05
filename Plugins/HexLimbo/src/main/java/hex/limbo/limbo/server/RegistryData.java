package hex.limbo.limbo.server;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The complete Minecraft 1.21.11 registry set the limbo replays during the CONFIGURATION phase,
 * loaded from the bundled {@code registries-1.21.11.nbt} resource.
 *
 * <h2>Why a captured dump instead of hand-rolled NBT</h2>
 *
 * A native 1.21.11 client (and ViaVersion/ViaFabric on its behalf) validates the registries at the
 * CONFIGURATION → PLAY transition and disconnects if ANY synchronized registry is missing or has a
 * malformed entry. The registry codecs changed substantially after 1.21.4 – e.g. the overworld
 * {@code dimension_type} gained {@code timelines} and an {@code attributes} sub-tree, and several
 * whole registries were added ({@code cow_variant}, {@code pig_variant}, {@code chicken_variant},
 * {@code frog_variant}, {@code wolf_sound_variant}, {@code zombie_nautilus_variant}, {@code dialog},
 * {@code timeline}, …). Hand-writing minimal NBT for all of that is unreliable, so instead we ship
 * the exact registry data a real 1.21.11 server sends, captured from {@code minecraft-data}
 * ({@code pc/1.21.11/loginPacket.json → dimensionCodec}) and serialised to the wire format offline.
 *
 * <p>Each element of {@link #all()} is a ready-to-send Clientbound Registry Data payload:
 * {@code String registryId, VarInt entryCount, (String entryId, Boolean hasData=true, NBT)*}. We
 * always send {@code hasData=true} (data inline), which is valid regardless of what the client
 * echoed for Select Known Packs – important because ViaVersion/ViaFabric answer that handshake with
 * {@code count=0}.
 *
 * <p>Resource layout: {@code VarInt registryCount}, then per registry {@code VarInt payloadLength}
 * followed by that many payload bytes.
 */
final class RegistryData {

    /** One synchronized registry: its resource-location name and the full Registry Data payload. */
    record Registry(String name, byte[] payload) {}

    private static final List<Registry> REGISTRIES = load();
    private static final byte[] UPDATE_TAGS_PAYLOAD = loadBytes("/tags-1.21.11.nbt");

    private RegistryData() {}

    /** All registries, in the exact order a vanilla 1.21.11 server sends them. */
    static List<Registry> all() {
        return REGISTRIES;
    }

    /**
     * The ready-to-send Update Tags packet payload (registry → tag → entry ids), captured for
     * 1.21.11. The client requires populated tags when transitioning CONFIGURATION → PLAY; an empty
     * Update Tags makes it reject the session right after Finish Configuration.
     */
    static byte[] updateTagsPayload() {
        return UPDATE_TAGS_PAYLOAD;
    }

    private static byte[] loadBytes(String resource) {
        try (InputStream in = RegistryData.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Bundled resource " + resource + " is missing from the jar");
            }
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to load bundled resource " + resource, ex);
        }
    }

    private static List<Registry> load() {
        try (InputStream raw = RegistryData.class.getResourceAsStream("/registries-1.21.11.nbt")) {
            if (raw == null) {
                throw new IllegalStateException("Bundled resource registries-1.21.11.nbt is missing from the jar");
            }
            DataInputStream in = new DataInputStream(new BufferedInputStream(raw));
            int registryCount = Protocol.readVarInt(in);
            List<Registry> out = new ArrayList<>(registryCount);
            for (int i = 0; i < registryCount; i++) {
                int payloadLength = Protocol.readVarInt(in);
                byte[] payload = new byte[payloadLength];
                in.readFully(payload);
                out.add(new Registry(peekRegistryName(payload), payload));
            }
            return List.copyOf(out);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to load bundled 1.21.11 registry data", ex);
        }
    }

    /** The first field of a Registry Data payload is the registry id; read it for logging. */
    private static String peekRegistryName(byte[] payload) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            return Protocol.readString(in, 32767);
        } catch (IOException ex) {
            return "?";
        }
    }
}
