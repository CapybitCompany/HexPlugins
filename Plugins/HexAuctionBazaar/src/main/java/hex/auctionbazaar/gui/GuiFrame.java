package hex.auctionbazaar.gui;

import hex.auctionbazaar.util.LegacyFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Pomocnicze funkcje do budowania spójnego wizualnie GUI:
 * ramka wypełniająca puste sloty, przyciski nawigacyjne itp.
 *
 * Dekoracyjne wypełnienie (filler) ma wyłączony tooltip - na Paperze 1.21.11
 * używamy wprost {@code ItemMeta#setHideTooltip(true)} (bez reflection).
 * Tooltip wyłączamy WYŁĄCZNIE na dekoracyjnych fillerach; prawdziwe przedmioty
 * i interaktywne przyciski zachowują swoje opisy.
 */
public final class GuiFrame {

    private GuiFrame() {
    }

    public static Material materialOrDefault(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Material.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    /**
     * Buduje dekoracyjny filler-item bez tooltipu (pusta ramka bez opisu przy
     * hoverze). Tooltip chowamy przez {@link ItemMeta#setHideTooltip(boolean)}.
     */
    public static ItemStack filler(Material material) {
        ItemStack s = new ItemStack(material);
        ItemMeta meta = s.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            meta.setHideTooltip(true);
            s.setItemMeta(meta);
        }
        return s;
    }

    /** Wypełnij puste sloty ramką. Nie nadpisuje istniejących przedmiotów. */
    public static void fillEmpty(Inventory inv, Material material) {
        ItemStack filler = filler(material);
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, filler);
            }
        }
    }

    public static ItemStack button(Material material, String name, List<String> lore) {
        ItemStack s = new ItemStack(material);
        ItemMeta meta = s.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyFormat.component(name));
            if (lore != null && !lore.isEmpty()) {
                meta.lore(LegacyFormat.components(lore));
            }
            // Interaktywne przyciski ZAWSZE zachowują tooltip - nigdy nie chowamy go tutaj.
            // Ukrywamy jedynie modyfikatory atrybutów (np. „+7 obrażeń" na mieczu użytym jako
            // ikona kategorii/przycisku), aby w GUI widoczna była tylko nazwa i opis.
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            s.setItemMeta(meta);
        }
        return s;
    }

    public static ItemStack button(Material material, String name) {
        return button(material, name, List.of());
    }
}
