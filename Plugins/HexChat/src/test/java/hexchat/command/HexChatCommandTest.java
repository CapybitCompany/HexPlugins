package hexchat.command;

import hexchat.HexChatPlugin;
import hexchat.service.HexChatMessageService;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HexChatCommandTest {

    private static CommandSender admin() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("hexchat.admin")).thenReturn(true);
        return sender;
    }

    private static CommandSender nonAdmin() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("hexchat.admin")).thenReturn(false);
        return sender;
    }

    @Test
    void nonAdminGetsNoPermissionMessage() {
        HexChatPlugin plugin = mock(HexChatPlugin.class);
        HexChatMessageService messages = mock(HexChatMessageService.class);
        HexChatCommand command = new HexChatCommand(plugin, messages);
        CommandSender sender = nonAdmin();

        command.onCommand(sender, null, "hexchat", new String[]{"reload"});

        verify(messages).sendNoPermission(sender);
        verify(plugin, never()).reloadHexChatConfiguration();
    }

    @Test
    void wrongArgCountShowsUsage() {
        HexChatPlugin plugin = mock(HexChatPlugin.class);
        HexChatMessageService messages = mock(HexChatMessageService.class);
        HexChatCommand command = new HexChatCommand(plugin, messages);
        CommandSender sender = admin();

        command.onCommand(sender, null, "hexchat", new String[]{});
        command.onCommand(sender, null, "hexchat", new String[]{"a", "b"});

        verify(messages, org.mockito.Mockito.times(2)).sendUsage(sender);
    }

    @Test
    void unknownSubcommandShowsUsage() {
        HexChatPlugin plugin = mock(HexChatPlugin.class);
        HexChatMessageService messages = mock(HexChatMessageService.class);
        HexChatCommand command = new HexChatCommand(plugin, messages);
        CommandSender sender = admin();

        command.onCommand(sender, null, "hexchat", new String[]{"nieistnieje"});

        verify(messages).sendUsage(sender);
    }

    @Test
    void reloadDelegatesToPluginAndConfirms() {
        HexChatPlugin plugin = mock(HexChatPlugin.class);
        HexChatMessageService messages = mock(HexChatMessageService.class);
        HexChatCommand command = new HexChatCommand(plugin, messages);
        CommandSender sender = admin();

        command.onCommand(sender, null, "hexchat", new String[]{"reload"});

        verify(plugin).reloadHexChatConfiguration();
        verify(messages).sendReloaded(sender);
    }

    @Test
    void muteWhenPreviouslyUnmutedSendsEnabled() {
        HexChatPlugin plugin = mock(HexChatPlugin.class);
        when(plugin.setGlobalChatMuted(true)).thenReturn(false);
        HexChatMessageService messages = mock(HexChatMessageService.class);
        HexChatCommand command = new HexChatCommand(plugin, messages);
        CommandSender sender = admin();

        command.onCommand(sender, null, "hexchat", new String[]{"mute"});

        verify(plugin).setGlobalChatMuted(true);
        verify(messages).sendChatMuteEnabled(sender);
    }

    @Test
    void muteWhenAlreadyMutedSendsAlreadyEnabled() {
        HexChatPlugin plugin = mock(HexChatPlugin.class);
        when(plugin.setGlobalChatMuted(true)).thenReturn(true);
        HexChatMessageService messages = mock(HexChatMessageService.class);
        HexChatCommand command = new HexChatCommand(plugin, messages);
        CommandSender sender = admin();

        command.onCommand(sender, null, "hexchat", new String[]{"mute"});

        verify(messages).sendChatMuteAlreadyEnabled(sender);
    }

    @Test
    void unmuteWhenPreviouslyMutedSendsDisabled() {
        HexChatPlugin plugin = mock(HexChatPlugin.class);
        when(plugin.setGlobalChatMuted(false)).thenReturn(true);
        HexChatMessageService messages = mock(HexChatMessageService.class);
        HexChatCommand command = new HexChatCommand(plugin, messages);
        CommandSender sender = admin();

        command.onCommand(sender, null, "hexchat", new String[]{"unmute"});

        verify(messages).sendChatMuteDisabled(sender);
    }

    @Test
    void unmuteWhenAlreadyUnmutedSendsAlreadyDisabled() {
        HexChatPlugin plugin = mock(HexChatPlugin.class);
        when(plugin.setGlobalChatMuted(false)).thenReturn(false);
        HexChatMessageService messages = mock(HexChatMessageService.class);
        HexChatCommand command = new HexChatCommand(plugin, messages);
        CommandSender sender = admin();

        command.onCommand(sender, null, "hexchat", new String[]{"unmute"});

        verify(messages).sendChatMuteAlreadyDisabled(sender);
    }

    @Test
    void toggleMuteReflectsNewState() {
        HexChatPlugin plugin = mock(HexChatPlugin.class);
        HexChatMessageService messages = mock(HexChatMessageService.class);
        HexChatCommand command = new HexChatCommand(plugin, messages);
        CommandSender sender = admin();

        when(plugin.toggleGlobalChatMuted()).thenReturn(true);
        command.onCommand(sender, null, "hexchat", new String[]{"togglemute"});
        verify(messages).sendChatMuteEnabled(sender);

        when(plugin.toggleGlobalChatMuted()).thenReturn(false);
        command.onCommand(sender, null, "hexchat", new String[]{"togglemute"});
        verify(messages).sendChatMuteDisabled(sender);
    }

    @Test
    void muteStatusUsesPluginState() {
        HexChatPlugin plugin = mock(HexChatPlugin.class);
        when(plugin.isGlobalChatMuted()).thenReturn(true);
        HexChatMessageService messages = mock(HexChatMessageService.class);
        HexChatCommand command = new HexChatCommand(plugin, messages);
        CommandSender sender = admin();

        command.onCommand(sender, null, "hexchat", new String[]{"mutestatus"});

        verify(messages).sendChatMuteStatus(sender, true);
    }

    @Test
    void subcommandIsCaseInsensitive() {
        HexChatPlugin plugin = mock(HexChatPlugin.class);
        HexChatMessageService messages = mock(HexChatMessageService.class);
        HexChatCommand command = new HexChatCommand(plugin, messages);
        CommandSender sender = admin();

        command.onCommand(sender, null, "hexchat", new String[]{"RELOAD"});

        verify(plugin).reloadHexChatConfiguration();
    }

    @Test
    void tabCompleteForAdminReturnsMatchingSubcommands() {
        HexChatPlugin plugin = mock(HexChatPlugin.class);
        HexChatMessageService messages = mock(HexChatMessageService.class);
        HexChatCommand command = new HexChatCommand(plugin, messages);
        CommandSender sender = admin();

        List<String> all = command.onTabComplete(sender, null, "hexchat", new String[]{""});
        assertTrue(all.containsAll(List.of("reload", "mute", "unmute", "togglemute", "mutestatus")));

        List<String> muteMatches = command.onTabComplete(sender, null, "hexchat", new String[]{"mute"});
        assertTrue(muteMatches.contains("mute"));
        assertTrue(muteMatches.contains("mutestatus"));
        assertEquals(2, muteMatches.size());
    }

    @Test
    void tabCompleteForNonAdminIsEmpty() {
        HexChatPlugin plugin = mock(HexChatPlugin.class);
        HexChatMessageService messages = mock(HexChatMessageService.class);
        HexChatCommand command = new HexChatCommand(plugin, messages);
        CommandSender sender = nonAdmin();

        List<String> result = command.onTabComplete(sender, null, "hexchat", new String[]{""});

        assertTrue(result.isEmpty());
    }

    @Test
    void tabCompleteBeyondFirstArgIsEmpty() {
        HexChatPlugin plugin = mock(HexChatPlugin.class);
        HexChatMessageService messages = mock(HexChatMessageService.class);
        HexChatCommand command = new HexChatCommand(plugin, messages);
        CommandSender sender = admin();

        List<String> result = command.onTabComplete(sender, null, "hexchat", new String[]{"mute", ""});

        assertTrue(result.isEmpty());
    }
}
