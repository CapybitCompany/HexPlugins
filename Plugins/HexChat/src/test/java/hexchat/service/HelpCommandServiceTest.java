package hexchat.service;

import hexchat.config.HexChatConfig;
import hexchat.support.CapturingLogger;
import hexchat.support.TestConfigs;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HelpCommandServiceTest {

    private static HexChatConfig.Help help(boolean enabled,
                                           HexChatConfig.Help.Mode mode,
                                           boolean fallback) {
        return new HexChatConfig.Help(
                enabled,
                mode,
                List.of("help"),
                List.of("<gold>linia</gold>"),
                fallback,
                "<red>unavailable</red>"
        );
    }

    private static PluginManager pluginManager(boolean essentialsPresent) {
        PluginManager manager = mock(PluginManager.class);
        when(manager.getPlugin("Essentials")).thenReturn(essentialsPresent ? mock(Plugin.class) : null);
        return manager;
    }

    @Test
    void disabledHelpDoesNothing() {
        CapturingLogger log = new CapturingLogger();
        HexChatConfig config = TestConfigs.withHelp(help(false, HexChatConfig.Help.Mode.CUSTOM, true));
        HelpCommandService service = new HelpCommandService(pluginManager(false), log.logger(), config);
        HexChatMessageService messages = mock(HexChatMessageService.class);

        assertFalse(service.handleIfNeeded(mock(Player.class), "/help", messages));
        verify(messages, never()).sendRawLinesWithoutPrefix(any(), any(), any());
    }

    @Test
    void customModeSendsCustomLinesAndCancels() {
        CapturingLogger log = new CapturingLogger();
        HexChatConfig config = TestConfigs.withHelp(help(true, HexChatConfig.Help.Mode.CUSTOM, true));
        HelpCommandService service = new HelpCommandService(pluginManager(false), log.logger(), config);
        HexChatMessageService messages = mock(HexChatMessageService.class);
        Player player = mock(Player.class);

        assertTrue(service.handleIfNeeded(player, "/help", messages), "CUSTOM cancels /help");
        verify(messages).sendRawLinesWithoutPrefix(eq(player), any(), eq("help.custom-lines"));
    }

    @Test
    void nonAliasCommandIsIgnored() {
        CapturingLogger log = new CapturingLogger();
        HexChatConfig config = TestConfigs.withHelp(help(true, HexChatConfig.Help.Mode.CUSTOM, true));
        HelpCommandService service = new HelpCommandService(pluginManager(false), log.logger(), config);
        HexChatMessageService messages = mock(HexChatMessageService.class);

        assertFalse(service.handleIfNeeded(mock(Player.class), "/spawn", messages));
    }

    @Test
    void essentialsModeWithEssentialsPresentPassesCommandThrough() {
        CapturingLogger log = new CapturingLogger();
        HexChatConfig config = TestConfigs.withHelp(help(true, HexChatConfig.Help.Mode.ESSENTIALS, true));
        HelpCommandService service = new HelpCommandService(pluginManager(true), log.logger(), config);
        HexChatMessageService messages = mock(HexChatMessageService.class);

        assertFalse(service.handleIfNeeded(mock(Player.class), "/help", messages), "Z Essentials komenda przechodzi");
        verify(messages, never()).sendRawLinesWithoutPrefix(any(), any(), any());
        verify(messages, never()).sendHelpUnavailable(any(), any());
    }

    @Test
    void essentialsModeWithoutEssentialsFallsBackToCustomWhenEnabled() {
        CapturingLogger log = new CapturingLogger();
        HexChatConfig config = TestConfigs.withHelp(help(true, HexChatConfig.Help.Mode.ESSENTIALS, true));
        HelpCommandService service = new HelpCommandService(pluginManager(false), log.logger(), config);
        HexChatMessageService messages = mock(HexChatMessageService.class);
        Player player = mock(Player.class);

        assertTrue(service.handleIfNeeded(player, "/help", messages));
        verify(messages).sendRawLinesWithoutPrefix(eq(player), any(), eq("help.custom-lines"));
    }

    @Test
    void essentialsModeWithoutEssentialsShowsUnavailableWhenFallbackDisabled() {
        CapturingLogger log = new CapturingLogger();
        HexChatConfig config = TestConfigs.withHelp(help(true, HexChatConfig.Help.Mode.ESSENTIALS, false));
        HelpCommandService service = new HelpCommandService(pluginManager(false), log.logger(), config);
        HexChatMessageService messages = mock(HexChatMessageService.class);
        Player player = mock(Player.class);

        assertTrue(service.handleIfNeeded(player, "/help", messages));
        verify(messages).sendHelpUnavailable(eq(player), any());
        verify(messages, never()).sendRawLinesWithoutPrefix(any(), any(), any());
    }

    @Test
    void namespacedAliasIsNormalized() {
        CapturingLogger log = new CapturingLogger();
        HexChatConfig config = TestConfigs.withHelp(help(true, HexChatConfig.Help.Mode.CUSTOM, true));
        HelpCommandService service = new HelpCommandService(pluginManager(false), log.logger(), config);
        HexChatMessageService messages = mock(HexChatMessageService.class);
        Player player = mock(Player.class);

        // minecraft:help -> alias "help".
        assertTrue(service.handleIfNeeded(player, "/minecraft:help", messages));
        verify(messages).sendRawLinesWithoutPrefix(eq(player), any(), eq("help.custom-lines"));
    }
}
