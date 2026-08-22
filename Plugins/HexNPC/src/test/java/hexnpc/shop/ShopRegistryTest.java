package hexnpc.shop;

import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.model.SellMatch;
import hexnpc.shop.model.Shop;
import hexnpc.shop.model.ShopItem;
import hexnpc.shop.model.ShopCurrency;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.StringReader;
import java.math.BigDecimal;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopRegistryTest {

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
    void loadsShopItemsPricesAndSlots() throws Exception {
        String yaml = """
                shops:
                  starter:
                    title: "&8Sklep startowy"
                    size: 54
                    sell-slot: 49
                    items:
                      cobblestone:
                        material: COBBLESTONE
                        amount: 64
                        slot: 10
                        display-name: "&7Bruk"
                        lore:
                          - "&8Podstawowy blok"
                        buy-price: "100.00"
                        sell-price: "25.00"
                        buy-enabled: true
                        sell-enabled: true
                        sell-match: PLAIN_MATERIAL
                      diamond:
                        material: DIAMOND
                        amount: 1
                        slot: 12
                        display-name: "&bDiament"
                        buy-price: "500.00"
                        sell-price: "200.00"
                """;
        ShopRegistry registry = new ShopRegistry(Logger.getLogger("test"));
        int count = registry.reloadFrom(new StringReader(yaml), ShopConfig.defaults());
        assertEquals(1, count);
        Shop shop = registry.find("starter").orElseThrow();
        assertEquals(54, shop.size());
        assertEquals(ShopCurrency.MONEY, shop.currency(), "brak currency musi zachować MONEY");
        assertEquals(49, shop.sellSlot());
        assertEquals(2, shop.itemValues().size());

        ShopItem stone = shop.item("cobblestone").orElseThrow();
        assertEquals(Material.COBBLESTONE, stone.material());
        assertEquals(64, stone.amount());
        assertEquals(10, stone.slot());
        assertEquals(new BigDecimal("100.00").stripTrailingZeros(), stone.buyPrice());
        assertEquals(SellMatch.PLAIN_MATERIAL, stone.sellMatch());

        ShopItem diamond = shop.item("diamond").orElseThrow();
        assertEquals(Material.DIAMOND, diamond.material());
        // sell-match omitted -> defaults to PLAIN_MATERIAL
        assertEquals(SellMatch.PLAIN_MATERIAL, diamond.sellMatch());
    }

    @Test
    void loadsExplicitHexCoinsCurrency() throws Exception {
        String yaml = """
                shops:
                  premium:
                    currency: HEX_COINS
                    size: 27
                    items:
                      token_item:
                        material: DIAMOND
                        amount: 1
                        buy-price: "25"
                        sell-enabled: false
                """;
        ShopRegistry registry = new ShopRegistry(Logger.getLogger("test"));
        assertEquals(1, registry.reloadFrom(new StringReader(yaml), ShopConfig.defaults()));
        Shop shop = registry.find("premium").orElseThrow();
        assertEquals(ShopCurrency.HEX_COINS, shop.currency());
        assertTrue(shop.item("token_item").isPresent());
    }

    @Test
    void invalidCurrencyRejectsShopInsteadOfFallingBackToMoney() throws Exception {
        String yaml = """
                shops:
                  premium:
                    currency: HEXCOINS
                    size: 27
                    items:
                      item:
                        material: DIAMOND
                        amount: 1
                        buy-price: "25"
                """;
        ShopRegistry registry = new ShopRegistry(Logger.getLogger("test"));
        assertEquals(0, registry.reloadFrom(new StringReader(yaml), ShopConfig.defaults()));
        assertTrue(registry.find("premium").isEmpty());
    }

    @Test
    void fractionalConfiguredHexCoinPriceSkipsItem() throws Exception {
        String yaml = """
                shops:
                  premium:
                    currency: HEX_COINS
                    size: 27
                    items:
                      bad:
                        material: COBBLESTONE
                        amount: 64
                        buy-price: "100.50"
                      good:
                        material: DIAMOND
                        amount: 1
                        buy-price: "25"
                """;
        ShopRegistry registry = new ShopRegistry(Logger.getLogger("test"));
        assertEquals(1, registry.reloadFrom(new StringReader(yaml), ShopConfig.defaults()));
        Shop shop = registry.find("premium").orElseThrow();
        assertTrue(shop.item("bad").isEmpty());
        assertTrue(shop.item("good").isPresent());
    }

    @Test
    void unknownMaterialIsSkippedNotFatal() throws Exception {
        String yaml = """
                shops:
                  test:
                    size: 27
                    sell-slot: 22
                    items:
                      bogus:
                        material: NOT_A_REAL_MATERIAL_XYZ
                        amount: 1
                        slot: 4
                        buy-price: "10"
                      stone:
                        material: STONE
                        amount: 1
                        slot: 5
                        buy-price: "10"
                """;
        ShopRegistry registry = new ShopRegistry(Logger.getLogger("test"));
        int count = registry.reloadFrom(new StringReader(yaml), ShopConfig.defaults());
        assertEquals(1, count);
        Shop shop = registry.find("test").orElseThrow();
        assertNotNull(shop.item("stone").orElse(null));
        assertTrue(shop.item("bogus").isEmpty(),
                "invalid material should be skipped, not fail load");
    }

    @Test
    void emptyShopsSectionYieldsEmptyRegistry() throws Exception {
        ShopRegistry registry = new ShopRegistry(Logger.getLogger("test"));
        int count = registry.reloadFrom(new StringReader("shops: {}\n"), ShopConfig.defaults());
        assertEquals(0, count);
    }
    @Test
    void loadsPremiumOneTimeCommandAndCustomItemRewards() throws Exception {
        String yaml = """
                shops:
                  premium:
                    currency: HEX_COINS
                    size: 54
                    placement: MANUAL
                    items:
                      sit:
                        material: STICK
                        custom-model-data: 9020
                        amount: 1
                        slot: 12
                        buy-price: "3"
                        sell-enabled: false
                        single-purchase-view: true
                        one-time:
                          enabled: true
                          permission: GSit.Sit
                        reward:
                          type: CONSOLE_COMMANDS
                          commands:
                            - "lp user <player> permission set GSit.Sit true"
                          verify-permission: GSit.Sit
                      epic3:
                        material: TRIAL_KEY
                        custom-model-data: 9007
                        icon-custom-item-id: epic_key
                        amount: 1
                        slot: 20
                        buy-price: "7"
                        sell-enabled: false
                        single-purchase-view: true
                        reward:
                          type: HEX_CUSTOM_ITEM
                          item-id: epic_key
                          amount: 3
                """;
        ShopRegistry registry = new ShopRegistry(Logger.getLogger("test"));
        assertEquals(1, registry.reloadFrom(new StringReader(yaml), ShopConfig.defaults()));
        Shop shop = registry.find("premium").orElseThrow();
        ShopItem sit = shop.item("sit").orElseThrow();
        assertEquals(9020, sit.customModelData());
        assertTrue(sit.singlePurchaseView());
        assertTrue(sit.oneTime().enabled());
        assertEquals("GSit.Sit", sit.oneTime().permission());
        assertEquals(hexnpc.shop.model.ShopRewardType.CONSOLE_COMMANDS, sit.reward().type());
        ShopItem key = shop.item("epic3").orElseThrow();
        assertEquals(hexnpc.shop.model.ShopRewardType.HEX_CUSTOM_ITEM, key.reward().type());
        assertEquals("epic_key", key.reward().itemId());
        assertEquals(3, key.reward().amount());
        assertEquals(9007, key.customModelData());
    }

    @Test
    void loadsPremiumCustomTagWorkflowActions() throws Exception {
        String yaml = """
                shops:
                  premium_shop:
                    currency: HEX_COINS
                    size: 54
                    placement: MANUAL
                    items:
                      custom_tag:
                        material: NAME_TAG
                        slot: 30
                        amount: 1
                        buy-price: "10"
                        sell-enabled: false
                        single-purchase-view: true
                        one-time:
                          enabled: true
                          permission: "hexnpc.feature.custom_tag"
                        reward:
                          type: CONSOLE_COMMANDS
                          commands:
                            - "lp user <player> permission set hexnpc.feature.custom_tag true"
                          verify-permission: "hexnpc.feature.custom_tag"
                        owned-action:
                          type: RUN_WORKFLOW
                          workflow: custom_tag_menu
                        post-purchase-action:
                          type: RUN_WORKFLOW
                          workflow: custom_tag_create
                """;
        ShopRegistry registry = new ShopRegistry(Logger.getLogger("test"));
        assertEquals(1, registry.reloadFrom(new StringReader(yaml), ShopConfig.defaults()));
        ShopItem item = registry.find("premium_shop").orElseThrow().item("custom_tag").orElseThrow();
        assertEquals(hexnpc.shop.model.ShopItemActionType.RUN_WORKFLOW, item.ownedAction().type());
        assertEquals("custom_tag_menu", item.ownedAction().workflow());
        assertEquals(hexnpc.shop.model.ShopItemActionType.RUN_WORKFLOW, item.postPurchaseAction().type());
        assertEquals("custom_tag_create", item.postPurchaseAction().workflow());
        assertEquals("hexnpc.feature.custom_tag", item.oneTime().permission());
    }

}
