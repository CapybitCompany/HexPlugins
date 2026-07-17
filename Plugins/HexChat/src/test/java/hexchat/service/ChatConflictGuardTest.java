package hexchat.service;

import hexchat.config.HexChatConfig;
import hexchat.support.TestConfigs;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatConflictGuardTest {

    private static HexChatConfig config(boolean enabled, boolean warn, boolean enforce, List<String> known) {
        return TestConfigs.withConflictGuard(new HexChatConfig.ConflictGuard(enabled, warn, enforce, known));
    }

    @Test
    void knownInstalledChatPluginIsFormatConflict() {
        ChatConflictGuard guard = new ChatConflictGuard(config(true, true, false, List.of("VentureChat")));

        ChatConflictGuard.ConflictReport report = guard.analyze(
                "HexChat",
                List.of("HexChat"),
                List.of("HexChat", "VentureChat", "WorldEdit")
        );

        assertTrue(report.hasFormatConflict());
        assertTrue(report.formatConflicts().stream().anyMatch(m -> m.contains("VentureChat")));
        assertFalse(report.formatConflicts().stream().anyMatch(m -> m.contains("WorldEdit")));
        assertTrue(report.listenerWarnings().isEmpty());
    }

    @Test
    void unknownListenerIsWarningNotFormatConflict() {
        ChatConflictGuard guard = new ChatConflictGuard(config(true, true, false, List.of()));

        ChatConflictGuard.ConflictReport report = guard.analyze(
                "HexChat",
                List.of("HexChat", "SomeLoggerPlugin"),
                List.of("HexChat", "SomeLoggerPlugin")
        );

        assertFalse(report.hasFormatConflict(), "Nieznany listener nie jest konfliktem formatu");
        assertTrue(report.listenerWarnings().stream().anyMatch(m -> m.contains("SomeLoggerPlugin")));
    }

    @Test
    void knownPluginThatAlsoListensCountsOnlyAsFormatConflict() {
        ChatConflictGuard guard = new ChatConflictGuard(config(true, true, false, List.of("VentureChat")));

        ChatConflictGuard.ConflictReport report = guard.analyze(
                "HexChat",
                List.of("HexChat", "VentureChat"),
                List.of("HexChat", "VentureChat")
        );

        assertTrue(report.hasFormatConflict());
        assertTrue(report.formatConflicts().stream().anyMatch(m -> m.contains("VentureChat")));
        assertTrue(report.listenerWarnings().isEmpty(), "Znany plugin nie jest podwójnie liczony jako listener");
    }

    @Test
    void ignoresSelfPlugin() {
        ChatConflictGuard guard = new ChatConflictGuard(config(true, true, false, List.of("HexChat")));

        ChatConflictGuard.ConflictReport report = guard.analyze(
                "HexChat",
                List.of("HexChat"),
                List.of("HexChat")
        );

        assertFalse(report.hasFormatConflict());
        assertTrue(report.listenerWarnings().isEmpty());
    }

    @Test
    void enforceFormatAlwaysRendersOwnFormat() {
        ChatConflictGuard guard = new ChatConflictGuard(config(true, true, true, List.of()));
        guard.setConflictDetected(true);

        assertTrue(guard.shouldRenderFormat(), "enforce-format=true zawsze renderuje własny format");
    }

    @Test
    void yieldsFormatOnlyOnRealFormatConflict() {
        ChatConflictGuard guard = new ChatConflictGuard(config(true, true, false, List.of()));

        guard.setConflictDetected(false);
        assertTrue(guard.shouldRenderFormat(), "Brak konfliktu formatu -> HexChat renderuje");

        guard.setConflictDetected(true);
        assertFalse(guard.shouldRenderFormat(), "Konflikt formatu + brak enforce -> HexChat ustępuje");
    }

    @Test
    void exposesConfigFlags() {
        ChatConflictGuard guard = new ChatConflictGuard(config(true, false, true, List.of()));

        assertTrue(guard.isEnabled());
        assertFalse(guard.shouldWarn());
        assertTrue(guard.shouldEnforceFormat());
    }
}
