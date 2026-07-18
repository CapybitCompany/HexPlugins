package hexpvpsmp;

import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatLogDeathTest {

    private ServerMock server;
    private HexPvpSmpPlugin plugin;
    private PlayerMock victim;
    private PlayerMock attacker;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexPvpSmpPlugin.class);
        victim = server.addPlayer("Victim");
        attacker = server.addPlayer("Attacker");
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private Location outside() {
        return new Location(server.getWorld("world"), 500, 64, 500);
    }

    @Test
    void combatLogCountsDeathForQuitter() {
        victim.teleport(outside());
        plugin.combatTagService().tag(victim);
        int before = victim.getStatistic(Statistic.DEATHS);

        server.getPluginManager().callEvent(new PlayerQuitEvent(victim, (String) null));

        assertEquals(before + 1, victim.getStatistic(Statistic.DEATHS),
                "combat-log must count a death for the quitter");
    }

    @Test
    void combatLogCreditsKillToAttacker() {
        victim.teleport(outside());
        attacker.teleport(outside());
        // Record the attacker as the last damager of the victim.
        plugin.combatTagService().tagVictim(victim, attacker);
        int before = attacker.getStatistic(Statistic.PLAYER_KILLS);

        server.getPluginManager().callEvent(new PlayerQuitEvent(victim, (String) null));

        assertEquals(before + 1, attacker.getStatistic(Statistic.PLAYER_KILLS),
                "combat-log must credit the kill to the last attacker");
    }

    @Test
    void combatLoggerRespawnsAtSpawnOnJoin() {
        victim.teleport(outside());
        plugin.combatTagService().tag(victim);

        server.getPluginManager().callEvent(new PlayerQuitEvent(victim, (String) null));
        // Simulate the rejoin at the (stale) logout location.
        victim.teleport(outside());
        server.getPluginManager().callEvent(new PlayerJoinEvent(victim, ""));

        Location spawn = server.getWorld("world").getSpawnLocation();
        assertEquals(spawn.getBlockX(), victim.getLocation().getBlockX(),
                "combat-logger must respawn at world spawn X");
        assertEquals(spawn.getBlockZ(), victim.getLocation().getBlockZ(),
                "combat-logger must respawn at world spawn Z");
    }

    @Test
    void untaggedQuitDoesNotCountDeathOrQueueRespawn() {
        victim.teleport(outside());
        int beforeDeaths = victim.getStatistic(Statistic.DEATHS);

        server.getPluginManager().callEvent(new PlayerQuitEvent(victim, (String) null));
        victim.teleport(outside());
        server.getPluginManager().callEvent(new PlayerJoinEvent(victim, ""));

        assertEquals(beforeDeaths, victim.getStatistic(Statistic.DEATHS),
                "an untagged quit is not a death");
        assertEquals(outside().getBlockX(), victim.getLocation().getBlockX(),
                "an untagged quitter is not teleported to spawn");
    }

    @Test
    void disabledCombatLogDoesNotCountDeath() {
        plugin.getConfig().set("combat.combat-log.enabled", false);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());

        victim.teleport(outside());
        plugin.combatTagService().tag(victim);
        int before = victim.getStatistic(Statistic.DEATHS);

        server.getPluginManager().callEvent(new PlayerQuitEvent(victim, (String) null));

        assertEquals(before, victim.getStatistic(Statistic.DEATHS),
                "with combat-log disabled, no death is counted");
    }
}
