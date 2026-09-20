package hex.minigames.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class HexMinigamesCommandTest {
    @Test void seriesTestAcceptsSelectedPlayersFromConsoleAndDeduplicatesNames() {
        try (var bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class)) {
            var sessions = org.mockito.Mockito.mock(hex.minigames.runtime.MinigamesSessionService.class);
            var sender = org.mockito.Mockito.mock(org.bukkit.command.CommandSender.class);
            org.mockito.Mockito.when(sender.hasPermission("hexminigames.admin")).thenReturn(true);
            var first = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
            var second = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
            bukkit.when(() -> org.bukkit.Bukkit.getPlayerExact("First")).thenReturn(first);
            bukkit.when(() -> org.bukkit.Bukkit.getPlayerExact("Second")).thenReturn(second);
            var command = new HexMinigamesCommand(() -> null, sessions, null, () -> {});
            command.onCommand(sender, null, "hexminigames", new String[]{"test", "First,Second", "First"});
            org.mockito.Mockito.verify(sessions).startAdminSeries(List.of(first, second));
            command.onCommand(sender, null, "hexminigames", new String[]{"test", "First", "Offline"});
            org.mockito.Mockito.verifyNoMoreInteractions(sessions);
        }
    }
    @Test
    void testArenyParserAcceptsSpaceSeparatedPlayers() {
        assertEquals(
                List.of("Quezo", "Nick2"),
                HexMinigamesCommand.parsePlayerNames(new String[]{"testareny", "hot_head", "Quezo", "Nick2"}, 2)
        );
    }

    @Test
    void testArenyParserAcceptsCommaSeparatedPlayers() {
        assertEquals(
                List.of("Quezo", "Nick2", "Nick3"),
                HexMinigamesCommand.parsePlayerNames(new String[]{"testareny", "hot_head", "Quezo,Nick2", "Nick3"}, 2)
        );
    }
}
