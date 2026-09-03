package hexchat.command;

import hexchat.HexChatPlugin;
import hexchat.config.HexChatConfig;
import hexchat.mute.MuteEntry;
import hexchat.service.HexChatMessageService;
import hexchat.service.PlayerDirectory;
import hexchat.service.PlayerMuteService;
import hexchat.support.TestConfigs;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HexChatCommandTest {

    private static final class Fixture {
        final HexChatPlugin plugin = mock(HexChatPlugin.class);
        final HexChatMessageService messages = mock(HexChatMessageService.class);
        final PlayerMuteService muteService = mock(PlayerMuteService.class);
        final PlayerDirectory directory = mock(PlayerDirectory.class);
        final HexChatConfig config;
        final HexChatCommand command;

        Fixture() {
            this(TestConfigs.config());
        }

        Fixture(HexChatConfig config) {
            this.config = config;
            this.command = new HexChatCommand(plugin, messages, muteService, directory, () -> this.config);
        }
    }

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

    // --- Uprawnienia / użycie ---

    @Test
    void nonAdminGetsNoPermissionMessage() {
        Fixture f = new Fixture();
        CommandSender sender = nonAdmin();

        f.command.onCommand(sender, null, "hexchat", new String[]{"reload"});

        verify(f.messages).sendNoPermission(sender);
        verify(f.plugin, never()).reloadHexChatConfiguration();
    }

    @Test
    void noArgsShowsUsage() {
        Fixture f = new Fixture();
        CommandSender sender = admin();

        f.command.onCommand(sender, null, "hexchat", new String[]{});

        verify(f.messages).sendUsage(sender);
    }

    @Test
    void unknownSubcommandShowsUsage() {
        Fixture f = new Fixture();
        CommandSender sender = admin();

        f.command.onCommand(sender, null, "hexchat", new String[]{"nieistnieje"});

        verify(f.messages).sendUsage(sender);
    }

    @Test
    void reloadDelegatesToPluginAndConfirms() {
        Fixture f = new Fixture();
        CommandSender sender = admin();

        f.command.onCommand(sender, null, "hexchat", new String[]{"reload"});

        verify(f.plugin).reloadHexChatConfiguration();
        verify(f.messages).sendReloaded(sender);
    }

    // --- Globalne wyciszenie (zachowanie zgodne wstecznie, jeden argument) ---

    @Test
    void globalMuteWhenPreviouslyUnmutedSendsEnabled() {
        Fixture f = new Fixture();
        when(f.plugin.setGlobalChatMuted(true)).thenReturn(false);
        CommandSender sender = admin();

        f.command.onCommand(sender, null, "hexchat", new String[]{"mute"});

        verify(f.plugin).setGlobalChatMuted(true);
        verify(f.messages).sendChatMuteEnabled(sender);
    }

    @Test
    void globalMuteWhenAlreadyMutedSendsAlreadyEnabled() {
        Fixture f = new Fixture();
        when(f.plugin.setGlobalChatMuted(true)).thenReturn(true);
        CommandSender sender = admin();

        f.command.onCommand(sender, null, "hexchat", new String[]{"mute"});

        verify(f.messages).sendChatMuteAlreadyEnabled(sender);
    }

    @Test
    void globalUnmuteWhenPreviouslyMutedSendsDisabled() {
        Fixture f = new Fixture();
        when(f.plugin.setGlobalChatMuted(false)).thenReturn(true);
        CommandSender sender = admin();

        f.command.onCommand(sender, null, "hexchat", new String[]{"unmute"});

        verify(f.messages).sendChatMuteDisabled(sender);
    }

    @Test
    void toggleGlobalMuteReflectsNewState() {
        Fixture f = new Fixture();
        CommandSender sender = admin();

        when(f.plugin.toggleGlobalChatMuted()).thenReturn(true);
        f.command.onCommand(sender, null, "hexchat", new String[]{"togglemute"});
        verify(f.messages).sendChatMuteEnabled(sender);

        when(f.plugin.toggleGlobalChatMuted()).thenReturn(false);
        f.command.onCommand(sender, null, "hexchat", new String[]{"togglemute"});
        verify(f.messages).sendChatMuteDisabled(sender);
    }

    @Test
    void muteStatusUsesPluginState() {
        Fixture f = new Fixture();
        when(f.plugin.isGlobalChatMuted()).thenReturn(true);
        CommandSender sender = admin();

        f.command.onCommand(sender, null, "hexchat", new String[]{"mutestatus"});

        verify(f.messages).sendChatMuteStatus(sender, true);
    }

    @Test
    void subcommandIsCaseInsensitive() {
        Fixture f = new Fixture();
        CommandSender sender = admin();

        f.command.onCommand(sender, null, "hexchat", new String[]{"RELOAD"});

        verify(f.plugin).reloadHexChatConfiguration();
    }

    // --- Per-gracz wyciszenie ---

    @Test
    void mutePlayerPermanentUsesDefaultReasonWhenNoneGiven() {
        Fixture f = new Fixture();
        CommandSender sender = admin();
        UUID uuid = UUID.randomUUID();
        when(f.directory.resolve("Steve")).thenReturn(Optional.of(new PlayerDirectory.ResolvedPlayer(uuid, "Steve")));
        MuteEntry entry = new MuteEntry(uuid, "Steve", 0L, "Powód domyślny.", 1000L);
        when(f.muteService.mute(eq(uuid), eq("Steve"), anyLong(), anyString())).thenReturn(entry);

        f.command.onCommand(sender, null, "hexchat", new String[]{"mute", "Steve"});

        verify(f.muteService).mute(uuid, "Steve", 0L, "Powód domyślny.");
        verify(f.messages).sendPlayerMuteSet(sender, "Steve", "na zawsze", "Powód domyślny.");
        verify(f.directory).notifyIfOnline(eq(uuid), any());
    }

    @Test
    void mutePlayerNotifiesOnlineTargetWithNotificationMessage() {
        Fixture f = new Fixture();
        CommandSender sender = admin();
        UUID uuid = UUID.randomUUID();
        when(f.directory.resolve("Steve")).thenReturn(Optional.of(new PlayerDirectory.ResolvedPlayer(uuid, "Steve")));
        MuteEntry entry = new MuteEntry(uuid, "Steve", 0L, "obraza", 1000L);
        when(f.muteService.mute(eq(uuid), eq("Steve"), anyLong(), anyString())).thenReturn(entry);

        f.command.onCommand(sender, null, "hexchat", new String[]{"mute", "Steve", "obraza"});

        // Powiadomienie gracza online idzie przez osobny, konfigurowalny tekst.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Player>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(f.directory).notifyIfOnline(eq(uuid), captor.capture());

        Player online = mock(Player.class);
        captor.getValue().accept(online);

        verify(f.messages).sendPlayerMuteNotification(online, "Steve", "na zawsze", "obraza");
        verify(f.messages, never()).sendPrivateMuted(any(), anyString(), anyString());
    }

    @Test
    void mutePlayerUsesConfiguredPermanentTimeText() {
        HexChatConfig.Messages messages = TestConfigs.messages();
        HexChatConfig config = TestConfigs.withMessages(new HexChatConfig.Messages(
                messages.prefix(),
                messages.noPermission(),
                messages.reloaded(),
                messages.usage(),
                messages.cooldownWait(),
                messages.chatMuted(),
                messages.chatMuteEnabled(),
                messages.chatMuteDisabled(),
                messages.chatMuteAlreadyEnabled(),
                messages.chatMuteAlreadyDisabled(),
                messages.chatMuteStatusEnabled(),
                messages.chatMuteStatusDisabled(),
                messages.privateMuted(),
                messages.playerMuteNotification(),
                messages.playerMuteSet(),
                messages.playerMuteRemoved(),
                messages.playerMuteNotMuted(),
                messages.playerMuteTargetNotFound(),
                messages.playerMuteInfo(),
                messages.playerMuteDurationInvalid(),
                "na wieki wieków"
        ));

        Fixture f = new Fixture(config);
        CommandSender sender = admin();
        UUID uuid = UUID.randomUUID();
        when(f.directory.resolve("Steve")).thenReturn(Optional.of(new PlayerDirectory.ResolvedPlayer(uuid, "Steve")));
        MuteEntry entry = new MuteEntry(uuid, "Steve", 0L, "obraza", 1000L);
        when(f.muteService.mute(eq(uuid), eq("Steve"), anyLong(), anyString())).thenReturn(entry);
        when(f.muteService.activeMute(uuid)).thenReturn(Optional.of(entry));

        f.command.onCommand(sender, null, "hexchat", new String[]{"mute", "Steve", "obraza"});
        f.command.onCommand(sender, null, "hexchat", new String[]{"muteinfo", "Steve"});

        verify(f.messages).sendPlayerMuteSet(sender, "Steve", "na wieki wieków", "obraza");
        verify(f.messages).sendPlayerMuteInfo(sender, "Steve", "na wieki wieków", "obraza");
    }

    @Test
    void mutePlayerTemporaryParsesDurationAndReason() {
        Fixture f = new Fixture();
        CommandSender sender = admin();
        UUID uuid = UUID.randomUUID();
        when(f.directory.resolve("Steve")).thenReturn(Optional.of(new PlayerDirectory.ResolvedPlayer(uuid, "Steve")));
        MuteEntry entry = new MuteEntry(uuid, "Steve", 2_000_000L, "spam", 1000L);
        when(f.muteService.mute(eq(uuid), eq("Steve"), eq(1_800_000L), eq("spam na czacie"))).thenReturn(entry);
        when(f.muteService.remainingMillis(entry)).thenReturn(1_800_000L);

        f.command.onCommand(sender, null, "hexchat", new String[]{"mute", "Steve", "30m", "spam", "na", "czacie"});

        verify(f.muteService).mute(uuid, "Steve", 1_800_000L, "spam na czacie");
        verify(f.messages).sendPlayerMuteSet(sender, "Steve", "30m", "spam na czacie");
    }

    @Test
    void muteWithNonDurationSecondArgTreatsItAsReason() {
        Fixture f = new Fixture();
        CommandSender sender = admin();
        UUID uuid = UUID.randomUUID();
        when(f.directory.resolve("Steve")).thenReturn(Optional.of(new PlayerDirectory.ResolvedPlayer(uuid, "Steve")));
        MuteEntry entry = new MuteEntry(uuid, "Steve", 0L, "obraza", 1000L);
        when(f.muteService.mute(eq(uuid), eq("Steve"), eq(0L), eq("obraza"))).thenReturn(entry);

        f.command.onCommand(sender, null, "hexchat", new String[]{"mute", "Steve", "obraza"});

        verify(f.muteService).mute(uuid, "Steve", 0L, "obraza");
    }

    @Test
    void muteWithInvalidDurationSendsError() {
        Fixture f = new Fixture();
        CommandSender sender = admin();
        UUID uuid = UUID.randomUUID();
        when(f.directory.resolve("Steve")).thenReturn(Optional.of(new PlayerDirectory.ResolvedPlayer(uuid, "Steve")));

        f.command.onCommand(sender, null, "hexchat", new String[]{"mute", "Steve", "10x"});

        verify(f.messages).sendPlayerMuteDurationInvalid(sender, "10x");
        verify(f.muteService, never()).mute(any(), any(), anyLong(), any());
    }

    @Test
    void muteUnknownPlayerSendsTargetNotFound() {
        Fixture f = new Fixture();
        CommandSender sender = admin();
        when(f.directory.resolve("Ghost")).thenReturn(Optional.empty());

        f.command.onCommand(sender, null, "hexchat", new String[]{"mute", "Ghost"});

        verify(f.messages).sendPlayerMuteTargetNotFound(sender, "Ghost");
        verify(f.muteService, never()).mute(any(), any(), anyLong(), any());
    }

    @Test
    void unmutePlayerWhenMutedSendsRemoved() {
        Fixture f = new Fixture();
        CommandSender sender = admin();
        UUID uuid = UUID.randomUUID();
        when(f.directory.resolve("Steve")).thenReturn(Optional.of(new PlayerDirectory.ResolvedPlayer(uuid, "Steve")));
        when(f.muteService.unmute(uuid)).thenReturn(true);

        f.command.onCommand(sender, null, "hexchat", new String[]{"unmute", "Steve"});

        verify(f.messages).sendPlayerMuteRemoved(sender, "Steve");
    }

    @Test
    void unmutePlayerWhenNotMutedSendsNotMuted() {
        Fixture f = new Fixture();
        CommandSender sender = admin();
        UUID uuid = UUID.randomUUID();
        when(f.directory.resolve("Steve")).thenReturn(Optional.of(new PlayerDirectory.ResolvedPlayer(uuid, "Steve")));
        when(f.muteService.unmute(uuid)).thenReturn(false);

        f.command.onCommand(sender, null, "hexchat", new String[]{"unmute", "Steve"});

        verify(f.messages).sendPlayerMuteNotMuted(sender, "Steve");
    }

    @Test
    void muteInfoForMutedPlayerShowsInfo() {
        Fixture f = new Fixture();
        CommandSender sender = admin();
        UUID uuid = UUID.randomUUID();
        when(f.directory.resolve("Steve")).thenReturn(Optional.of(new PlayerDirectory.ResolvedPlayer(uuid, "Steve")));
        MuteEntry entry = new MuteEntry(uuid, "Steve", 0L, "obraza", 1000L);
        when(f.muteService.activeMute(uuid)).thenReturn(Optional.of(entry));

        f.command.onCommand(sender, null, "hexchat", new String[]{"muteinfo", "Steve"});

        verify(f.messages).sendPlayerMuteInfo(sender, "Steve", "na zawsze", "obraza");
    }

    @Test
    void muteInfoForNotMutedPlayerSendsNotMuted() {
        Fixture f = new Fixture();
        CommandSender sender = admin();
        UUID uuid = UUID.randomUUID();
        when(f.directory.resolve("Steve")).thenReturn(Optional.of(new PlayerDirectory.ResolvedPlayer(uuid, "Steve")));
        when(f.muteService.activeMute(uuid)).thenReturn(Optional.empty());

        f.command.onCommand(sender, null, "hexchat", new String[]{"muteinfo", "Steve"});

        verify(f.messages).sendPlayerMuteNotMuted(sender, "Steve");
    }

    // --- Tab-complete ---

    @Test
    void tabCompleteForAdminReturnsMatchingSubcommands() {
        Fixture f = new Fixture();
        CommandSender sender = admin();

        List<String> all = f.command.onTabComplete(sender, null, "hexchat", new String[]{""});
        assertTrue(all.containsAll(List.of("reload", "mute", "unmute", "togglemute", "mutestatus", "muteinfo")));

        List<String> muteMatches = f.command.onTabComplete(sender, null, "hexchat", new String[]{"mute"});
        assertTrue(muteMatches.contains("mute"));
        assertTrue(muteMatches.contains("mutestatus"));
        assertTrue(muteMatches.contains("muteinfo"));
        assertEquals(3, muteMatches.size());
    }

    @Test
    void tabCompleteForNonAdminIsEmpty() {
        Fixture f = new Fixture();
        CommandSender sender = nonAdmin();

        List<String> result = f.command.onTabComplete(sender, null, "hexchat", new String[]{""});

        assertTrue(result.isEmpty());
    }

    @Test
    void tabCompleteSuggestsOnlinePlayersForMuteSubcommands() {
        Fixture f = new Fixture();
        CommandSender sender = admin();
        when(f.directory.onlineNames(any())).thenReturn(List.of("Steve", "Alex"));

        List<String> muteNames = f.command.onTabComplete(sender, null, "hexchat", new String[]{"mute", ""});
        assertTrue(muteNames.containsAll(List.of("Steve", "Alex")));

        List<String> unmuteNames = f.command.onTabComplete(sender, null, "hexchat", new String[]{"unmute", ""});
        assertTrue(unmuteNames.containsAll(List.of("Steve", "Alex")));

        List<String> infoNames = f.command.onTabComplete(sender, null, "hexchat", new String[]{"muteinfo", ""});
        assertTrue(infoNames.containsAll(List.of("Steve", "Alex")));
    }

    @Test
    void tabCompleteFiltersPlayerNamesByPrefix() {
        Fixture f = new Fixture();
        CommandSender sender = admin();
        when(f.directory.onlineNames(any())).thenReturn(List.of("Steve", "Alex"));

        List<String> result = f.command.onTabComplete(sender, null, "hexchat", new String[]{"mute", "st"});

        assertEquals(List.of("Steve"), result);
    }

    @Test
    void tabCompleteSuggestsDurationsAfterPlayer() {
        Fixture f = new Fixture();
        CommandSender sender = admin();

        List<String> all = f.command.onTabComplete(sender, null, "hexchat", new String[]{"mute", "Steve", ""});
        assertTrue(all.containsAll(List.of("30m", "1h", "2h", "1d", "7d", "perm")));

        List<String> filtered = f.command.onTabComplete(sender, null, "hexchat", new String[]{"mute", "Steve", "7"});
        assertEquals(List.of("7d"), filtered);
    }

    @Test
    void tabCompleteIsEmptyAtReasonPosition() {
        Fixture f = new Fixture();
        CommandSender sender = admin();

        List<String> result = f.command.onTabComplete(sender, null, "hexchat", new String[]{"mute", "Steve", "30m", ""});

        assertTrue(result.isEmpty());
    }

    @Test
    void tabCompleteIsEmptyForNonPlayerSubcommandSecondArg() {
        Fixture f = new Fixture();
        CommandSender sender = admin();

        List<String> result = f.command.onTabComplete(sender, null, "hexchat", new String[]{"reload", ""});

        assertTrue(result.isEmpty());
    }
}
