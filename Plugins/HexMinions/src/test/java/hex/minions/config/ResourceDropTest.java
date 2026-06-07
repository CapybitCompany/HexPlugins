package hex.minions.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceDropTest {
	@Test
	void acceptsValidDrop() {
		assertDoesNotThrow(() -> new ResourceDrop("cobblestone", 1, 3, 0.5));
	}

	@Test
	void rejectsInvalidAmountRange() {
		assertThrows(IllegalArgumentException.class, () -> new ResourceDrop("cobblestone", 4, 3, 0.5));
	}

	@Test
	void rejectsInvalidChance() {
		assertThrows(IllegalArgumentException.class, () -> new ResourceDrop("cobblestone", 1, 1, 1.5));
	}
}

