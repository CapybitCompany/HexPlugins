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
        assertEquals(20, plugin.config().rewards().base().amount());
        assertEquals(3, plugin.config().rewards().chanceRewards().size());
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

        assertFalse(player.isSleepingIgnored());
        player.teleport(location(2784, 75, 960));
        plugin.service().updatePlayer(player);

        assertTrue(plugin.service().isAfk(player));
        assertTrue(player.isSleepingIgnored());
        AfkSession first = plugin.service().session(player.getUniqueId()).orElseThrow();
        assertEquals("default", first.profileId());
        assertEquals(Instant.parse("2026-07-22T10:10:00Z"), first.nextRewardAt());

        player.teleport(location(2700, 75, 960));
        plugin.service().updatePlayer(player);

        assertFalse(plugin.service().isAfk(player));
        assertFalse(player.isSleepingIgnored());

        plugin.service().setClock(Clock.fixed(Instant.parse("2026-07-22T10:10:00Z"), ZoneOffset.UTC));
        player.teleport(location(2784, 75, 960));
        plugin.service().updatePlayer(player);

        assertTrue(player.isSleepingIgnored());
        AfkSession second = plugin.service().session(player.getUniqueId()).orElseThrow();
        assertEquals(Instant.parse("2026-07-22T10:10:00Z"), second.enteredAt());
        assertEquals(Instant.parse("2026-07-22T10:20:00Z"), second.nextRewardAt());
    }

    @Test
    void shouldRestoreExistingSleepingIgnoredStateWhenPlayerLeavesRegion() {
        Player player = server.addPlayer("IgnoredUser");
        player.setSleepingIgnored(true);

        player.teleport(location(2784, 75, 960));
        plugin.service().updatePlayer(player);

        assertTrue(plugin.service().isAfk(player));
        assertTrue(player.isSleepingIgnored());

        player.teleport(location(2700, 75, 960));
        plugin.service().updatePlayer(player);

        assertFalse(plugin.service().isAfk(player));
        assertTrue(player.isSleepingIgnored());
    }

    @Test
    void shouldResolveRankProfilesWithConfiguredIntervals() {
        Player defaultPlayer = server.addPlayer("DefaultUser");
        Player media = server.addPlayer("MediaUser");
        Player vip = server.addPlayer("VipUser");
        Player svip = server.addPlayer("SvipUser");
        Player elite = server.addPlayer("EliteUser");
        Player admin = server.addPlayer("AdminUser");
        Player op = server.addPlayer("OperatorUser");

        media.addAttachment(plugin, "hexafkzone.rank.media", true);
        vip.addAttachment(plugin, "hexafkzone.rank.vip", true);
        svip.addAttachment(plugin, "hexafkzone.rank.svip", true);
        elite.addAttachment(plugin, "hexafkzone.rank.elita", true);
        admin.addAttachment(plugin, "hexafkzone.admin", true);
        op.setOp(true);

        assertProfile(defaultPlayer, "default", "&7", 600L);
        assertProfile(media, "media", "&d", 360L);
        assertProfile(vip, "vip", "&e", 540L);
        assertProfile(svip, "svip", "&6", 480L);
        assertProfile(elite, "elite", "&b", 360L);
        assertProfile(admin, "admin", "&c", 360L);
        assertProfile(op, "admin", "&c", 360L);
    }

    @Test
    void shouldAwardRewardsEveryConfiguredInterval() {
        List<String> executed = new ArrayList<>();
        server.getCommandMap().register("hexafkzonetest", new Command("afktest") {
            @Override
            public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
                executed.add(String.join(" ", args));
                return true;
            }
        });
        plugin.getConfig().set("rank-profiles.default.reward-interval", "5m");
        plugin.getConfig().set("rewards.base.commands",
                List.of("afktest {player} base {base_amount} {profile} {interval_seconds}"));
        plugin.getConfig().set("rewards.chance.afk_key.chance-percent", 100.0D);
        plugin.getConfig().set("rewards.chance.afk_key.commands", List.of("afktest {player} afk_key"));
        plugin.getConfig().set("rewards.chance.epic_key.chance-percent", 0.0D);
        plugin.getConfig().set("rewards.chance.premium_key.chance-percent", 0.0D);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());

        Player player = server.addPlayer("RewardUser");
        plugin.service().setClock(Clock.fixed(Instant.parse("2026-07-22T10:00:00Z"), ZoneOffset.UTC));
        player.teleport(location(2784, 75, 960));
        plugin.service().updatePlayer(player);

        plugin.service().setClock(Clock.fixed(Instant.parse("2026-07-22T10:04:59Z"), ZoneOffset.UTC));
        plugin.service().tickPlayer(player);
        assertTrue(executed.isEmpty());

        plugin.service().setClock(Clock.fixed(Instant.parse("2026-07-22T10:05:00Z"), ZoneOffset.UTC));
        plugin.service().tickPlayer(player);
        plugin.service().tickPlayer(player);

        assertEquals(List.of(
                "RewardUser base 20 default 300",
                "RewardUser afk_key"
        ), executed);

        plugin.service().setClock(Clock.fixed(Instant.parse("2026-07-22T10:10:00Z"), ZoneOffset.UTC));
        plugin.service().tickPlayer(player);

        assertEquals(List.of(
                "RewardUser base 20 default 300",
                "RewardUser afk_key",
                "RewardUser base 20 default 300",
                "RewardUser afk_key"
        ), executed);
    }

    private void assertProfile(Player player, String id, String color, long intervalSeconds) {
        AfkZoneConfig.RankProfile profile = plugin.service().profileFor(player);
        assertEquals(id, profile.id());
        assertEquals(color, profile.color());
        assertEquals(intervalSeconds, profile.rewardIntervalSeconds());
    }

    private Location location(int x, int y, int z) {
        return new Location(server.getWorld("world"), x, y, z);
    }
}
