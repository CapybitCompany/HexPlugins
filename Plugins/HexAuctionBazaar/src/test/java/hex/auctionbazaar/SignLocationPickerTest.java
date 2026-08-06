package hex.auctionbazaar;

import hex.auctionbazaar.gui.SignLocationPicker;
import org.bukkit.Location;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #1: wybór lokalizacji tabliczki. Pozycja musi być w granicach świata i
 * w zasięgu gracza (~2 nad graczem), a wybieramy tylko bloki zastępowalne.
 */
class SignLocationPickerTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void airAndLightBlocksAreReplaceable() {
        assertTrue(SignLocationPicker.isReplaceable(Material.AIR));
        assertTrue(SignLocationPicker.isReplaceable(Material.SHORT_GRASS));
        assertTrue(SignLocationPicker.isReplaceable(Material.SNOW));
    }

    @Test
    void valuableOrSolidBlocksAreNotReplaceable() {
        assertFalse(SignLocationPicker.isReplaceable(Material.DIAMOND_BLOCK));
        assertFalse(SignLocationPicker.isReplaceable(Material.CHEST));
        assertFalse(SignLocationPicker.isReplaceable(Material.STONE));
        assertFalse(SignLocationPicker.isReplaceable(null));
    }

    @Test
    void withinBoundsRespectsMinAndMax() {
        assertTrue(SignLocationPicker.withinBounds(70, -64, 320));
        assertFalse(SignLocationPicker.withinBounds(-65, -64, 320));
        assertFalse(SignLocationPicker.withinBounds(320, -64, 320)); // maxHeight ekskluzywne
        assertTrue(SignLocationPicker.withinBounds(319, -64, 320));
    }

    @Test
    void pickReturnsLocationInBoundsAndInReach() {
        WorldMock world = server.addSimpleWorld("signworld");
        PlayerMock player = server.addPlayer("Bob");
        player.setLocation(new Location(world, 100.5, 70.0, 200.5));

        org.bukkit.World w = player.getWorld();
        int feetX = player.getLocation().getBlockX();
        int feetY = player.getLocation().getBlockY();
        int feetZ = player.getLocation().getBlockZ();
        // W produkcji chunk gracza jest zawsze załadowany; MockBukkit wymaga tego jawnie.
        w.loadChunk(feetX >> 4, feetZ >> 4);
        // Deterministycznie: blok 2 nad graczem jest wolny (powietrze).
        w.getBlockAt(feetX, feetY + 2, feetZ).setType(Material.AIR);

        Optional<Location> picked = SignLocationPicker.pick(player);
        assertTrue(picked.isPresent(), "powinna istnieć wolna lokalizacja nad graczem (powietrze)");
        Location loc = picked.get();

        assertTrue(loc.getBlockY() >= w.getMinHeight() && loc.getBlockY() < w.getMaxHeight(),
                "Y musi mieścić się w granicach świata");
        // Zasięg względem stóp gracza: |dy| <= 3, |dx|,|dz| <= 1.
        assertTrue(Math.abs(loc.getBlockY() - feetY) <= 3, "Y w zasięgu (~2 nad graczem)");
        assertTrue(Math.abs(loc.getBlockX() - feetX) <= 1, "X w zasięgu");
        assertTrue(Math.abs(loc.getBlockZ() - feetZ) <= 1, "Z w zasięgu");
        // Preferencja: dokładnie 2 nad blokiem stóp gracza (pierwszy wolny kandydat).
        assertEquals(feetY + 2, loc.getBlockY(), "preferowana pozycja to 2 bloki nad graczem");
    }
}
