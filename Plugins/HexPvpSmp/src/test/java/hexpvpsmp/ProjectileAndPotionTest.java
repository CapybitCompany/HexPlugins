package hexpvpsmp;

import org.bukkit.Location;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.LingeringPotionSplashEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ranged protection: shooters standing OUTSIDE a protected region must not be
 * able to affect players INSIDE it with projectiles or potions.
 *
 * <p>Projectile / potion / cloud entities are Mockito stubs (not live MockBukkit
 * spawns) so the tests only exercise the listener's decision logic and stay
 * deterministic.
 */
class ProjectileAndPotionTest {

    private ServerMock server;
    private HexPvpSmpPlugin plugin;
    private PlayerMock shooter;
    private PlayerMock victim;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexPvpSmpPlugin.class);
        shooter = server.addPlayer("Shooter");
        victim = server.addPlayer("Victim");
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

    // ---- Projectile damage ----------------------------------------------

    private boolean fireArrowDamage(Location victimAt, boolean shooterBypass) {
        // Shooter stands in the wilderness so only the victim's region drives the
        // decision (a shooter inside spawn would already be stopped by PvP rules).
        shooter.teleport(wild());
        if (shooterBypass) {
            shooter.setOp(true);
        }
        victim.teleport(victimAt);
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(shooter);
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                arrow, victim, DamageCause.PROJECTILE,
                DamageSource.builder(DamageType.ARROW).build(), 4.0);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    @Test
    void arrowIntoSpawnFromOutsideIsBlocked() {
        assertTrue(fireArrowDamage(spawn(), false),
                "arrow hitting a player inside spawn must be blocked even if the shooter is outside");
    }

    @Test
    void arrowInNoBuildAllowedByDefault() {
        assertFalse(fireArrowDamage(noBuild(), false),
                "arrow into a no-build zone is allowed while block-pvp-in-no-build=false");
    }

    @Test
    void arrowInNoBuildBlockedWhenConfigured() {
        plugin.getConfig().set("protection.items.block-pvp-in-no-build", true);
        reload();
        assertTrue(fireArrowDamage(noBuild(), false),
                "arrow into a no-build zone is blocked when block-pvp-in-no-build=true");
    }

    @Test
    void arrowInWildernessIsAllowed() {
        assertFalse(fireArrowDamage(wild(), false), "arrow in wilderness is unaffected");
    }

    @Test
    void bypassShooterExemptInNoBuildWhenItemsBypassOn() {
        // In a no-build zone with block-pvp-in-no-build=true, a normal shooter is
        // blocked but a bypass shooter is exempt (CombatListener does not protect
        // no-build, so this isolates the item-bypass path).
        plugin.getConfig().set("protection.items.block-pvp-in-no-build", true);
        reload();
        assertTrue(fireArrowDamage(noBuild(), false), "normal shooter blocked in no-build when configured");
        assertFalse(fireArrowDamage(noBuild(), true), "bypass shooter exempt when bypass.items=true");
    }

    // ---- Splash potions --------------------------------------------------

    private double splashIntensityInto(Location victimAt) {
        return splashIntensity(victimAt, false);
    }

    private double splashIntensity(Location victimAt, boolean shooterBypass) {
        if (shooterBypass) {
            shooter.setOp(true);
        }
        victim.teleport(victimAt);
        ThrownPotion potion = mock(ThrownPotion.class);
        when(potion.getShooter()).thenReturn(shooter);
        Map<LivingEntity, Double> affected = new HashMap<>();
        affected.put(victim, 1.0);
        PotionSplashEvent event = new PotionSplashEvent(potion, affected);
        server.getPluginManager().callEvent(event);
        return event.getIntensity(victim);
    }

    @Test
    void splashPotionNeutralisedForPlayerInSpawn() {
        assertEquals(0.0, splashIntensityInto(spawn()),
                "splash potion must not affect a player standing in spawn");
    }

    @Test
    void splashPotionInSpawnNeutralisedEvenForBypassShooter() {
        assertEquals(0.0, splashIntensity(spawn(), true),
                "spawn must stay safe from splash potions even for OP/bypass shooters");
    }

    @Test
    void splashPotionAffectsPlayerInWilderness() {
        assertEquals(1.0, splashIntensityInto(wild()),
                "splash potion still works in the wilderness");
    }

    @Test
    void splashPotionAffectsNoBuildByDefault() {
        assertEquals(1.0, splashIntensityInto(noBuild()),
                "splash potion works in no-build while block-pvp-in-no-build=false");
    }

    @Test
    void splashPotionInNoBuildBlockedForNormalShooterWhenConfigured() {
        plugin.getConfig().set("protection.items.block-pvp-in-no-build", true);
        reload();
        assertEquals(0.0, splashIntensity(noBuild(), false),
                "normal shooter's splash is neutralised in no-build when configured");
    }

    @Test
    void splashPotionInNoBuildAllowedForBypassShooterWhenConfigured() {
        plugin.getConfig().set("protection.items.block-pvp-in-no-build", true);
        reload();
        assertEquals(1.0, splashIntensity(noBuild(), true),
                "bypass shooter may still splash in no-build (bypass only relaxes no-build)");
    }

    // ---- Lingering potions ----------------------------------------------

    private boolean fireLingeringAt(Location cloudAt) {
        return fireLingeringAt(cloudAt, false);
    }

    private boolean fireLingeringAt(Location cloudAt, boolean shooterBypass) {
        if (shooterBypass) {
            shooter.setOp(true);
        }
        ThrownPotion potion = mock(ThrownPotion.class);
        when(potion.getShooter()).thenReturn(shooter);
        AreaEffectCloud cloud = mock(AreaEffectCloud.class);
        when(cloud.getLocation()).thenReturn(cloudAt);
        LingeringPotionSplashEvent event = new LingeringPotionSplashEvent(potion, cloud);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    @Test
    void lingeringCloudInSpawnIsCancelled() {
        assertTrue(fireLingeringAt(spawn()),
                "a lingering effect cloud must not form inside spawn");
    }

    @Test
    void lingeringCloudInSpawnCancelledEvenForBypassShooter() {
        assertTrue(fireLingeringAt(spawn(), true),
                "spawn must stay safe from lingering clouds even for OP/bypass shooters");
    }

    @Test
    void lingeringCloudInWildernessIsAllowed() {
        assertFalse(fireLingeringAt(wild()),
                "a lingering effect cloud in the wilderness is unaffected");
    }

    @Test
    void lingeringCloudInNoBuildBlockedForNormalShooterWhenConfigured() {
        plugin.getConfig().set("protection.items.block-pvp-in-no-build", true);
        reload();
        assertTrue(fireLingeringAt(noBuild(), false),
                "normal shooter's lingering cloud is cancelled in no-build when configured");
        assertFalse(fireLingeringAt(noBuild(), true),
                "bypass shooter may still create a cloud in no-build");
    }
}
