package hexcustomitems.listener;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.service.CustomItemRegistryService;
import hexcustomitems.service.GiveService;
import hexcustomitems.ui.ItemsMenu;
import hexcustomitems.ui.MenuService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Behandelt ausschließlich Klicks im Give-Menü (Holder-Identität).
 * Für normale Inventare bricht der Listener sofort ab - keine SMP-Last.
 */
public final class CustomItemsMenuListener implements Listener {

    private final CustomItemRegistryService registryService;
    private final GiveService giveService;
    private final MenuService menuService;
    private final Supplier<HexCustomItemsConfig> configSupplier;

    public CustomItemsMenuListener(
            CustomItemRegistryService registryService,
            GiveService giveService,
            MenuService menuService,
            Supplier<HexCustomItemsConfig> configSupplier
    ) {
        this.registryService = Objects.requireNonNull(registryService, "registryService");
        this.giveService = Objects.requireNonNull(giveService, "giveService");
        this.menuService = Objects.requireNonNull(menuService, "menuService");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ItemsMenu menu)) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        // Nur Klicks im oberen (Menü-)Inventar auswerten.
        if (event.getClickedInventory() == null || !(event.getClickedInventory().getHolder() instanceof ItemsMenu)) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) {
            return;
        }

        String action = menuService.readAction(clicked);
        if (action != null) {
            switch (action) {
                case MenuService.ACTION_PREV -> menuService.openLater(viewer, menu.targetId(), menu.page() - 1);
                case MenuService.ACTION_NEXT -> menuService.openLater(viewer, menu.targetId(), menu.page() + 1);
                default -> {
                    // Info-Button: keine Aktion.
                }
            }
            return;
        }

        String itemId = registryService.resolveItemId(clicked);
        if (itemId == null) {
            return;
        }
        CustomItemDefinition definition = registryService.findById(itemId);
        if (definition == null) {
            return;
        }

        Player target = resolveTarget(menu.targetId(), viewer);
        if (target == null) {
            return;
        }

        int amount = event.isRightClick() ? definition.adminPanelStack() : 1;
        giveService.giveTo(target, definition, amount);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ItemsMenu) {
            event.setCancelled(true);
        }
    }

    private Player resolveTarget(UUID targetId, Player viewer) {
        if (targetId == null) {
            return viewer;
        }
        return Bukkit.getPlayer(targetId);
    }
}
