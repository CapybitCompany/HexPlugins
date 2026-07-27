package hexnpc.shop.gui;

import hexnpc.HexNpcPlugin;
import hexnpc.shop.ShopService;
import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.economy.EconomyBridge;
import hexnpc.shop.inventory.ShopItemStackFactory;
import hexnpc.shop.model.SellMatch;
import hexnpc.shop.model.Shop;
import hexnpc.shop.model.ShopItem;
import hexnpc.shop.model.ShopLayout;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ikony widoku szczegółów: presety jako realny item ze stackiem 1/64, wyraźne
 * zaznaczenie wybranego presetu (lore „Wybrano") bez zmiany szablonów, oferta
 * „Sprzedaj wszystko" oraz BARRIER na „Wróć" i strzałki nawigacji.
 */
class ShopDetailIconsTest {

    private ServerMock server;
    private HexNpcPlugin plugin;
    private PlayerMock player;
    private ShopGuiBuilder builder;
    private ShopService service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        ShopGuiBuilder.setInventoryFactory((h, s, t) ->
                Bukkit.createInventory(h, s, LegacyComponentSerializer.legacySection().serialize(t)));
        plugin = MockBukkit.load(HexNpcPlugin.class);
        player = server.addPlayer("Viewer");
        builder = new ShopGuiBuilder(ShopConfig.defaults(), new EconomyBridge(Logger.getLogger("t")));
        service = new ShopService(plugin, plugin.shopRegistry(), new EconomyBridge(Logger.getLogger("t")),
                ShopConfig::defaults, Logger.getLogger("t"));
    }

    @AfterEach
    void tearDown() {
        ShopGuiBuilder.setInventoryFactory(null);
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private ShopItem plain(String id, Material mat, String sell) {
        return new ShopItem(id, mat, 1, ShopItem.NO_SLOT, 0, "", List.of(),
                new BigDecimal("10"), sell == null ? BigDecimal.ZERO : new BigDecimal(sell),
                true, sell != null, SellMatch.PLAIN_MATERIAL, 0);
    }

    private Shop shopOf(ShopItem item) {
        return new Shop("test", "&8Test", ShopLayout.defaults(54), Map.of(item.id(), item));
    }

    private String lore(ItemStack stack) {
        StringBuilder sb = new StringBuilder();
        if (stack != null && stack.getItemMeta() != null && stack.getItemMeta().lore() != null) {
            for (Component c : stack.getItemMeta().lore()) {
                sb.append(LegacyComponentSerializer.legacySection().serialize(c)).append('\n');
            }
        }
        return sb.toString();
    }

    @Test
    void presetsAreRealItemWithVisibleStackSize() {
        ShopItem stone = plain("stone", Material.STONE, "5");
        Inventory inv = builder.buildDetail(shopOf(stone), stone, 1, 0, Integer.MAX_VALUE, SellAllQuote.empty());
        // Domyślny układ: quantity-slots [19,21,23], presety [1,64] -> 19=1, 21=64.
        ItemStack preset1 = inv.getItem(19);
        ItemStack preset64 = inv.getItem(21);
        assertEquals(Material.STONE, preset1.getType());
        assertEquals(1, preset1.getAmount());
        assertEquals(Material.STONE, preset64.getType());
        assertEquals(64, preset64.getAmount(), "preset 64 musi pokazywać stack 64");
    }

    @Test
    void selectedPresetIsMarkedWithoutChangingTemplates() {
        ShopItem stone = plain("stone", Material.STONE, "5");
        Inventory inv = builder.buildDetail(shopOf(stone), stone, 1, 0, Integer.MAX_VALUE, SellAllQuote.empty());
        assertTrue(lore(inv.getItem(19)).contains("Wybrano"), "wybrany preset (1) musi być oznaczony");
        assertFalse(lore(inv.getItem(21)).contains("Wybrano"), "niewybrany preset nie jest oznaczony");
        // Szablony trade/sell pozostają nietknięte (osobne obiekty, bez „Wybrano").
        ItemStack template = ShopItemStackFactory.exactTemplate(stone);
        assertFalse(lore(template).contains("Wybrano"));
        ItemStack trade = ShopItemStackFactory.tradeStack(stone);
        assertFalse(lore(trade).contains("Wybrano"));
    }

    @Test
    void presetPreservesConfiguredNameAndLoreAndTemplatesUnchanged() {
        ShopItem named = new ShopItem("d", Material.DIAMOND, 1, ShopItem.NO_SLOT, 0,
                "&bDiament", List.of("&7Cenny"), new BigDecimal("10"), new BigDecimal("5"),
                true, true, SellMatch.PLAIN_MATERIAL, 64); // limit dzienny 64
        Shop shop = shopOf(named);
        // selected=1 (preset 1 wybrany), buyRemaining=5 (preset 64 ponad limitem).
        Inventory inv = builder.buildDetail(shop, named, 1, 0, 5, SellAllQuote.empty());

        ItemStack preset1 = inv.getItem(19);
        assertEquals(Material.DIAMOND, preset1.getType());
        String name1 = LegacyComponentSerializer.legacySection().serialize(preset1.getItemMeta().displayName());
        assertTrue(name1.contains("Diament"), "konfigurowany display-name zachowany (nie nadpisany '1x')");
        String lore1 = lore(preset1);
        assertTrue(lore1.contains("Cenny"), "konfigurowane lore zachowane");
        assertTrue(lore1.contains("1x"), "linia ilości dopisana");
        assertTrue(lore1.contains("Wybrano"), "zaznaczenie dopisane");

        ItemStack preset64 = inv.getItem(21);
        assertEquals(64, preset64.getAmount());
        assertTrue(lore(preset64).contains("limit"), "lore limitu dziennego zachowana");
        assertFalse(lore(preset64).contains("Wybrano"), "niewybrany preset bez oznaczenia");

        // Szablony trade/sell pozostają nietknięte.
        assertFalse(lore(ShopItemStackFactory.tradeUnit(named)).contains("Wybrano"));
        assertFalse(lore(ShopItemStackFactory.exactTemplate(named)).contains("Wybrano"));
        assertFalse(lore(ShopItemStackFactory.exactTemplate(named)).contains("1x"));
    }

    @Test
    void sellAllQuotePlainMaterialCountsAndPrices() {
        ShopItem stone = plain("stone", Material.STONE, "5");
        player.getInventory().addItem(new ItemStack(Material.STONE, 100));
        SellAllQuote quote = service.sellAllQuote(player, shopOf(stone), stone);
        assertEquals(100, quote.amount());
        assertEquals(new BigDecimal("500.00"), quote.totalPrice(), "5 * 100");
    }

    @Test
    void sellAllQuoteExactItemRejectsAlteredCopies() {
        ShopItem rare = new ShopItem("rare", Material.DIAMOND_PICKAXE, 1, ShopItem.NO_SLOT, 0,
                "&bRzadki", List.of("&7Specjalny"), BigDecimal.ZERO, new BigDecimal("200"),
                false, true, SellMatch.EXACT_ITEM, 0);
        Shop shop = shopOf(rare);
        // 3 zgodne z szablonem + 2 uszkodzone (EXACT_ITEM je odrzuca).
        player.getInventory().addItem(ShopItemStackFactory.exactTemplate(rare));
        player.getInventory().addItem(ShopItemStackFactory.exactTemplate(rare));
        player.getInventory().addItem(ShopItemStackFactory.exactTemplate(rare));
        for (int i = 0; i < 2; i++) {
            ItemStack damaged = ShopItemStackFactory.exactTemplate(rare);
            org.bukkit.inventory.meta.Damageable dm =
                    (org.bukkit.inventory.meta.Damageable) damaged.getItemMeta();
            dm.setDamage(50);
            damaged.setItemMeta(dm);
            player.getInventory().addItem(damaged);
        }
        SellAllQuote quote = service.sellAllQuote(player, shop, rare);
        assertEquals(3, quote.amount(), "EXACT_ITEM liczy tylko dokładne dopasowania");
    }

    @Test
    void sellAllQuoteEmptyWhenNoItems() {
        ShopItem stone = plain("stone", Material.STONE, "5");
        SellAllQuote quote = service.sellAllQuote(player, shopOf(stone), stone);
        assertFalse(quote.hasItems());
        assertEquals(0, quote.amount());
    }

    @Test
    void backButtonIsBarrierAndNavIsArrow() {
        ShopItem stone = plain("stone", Material.STONE, "5");
        Shop shop = shopOf(stone);
        Inventory detail = builder.buildDetail(shop, stone, 1, 0, Integer.MAX_VALUE, SellAllQuote.empty());
        assertEquals(Material.BARRIER, detail.getItem(shop.layout().detailBackSlot()).getType(),
                "przycisk Wróć musi być BARRIER");

        // Widok główny z dwiema stronami: przycisk następnej strony to strzałka.
        Map<String, ShopItem> many = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 13; i++) {
            many.put("i" + i, plain("i" + i, Material.STONE, "1"));
        }
        Shop bigShop = new Shop("big", "&8Big", ShopLayout.defaults(54), many);
        Inventory main = builder.buildMain(bigShop, 0);
        assertEquals(Material.ARROW, main.getItem(bigShop.layout().nextSlot()).getType(),
                "nawigacja stron pozostaje strzałką");
    }
}
