package hexposterunki;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding 9 regression: a plugin that started with a blocker is "not initialised", not "running",
 * so a later reload knows it still has to perform the database bootstrap and the recovery.
 */
class RuntimeStateTest {

    @Test
    void aBlockedStartIsNotInitialised() {
        assertFalse(RuntimeState.NOT_INITIALIZED.initialized(),
                "start z blokerem musi wymusić bootstrap przy reloadzie");
        assertFalse(RuntimeState.FAILED.initialized(),
                "nieudana inicjalizacja musi zostać powtórzona");
        assertFalse(RuntimeState.INITIALIZING.initialized());
    }

    @Test
    void onlyRunningAndPausedCountAsInitialised() {
        assertTrue(RuntimeState.RUNNING.initialized());
        assertTrue(RuntimeState.PAUSED.initialized(),
                "wstrzymany silnik jest już zainicjalizowany - reload tylko go wznawia");
    }

    @Test
    void everyStateHasAPolishLabelForTheStatusCommand() {
        for (RuntimeState state : RuntimeState.values()) {
            assertFalse(state.polishLabel().isBlank(), state + " nie ma polskiej etykiety");
            assertFalse(state.polishLabel().equals(state.name()));
        }
    }
}
