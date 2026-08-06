package hex.auctionbazaar;

import hex.auctionbazaar.gui.GuiFrame;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #6: dekoracyjne szybki (filler) MUSZĄ mieć realnie wyłączony tooltip
 * (Paper 1.21.11 {@code setHideTooltip(true)}), a interaktywne przyciski NIE.
 * Test uruchamia MockBukkit i sprawdza rzeczywistą wartość {@code isHideTooltip()}
 * - niezależnie od materiału szyby.
 */
class GuiFillerTooltipTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void fillerHasHideTooltipTrueForBlackGlass() {
        ItemStack filler = GuiFrame.filler(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        assertNotNull(meta);
        assertTrue(meta.isHideTooltip(),
                "Dekoracyjny filler musi mieć isHideTooltip()==true");
    }

    @Test
    void fillerHasHideTooltipTrueForGrayGlass() {
        // Niezależnie od skonfigurowanego materiału szyby.
        ItemStack filler = GuiFrame.filler(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        assertNotNull(meta);
        assertTrue(meta.isHideTooltip(),
                "Filler z innego materiału też musi mieć isHideTooltip()==true");
    }

    @Test
    void interactiveButtonKeepsTooltip() {
        ItemStack button = GuiFrame.button(Material.EMERALD, "&aWystaw przedmiot",
                List.of("&7Kliknij tutaj"));
        ItemMeta meta = button.getItemMeta();
        assertNotNull(meta);
        assertFalse(meta.isHideTooltip(),
                "Interaktywny przycisk musi mieć isHideTooltip()==false");
    }

    @Test
    void fillEmptyUsesHiddenTooltipFiller() {
        var server = MockBukkit.getMock();
        var holder = new hex.auctionbazaar.gui.GuiHolder(
                hex.auctionbazaar.gui.GuiHolder.Kind.AUCTION_BROWSE);
        var inv = server.createInventory(holder, 27);
        holder.bindInventory(inv);
        GuiFrame.fillEmpty(inv, Material.BLACK_STAINED_GLASS_PANE);
        ItemStack any = inv.getItem(0);
        assertNotNull(any);
        assertTrue(any.getItemMeta().isHideTooltip(),
                "Wypełnienie ramki musi używać fillera z ukrytym tooltipem");
    }
}
