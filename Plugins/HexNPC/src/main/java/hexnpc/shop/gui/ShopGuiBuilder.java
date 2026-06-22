package hexnpc.shop.gui;

import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.economy.EconomyBridge;
import hexnpc.shop.inventory.ShopItemStackFactory;
import hexnpc.shop.model.Shop;
import hexnpc.shop.model.ShopItem;
import hexnpc.util.LegacyFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Buduje inventory shopu dla obu widoków (MAIN grid + DETAIL).
 * Builder jest bezstanowy — żywą konfigurację i bridge ekonomii
 * przekazujemy w konstruktorze, dzięki czemu ceny zawsze są
 * formatowane zgodnie z bieżącą walutą.
 */
public final class ShopGuiBuilder {

    private static final Material FILLER_MATERIAL = Material.GRAY_STAINED_GLASS_PANE;

    private final ShopConfig config;
    private final EconomyBridge economy;

    public ShopGuiBuilder(ShopConfig config, EconomyBridge economy) {
        this.config = config;
        this.economy = economy;
    }

    public Inventory buildMain(Shop shop) {
        Map<Integer, String> slotMap = new LinkedHashMap<>();
        for (ShopItem item : shop.itemValues()) {
            slotMap.put(item.slot(), item.id());
        }
        ShopGuiHolder holder = new ShopGuiHolder(shop, ShopGuiHolder.View.MAIN, null,
                slotMap, -1, -1, -1);
        Inventory inv = Bukkit.createInventory(holder, shop.size(), renderTitle(shop));
        holder.bind(inv);
        fillBackground(inv);
        for (ShopItem item : shop.itemValues()) {
            inv.setItem(item.slot(), buildIcon(item));
        }
        return inv;
    }

    public Inventory buildDetail(Shop shop, ShopItem item) {
        int size = shop.size();
        int sellSlot = shop.sellSlot();
        int buySlot = pickBuySlot(size, sellSlot);
        int backSlot = pickBackSlot(size, sellSlot, buySlot);
        int iconSlot = pickIconSlot(size, sellSlot, buySlot, backSlot);

        ShopGuiHolder holder = new ShopGuiHolder(shop, ShopGuiHolder.View.DETAIL, item.id(),
                Map.of(iconSlot, item.id()), buySlot, sellSlot, backSlot);
        Inventory inv = Bukkit.createInventory(holder, size, renderTitle(shop));
        holder.bind(inv);
        fillBackground(inv);
        inv.setItem(iconSlot, buildIcon(item));
        if (item.hasBuyPrice()) {
            inv.setItem(buySlot, buildBuyButton(item));
        }
        if (item.hasSellPrice()) {
            inv.setItem(sellSlot, buildSellButton(item));
        }
        inv.setItem(backSlot, buildBackButton());
        return inv;
    }

    private Component renderTitle(Shop shop) {
        String pattern = config.titleFormat();
        String shopTitle = shop.title() == null ? shop.id() : shop.title();
        String rendered = LegacyFormat.replace(pattern, "<shop>", shopTitle);
        return LegacyFormat.component(rendered);
    }

    private void fillBackground(Inventory inv) {
        ItemStack filler = new ItemStack(FILLER_MATERIAL);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            meta.addItemFlags(ItemFlag.values());
            filler.setItemMeta(meta);
        }
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }
    }

    private ItemStack buildIcon(ShopItem item) {
        // Dodatkowe linie lore (ceny + podpowiedź) trafiają tylko na
        // kopię w GUI. Wzorzec EXACT_ITEM (exactTemplate) zostaje czysty.
        List<Component> extra = new ArrayList<>();
        extra.add(Component.empty());
        if (item.hasBuyPrice()) {
            extra.add(LegacyFormat.component("&7Kup: &a" + economy.format(item.buyPrice())));
        }
        if (item.hasSellPrice()) {
            extra.add(LegacyFormat.component("&7Sprzedaj: &e" + economy.format(item.sellPrice())));
        }
        extra.add(LegacyFormat.component("&8Kliknij aby otworzyć szczegóły"));
        ItemStack stack = ShopItemStackFactory.displayStack(item, extra);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack buildBuyButton(ShopItem item) {
        ItemStack stack = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyFormat.component("&aKup &f" + item.amount() + "x"));
            List<Component> lore = new ArrayList<>();
            lore.add(LegacyFormat.component("&7Cena: &a" + economy.format(item.buyPrice())));
            lore.add(Component.empty());
            lore.add(LegacyFormat.component("&eKliknij aby kupić"));
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack buildSellButton(ShopItem item) {
        ItemStack stack = new ItemStack(Material.ORANGE_STAINED_GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyFormat.component("&6Sprzedaj &f" + item.amount() + "x"));
            List<Component> lore = new ArrayList<>();
            lore.add(LegacyFormat.component("&7Otrzymasz: &e" + economy.format(item.sellPrice())));
            lore.add(Component.empty());
            lore.add(LegacyFormat.component("&eKliknij aby sprzedać"));
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack buildBackButton() {
        ItemStack stack = new ItemStack(Material.ARROW);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyFormat.component("&cWróć"));
            meta.lore(List.of(LegacyFormat.component("&7Powrót do listy")));
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private int pickBuySlot(int size, int sellSlot) {
        // Środek górnej połowy inventory, najlepiej nad slotem sell.
        int candidate = size <= 27 ? size / 2 - 2 : 22;
        if (candidate == sellSlot || candidate < 0 || candidate >= size) {
            candidate = Math.max(0, sellSlot - 9);
        }
        if (candidate == sellSlot) {
            candidate = 0;
        }
        return candidate;
    }

    private int pickBackSlot(int size, int sellSlot, int buySlot) {
        int candidate = size - 1;
        while ((candidate == sellSlot || candidate == buySlot) && candidate > 0) {
            candidate--;
        }
        return Math.max(0, candidate);
    }

    private int pickIconSlot(int size, int sellSlot, int buySlot, int backSlot) {
        int candidate = size / 2;
        if (candidate == sellSlot || candidate == buySlot || candidate == backSlot) {
            for (int i = 0; i < size; i++) {
                if (i != sellSlot && i != buySlot && i != backSlot) {
                    return i;
                }
            }
        }
        return candidate;
    }
}
