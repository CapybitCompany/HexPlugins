package hexpvpsmp;

import hexpvpsmp.combat.PermissionGate;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
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
 * Item-frame protection. The frame is a Mockito stub (only its location matters
 * to the listener) so tests are deterministic and independent of MockBukkit's
 * hanging-entity fidelity.
 */
class ItemFrameProtectionTest {

    private ServerMock server;
    private HexPvpSmpPlugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexPvpSmpPlugin.class);
        player = server.addPlayer("Framer");
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private ItemFrame frameAt(Location at) {
        ItemFrame frame = mock(ItemFrame.class);
        when(frame.getLocation()).thenReturn(at);
        return frame;
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

    private boolean fireInteract(ItemFrame frame) {
        PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(player, frame);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private boolean firePlace(Location at) {
        ItemFrame frame = frameAt(at);
        HangingPlaceEvent event = new HangingPlaceEvent(
                frame, player, at.getBlock(), BlockFace.NORTH, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private boolean firePlayerDamage(ItemFrame frame) {
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                player, frame, DamageCause.ENTITY_ATTACK,
                DamageSource.builder(DamageType.PLAYER_ATTACK).build(), 1.0);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private boolean fireArrowDamage(ItemFrame frame) {
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(player);
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                arrow, frame, DamageCause.PROJECTILE,
                DamageSource.builder(DamageType.ARROW).build(), 1.0);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    @Test
    void interactBlockedInSpawnAndNoBuild() {
        assertTrue(fireInteract(frameAt(spawn())), "item frame interact in spawn blocked");
        assertTrue(fireInteract(frameAt(noBuild())), "item frame interact in no-build blocked");
    }

    @Test
    void interactAllowedInWilderness() {
        assertFalse(fireInteract(frameAt(wild())), "item frame interact in wilderness allowed");
    }

    @Test
    void placeBlockedInProtectedRegions() {
        assertTrue(firePlace(spawn()), "placing item frame in spawn blocked");
        assertTrue(firePlace(noBuild()), "placing item frame in no-build blocked");
        assertFalse(firePlace(wild()), "placing item frame in wilderness allowed");
    }

    @Test
    void playerDamageBlockedInSpawn() {
        assertTrue(firePlayerDamage(frameAt(spawn())), "breaking item frame in spawn blocked");
    }

    @Test
    void projectileDamageBlockedInSpawn() {
        assertTrue(fireArrowDamage(frameAt(spawn())),
                "shooting an item frame in spawn must be blocked");
    }

    @Test
    void projectileDamageAllowedInWilderness() {
        assertFalse(fireArrowDamage(frameAt(wild())),
                "shooting an item frame in the wilderness is unaffected");
    }

    @Test
    void bypassPlayerMayInteractByDefault() {
        player.addAttachment(plugin, PermissionGate.BYPASS_PERMISSION, true);
        assertFalse(fireInteract(frameAt(spawn())), "bypass player may use item frames");
    }

    @Test
    void bypassInteractDisabledEnforcesForOp() {
        player.addAttachment(plugin, PermissionGate.BYPASS_PERMISSION, true);
        plugin.getConfig().set("protection.bypass.interact", false);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());
        assertTrue(fireInteract(frameAt(spawn())),
                "bypass.interact=false enforces item-frame protection even for bypass");
    }
}
