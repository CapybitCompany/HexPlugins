package hexchat.service;

import hexchat.config.HexChatConfig;
import hexchat.support.TestConfigs;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TabCompleteFilterServiceTest {

    private static Player normalPlayer() {
        Player player = mock(Player.class);
        lenient().when(player.isOp()).thenReturn(false);
        lenient().when(player.hasPermission(anyString())).thenReturn(false);
        return player;
    }

    private static HexChatConfig.TabCompleteFilter filter(boolean enabled) {
        return new HexChatConfig.TabCompleteFilter(
                enabled,
                "hexchat.tabcomplete.bypass",
                List.of("plugins", "version", "bukkit:plugins")
        );
    }

    @Test
    void hidesConfiguredCommands() {
        HexChatConfig config = TestConfigs.withTabCompleteFilter(filter(true));
        TabCompleteFilterService service = new TabCompleteFilterService(config);
        List<String> completions = new ArrayList<>(List.of("plugins", "version", "help"));

        service.filterTabCompletions(normalPlayer(), completions);

        assertFalse(completions.contains("plugins"));
        assertFalse(completions.contains("version"));
        assertTrue(completions.contains("help"), "Nieukryte komendy pozostają");
    }

    @Test
    void disabledFilterHidesNothing() {
        HexChatConfig config = TestConfigs.withTabCompleteFilter(filter(false));
        TabCompleteFilterService service = new TabCompleteFilterService(config);
        List<String> completions = new ArrayList<>(List.of("plugins", "version"));

        service.filterTabCompletions(normalPlayer(), completions);

        assertTrue(completions.contains("plugins"));
        assertTrue(completions.contains("version"));
    }

    @Test
    void bypassPermissionSkipsFilter() {
        HexChatConfig config = TestConfigs.withTabCompleteFilter(filter(true));
        TabCompleteFilterService service = new TabCompleteFilterService(config);
        Player player = normalPlayer();
        when(player.hasPermission("hexchat.tabcomplete.bypass")).thenReturn(true);
        List<String> completions = new ArrayList<>(List.of("plugins", "version"));

        service.filterTabCompletions(player, completions);

        assertTrue(completions.contains("plugins"));
        assertTrue(completions.contains("version"));
    }

    @Test
    void opBypassesFilterConsistentlyWithCommandFilter() {
        // Naprawiona niespójność: OP pomija filtr tab-complete, tak jak w CommandFilterService.
        HexChatConfig config = TestConfigs.withTabCompleteFilter(filter(true));
        TabCompleteFilterService service = new TabCompleteFilterService(config);
        Player op = normalPlayer();
        when(op.isOp()).thenReturn(true);
        List<String> completions = new ArrayList<>(List.of("plugins", "version"));

        service.filterTabCompletions(op, completions);

        assertTrue(completions.contains("plugins"), "OP powinien widzieć wszystkie podpowiedzi");
        assertTrue(completions.contains("version"));
    }

    @Test
    void adminPermissionBypassesFilter() {
        HexChatConfig config = TestConfigs.withTabCompleteFilter(filter(true));
        TabCompleteFilterService service = new TabCompleteFilterService(config);
        Player admin = normalPlayer();
        when(admin.hasPermission("hexchat.admin")).thenReturn(true);
        List<String> completions = new ArrayList<>(List.of("plugins", "version"));

        service.filterTabCompletions(admin, completions);

        assertTrue(completions.contains("plugins"));
        assertTrue(completions.contains("version"));
    }

    @Test
    void filtersCommandSendSuggestionsWithNamespaceNormalization() {
        HexChatConfig config = TestConfigs.withTabCompleteFilter(filter(true));
        TabCompleteFilterService service = new TabCompleteFilterService(config);
        // paper:plugins -> kandydat "plugins" ukryty; bukkit:plugins wprost na liście ukrytych.
        List<String> commands = new ArrayList<>(List.of("paper:plugins", "bukkit:plugins", "help"));

        service.filterCommandSendSuggestions(normalPlayer(), commands);

        assertFalse(commands.contains("paper:plugins"));
        assertFalse(commands.contains("bukkit:plugins"));
        assertTrue(commands.contains("help"));
    }
}
