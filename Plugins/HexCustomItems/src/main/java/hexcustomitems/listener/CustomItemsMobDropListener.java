package hexcustomitems.listener;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.service.CustomItemRegistryService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

public final class CustomItemsMobDropListener implements Listener {

    private final Supplier<HexCustomItemsConfig> configSupplier;
    private final CustomItemRegistryService registryService;
    private final Random random = new Random();

    public CustomItemsMobDropListener(Supplier<HexCustomItemsConfig> configSupplier, CustomItemRegistryService registryService) {
        this.configSupplier = configSupplier;
        this.registryService = registryService;
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        HexCustomItemsConfig config = configSupplier.get();
        if (!config.mobDrops().enabled()) {
            return;
        }
        List<HexCustomItemsConfig.MobDropSpec> drops = config.mobDrops().byMob().get(event.getEntityType());
        if (drops == null || drops.isEmpty()) {
            return;
        }
        for (HexCustomItemsConfig.MobDropSpec drop : drops) {
            if (random.nextDouble() * 100.0D > drop.chance()) {
                continue;
            }
            var definition = registryService.findById(drop.item());
            if (definition == null) {
                continue;
            }
            ItemStack stack = registryService.createItem(definition, drop.amount());
            event.getDrops().add(stack);
        }
    }
}
