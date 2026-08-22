package hex.collections.listener;

import hex.collections.api.CollectionProgressContext;
import hex.collections.api.CollectionSource;
import hex.collections.service.CollectionProgressService;
import hex.towns.api.TownsApi;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class MobKillCollectionListener implements Listener {
    private static final Map<EntityType, String> MOB_COLLECTIONS = Map.of(
            EntityType.ZOMBIE, "mobs.zombie",
            EntityType.SKELETON, "mobs.skeleton",
            EntityType.SPIDER, "mobs.spider",
            EntityType.SILVERFISH, "mobs.silverfish"
    );

    private static final Map<EntityType, Map<String, List<Material>>> ANIMAL_DROP_COLLECTIONS = Map.of(
            EntityType.COW, Map.of(
                    "animals.beef", List.of(Material.BEEF),
                    "animals.leather", List.of(Material.LEATHER)
            ),
            EntityType.PIG, Map.of(
                    "animals.pork", List.of(Material.PORKCHOP)
            ),
            EntityType.SHEEP, Map.of(
                    "animals.mutton", List.of(Material.MUTTON),
                    "animals.wool", List.of(Material.WHITE_WOOL, Material.ORANGE_WOOL, Material.MAGENTA_WOOL, Material.LIGHT_BLUE_WOOL,
                            Material.YELLOW_WOOL, Material.LIME_WOOL, Material.PINK_WOOL, Material.GRAY_WOOL, Material.LIGHT_GRAY_WOOL,
                            Material.CYAN_WOOL, Material.PURPLE_WOOL, Material.BLUE_WOOL, Material.BROWN_WOOL, Material.GREEN_WOOL,
                            Material.RED_WOOL, Material.BLACK_WOOL)
            ),
            EntityType.CHICKEN, Map.of(
                    "animals.chicken_meat", List.of(Material.CHICKEN)
            )
    );

    private static final Map<EntityType, List<Material>> MOB_DROP_MATERIALS = Map.of(
            EntityType.ZOMBIE, List.of(Material.ROTTEN_FLESH, Material.CARROT, Material.POTATO, Material.IRON_INGOT),
            EntityType.SKELETON, List.of(Material.BONE, Material.ARROW, Material.BOW),
            EntityType.SPIDER, List.of(Material.STRING, Material.SPIDER_EYE),
            EntityType.SILVERFISH, List.of(Material.EXPERIENCE_BOTTLE)
    );

    private final TownsApi towns;
    private final CollectionProgressService service;

    public MobKillCollectionListener(TownsApi towns, CollectionProgressService service) {
        this.towns = towns;
        this.service = service;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMobDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        Optional<UUID> townId = towns.townIdOf(killer.getUniqueId());
        if (townId.isEmpty()) return;

        // A kill inside a claim belongs only to that claim. This prevents a member
        // of town A from progressing A's collection by killing entities in town B.
        Optional<hex.towns.model.Town> targetTown = towns.townAt(event.getEntity().getLocation());
        if (targetTown.isPresent() && !targetTown.get().id().equals(townId.get())) return;

        EntityType type = event.getEntity().getType();
        String mobCollectionId = MOB_COLLECTIONS.get(type);
        if (mobCollectionId != null) {
            long amount = amountOf(event.getDrops(), MOB_DROP_MATERIALS.getOrDefault(type, List.of()));
            if (amount > 0L) add(townId.get(), killer, event.getEntity().getLocation(), mobCollectionId, amount, "mob-drop:" + type.name().toLowerCase(Locale.ROOT));
        }

        Map<String, List<Material>> animalCollections = ANIMAL_DROP_COLLECTIONS.getOrDefault(type, Map.of());
        for (Map.Entry<String, List<Material>> entry : animalCollections.entrySet()) {
            long amount = amountOf(event.getDrops(), entry.getValue());
            if (amount > 0L) add(townId.get(), killer, event.getEntity().getLocation(), entry.getKey(), amount, "animal-drop:" + type.name().toLowerCase(Locale.ROOT));
        }
    }

    private long amountOf(List<ItemStack> drops, List<Material> materials) {
        if (drops == null || drops.isEmpty() || materials == null || materials.isEmpty()) return 0L;
        Map<Material, Boolean> allowed = new LinkedHashMap<>();
        for (Material material : materials) allowed.put(material, Boolean.TRUE);
        long amount = 0L;
        for (ItemStack drop : drops) {
            if (drop != null && allowed.containsKey(drop.getType())) amount += Math.max(0, drop.getAmount());
        }
        return amount;
    }

    private void add(UUID townId, Player player, Location location, String collectionId, long amount, String reason) {
        service.addProgress(new CollectionProgressContext()
                .playerUuid(player.getUniqueId())
                .townId(townId)
                .collectionId(collectionId)
                .amount(amount)
                .source(CollectionSource.NATURAL_MOB_DROP)
                .location(location)
                .reason(reason));
    }
}
