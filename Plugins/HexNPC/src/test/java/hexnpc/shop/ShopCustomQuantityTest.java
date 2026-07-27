package hexnpc.shop;

import hexnpc.HexNpcPlugin;
import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.config.ShopMessages;
import hexnpc.shop.economy.EconomyBridge;
import hexnpc.shop.gui.ShopGuiBuilder;
import hexnpc.shop.gui.ShopGuiHolder;
import hexnpc.shop.limit.DailyBuyLimitService;
import hexnpc.shop.model.SellMatch;
import hexnpc.shop.model.Shop;
import hexnpc.shop.model.ShopItem;
import hexnpc.shop.model.ShopLayout;
import hexnpc.shop.sign.SignInputService;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zachowanie wybranej ilości sesji przez flow „własnej ilości": timeout oraz
 * nieprawidłowa/pusta wpiska wracają z TĄ SAMĄ ilością; poprawna wpiska używa
 * nowej. Shop/item są rozwiązywane po ID.
 */
class ShopCustomQuantityTest {

    private ServerMock server;
    private HexNpcPlugin plugin;
    private PlayerMock player;
    private ShopService service;

    private final Supplier<ShopConfig> cfg = () -> new ShopConfig(true, true, "&8x", true,
            ShopLayout.defaults(54), List.of(1, 64), true, true, true, 1, 1, 2,
            ShopConfig.Confirmation.defaults(), ShopConfig.AuditLog.defaults(), ShopMessages.defaults());

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        ShopGuiBuilder.setInventoryFactory((h, s, t) ->
                Bukkit.createInventory(h, s, LegacyComponentSerializer.legacySection().serialize(t)));
        plugin = MockBukkit.load(HexNpcPlugin.class);
        player = server.addPlayer("Trader");
        SignInputService sign = new SignInputService(plugin, cfg); // transport niedostępny -> czat/timeout
        service = new ShopService(plugin, plugin.shopRegistry(), new EconomyBridge(Logger.getLogger("t")),
                cfg, Logger.getLogger("t"), new DailyBuyLimitService(null, Logger.getLogger("t")), sign);
    }

    @AfterEach
    void tearDown() {
        ShopGuiBuilder.setInventoryFactory(null);
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private Shop registerShop() {
        ShopItem stone = new ShopItem("stone", Material.STONE, 1, ShopItem.NO_SLOT, 0, "", List.of(),
                new BigDecimal("10"), new BigDecimal("5"), true, true, SellMatch.PLAIN_MATERIAL, 0);
        Shop shop = new Shop("test", "&8Test", ShopLayout.defaults(54), Map.of("stone", stone));
        plugin.shopRegistry().register(shop);
        return shop;
    }

    private ShopGuiHolder openHolder() {
        InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
        return holder instanceof ShopGuiHolder h ? h : null;
    }

    private String drain() {
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = player.nextMessage()) != null) {
            sb.append(m).append('\n');
        }
        return sb.toString();
    }

    @Test
    void invalidInputPreservesSelectedQuantity() {
        registerShop();
        service.handleCustomQuantityInput(player.getUniqueId(), "test", "stone", 0, 77, "abc");
        assertTrue(drain().toLowerCase().contains("nieprawidłowa"));
        ShopGuiHolder h = openHolder();
        assertTrue(h != null && h.view() == ShopGuiHolder.View.DETAIL);
        assertEquals(77, h.selectedQuantity(), "nieprawidłowa wpiska zachowuje wybraną ilość");
    }

    @Test
    void validInputUsesNewQuantity() {
        registerShop();
        service.handleCustomQuantityInput(player.getUniqueId(), "test", "stone", 0, 77, "120");
        ShopGuiHolder h = openHolder();
        assertTrue(h != null && h.view() == ShopGuiHolder.View.DETAIL);
        assertEquals(120, h.selectedQuantity(), "poprawna wpiska używa nowej ilości");
    }

    @Test
    void timeoutPreservesSelectedQuantity() {
        Shop shop = registerShop();
        ShopItem item = shop.item("stone").orElseThrow();
        service.requestCustomQuantity(player, shop, item, 0, 77);
        server.getScheduler().performTicks(30L); // > timeout (1 s)
        ShopGuiHolder h = openHolder();
        assertTrue(h != null && h.view() == ShopGuiHolder.View.DETAIL, "timeout wraca do szczegółów");
        assertEquals(77, h.selectedQuantity(), "timeout zachowuje wybraną ilość");
    }
}
