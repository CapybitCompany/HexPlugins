package hexchat.scheduler;

import hexchat.config.HexChatConfig;
import hexchat.service.HexChatMessageService;
import hexchat.support.CapturingLogger;
import hexchat.support.TestConfigs;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testujemy ścieżki, które nie dotykają statycznego {@code Bukkit.getScheduler()}:
 * wyłączony scheduler oraz włączony z pustą listą wiadomości. Faktyczne planowanie/wysyłka
 * wymaga działającego serwera i jest celowo poza zakresem testów jednostkowych.
 */
class AutoMessageSchedulerTest {

    private static JavaPlugin plugin(CapturingLogger log) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(log.logger());
        return plugin;
    }

    @Test
    void disabledSchedulerDoesNotCrashAndSchedulesNothing() {
        CapturingLogger log = new CapturingLogger();
        HexChatConfig config = TestConfigs.withAutoMessages(
                new HexChatConfig.AutoMessages(false, 180, false, List.of("a"))
        );
        AutoMessageScheduler scheduler = new AutoMessageScheduler(plugin(log), mock(HexChatMessageService.class), config);

        assertDoesNotThrow(scheduler::start);
        assertDoesNotThrow(scheduler::stop);
        assertFalse(log.hasWarningContaining("pusta"), "Wyłączony scheduler nie ostrzega o pustej liście");
    }

    @Test
    void enabledWithEmptyMessageListWarnsAndSchedulesNothing() {
        CapturingLogger log = new CapturingLogger();
        HexChatConfig config = TestConfigs.withAutoMessages(
                new HexChatConfig.AutoMessages(true, 180, false, List.of())
        );
        AutoMessageScheduler scheduler = new AutoMessageScheduler(plugin(log), mock(HexChatMessageService.class), config);

        assertDoesNotThrow(scheduler::start);
        assertTrue(
                log.hasWarningContaining("lista wiadomości jest pusta"),
                "Włączony scheduler z pustą listą powinien ostrzec"
        );
    }

    @Test
    void updateConfigToDisabledDoesNotCrash() {
        CapturingLogger log = new CapturingLogger();
        HexChatConfig enabledEmpty = TestConfigs.withAutoMessages(
                new HexChatConfig.AutoMessages(true, 180, false, List.of())
        );
        AutoMessageScheduler scheduler = new AutoMessageScheduler(plugin(log), mock(HexChatMessageService.class), enabledEmpty);
        scheduler.start();

        HexChatConfig disabled = TestConfigs.withAutoMessages(
                new HexChatConfig.AutoMessages(false, 180, false, List.of("a"))
        );

        assertDoesNotThrow(() -> scheduler.updateConfig(disabled));
        assertDoesNotThrow(scheduler::stop);
    }
}
