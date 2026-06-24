package hex.collections.listener;

import hex.collections.api.CollectionProgressContext;
import hex.collections.api.CollectionSource;
import hex.collections.service.CollectionProgressService;
import hex.towns.api.TownsApi;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class MobKillCollectionListener implements Listener {
    private static final Map<EntityType, String> COLLECTIONS = Map.of(
            EntityType.ZOMBIE, "mobs.zombie",
            EntityType.SKELETON, "mobs.skeleton",
            EntityType.SPIDER, "mobs.spider",
            EntityType.SILVERFISH, "mobs.silverfish"
    );

    private final TownsApi towns;
    private final CollectionProgressService service;

    public MobKillCollectionListener(TownsApi towns, CollectionProgressService service) {
        this.towns = towns;
        this.service = service;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMobDeath(EntityDeathEvent event) {
        String collectionId = COLLECTIONS.get(event.getEntity().getType());
        if (collectionId == null) return;
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        Optional<UUID> townId = towns.townIdOf(killer.getUniqueId());
        if (townId.isEmpty()) return;
        service.addProgress(new CollectionProgressContext()
                .playerUuid(killer.getUniqueId())
                .townId(townId.get())
                .collectionId(collectionId)
                .amount(1L)
                .source(CollectionSource.NATURAL_MOB_DROP)
                .location(event.getEntity().getLocation())
                .reason("mob-kill:" + event.getEntity().getType().name().toLowerCase(Locale.ROOT)));
    }
}
