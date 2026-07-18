package hexpvpsmp;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Egg;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Non-damage projectile hit effects (wind charge, snowball, egg, ender pearl)
 * must not take effect inside protected regions when they fly in from outside.
 * Projectiles are Mockito stubs so only the listener decision is exercised.
 */
class ProjectileHitTest {

    private ServerMock server;
    private HexPvpSmpPlugin plugin;
    private PlayerMock shooter;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexPvpSmpPlugin.class);
        shooter = server.addPlayer("Shooter");
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private Location spawn() {
        return new Location(server.getWorld("world"), 0, 64, 0);
    }

    private Location noBuild() {
        return new Location(server.getWorld("world"), 0, 64, 150);
    }

    private Location wild() {
        return new Location(server.getWorld("world"), 500, 64, 500);
    }

    private void reload() {
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());
    }

    private boolean fireHit(Class<? extends Projectile> type, Location hitAt, boolean shooterBypass) {
        if (shooterBypass) {
            shooter.setOp(true);
        }
        Projectile projectile = mock(type);
        when(projectile.getShooter()).thenReturn(shooter);
        Block hit = hitAt.getBlock();
        hit.setType(Material.STONE);
        ProjectileHitEvent event = new ProjectileHitEvent(projectile, hit);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    // ---- Spawn: always blocked ------------------------------------------

    @Test
    void windChargeHitInSpawnIsBlocked() {
        assertTrue(fireHit(WindCharge.class, spawn(), false), "wind charge hit in spawn blocked");
    }

    @Test
    void snowballHitInSpawnIsBlocked() {
        assertTrue(fireHit(Snowball.class, spawn(), false), "snowball hit in spawn blocked");
    }

    @Test
    void eggHitInSpawnIsBlocked() {
        assertTrue(fireHit(Egg.class, spawn(), false), "egg hit in spawn blocked");
    }

    @Test
    void enderPearlHitInSpawnIsBlocked() {
        assertTrue(fireHit(EnderPearl.class, spawn(), false), "ender pearl hit in spawn blocked");
    }

    @Test
    void spawnStaysSafeEvenForBypassShooter() {
        assertTrue(fireHit(WindCharge.class, spawn(), true),
                "spawn projectile hit must be blocked even for OP/bypass");
    }

    // ---- No-build: category + config ------------------------------------

    @Test
    void noBuildDefaultAllowsCombatProjectileButBlocksTerrain() {
        assertFalse(fireHit(Snowball.class, noBuild(), false),
                "combat projectile allowed in no-build by default");
        assertTrue(fireHit(Egg.class, noBuild(), false),
                "terrain projectile (egg) blocked in no-build always");
        assertTrue(fireHit(EnderPearl.class, noBuild(), false),
                "terrain projectile (ender pearl) blocked in no-build always");
    }

    @Test
    void noBuildBlocksCombatProjectileWhenConfigured() {
        plugin.getConfig().set("protection.items.block-pvp-in-no-build", true);
        reload();
        assertTrue(fireHit(Snowball.class, noBuild(), false),
                "combat projectile blocked in no-build when configured");
        assertFalse(fireHit(Snowball.class, noBuild(), true),
                "bypass shooter may still use combat projectiles in no-build");
    }

    @Test
    void bypassCannotUseTerrainProjectilesInNoBuild() {
        // TERRAIN items are forbidden in EVERY protected region; bypass.items must
        // NOT relax them in no-build (it only relaxes COMBAT items there).
        assertTrue(fireHit(EnderPearl.class, noBuild(), true),
                "OP/bypass ender pearl into no-build must stay blocked (TERRAIN)");
        assertTrue(fireHit(Egg.class, noBuild(), true),
                "OP/bypass egg into no-build must stay blocked (TERRAIN)");
    }

    // ---- Wilderness ------------------------------------------------------

    @Test
    void wildernessHitIsAllowed() {
        assertFalse(fireHit(WindCharge.class, wild(), false), "wilderness hit unaffected");
        assertFalse(fireHit(Egg.class, wild(), false), "wilderness egg hit unaffected");
    }
}
