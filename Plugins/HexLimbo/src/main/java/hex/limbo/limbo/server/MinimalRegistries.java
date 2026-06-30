package hex.limbo.limbo.server;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * The data-driven registries the limbo bootstraps during the CONFIGURATION phase of the 1.21.4
 * (protocol 769) handshake.
 *
 * <h2>Two modes</h2>
 *
 * The actual on-wire encoding depends on whether the client acknowledged
 * {@code minecraft:core 1.21.4} in its Select Known Packs response:
 *
 * <ul>
 *     <li><b>Known pack accepted</b>: each entry is sent with {@code hasData=false}. The client
 *     resolves the NBT from its own built-in minecraft:core pack. This is what vanilla servers
 *     do – packet payloads stay tiny.</li>
 *     <li><b>Known pack missing</b> (Velocity/ViaVersion has been observed to answer Select
 *     Known Packs with {@code count=0}): entries that ship full NBT via {@link Entry#nbt()} are
 *     sent with {@code hasData=true}; entries without an NBT writer are skipped entirely –
 *     sending a {@code hasData=true} entry without a body would be worse, the client would try
 *     to deserialise garbage and disconnect.</li>
 * </ul>
 *
 * <h2>Scope</h2>
 *
 * v1 ships full NBT for the four registries actively referenced during PLAY init:
 * {@code minecraft:dimension_type/overworld}, {@code minecraft:worldgen/biome/plains},
 * {@code minecraft:damage_type/generic_kill}, {@code minecraft:chat_type/chat}. The remaining
 * registries (trim, wolf/cat/painting variants, banner, enchantment, jukebox, instrument) are
 * token-only – they participate when the known pack is accepted, and are skipped in fallback
 * mode. A void player never references them.
 */
public final class MinimalRegistries {

    /** Writes a complete NBT compound (root, no name) for a single registry entry. */
    @FunctionalInterface
    public interface NbtPayload {
        void write(DataOutputStream out) throws IOException;
    }

    /**
     * A single registry entry. {@code nbt} is {@code null} for "token" entries – those only
     * participate when the Known Packs handshake succeeded.
     */
    public record Entry(String id, NbtPayload nbt) {
        public static Entry token(String id) {
            return new Entry(id, null);
        }

        public static Entry withNbt(String id, NbtPayload nbt) {
            return new Entry(id, nbt);
        }

        public boolean hasNbt() {
            return nbt != null;
        }
    }

    public record Registry(String name, List<Entry> entries) {}

    private MinimalRegistries() {}

    /** The fixed list of registry packets the limbo considers, in the order they're sent. */
    public static final List<Registry> ALL = List.of(
            // Registries with full NBT – sent in both modes.
            new Registry("minecraft:dimension_type", List.of(
                    Entry.withNbt("minecraft:overworld", RegistryNbtWriters::writeOverworldDimensionType)
            )),
            new Registry("minecraft:worldgen/biome", List.of(
                    Entry.withNbt("minecraft:plains", RegistryNbtWriters::writePlainsBiome)
            )),
            new Registry("minecraft:chat_type", List.of(
                    Entry.withNbt("minecraft:chat", RegistryNbtWriters::writeChatChatType)
            )),
            new Registry("minecraft:damage_type", List.of(
                    Entry.withNbt("minecraft:generic_kill", RegistryNbtWriters::writeGenericKillDamageType)
            )),
            // Token-only registries – only carried in known-pack mode.
            new Registry("minecraft:trim_pattern", List.of(
                    Entry.token("minecraft:sentry")
            )),
            new Registry("minecraft:trim_material", List.of(
                    Entry.token("minecraft:iron")
            )),
            new Registry("minecraft:wolf_variant", List.of(
                    Entry.token("minecraft:pale")
            )),
            new Registry("minecraft:cat_variant", List.of(
                    Entry.token("minecraft:tabby")
            )),
            new Registry("minecraft:painting_variant", List.of(
                    Entry.token("minecraft:kebab")
            )),
            new Registry("minecraft:banner_pattern", List.of(
                    Entry.token("minecraft:square_bottom_left")
            )),
            new Registry("minecraft:enchantment", List.of(
                    Entry.token("minecraft:protection")
            )),
            new Registry("minecraft:jukebox_song", List.of(
                    Entry.token("minecraft:11")
            )),
            new Registry("minecraft:instrument", List.of(
                    Entry.token("minecraft:ponder_goat_horn")
            ))
    );
}
