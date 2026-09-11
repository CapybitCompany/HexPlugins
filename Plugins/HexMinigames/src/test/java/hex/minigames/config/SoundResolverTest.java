package hex.minigames.config;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SoundResolverTest {
    private static final List<NamespacedKey> PAPER_1_21_11_SOUNDS = List.of(
            NamespacedKey.minecraft("item.goat_horn.sound.0"),
            NamespacedKey.minecraft("entity.experience_orb.pickup"),
            NamespacedKey.minecraft("block.amethyst_block.chime")
    );

    @Test
    void resolvesPaperRegistryBackedLegacySoundNames() {
        assertResolved("ITEM_GOAT_HORN_SOUND_0", "minecraft:item.goat_horn.sound.0");
        assertResolved("ENTITY_EXPERIENCE_ORB_PICKUP", "minecraft:entity.experience_orb.pickup");
        assertResolved("BLOCK_AMETHYST_BLOCK_CHIME", "minecraft:block.amethyst_block.chime");
    }

    @Test
    void resolvesPaperNamespacedSoundKeys() {
        assertResolved("minecraft:item.goat_horn.sound.0", "minecraft:item.goat_horn.sound.0");
        assertResolved("minecraft:entity.experience_orb.pickup", "minecraft:entity.experience_orb.pickup");
        assertResolved("minecraft:block.amethyst_block.chime", "minecraft:block.amethyst_block.chime");
    }

    private static void assertResolved(String raw, String expected) {
        NamespacedKey resolved = SoundResolver.resolveKey(raw, PAPER_1_21_11_SOUNDS).orElseThrow();

        assertEquals(expected, resolved.asString());
    }
}
