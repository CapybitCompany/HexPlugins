package hexcustomitems.ui;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.listener.CustomItemsMenuListener;
import hexcustomitems.model.CommandExecutorType;
import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.service.CustomItemRegistryService;
import hexcustomitems.service.GiveService;
import hexcustomitems.service.MessageService;
import hexcustomitems.support.PluginTestBase;
import hexcustomitems.support.TestConfig;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class MenuTest extends PluginTestBase {

    private HexCustomItemsConfig config;
    private CustomItemRegistryService registry;
    private MenuService menuService;
    private CustomItemsMenuListener listener;

    private void setupWithItems(int count) {
        Map<String, CustomItemDefinition> items = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            items.put("test_" + i, TestConfig.commandItem("test_" + i, Material.PAPER,
                    CommandExecutorType.CONSOLE, List.of("say " + i), false));
        }
        config = TestConfig.withItems(items);
        registry = new CustomItemRegistryService(plugin, config);
        MessageService messageService = new MessageService(() -> config);
        GiveService giveService = new GiveService(() -> config, registry, messageService);
        menuService = new MenuService(plugin, registry, () -> config);
        listener = new CustomItemsMenuListener(registry, giveService, menuService, () -> config);
    }

    private int countPaper(Inventory inventory) {
        int total = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getType() == Material.PAPER) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private void click(PlayerMock viewer, int rawSlot, ClickType clickType) {
        InventoryClickEvent event = new InventoryClickEvent(
                viewer.getOpenInventory(), InventoryType.SlotType.CONTAINER, rawSlot, clickType, InventoryAction.PICKUP_ALL);
        listener.onClick(event);
    }

    @Test
    void commandOpensItemsMenuForSelf() {
        PlayerMock viewer = server.addPlayer();
        viewer.setOp(true);
        viewer.performCommand("hexcustomitem adminpanel");

        ItemsMenu menu = assertInstanceOf(ItemsMenu.class, viewer.getOpenInventory().getTopInventory().getHolder());
        assertNull(menu.targetId(), "Ohne Ziel gibt man an sich selbst");
    }

    @Test
    void directMenuOpenWithTargetSetsTargetId() {
        setupWithItems(3);
        PlayerMock viewer = server.addPlayer();
        PlayerMock target = server.addPlayer();
        menuService.open(viewer, target.getUniqueId(), 0);

        ItemsMenu menu = (ItemsMenu) viewer.getOpenInventory().getTopInventory().getHolder();
        assertEquals(target.getUniqueId(), menu.targetId());
    }

    @Test
    void paginationShowsNextAndPrevButtons() {
        setupWithItems(50); // -> 2 Seiten (45 + 5)
        PlayerMock viewer = server.addPlayer();

        menuService.open(viewer, null, 0);
        Inventory page0 = viewer.getOpenInventory().getTopInventory();
        assertEquals(MenuService.ACTION_NEXT, menuService.readAction(page0.getItem(ItemsMenu.SLOT_NEXT)));
        assertNull(menuService.readAction(page0.getItem(ItemsMenu.SLOT_PREV)));

        menuService.open(viewer, null, 1);
        Inventory page1 = viewer.getOpenInventory().getTopInventory();
        assertEquals(MenuService.ACTION_PREV, menuService.readAction(page1.getItem(ItemsMenu.SLOT_PREV)));
    }

    @Test
    void clickingNextButtonSwitchesPage() {
        setupWithItems(50);
        PlayerMock viewer = server.addPlayer();
        menuService.open(viewer, null, 0);

        click(viewer, ItemsMenu.SLOT_NEXT, ClickType.LEFT);
        server.getScheduler().performOneTick(); // openLater läuft im nächsten Tick

        ItemsMenu menu = (ItemsMenu) viewer.getOpenInventory().getTopInventory().getHolder();
        assertEquals(1, menu.page());
    }

    @Test
    void clickGivesOneItemToTarget() {
        setupWithItems(3);
        PlayerMock viewer = server.addPlayer();
        PlayerMock target = server.addPlayer();
        menuService.open(viewer, target.getUniqueId(), 0);

        click(viewer, 0, ClickType.LEFT);

        assertEquals(1, countPaper(target.getInventory()));
    }

    @Test
    void rightClickGivesConfiguredItemStackAmount() {
        setupWithItems(3);
        PlayerMock viewer = server.addPlayer();
        PlayerMock target = server.addPlayer();
        menuService.open(viewer, target.getUniqueId(), 0);

        click(viewer, 0, ClickType.RIGHT);

        assertEquals(config.items().get("test_0").adminPanelStack(), countPaper(target.getInventory()));
    }

    @Test
    void clicksInPlayerInventoryAreIgnored() {
        setupWithItems(3);
        PlayerMock viewer = server.addPlayer();
        PlayerMock target = server.addPlayer();
        menuService.open(viewer, target.getUniqueId(), 0);

        // rawSlot >= Menügröße -> unteres (Spieler-)Inventar
        click(viewer, ItemsMenu.SIZE, ClickType.LEFT);

        assertEquals(0, countPaper(target.getInventory()));
    }
}
