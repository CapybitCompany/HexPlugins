package hex.auctionbazaar.gui;

import hex.auctionbazaar.util.LegacyFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Pomocnicze funkcje do budowania spójnego wizualnie GUI:
 * ramka wypełniająca puste sloty, przyciski nawigacyjne itp.
 * Filler-panel ma ukryty tooltip (pusta ramka bez opisu przy hoverze).
 */
public final class GuiFrame {

    private static final ItemFlag HIDE_TOOLTIP_FLAG = resolveHideTooltipFlag();

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
     * Buduje filler-item ktory NIE pokazuje pustego tooltipu.
     * Na Paperze uzywamy meta.setHideTooltip(true) jesli dostepne, w
     * przeciwnym razie ItemFlag.HIDE_TOOLTIP, w przeciwnym razie sadzimy
     * wszystkie znane HIDE_* flagi + pusta nazwe (najbardziej minimalny
     * tooltip mozliwy na starych API).
     */
    public static ItemStack filler(Material material) {
        ItemStack s = new ItemStack(material);
        ItemMeta meta = s.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            applyHideTooltip(meta);
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
            s.setItemMeta(meta);
        }
        return s;
    }

    public static ItemStack button(Material material, String name) {
        return button(material, name, List.of());
    }

    /**
     * Best-effort ukrycie tooltipu przedmiotu na fillerze.
     *
     * Priorytet:
     *  1. Paper 1.20.6+ meta.setHideTooltip(true) - reflection zeby budowac
     *     na starszym API.
     *  2. ItemFlag.HIDE_TOOLTIP (dodane w nowszym Bukkit) jesli obecne.
     *  3. Fallback: zestaw wszystkich HIDE_* flag, aby lore nie mrugala.
     * Kazde ustawienie jest opcjonalne - brak wsparcia nie rzuca wyjatku.
     */
    static void applyHideTooltip(ItemMeta meta) {
        // 1) Paper reflectively
        try {
            Method setHideTooltip = meta.getClass().getMethod("setHideTooltip", boolean.class);
            setHideTooltip.invoke(meta, true);
            return;
        } catch (ReflectiveOperationException ignored) {
        }
        // 2) ItemFlag.HIDE_TOOLTIP
        if (HIDE_TOOLTIP_FLAG != null) {
            try {
                meta.addItemFlags(HIDE_TOOLTIP_FLAG);
                return;
            } catch (Throwable ignored) {
            }
        }
        // 3) fallback - ukryj wszystkie mozliwe atrybuty
        for (ItemFlag f : ItemFlag.values()) {
            try {
                meta.addItemFlags(f);
            } catch (Throwable ignored) {
            }
        }
    }

    private static ItemFlag resolveHideTooltipFlag() {
        try {
            return ItemFlag.valueOf("HIDE_TOOLTIP");
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
