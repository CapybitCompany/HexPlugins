package mysterybox.listener;

import mysterybox.config.MysteryBoxConfig;
import mysterybox.service.ItemFactoryService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.function.Supplier;

public final class MysteryBoxDropListener implements Listener {

    private final Supplier<MysteryBoxConfig> configSupplier;
    private final ItemFactoryService itemFactoryService;

    public MysteryBoxDropListener(
            Supplier<MysteryBoxConfig> configSupplier,
            ItemFactoryService itemFactoryService
    ) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.itemFactoryService = Objects.requireNonNull(itemFactoryService, "itemFactoryService");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (dropped == null || dropped.getType().isAir()) {
            return;
        }

        MysteryBoxConfig.DropProtectionSettings protection = configSupplier.get().dropProtection();
        if (protection.blockMysteryBoxDrop() && itemFactoryService.isMysteryBoxItem(dropped)) {
            event.setCancelled(true);
            return;
        }

        if (protection.blockVoucherDrop() && itemFactoryService.isVipVoucherItem(dropped)) {
            event.setCancelled(true);
        }
    }
}
