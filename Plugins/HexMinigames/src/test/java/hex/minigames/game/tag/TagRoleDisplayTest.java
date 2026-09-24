package hex.minigames.game.tag;

import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Command integration: colors, role transfers, late viewers and idempotent cleanup. */
class TagRoleDisplayTest {
    private final Plugin plugin = mock(Plugin.class);
    private final Server server = mock(Server.class);
    private final PluginManager manager = mock(PluginManager.class);
    private final ConsoleCommandSender console = mock(ConsoleCommandSender.class);
    private final List<Player> online = new ArrayList<>();
    private final Player one = player("One"), two = player("Two");
    private final TagRuntime runtime = new TagRuntime(List.of(one.getUniqueId(), two.getUniqueId()), new Random(1), 40, 0, 3000);
    private final TagRoleDisplay display = new TagRoleDisplay(plugin);

    TagRoleDisplayTest() {
        online.addAll(List.of(one, two));
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        when(manager.isPluginEnabled("GlowAPI")).thenReturn(true);
        when(server.getPluginCommand("glowapi:glow")).thenReturn(mock(PluginCommand.class));
        when(server.getConsoleSender()).thenReturn(console);
        doReturn(online).when(server).getOnlinePlayers();
        when(server.dispatchCommand(eq(console), anyString())).thenReturn(true);
    }

    @Test void assignsColorsAndChangesThemImmediatelyOnTransfer() {
        display.show(List.of(one, two), runtime);
        Player tagger = runtime.isTagger(one.getUniqueId()) ? one : two;
        Player runner = tagger == one ? two : one;
        verify(server).dispatchCommand(console, "glowapi:glow red " + tagger.getName());
        verify(server).dispatchCommand(console, "glowapi:glow green " + runner.getName());
        assertTrue(runtime.transfer(tagger.getUniqueId(), runner.getUniqueId(), 100));
        display.update(tagger, false);
        display.update(runner, true);
        verify(server).dispatchCommand(console, "glowapi:glow green " + tagger.getName());
        verify(server).dispatchCommand(console, "glowapi:glow red " + runner.getName());
        verify(one, never()).setGlowing(anyBoolean());
        verify(one, never()).getScoreboard();
    }

    @Test void sendsToLateViewersWithoutDispatchingCommandsEveryTick() {
        display.show(List.of(one, two), runtime);
        display.refresh(List.of(one, two), runtime); // delayed login pass
        clearInvocations(server);
        for (int i = 0; i < 10; i++) display.refresh(List.of(one, two), runtime);
        verify(server, never()).dispatchCommand(any(), anyString());
        online.add(player("Observer"));
        display.refresh(List.of(one, two), runtime);
        display.refresh(List.of(one, two), runtime);
        verify(server, times(4)).dispatchCommand(eq(console), anyString());
    }

    @Test void quitAndRepeatedRoundCleanupDisableEachPlayerOnlyOnce() {
        display.show(List.of(one, two), runtime);
        display.restore(one.getUniqueId());
        display.update(one, true);
        display.clear();
        display.clear();
        verify(server).dispatchCommand(console, "glowapi:glow off One");
        verify(server).dispatchCommand(console, "glowapi:glow off Two");
    }

    @Test void rejectsMissingPluginAndRetriesFailedColorCommand() {
        when(manager.isPluginEnabled("GlowAPI")).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> display.show(List.of(one, two), runtime));
        verify(server, never()).dispatchCommand(any(), anyString());
        when(manager.isPluginEnabled("GlowAPI")).thenReturn(true);
        when(server.dispatchCommand(eq(console), anyString())).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> display.show(List.of(one, two), runtime));
        when(server.dispatchCommand(eq(console), anyString())).thenReturn(true);
        assertDoesNotThrow(() -> display.refresh(List.of(one, two), runtime));
        verify(server, times(3)).dispatchCommand(eq(console), anyString());
    }

    private Player player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        return player;
    }
}
