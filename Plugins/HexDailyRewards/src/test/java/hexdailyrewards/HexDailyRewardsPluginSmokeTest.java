package hexdailyrewards;

import hexdailyrewards.gui.DailyRewardsGuiHolder;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HexDailyRewardsPluginSmokeTest {

    private ServerMock server;
    private HexDailyRewardsPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexDailyRewardsPlugin.class);
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void shouldLoadAndExposeServices() {
        assertNotNull(plugin);
        assertNotNull(plugin.config());
        assertNotNull(plugin.rewardService());
        assertNotNull(plugin.gui());
    }

    @Test
    void shouldRunReloadCommandWithoutErrors() {
        Player op = server.addPlayer("OpUser");
        op.setOp(true);

        assertTrue(op.performCommand("hexdailyrewards reload"));
        assertNotNull(plugin.config());
    }

    @Test
    void shouldAllowNextRewardAfterCalendarDayChanges() {
        Player player = server.addPlayer("DailyUser");
        plugin.rewardService().setClock(Clock.fixed(Instant.parse("2026-07-20T21:59:00Z"), ZoneOffset.UTC));

        ClaimResult first = plugin.rewardService().claim(player);
        assertTrue(first.claimed());

        plugin.rewardService().setClock(Clock.fixed(Instant.parse("2026-07-20T22:01:00Z"), ZoneOffset.UTC));
        ClaimState afterMidnightWarsaw = plugin.rewardService().state(player);
        assertTrue(afterMidnightWarsaw.available(), "New Warsaw calendar day should reset the reward.");
    }

    @Test
    void shouldBlockSecondRewardOnSameCalendarDay() {
        Player player = server.addPlayer("SameDayUser");
        plugin.rewardService().setClock(Clock.fixed(Instant.parse("2026-07-20T08:00:00Z"), ZoneOffset.UTC));

        assertTrue(plugin.rewardService().claim(player).claimed());

        plugin.rewardService().setClock(Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC));
        ClaimState sameDay = plugin.rewardService().state(player);
        assertFalse(sameDay.available());
        assertTrue(sameDay.remaining().toSeconds() > 0);
    }

    @Test
    void shouldOpenGuiWithFillerAndRewardItem() {
        Player player = server.addPlayer("GuiUser");
        plugin.rewardService().setClock(Clock.fixed(Instant.parse("2026-07-20T10:00:00Z"), ZoneOffset.UTC));

        plugin.gui().open(player);

        Inventory top = player.getOpenInventory().getTopInventory();
        assertInstanceOf(DailyRewardsGuiHolder.class, top.getHolder());
        assertEquals(27, top.getSize());
        assertEquals(Material.BLACK_STAINED_GLASS_PANE, top.getItem(0).getType());
        assertEquals(Material.DIAMOND, top.getItem(13).getType());
        assertEquals(Material.BLACK_STAINED_GLASS_PANE, top.getItem(15).getType(), "clock info item should be removed");
        assertEquals(Material.BARRIER, top.getItem(18).getType());
        assertEquals(Material.LIME_DYE, top.getItem(26).getType());
        assertTrue(plugin.config().gui().filler().hideTooltip());
    }

    @Test
    void shouldMigrateOldVisualConfigOnReload() {
        plugin.getConfig().set("config-version", 1);
        plugin.getConfig().set("gui.title", "&6Daily Rewards");
        plugin.getConfig().set("gui.items.close.slot", 22);
        plugin.getConfig().set("gui.items.status-available.slot", 11);
        plugin.getConfig().set("gui.items.status-claimed.slot", 11);
        plugin.getConfig().set("gui.items.info.enabled", true);
        plugin.getConfig().set("gui.items.available.lore", List.of("&7Dzisiejsza nagroda jest gotowa.", "{reward_lore}"));
        plugin.saveConfig();

        assertTrue(plugin.reloadPluginRuntime());

        assertEquals(2, plugin.getConfig().getInt("config-version"));
        assertEquals("&cDaily Rewards", plugin.config().gui().title());
        assertEquals(18, plugin.config().gui().items().close().slot());
        assertEquals(26, plugin.config().gui().items().statusAvailable().slot());
        assertEquals(26, plugin.config().gui().items().statusClaimed().slot());
        assertFalse(plugin.config().gui().items().info().enabled());
        assertEquals(List.of("{reward_lore}"), plugin.config().gui().items().available().lore());
        assertTrue(plugin.config().gui().filler().hideTooltip());
    }

    @Test
    void shouldResolveCycleDaysAndWrapAfterConfiguredCycle() {
        ResolvedDailyReward dayOne = plugin.rewardService()
                .currentReward(LocalDate.of(2026, 7, 20))
                .orElseThrow();
        ResolvedDailyReward dayFourteen = plugin.rewardService()
                .currentReward(LocalDate.of(2026, 8, 2))
                .orElseThrow();
        ResolvedDailyReward wrapped = plugin.rewardService()
                .currentReward(LocalDate.of(2026, 8, 3))
                .orElseThrow();

        assertEquals(1, dayOne.cycleDay());
        assertEquals("day-1", dayOne.definition().id());
        assertEquals(Material.DIAMOND, dayOne.definition().material());
        assertEquals(14, dayFourteen.cycleDay());
        assertEquals("day-14", dayFourteen.definition().id());
        assertEquals(1, wrapped.cycleDay());
        assertEquals("day-1", wrapped.definition().id());
    }

    @Test
    void shouldUseDateOverrideOverCycleReward() {
        ResolvedDailyReward reward = plugin.rewardService()
                .currentReward(LocalDate.of(2026, 12, 24))
                .orElseThrow();

        assertTrue(reward.dateOverride());
        assertEquals("2026-12-24", reward.definition().id());
        assertEquals(Material.CHEST, reward.definition().material());
    }

    @Test
    void shouldExecuteCommandsFromTodaysRewardDefinition() {
        List<String> executed = new ArrayList<>();
        server.getCommandMap().register("hexdailyrewardstest", new Command("hdrtest") {
            @Override
            public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
                executed.add(String.join(" ", args));
                return true;
            }
        });
        plugin.getConfig().set("rewards-calendar.days.day-1.commands",
                List.of("hdrtest {player} {reward_day} {reward_id}"));
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());

        Player player = server.addPlayer("CommandUser");
        plugin.rewardService().setClock(Clock.fixed(Instant.parse("2026-07-20T10:00:00Z"), ZoneOffset.UTC));

        assertTrue(plugin.rewardService().claim(player).claimed());

        assertEquals(List.of("CommandUser 1 day-1"), executed);
    }

    @Test
    void shouldUseDefaultRewardLoreWhenConfiguredLoreIsBlank() {
        plugin.getConfig().set("rewards-calendar.default-lore", List.of("&7Default {reward_name}"));
        plugin.getConfig().set("rewards-calendar.days.day-1.lore", List.of(""));
        plugin.saveConfig();

        assertTrue(plugin.reloadPluginRuntime());

        ResolvedDailyReward reward = plugin.rewardService()
                .currentReward(LocalDate.of(2026, 7, 20))
                .orElseThrow();
        assertEquals(List.of("&7Default {reward_name}"), reward.definition().lore());
    }

    @Test
    void shouldExposeRemainingTimePlaceholderValues() {
        Player player = server.addPlayer("PlaceholderUser");
        plugin.rewardService().setClock(Clock.fixed(Instant.parse("2026-07-20T08:00:00Z"), ZoneOffset.UTC));
        assertTrue(plugin.rewardService().claim(player).claimed());

        plugin.rewardService().setClock(Clock.fixed(Instant.parse("2026-07-20T20:30:00Z"), ZoneOffset.UTC));
        ClaimState state = plugin.rewardService().state(player);

        var values = plugin.rewardService().placeholders(player.getUniqueId(), player.getName(), state);
        assertEquals("1h 30min", values.get("time"));
        assertEquals("1", values.get("hours"));
        assertEquals("30", values.get("minutes"));
        assertEquals("Odebrane", values.get("status"));
        assertEquals("&cOdebrany", values.get("player_status"));
        assertEquals("false", values.get("available"));
    }
}
