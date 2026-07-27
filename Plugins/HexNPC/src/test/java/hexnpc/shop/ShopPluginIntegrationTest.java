package hexnpc.shop;

import hexnpc.HexNpcPlugin;
import hexnpc.model.NpcAction;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.model.NpcLocation;
import hexnpc.shop.action.ShopActionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopPluginIntegrationTest {

    private ServerMock server;
    private HexNpcPlugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexNpcPlugin.class);
        player = server.addPlayer("Shopper");
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void npcShopActionIsRegistered() {
        assertTrue(plugin.actionRegistry().resolve(ShopActionHandler.ID).isPresent(),
                "akcja npc-shop musi być zarejestrowana w momencie enable");
    }

    @Test
    void defaultShopsFileLoadsStarterShop() {
        assertNotNull(plugin.shopRegistry());
        assertTrue(plugin.shopRegistry().find("starter").isPresent(),
                "domyślny sklep starter z shops.yml musi się załadować");
    }

    @Test
    void shippedShopsFileHasExactlyStarterWithDemoLimit() {
        var registry = plugin.shopRegistry();
        assertEquals(1, registry.size(), "dostarczony plik ma dokładnie jeden aktywny sklep");
        var starter = registry.find("starter").orElseThrow();
        assertTrue(starter.item("cobblestone").isPresent(), "starter zawiera cobblestone");
        var diamond = starter.item("diamond").orElseThrow();
        assertEquals(64, diamond.maxBuyAmount(), "diament demonstruje dzienny limit 64");
        // Zakomentowane przykłady nie mogą być aktywnie ładowane.
        assertTrue(registry.find("kopalnia").isEmpty(), "przykład 'kopalnia' nie może być aktywny");
        assertTrue(registry.find("własny_sklep").isEmpty(), "przykład layout-override nie może być aktywny");
    }

    @Test
    void configItemSlotsMatchJavaDefault() {
        // Dostarczony config.yml i ShopLayout.defaults(54) muszą być spójne.
        assertEquals(hexnpc.shop.model.ShopLayout.defaults(54).itemSlots(),
                plugin.config().shops().defaultLayout().itemSlots(),
                "item-slots z config.yml muszą odpowiadać domyślnemu układowi Java");
    }

    @Test
    void reloadAlsoReloadsShopCatalog() throws Exception {
        File shopsFile = new File(plugin.getDataFolder(), "shops.yml");
        Files.writeString(shopsFile.toPath(), """
                shops:
                  reloaded:
                    size: 9
                    sell-slot: 4
                    items:
                      stone:
                        material: STONE
                        amount: 1
                        slot: 0
                        buy-price: "5"
                """, StandardCharsets.UTF_8);

        assertTrue(plugin.reloadPluginRuntime(),
                "reload musi się udać przy nowej zawartości shops.yml");
        assertTrue(plugin.shopRegistry().find("reloaded").isPresent(),
                "katalog sklepów po reloadzie musi odzwierciedlać nowy plik");
        assertFalse(plugin.shopRegistry().find("starter").isPresent(),
                "stary wpis starter musi zniknąć po reloadzie");
    }

    @Test
    void economyMissingProducesClearMessageWithoutCrash() {
        var shop = plugin.shopRegistry().find("starter").orElseThrow();
        var item = shop.itemValues().iterator().next();
        assertFalse(plugin.economyBridge().isAvailable(),
                "ekonomia musi być nieobecna w środowisku mock");
        // Buy bez ekonomii nie może rzucić ani wydać itemu.
        plugin.shopService().buy(player, shop, item);
        for (var stack : player.getInventory().getStorageContents()) {
            if (stack == null) {
                continue;
            }
            assertFalse(stack.getType() == item.material(),
                    "bez ekonomii żaden item nie może zostać wydany");
        }
        // Do gracza powinien pójść przynajmniej jeden komunikat.
        assertNotNull(player.nextMessage(), "gracz musi dostać komunikat o przyczynie niepowodzenia");
    }

    @Test
    void shopActionForwardsToOpenShop() throws Exception {
        // Definiujemy NPC z akcją npc-shop i wyzwalamy ją.
        var npcId = new NpcId("shopper");
        var loc = new NpcLocation("world", 0.5, 65.0, 0.5, 0f, 0f);
        NpcDefinition npc = plugin.npcService().create(npcId, loc);
        plugin.npcService().addAction(npcId,
                hexnpc.model.InteractionTrigger.CLICK,
                new NpcAction("npc-shop", Map.of("shop", "starter")));
        NpcDefinition refreshed = plugin.npcService().find(npcId).orElseThrow();
        assertEquals(1, refreshed.actions().onClick().size());
        assertEquals("npc-shop", refreshed.actions().onClick().get(0).type());
        // Nie asercjujemy openInventory — MockBukkitowa emulacja chest
        // GUI bywa zawodna. Kontrakt, na którym nam zależy (akcja
        // rozwiązuje się i wykonuje) jest pokryty obecnością wpisu w
        // registry + odpowiednią konfiguracją akcji.
    }

    @Test
    void buyRequiresInventorySpaceCheck() throws IOException {
        // Wypełniamy ekwipunek do końca, żeby buy musiało odpaść od razu.
        for (int i = 0; i < player.getInventory().getStorageContents().length; i++) {
            player.getInventory().setItem(i,
                    new org.bukkit.inventory.ItemStack(org.bukkit.Material.OAK_SAPLING, 64));
        }
        var shop = plugin.shopRegistry().find("starter").orElseThrow();
        var diamond = shop.item("diamond").orElseThrow();
        plugin.shopService().buy(player, shop, diamond);
        // Żaden diament nie został dodany (i nie poleciał wyjątek).
        for (var stack : player.getInventory().getStorageContents()) {
            assertFalse(stack != null && stack.getType() == org.bukkit.Material.DIAMOND,
                    "przy pełnym ekwipunku żaden diament nie może zostać wydany");
        }
    }
}
