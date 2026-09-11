package hex.minigames.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConfiguredSoundTest {
    @Test
    void acceptsLegacySoundNamesWhenRegistryIsUnavailableInUnitTests() {
        ConfiguredSound configured = new ConfiguredSound(true, "UI_BUTTON_CLICK", 1.0f, 1.0f);

        assertTrue(configured.validSound());
    }

    @Test
    void acceptsNamespacedSoundKeysWhenRegistryIsUnavailableInUnitTests() {
        ConfiguredSound configured = new ConfiguredSound(true, "minecraft:ui.button.click", 1.0f, 1.0f);

        assertTrue(configured.validSound());
    }

    @Test
    void rejectsBlankEnabledSounds() {
        assertFalse(new ConfiguredSound(true, "", 1.0f, 1.0f).validSound());
    }

    @Test
    void disabledBlankSoundDoesNotResolve() {
        ConfiguredSound configured = new ConfiguredSound(false, "", 1.0f, 1.0f);

        assertTrue(configured.validSound());
        assertNull(configured.bukkitSound());
    }
}
