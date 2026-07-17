package hexchat.service;

import hexchat.config.HexChatConfig;
import hexchat.support.CapturingLogger;
import hexchat.support.TestConfigs;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class HexChatMessageServiceTest {

    private static HexChatConfig configWithMessages(HexChatConfig.Messages messages) {
        return new HexChatConfig(
                TestConfigs.chat(),
                TestConfigs.cooldown(),
                TestConfigs.contentFilter(),
                TestConfigs.playerMute(),
                TestConfigs.autoMessages(),
                TestConfigs.commandFilter(),
                TestConfigs.tabCompleteFilter(),
                TestConfigs.help(),
                messages
        );
    }

    private static HexChatConfig.Messages messages(String prefix, String cooldownWait) {
        return new HexChatConfig.Messages(
                prefix,
                "<red>no-perm</red>",
                "<green>reloaded</green>",
                "<yellow>usage</yellow>",
                cooldownWait,
                "<red>muted</red>",
                "e", "d", "ae", "ad", "se", "sd",
                "pm", "ms", "mr", "mn", "mt", "mi", "md"
        );
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void cooldownMessageReplacesSecondsPlaceholderAndAppliesPrefix() {
        CapturingLogger log = new CapturingLogger();
        HexChatConfig config = configWithMessages(messages("<gray>[HEX]</gray> ", "Poczekaj <seconds> sek."));
        HexChatMessageService service = new HexChatMessageService(() -> config, log.logger());
        CommandSender sender = mock(CommandSender.class);

        service.sendCooldownWait(sender, 7);

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(sender).sendMessage(captor.capture());
        String text = plain(captor.getValue());

        assertTrue(text.contains("[HEX]"), "Prefiks powinien być dołączony");
        assertTrue(text.contains("Poczekaj 7 sek."), "Placeholder <seconds> powinien zostać podmieniony, było: " + text);
    }

    @Test
    void malformedMiniMessageDoesNotCrashAndKeepsText() {
        // Paperowy MiniMessage jest pobłażliwy: niepoprawne tagi nie rzucają wyjątku.
        // Weryfikujemy odporność (brak crasha) oraz zachowanie treści i placeholdera.
        CapturingLogger log = new CapturingLogger();
        HexChatConfig config = configWithMessages(messages("", "<nieznany:tag>Poczekaj <seconds> sek.<niezamkniety"));
        HexChatMessageService service = new HexChatMessageService(() -> config, log.logger());
        CommandSender sender = mock(CommandSender.class);

        assertDoesNotThrow(() -> service.sendCooldownWait(sender, 3));

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(sender).sendMessage(captor.capture());
        String text = plain(captor.getValue());

        assertTrue(text.contains("Poczekaj 3 sek."), "Treść i placeholder powinny zostać zachowane, było: " + text);
    }

    @Test
    void raidLinesSentPerLineWithoutPrefix() {
        CapturingLogger log = new CapturingLogger();
        HexChatConfig config = configWithMessages(messages("<gray>PREF</gray> ", "x"));
        HexChatMessageService service = new HexChatMessageService(() -> config, log.logger());
        CommandSender sender = mock(CommandSender.class);

        service.sendRawLinesWithoutPrefix(sender, java.util.List.of("<green>a</green>", "<green>b</green>"), "help.custom-lines");

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(sender, org.mockito.Mockito.times(2)).sendMessage(captor.capture());
        // Bez prefiksu: żadna z linii nie zawiera "PREF".
        for (Component component : captor.getAllValues()) {
            assertFalse(plain(component).contains("PREF"), "Linie custom nie powinny mieć prefiksu");
        }
    }
}
