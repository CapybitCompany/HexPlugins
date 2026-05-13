package mysterybox.listener;

import mysterybox.gui.MysteryBoxOpeningService;
import mysterybox.service.ItemFactoryService;
import mysterybox.service.VoucherService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

public final class MysteryBoxInteractListener implements Listener {

    private final ItemFactoryService itemFactoryService;
    private final MysteryBoxOpeningService openingService;
    private final VoucherService voucherService;

    public MysteryBoxInteractListener(
            ItemFactoryService itemFactoryService,
            MysteryBoxOpeningService openingService,
            VoucherService voucherService
    ) {
        this.itemFactoryService = Objects.requireNonNull(itemFactoryService, "itemFactoryService");
        this.openingService = Objects.requireNonNull(openingService, "openingService");
        this.voucherService = Objects.requireNonNull(voucherService, "voucherService");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir()) {
            return;
        }

        if (itemFactoryService.isMysteryBoxItem(item)) {
            event.setCancelled(true);
            openingService.openBox(event.getPlayer(), event.getHand());
            return;
        }

        if (itemFactoryService.isVipVoucherItem(item)) {
            event.setCancelled(true);
            voucherService.activateVoucher(event.getPlayer(), event.getHand());
        }
    }
}
