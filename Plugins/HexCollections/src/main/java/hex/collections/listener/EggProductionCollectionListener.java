package hex.collections.listener;

import hex.collections.api.CollectionProgressContext;
import hex.collections.api.CollectionSource;
import hex.collections.service.CollectionProgressService;
import hex.towns.api.TownsApi;
import org.bukkit.Material;
import org.bukkit.entity.Chicken;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDropItemEvent;

import java.util.UUID;

/**
 * Counts only eggs actually produced by a chicken entity.
 * Player drop/pickup/inventory operations never reach this listener, so the same egg cannot be farmed repeatedly.
 */
public final class EggProductionCollectionListener implements Listener {
    private final TownsApi towns;
    private final CollectionProgressService service;

    public EggProductionCollectionListener(TownsApi towns, CollectionProgressService service) {
        this.towns = towns;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChickenDrop(EntityDropItemEvent event) {
        if (!(event.getEntity() instanceof Chicken)) return;
        if (event.getItemDrop() == null || event.getItemDrop().getItemStack().getType() != Material.EGG) return;

        var location = event.getItemDrop().getLocation();
        UUID townId = towns.townAt(location).map(town -> town.id()).orElse(null);
        if (townId == null) return;

        service.registry().find("animals.eggs").ifPresent(definition ->
                service.addProgress(new CollectionProgressContext()
                        .townId(townId)
                        .collectionId(definition.id())
                        .amount(Math.max(1, event.getItemDrop().getItemStack().getAmount()))
                        .source(CollectionSource.NATURAL_ANIMAL_PRODUCTION)
                        .location(location)
                        .itemStack(event.getItemDrop().getItemStack())
                        .reason("chicken-laid-egg")));
    }
}
