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

class CommandFilterServiceTest {

    private static Player normalPlayer() {
        Player player = mock(Player.class);
        lenient().when(player.isOp()).thenReturn(false);
        lenient().when(player.hasPermission(anyString())).thenReturn(false);
        return player;
    }

    private static HexChatConfig.CommandFilter filter(boolean enabled) {
        return new HexChatConfig.CommandFilter(
                enabled,
                "hexchat.commandfilter.bypass",
                List.of("help", "spawn"),
                "<red>blocked</red>",
                true,
                List.of("essentials:spawn")
        );
    }

    @Test
    void disabledFilterBlocksNothing() {
        HexChatConfig config = TestConfigs.withCommandFilter(filter(false));
        CommandFilterService service = new CommandFilterService(config);

        assertFalse(service.isBlocked(normalPlayer(), "/cokolwiek"));
    }

    @Test
    void enabledFilterAllowsOnlyAllowedCommands() {
        HexChatConfig config = TestConfigs.withCommandFilter(filter(true));
        CommandFilterService service = new CommandFilterService(config);
        Player player = normalPlayer();

        assertFalse(service.isBlocked(player, "/help"), "help jest dozwolone");
        assertFalse(service.isBlocked(player, "/spawn arg"), "spawn jest dozwolone");
        assertTrue(service.isBlocked(player, "/gamemode"), "gamemode nie jest dozwolone");
    }

    @Test
    void namespacedCommandsAreNormalized() {
        HexChatConfig config = TestConfigs.withCommandFilter(filter(true));
        CommandFilterService service = new CommandFilterService(config);
        Player player = normalPlayer();

        // minecraft:help -> help, więc dozwolone.
        assertFalse(service.isBlocked(player, "/minecraft:help"));
        // essentials:fly -> fly, niedozwolone.
        assertTrue(service.isBlocked(player, "/essentials:fly"));
    }

    @Test
    void bypassPermissionSkipsFilter() {
        HexChatConfig config = TestConfigs.withCommandFilter(filter(true));
        CommandFilterService service = new CommandFilterService(config);
        Player player = normalPlayer();
        when(player.hasPermission("hexchat.commandfilter.bypass")).thenReturn(true);

        assertFalse(service.isBlocked(player, "/gamemode"));
    }

    @Test
    void opAndAdminSkipFilter() {
        HexChatConfig config = TestConfigs.withCommandFilter(filter(true));
        CommandFilterService service = new CommandFilterService(config);

        Player op = normalPlayer();
        when(op.isOp()).thenReturn(true);
        assertFalse(service.isBlocked(op, "/gamemode"));

        Player admin = normalPlayer();
        when(admin.hasPermission("hexchat.admin")).thenReturn(true);
        assertFalse(service.isBlocked(admin, "/gamemode"));
    }

    @Test
    void filtersCommandSendList() {
        HexChatConfig config = TestConfigs.withCommandFilter(filter(true));
        CommandFilterService service = new CommandFilterService(config);
        List<String> commands = new ArrayList<>(List.of("help", "spawn", "gamemode", "op"));

        service.filterCommandSendList(normalPlayer(), commands);

        assertTrue(commands.contains("help"));
        assertTrue(commands.contains("spawn"));
        assertFalse(commands.contains("gamemode"));
        assertFalse(commands.contains("op"));
    }

    @Test
    void filtersTabCompletions() {
        HexChatConfig config = TestConfigs.withCommandFilter(filter(true));
        CommandFilterService service = new CommandFilterService(config);
        List<String> completions = new ArrayList<>(List.of("/help", "/gamemode"));

        service.filterTabCompletions(normalPlayer(), completions);

        assertTrue(completions.contains("/help"));
        assertFalse(completions.contains("/gamemode"));
    }

    @Test
    void allowedNamespacedSuggestionSurvivesWhileOtherNamespacedRemoved() {
        HexChatConfig config = TestConfigs.withCommandFilter(filter(true));
        CommandFilterService service = new CommandFilterService(config);
        List<String> commands = new ArrayList<>(List.of("essentials:spawn", "essentials:fly", "help"));

        service.filterCommandSendList(normalPlayer(), commands);

        assertTrue(commands.contains("essentials:spawn"), "Namespaced dozwolone wprost pozostaje");
        assertFalse(commands.contains("essentials:fly"), "Inne namespaced są ukrywane");
        assertTrue(commands.contains("help"));
    }

    @Test
    void sendListRemovesNothingWhenBypassed() {
        HexChatConfig config = TestConfigs.withCommandFilter(filter(true));
        CommandFilterService service = new CommandFilterService(config);
        Player op = normalPlayer();
        when(op.isOp()).thenReturn(true);
        List<String> commands = new ArrayList<>(List.of("help", "gamemode"));

        service.filterCommandSendList(op, commands);

        assertTrue(commands.contains("gamemode"), "OP widzi wszystko");
    }
}
