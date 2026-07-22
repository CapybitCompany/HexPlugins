package hexafkzone;

import hexafkzone.config.AfkZoneConfig;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HexAfkZonePluginTest {

    private ServerMock server;
    private HexAfkZonePlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        server.addSimpleWorld("spawn");
        plugin = MockBukkit.load(HexAfkZonePlugin.class);
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void shouldLoadServicesAndConfig() {
        assertNotNull(plugin.config());
        assertNotNull(plugin.service());
        assertEquals("world", plugin.config().region().world());
        assertTrue(plugin.config().region().contains(location(2782, 72, 952)));
        assertTrue(plugin.config().region().contains(location(2786, 81, 965)));
        assertFalse(plugin.config().region().contains(location(2787, 81, 965)));
    }

    @Test
    void shouldRunReloadCommand() {
        Player op = server.addPlayer("OpUser");
        op.setOp(true);

        assertTrue(op.performCommand("hexafkzone reload"));
    }

    @Test
    void shouldStartAndResetAfkSessionWhenPlayerLeavesRegion() {
        Player player = server.addPlayer("AfkUser");
        plugin.service().setClock(Clock.fixed(Instant.parse("2026-07-22T10:00:00Z"), ZoneOffset.UTC));

        player.teleport(location(2784, 75, 960));
        plugin.service().updatePlayer(player);

        assertTrue(plugin.service().isAfk(player));
        AfkSession first = plugin.service().session(player.getUniqueId()).orElseThrow();
        assertEquals("default", first.profileId());
        assertEquals("default", first.rewardGroupId());

        player.teleport(location(2700, 75, 960));
        plugin.service().updatePlayer(player);

        assertFalse(plugin.service().isAfk(player));

        plugin.service().setClock(Clock.fixed(Instant.parse("2026-07-22T10:10:00Z"), ZoneOffset.UTC));
        player.teleport(location(2784, 75, 960));
        plugin.service().updatePlayer(player);

        AfkSession second = plugin.service().session(player.getUniqueId()).orElseThrow();
        assertEquals(Instant.parse("2026-07-22T10:10:00Z"), second.enteredAt());
        assertTrue(second.claimedMilestones().isEmpty());
    }

    @Test
    void shouldResolveRankProfilesWithMediaSharingDefaultRewards() {
        Player defaultPlayer = server.addPlayer("DefaultUser");
        Player media = server.addPlayer("MediaUser");
        Player vip = server.addPlayer("VipUser");
        Player svip = server.addPlayer("SvipUser");
        Player elite = server.addPlayer("EliteUser");
        Player op = server.addPlayer("OperatorUser");

        media.addAttachment(plugin, "hexafkzone.rank.media", true);
        vip.addAttachment(plugin, "hexafkzone.rank.vip", true);
        svip.addAttachment(plugin, "hexafkzone.rank.svip", true);
        elite.addAttachment(plugin, "hexafkzone.rank.elita", true);
        op.setOp(true);

        assertProfile(defaultPlayer, "default", "&7", "default");
        assertProfile(media, "media", "&d", "default");
        assertProfile(vip, "vip", "&e", "vip");
        assertProfile(svip, "svip", "&6", "svip");
        assertProfile(elite, "elite", "&b", "elite");
        assertProfile(op, "elite", "&b", "elite");
    }

    @Test
    void shouldAwardMilestoneOncePerAfkSessionAndAgainAfterReentering() {
        List<String> executed = new ArrayList<>();
        server.getCommandMap().register("hexafkzonetest", new Command("afktest") {
            @Override
            public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
                executed.add(String.join(" ", args));
                return true;
            }
        });
        plugin.getConfig().set("reward-groups.default.milestones.5m.display-name", "Testowy diament");
        plugin.getConfig().set("reward-groups.default.milestones.5m.amount", 2);
        plugin.getConfig().set("reward-groups.default.milestones.5m.commands",
                List.of("afktest {player} {profile} {group} {milestone} {reward_name} {amount}"));
        plugin.getConfig().set("reward-groups.default.milestones.20m", null);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());

        Player player = server.addPlayer("RewardUser");
        plugin.service().setClock(Clock.fixed(Instant.parse("2026-07-22T10:00:00Z"), ZoneOffset.UTC));
        player.teleport(location(2784, 75, 960));
        plugin.service().updatePlayer(player);

        plugin.service().setClock(Clock.fixed(Instant.parse("2026-07-22T10:05:00Z"), ZoneOffset.UTC));
        plugin.service().tickPlayer(player);
        plugin.service().tickPlayer(player);

        assertEquals(List.of("RewardUser default default 5m Testowy diament 2"), executed);

        player.teleport(location(2700, 75, 960));
        plugin.service().updatePlayer(player);

        plugin.service().setClock(Clock.fixed(Instant.parse("2026-07-22T11:00:00Z"), ZoneOffset.UTC));
        player.teleport(location(2784, 75, 960));
        plugin.service().updatePlayer(player);

        plugin.service().setClock(Clock.fixed(Instant.parse("2026-07-22T11:05:00Z"), ZoneOffset.UTC));
        plugin.service().tickPlayer(player);

        assertEquals(List.of(
                "RewardUser default default 5m Testowy diament 2",
                "RewardUser default default 5m Testowy diament 2"
        ), executed);
    }

    private void assertProfile(Player player, String id, String color, String rewardGroup) {
        AfkZoneConfig.RankProfile profile = plugin.service().profileFor(player);
        assertEquals(id, profile.id());
        assertEquals(color, profile.color());
        assertEquals(rewardGroup, profile.rewardGroup());
    }

    private Location location(int x, int y, int z) {
        return new Location(server.getWorld("world"), x, y, z);
    }
}
