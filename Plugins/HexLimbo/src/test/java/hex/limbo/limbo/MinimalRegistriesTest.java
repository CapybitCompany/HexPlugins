package hex.limbo.limbo;

import hex.limbo.limbo.server.MinimalRegistries;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Static smoke test for the {@link MinimalRegistries} bootstrap list. Catches regressions where
 * a refactor accidentally drops a registry that the 1.21.4 client requires – we don't have a
 * real client in CI, so this is the next best line of defence.
 */
class MinimalRegistriesTest {

    @Test
    void allRegistriesHaveAtLeastOneEntry() {
        for (MinimalRegistries.Registry registry : MinimalRegistries.ALL) {
            assertTrue(registry.entries().size() >= 1,
                    "Registry " + registry.name() + " must declare at least one entry; an empty "
                            + "registry triggers a client kick when Play Login references it.");
        }
    }

    @Test
    void registryNamesAreVanillaResourceLocations() {
        for (MinimalRegistries.Registry registry : MinimalRegistries.ALL) {
            assertTrue(registry.name().startsWith("minecraft:"),
                    "Registry name must be a minecraft: resource location: " + registry.name());
            for (MinimalRegistries.Entry entry : registry.entries()) {
                assertTrue(entry.id().startsWith("minecraft:"),
                        "Entry id must be a minecraft: resource location: " + entry.id());
            }
        }
    }

    @Test
    void dimensionTypeContainsOverworld() {
        // Play Login sends dimensionType id=0; that VarInt indexes into dimension_type, so the
        // entry at position 0 must be the overworld definition. The known-packs handshake then
        // lets the client load the NBT from minecraft:core 1.21.4.
        MinimalRegistries.Registry dim = findRegistry("minecraft:dimension_type");
        assertEquals("minecraft:overworld", dim.entries().get(0).id());
    }

    @Test
    void biomeContainsPlains() {
        // Chunk data biome palette uses id 0; same indexing rule.
        MinimalRegistries.Registry biome = findRegistry("minecraft:worldgen/biome");
        assertEquals("minecraft:plains", biome.entries().get(0).id());
    }

    @Test
    void coversTheClientCriticalRegistries() {
        Set<String> names = new HashSet<>();
        for (MinimalRegistries.Registry registry : MinimalRegistries.ALL) {
            names.add(registry.name());
        }
        // Each of these is touched by 1.21.4 client init paths even for a player who never
        // moves. Skipping any of them leaves a NullPointer / "missing registry" disconnect.
        assertTrue(names.contains("minecraft:dimension_type"));
        assertTrue(names.contains("minecraft:worldgen/biome"));
        assertTrue(names.contains("minecraft:chat_type"));
        assertTrue(names.contains("minecraft:damage_type"));
        assertTrue(names.contains("minecraft:wolf_variant"));
        assertTrue(names.contains("minecraft:painting_variant"));
        assertTrue(names.contains("minecraft:trim_pattern"));
        assertTrue(names.contains("minecraft:trim_material"));
    }

    @Test
    void primaryRegistriesCarryFullNbtWriters() {
        // When the client refuses every known pack (Velocity/ViaVersion responds count=0), the
        // limbo MUST be able to send these four registries with hasData=true + inline NBT or
        // the client disconnects after Finish Configuration.
        for (String name : new String[]{
                "minecraft:dimension_type",
                "minecraft:worldgen/biome",
                "minecraft:chat_type",
                "minecraft:damage_type"
        }) {
            MinimalRegistries.Registry registry = findRegistry(name);
            for (MinimalRegistries.Entry entry : registry.entries()) {
                assertTrue(entry.hasNbt(),
                        "Registry " + name + " entry " + entry.id() + " MUST ship full NBT for "
                                + "the no-known-pack fallback path; got a token entry.");
            }
        }
    }

    @Test
    void tokenRegistriesDoNotShipNbt() {
        // These are the optional registries; in fallback mode the limbo skips them entirely.
        for (String name : new String[]{
                "minecraft:trim_pattern",
                "minecraft:trim_material",
                "minecraft:wolf_variant",
                "minecraft:cat_variant",
                "minecraft:painting_variant",
                "minecraft:banner_pattern",
                "minecraft:enchantment",
                "minecraft:jukebox_song",
                "minecraft:instrument"
        }) {
            MinimalRegistries.Registry registry = findRegistry(name);
            for (MinimalRegistries.Entry entry : registry.entries()) {
                assertEquals(false, entry.hasNbt(),
                        "Registry " + name + " carries fake NBT; tokens must remain null to be "
                                + "safely skipped in fallback mode.");
            }
        }
    }

    private MinimalRegistries.Registry findRegistry(String name) {
        return MinimalRegistries.ALL.stream()
                .filter(r -> r.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("MinimalRegistries.ALL is missing " + name));
    }
}
