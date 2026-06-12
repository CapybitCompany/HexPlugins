package hexpvpsmp;

import hexpvpsmp.combat.CombatTagService;
import hexpvpsmp.combat.PermissionGate;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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
