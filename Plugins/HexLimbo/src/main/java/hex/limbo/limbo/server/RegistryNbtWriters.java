package hex.limbo.limbo.server;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Full-NBT bodies for registry entries the limbo references directly when the client did not
 * acknowledge any known pack (Velocity/ViaVersion has been observed to answer Select Known Packs
 * with {@code count=0}). In that case {@code hasData=false} would leave the client with no NBT
 * for the registry entry and it disconnects after Finish Configuration; we have to ship the
 * actual NBT body inline.
 *
 * <p>v1 ships full NBT for the four registries that are actually referenced during PLAY init:
 * <ul>
 *     <li>{@code minecraft:dimension_type} – Play Login sends dimension type id {@code 0}.</li>
 *     <li>{@code minecraft:worldgen/biome} – chunk-data biome palette uses id {@code 0}.</li>
 *     <li>{@code minecraft:damage_type} – client validates the registry exists on PLAY join.</li>
 *     <li>{@code minecraft:chat_type} – client validates the registry exists on PLAY join.</li>
 * </ul>
 * Other registries (trim, wolf/cat/painting variants, banner, enchantment, jukebox, instrument)
 * are intentionally token-only: in known-pack mode they are sent with {@code hasData=false}; in
 * fallback mode the limbo skips them entirely. Sending fake NBT for them would be worse than
 * leaving them out, because the client would try to deserialise the bogus body and crash.
 */
final class RegistryNbtWriters {

    private RegistryNbtWriters() {}

    /**
     * Overworld dimension type NBT for 1.21.4. Field set matches the vanilla
     * {@code data/minecraft/dimension_type/overworld.json}.
     */
    static void writeOverworldDimensionType(DataOutputStream out) throws IOException {
        NbtWriter.startRootCompound(out);
        NbtWriter.writeNamedBoolean(out, "piglin_safe", false);
        NbtWriter.writeNamedBoolean(out, "has_raids", true);
        NbtWriter.writeNamedInt(out, "monster_spawn_block_light_limit", 0);
        // monster_spawn_light_level is an int-provider compound in 1.21.4.
        NbtWriter.startNamedCompound(out, "monster_spawn_light_level");
        NbtWriter.writeNamedString(out, "type", "minecraft:uniform");
        NbtWriter.startNamedCompound(out, "value");
        NbtWriter.writeNamedInt(out, "min_inclusive", 0);
        NbtWriter.writeNamedInt(out, "max_inclusive", 7);
        NbtWriter.endCompound(out);
        NbtWriter.endCompound(out);
        NbtWriter.writeNamedBoolean(out, "natural", true);
        NbtWriter.writeNamedFloat(out, "ambient_light", 0.0f);
        NbtWriter.writeNamedString(out, "infiniburn", "#minecraft:infiniburn_overworld");
        NbtWriter.writeNamedBoolean(out, "respawn_anchor_works", false);
        NbtWriter.writeNamedBoolean(out, "has_skylight", true);
        NbtWriter.writeNamedBoolean(out, "bed_works", true);
        NbtWriter.writeNamedString(out, "effects", "minecraft:overworld");
        NbtWriter.writeNamedInt(out, "min_y", -64);
        NbtWriter.writeNamedInt(out, "height", 384);
        NbtWriter.writeNamedInt(out, "logical_height", 384);
        NbtWriter.writeNamedDouble(out, "coordinate_scale", 1.0);
        NbtWriter.writeNamedBoolean(out, "ultrawarm", false);
        NbtWriter.writeNamedBoolean(out, "has_ceiling", false);
        NbtWriter.endCompound(out);
    }

    /**
     * Plains biome NBT for 1.21.4. Field set is the minimal accepted shape; mood_sound,
     * music, particle and ambient_sound are omitted because none are required.
     */
    static void writePlainsBiome(DataOutputStream out) throws IOException {
        NbtWriter.startRootCompound(out);
        NbtWriter.writeNamedBoolean(out, "has_precipitation", true);
        NbtWriter.writeNamedFloat(out, "temperature", 0.8f);
        NbtWriter.writeNamedFloat(out, "downfall", 0.4f);
        NbtWriter.startNamedCompound(out, "effects");
        NbtWriter.writeNamedInt(out, "sky_color", 7907327);
        NbtWriter.writeNamedInt(out, "water_fog_color", 329011);
        NbtWriter.writeNamedInt(out, "fog_color", 12638463);
        NbtWriter.writeNamedInt(out, "water_color", 4159204);
        NbtWriter.endCompound(out);
        NbtWriter.endCompound(out);
    }

    /**
     * Generic-kill damage-type NBT for 1.21.4. Mirrors
     * {@code data/minecraft/damage_type/generic_kill.json}.
     */
    static void writeGenericKillDamageType(DataOutputStream out) throws IOException {
        NbtWriter.startRootCompound(out);
        NbtWriter.writeNamedString(out, "message_id", "generic_kill");
        NbtWriter.writeNamedString(out, "scaling", "never");
        NbtWriter.writeNamedFloat(out, "exhaustion", 0.0f);
        NbtWriter.endCompound(out);
    }

    /**
     * Chat chat-type NBT for 1.21.4. Two sub-compounds: chat (default translation) and
     * narration. Each carries a string list of parameter ids.
     */
    static void writeChatChatType(DataOutputStream out) throws IOException {
        NbtWriter.startRootCompound(out);
        NbtWriter.startNamedCompound(out, "chat");
        NbtWriter.writeNamedString(out, "translation_key", "chat.type.text");
        NbtWriter.startNamedStringList(out, "parameters", 2);
        NbtWriter.writeListString(out, "sender");
        NbtWriter.writeListString(out, "content");
        NbtWriter.endCompound(out);
        NbtWriter.startNamedCompound(out, "narration");
        NbtWriter.writeNamedString(out, "translation_key", "chat.type.text.narrate");
        NbtWriter.startNamedStringList(out, "parameters", 2);
        NbtWriter.writeListString(out, "sender");
        NbtWriter.writeListString(out, "content");
        NbtWriter.endCompound(out);
        NbtWriter.endCompound(out);
    }
}
