package hexleszek;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HexLeszekPluginTest {

    private ServerMock server;
    private HexLeszekPlugin plugin;
    private List<String> executedCommands;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        executedCommands = new ArrayList<>();
        server.getCommandMap().register("hexleszektest", new Command("hexeconomy") {
            @Override
            public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
                executedCommands.add(String.join(" ", args));
                return true;
            }
        });
        plugin = MockBukkit.load(HexLeszekPlugin.class);
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void shouldRewardAndTrackPlayerOnce() {
        Player player = server.addPlayer("Viewer");
        player.addAttachment(plugin, "hexleszek.use", true);

        assertTrue(player.performCommand("leszek"));
        assertTrue(player.performCommand("leszek"));

        assertEquals(List.of("add Viewer 30"), executedCommands);
        assertEquals(1, plugin.storage().trackedPlayers());
        assertTrue(plugin.storage().hasClaim(player.getUniqueId()));
    }

    @Test
    void shouldBlockPlayerWithoutPermission() {
        Player player = server.addPlayer("NoPerm");

        assertTrue(player.performCommand("leszek"));

        assertTrue(executedCommands.isEmpty());
        assertEquals(0, plugin.storage().trackedPlayers());
    }
}
