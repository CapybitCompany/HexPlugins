package hexpvpsmp;

import hexpvpsmp.combat.CombatTagService;
import hexpvpsmp.combat.PermissionGate;
import org.bukkit.Location;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatBehaviorTest {

    private ServerMock server;
    private HexPvpSmpPlugin plugin;
    private PlayerMock attacker;
    private PlayerMock victim;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexPvpSmpPlugin.class);
        attacker = server.addPlayer("Attacker");
        victim = server.addPlayer("Victim");
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private Location outsideSpawn() {
        return new Location(server.getWorld("world"), 500, 64, 500);
    }

    private Location insideSpawn() {
        return new Location(server.getWorld("world"), 0, 64, 0);
    }

    private Location insideNoBuildZone() {
        // front_spawn no-build zone: x in [-150,150], z in [101,180].
        return new Location(server.getWorld("world"), 0, 64, 150);
    }

    private void fireDamage(Player a, Player v) {
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                a, v, EntityDamageByEntityEvent.DamageCause.ENTITY_ATTACK,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK).build(),
                1.0
        );
        server.getPluginManager().callEvent(event);
    }

    @Test
    void pvpInsideSpawnIsCancelledAndDoesNotTag() {
        attacker.teleport(insideSpawn());
        victim.teleport(insideSpawn());

        fireDamage(attacker, victim);

        CombatTagService tagger = plugin.combatTagService();
        assertFalse(tagger.isTagged(attacker), "attacker must not be tagged inside spawn");
        assertFalse(tagger.isTagged(victim), "victim must not be tagged inside spawn");
    }

    @Test
    void pvpOutsideSpawnTagsBothPlayers() {
        attacker.teleport(outsideSpawn());
        victim.teleport(outsideSpawn());

        fireDamage(attacker, victim);

        CombatTagService tagger = plugin.combatTagService();
        assertTrue(tagger.isTagged(attacker), "attacker must be tagged after PvP hit");
        assertTrue(tagger.isTagged(victim), "victim must be tagged after PvP hit");
    }

    @Test
    void pvpInNoBuildZoneIsAllowedAndTagsBothPlayers() {
        attacker.teleport(insideNoBuildZone());
        victim.teleport(insideNoBuildZone());

        fireDamage(attacker, victim);

        CombatTagService tagger = plugin.combatTagService();
        assertTrue(tagger.isTagged(attacker), "PvP is allowed in a no-build zone -> attacker tagged");
        assertTrue(tagger.isTagged(victim), "PvP is allowed in a no-build zone -> victim tagged");
    }

    @Test
    void pvpInSpawnIsBlockedAtAnyHeight() {
        // High above the old y-range: still spawn (X/Z only).
        Location high = new Location(server.getWorld("world"), 0, 250, 0);
        attacker.teleport(high);
        victim.teleport(high);

        fireDamage(attacker, victim);

        CombatTagService tagger = plugin.combatTagService();
        assertFalse(tagger.isTagged(attacker), "PvP high above spawn must still be blocked");
        assertFalse(tagger.isTagged(victim), "PvP high above spawn must still be blocked");
    }

    @Test
    void opAttackerBypassesTagging() {
        attacker.setOp(true);
        attacker.teleport(outsideSpawn());
        victim.teleport(outsideSpawn());

        fireDamage(attacker, victim);

        CombatTagService tagger = plugin.combatTagService();
        assertFalse(tagger.isTagged(attacker), "OP attacker must not be tagged");
        // Victim still gets tagged.
        assertTrue(tagger.isTagged(victim));
    }

    @Test
    void permissionGateRecognizesOpAndExplicitPermission() {
        attacker.setOp(true);
        assertTrue(PermissionGate.bypasses(attacker));
        attacker.setOp(false);
        attacker.addAttachment(plugin, PermissionGate.BYPASS_PERMISSION, true);
        assertTrue(PermissionGate.bypasses(attacker));
    }

    @Test
    void realDeathClearsCombatTag() {
        CombatTagService tagger = plugin.combatTagService();
        victim.teleport(outsideSpawn());
        tagger.tag(victim);
        assertTrue(tagger.isTagged(victim), "precondition: victim is tagged");

        PlayerDeathEvent event = new PlayerDeathEvent(
                victim,
                DamageSource.builder(DamageType.GENERIC).build(),
                new java.util.ArrayList<>(), 0, "died");
        server.getPluginManager().callEvent(event);

        assertFalse(tagger.isTagged(victim), "a real death must clear the combat tag");
    }

    @Test
    void combatTagExpiresAfterDuration() {
        CombatTagService tagger = plugin.combatTagService();
        tagger.tag(attacker);
        assertTrue(tagger.isTagged(attacker));
        // Force expire by jumping the internal map's expiry record.
        var snapshot = tagger.snapshot();
        var state = snapshot.get(attacker.getUniqueId());
        state.refreshExpiry(-1L); // expired in the past
        tagger.expire(0L);
        assertFalse(tagger.isTagged(attacker));
        // Avoid unused-import noise:
        new HashMap<>();
    }
}
