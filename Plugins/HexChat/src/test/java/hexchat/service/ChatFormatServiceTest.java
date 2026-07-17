package hexchat.service;

import hexchat.config.HexChatConfig;
import hexchat.support.CapturingLogger;
import hexchat.support.TestConfigs;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFormatServiceTest {

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void rendersPlayerAndMessagePlaceholders() {
        CapturingLogger log = new CapturingLogger();
        ChatFormatService service = new ChatFormatService(TestConfigs.config(), log.logger());

        Component result = service.render(Component.text("Filip"), Component.text("cześć świecie"));
        String text = plain(result);

        assertTrue(text.contains("Filip"), "Format powinien zawierać nazwę gracza");
        assertTrue(text.contains("cześć świecie"), "Format powinien zawierać treść wiadomości");
        assertTrue(text.contains("Chat"), "Domyślny format zawiera etykietę Chat");
    }

    @Test
    void malformedFormatDoesNotCrashAndKeepsPlayerAndMessage() {
        // Uwaga: MiniMessage dostarczany przez Paper jest pobłażliwy — nieznane/niepoprawne
        // tagi NIE rzucają wyjątku, tylko są renderowane liberalnie. Kontrakt, który tu
        // weryfikujemy, to odporność: render nie może rzucić wyjątku, a nazwa gracza i treść
        // wiadomości muszą zostać zachowane. (Gałąź awaryjna "player: message" w
        // ChatFormatService jest zabezpieczeniem na wypadek surowszej konfiguracji MiniMessage.)
        CapturingLogger log = new CapturingLogger();
        HexChatConfig config = TestConfigs.withChat(
                TestConfigs.chat(true, "<nieznany:tag><player>: <message><niezamkniety", TestConfigs.globalMute(true, false))
        );
        ChatFormatService service = new ChatFormatService(config, log.logger());

        Component result = assertDoesNotThrow(
                () -> service.render(Component.text("Filip"), Component.text("wiadomość"))
        );
        String text = plain(result);

        assertTrue(text.contains("Filip"), "Render powinien zachować nazwę gracza");
        assertTrue(text.contains("wiadomość"), "Render powinien zachować treść wiadomości");
    }

    @Test
    void isChatEnabledReflectsConfig() {
        CapturingLogger log = new CapturingLogger();
        HexChatConfig disabled = TestConfigs.withChat(
                TestConfigs.chat(false, TestConfigs.DEFAULT_FORMAT, TestConfigs.globalMute(true, false))
        );
        ChatFormatService service = new ChatFormatService(disabled, log.logger());

        assertEquals(false, service.isChatEnabled());
    }
}
