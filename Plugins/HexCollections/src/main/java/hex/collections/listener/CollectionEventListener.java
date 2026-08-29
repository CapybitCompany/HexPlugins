package hex.collections.listener;

import hex.collections.api.CollectionProgressContext;
import hex.collections.api.CollectionSource;
import hex.collections.model.CollectionDefinition;
import hex.collections.model.SourceRule;
import hex.collections.service.AntiExploitService;
import hex.collections.service.CollectionProgressService;
import hex.towns.api.TownsApi;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.UUID;

public final class CollectionEventListener implements Listener {
    private final TownsApi towns;
    private final CollectionProgressService service;
    private final AntiExploitService antiExploit;

    public CollectionEventListener(TownsApi towns, CollectionProgressService service, AntiExploitService antiExploit) {
        this.towns = towns; this.service = service; this.antiExploit = antiExploit;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) { towns.townIdOf(event.getPlayer().getUniqueId()).ifPresent(service::loadTown); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent event) {
        Material material = event.getBlockPlaced().getType();
        if (!service.registry().matching(CollectionSource.NATURAL_BLOCK_BREAK, material).isEmpty()
                || !service.registry().matching(CollectionSource.NATURAL_CROP_HARVEST, material).isEmpty()) {
            antiExploit.markPlaced(event.getBlockPlaced());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        Optional<UUID> townId = towns.townIdOf(event.getPlayer().getUniqueId());
        if (townId.isEmpty()) return;
        ClaimRelation claimRelation = claimRelation(townId.get(), event.getBlock().getLocation());
        Material material = event.getBlock().getType();
        boolean silkTouch = hasSilkTouch(event.getPlayer().getInventory().getItemInMainHand());

        // Rudy sa naliczane dopiero w BlockDropItemEvent, czyli z realnej liczby itemow po wykopaniu
        // (np. miedz/redstone z Fortune). Bez tego liczylo blok, a nie faktyczny zebrany surowiec.
        if (isOreLike(material)) return;

        // Kaktus i trzcina zrzucaja kolejne segmenty przez fizyke po zniszczeniu dolnego bloku.
        // BlockDropItemEvent widzi wtedy tylko bezposrednio zniszczony segment, przez co kolekcja
        // rosla zawsze o 1. Snapshotujemy wysokosc przed zniszczeniem i liczymy faktycznie
        // zbierane segmenty od kliknietego bloku w gore, maksymalnie 3.
        if (material == Material.CACTUS || material == Material.SUGAR_CANE) {
            int harvestedSegments = verticalHarvestSegments(event.getBlock(), material, 3);
            if (harvestedSegments <= 0) return;
            String collectionId = cropCollectionId(material);
            service.registry().find(collectionId).ifPresent(def ->
                    tryAddCropBreak(def, townId.get(), event, claimRelation, material, harvestedSegments));
            return;
        }

        // STONE ma specjalne rozróżnienie:
        // - bez Silk Toucha naturalny STONE wpada do kolekcji cobblestone,
        // - z Silk Touchem naturalny STONE wpada do kolekcji stone.
        if (material == Material.STONE) {
            String collectionId = silkTouch ? "mining.stone" : "mining.cobblestone";
            service.registry().find(collectionId).ifPresent(def -> tryAddBlockBreak(def, townId.get(), event, claimRelation, material));
            return;
        }

        for (CollectionDefinition def : service.registry().matching(CollectionSource.NATURAL_BLOCK_BREAK, material)) {
            tryAddBlockBreak(def, townId.get(), event, claimRelation, material);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockDrops(BlockDropItemEvent event) {
        Material material = event.getBlockState().getType();
        if (isOreLike(material)) {
            ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
            // Kolekcje z rud liczymy wylacznie z realnego wykopania zloza kilofem.
            // Silk Touch daje blok rudy, wiec nie jest traktowany jako pozyskanie surowca.
            // Podnoszenie itemow, rzucanie gracz-gracz ani trade nie przechodza przez ten event.
            if ((material != Material.ANCIENT_DEBRIS && hasSilkTouch(tool)) || !isPickaxe(tool)) return;

            Optional<UUID> townId = towns.townIdOf(event.getPlayer().getUniqueId());
            if (townId.isEmpty()) return;
            ClaimRelation claimRelation = claimRelation(townId.get(), event.getBlockState().getLocation());
            long amount = droppedAmount(event);
            if (amount <= 0L) return;
            for (CollectionDefinition def : service.registry().matching(CollectionSource.NATURAL_BLOCK_BREAK, material)) {
                tryAddBlockDrop(def, townId.get(), event, claimRelation, material, amount);
            }
            return;
        }
        // Te dwie rosliny sa liczone w BlockBreakEvent na podstawie wysokosci sprzed zniszczenia.
        if (material == Material.CACTUS || material == Material.SUGAR_CANE) return;
        handleCropDrop(event, material);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onFurnaceExtract(FurnaceExtractEvent event) {
        if (event.getItemAmount() <= 0) return;
        String collectionId;
        if (event.getItemType() == Material.STONE) {
            collectionId = "mining.stone";
        } else if (event.getItemType() == Material.NETHERITE_SCRAP) {
            // mining.netherite represents the actual resource produced by HexMinions as well:
            // NETHERITE_SCRAP. Count it once, when the player really takes scrap from the furnace.
            collectionId = "mining.netherite";
        } else {
            return;
        }
        Optional<UUID> townId = towns.townIdOf(event.getPlayer().getUniqueId());
        if (townId.isEmpty()) return;
        service.registry().find(collectionId).ifPresent(def -> service.addProgress(new CollectionProgressContext()
                .playerUuid(event.getPlayer().getUniqueId())
                .townId(townId.get())
                .collectionId(def.id())
                .amount(event.getItemAmount())
                .source(CollectionSource.CUSTOM_PLUGIN_GRANTED)
                .location(event.getBlock().getLocation())
                .reason("furnace-extract:" + event.getItemType())));
    }

    private void handleCropDrop(BlockDropItemEvent event, Material blockMaterial) {
        String collectionId = cropCollectionId(blockMaterial);
        Material dropMaterial = cropDropMaterial(blockMaterial);
        if (collectionId.isBlank() || dropMaterial == Material.AIR) return;
        if (!cropBlockEligible(event, blockMaterial)) return;

        Optional<UUID> townId = towns.townIdOf(event.getPlayer().getUniqueId());
        if (townId.isEmpty()) return;
        long amount = droppedAmount(event, dropMaterial);
        if (amount <= 0L) return;
        ClaimRelation claimRelation = claimRelation(townId.get(), event.getBlockState().getLocation());
        service.registry().find(collectionId).ifPresent(def -> tryAddCropDrop(def, townId.get(), event, claimRelation, blockMaterial, amount));
    }

    private boolean cropBlockEligible(BlockDropItemEvent event, Material blockMaterial) {
        if (blockMaterial == Material.WHEAT || blockMaterial == Material.BEETROOTS) {
            if (!(event.getBlockState().getBlockData() instanceof Ageable ageable)) return false;
            return ageable.getAge() >= ageable.getMaximumAge();
        }
        return blockMaterial == Material.CACTUS || blockMaterial == Material.SUGAR_CANE;
    }

    private String cropCollectionId(Material blockMaterial) {
        return switch (blockMaterial) {
            case WHEAT -> "farming.wheat";
            case BEETROOTS -> "farming.beetroot";
            case CACTUS -> "farming.cactus";
            case SUGAR_CANE -> "farming.sugar_cane";
            default -> "";
        };
    }

    private Material cropDropMaterial(Material blockMaterial) {
        return switch (blockMaterial) {
            case WHEAT -> Material.WHEAT;
            case BEETROOTS -> Material.BEETROOT;
            case CACTUS -> Material.CACTUS;
            case SUGAR_CANE -> Material.SUGAR_CANE;
            default -> Material.AIR;
        };
    }

    private int verticalHarvestSegments(Block start, Material material, int maxSegments) {
        if (start == null || material == null || maxSegments <= 0 || start.getType() != material) return 0;
        int amount = 0;
        Block current = start;
        while (amount < maxSegments && current.getType() == material) {
            amount++;
            current = current.getRelative(0, 1, 0);
        }
        return amount;
    }

    private void tryAddCropBreak(CollectionDefinition def, UUID townId, BlockBreakEvent event, ClaimRelation claimRelation, Material material, long amount) {
        SourceRule rule = def.sourceRules().get(CollectionSource.NATURAL_CROP_HARVEST);
        if (rule == null || amount <= 0L) return;
        if (!rule.worldAllowed(event.getBlock().getWorld().getName())) return;
        if (!claimAllowed(rule, claimRelation)) return;
        // Zachowujemy dotychczasowa ochrone anty-exploit dla bloku kliknietego przez gracza.
        // Jezeli ten segment byl recznie postawiony, cala operacja nie nalicza kolekcji.
        if (rule.denyPlayerPlacedBlocks() && antiExploit.consumePlayerPlaced(event.getBlock().getLocation())) return;
        if (rule.denyRecentlyBrokenBlocks() && antiExploit.recentlyBroken(event.getBlock().getLocation(), service.settings().recentlyBrokenTtlMs())) return;
        service.addProgress(new CollectionProgressContext()
                .playerUuid(event.getPlayer().getUniqueId())
                .townId(townId)
                .collectionId(def.id())
                .amount(Math.min(3L, amount))
                .source(CollectionSource.NATURAL_CROP_HARVEST)
                .location(event.getBlock().getLocation())
                .reason("crop-column-break:" + material));
    }

    private void tryAddBlockBreak(CollectionDefinition def, UUID townId, BlockBreakEvent event, ClaimRelation claimRelation, Material material) {
        SourceRule rule = def.sourceRules().get(CollectionSource.NATURAL_BLOCK_BREAK);
        if (rule == null) return; // material-driven sources are always fail-closed
        if (!rule.worldAllowed(event.getBlock().getWorld().getName())) return;
        if (!claimAllowed(rule, claimRelation)) return;
        if (rule.denyPlayerPlacedBlocks() && antiExploit.consumePlayerPlaced(event.getBlock().getLocation())) return;
        if (rule.denyRecentlyBrokenBlocks() && antiExploit.recentlyBroken(event.getBlock().getLocation(), service.settings().recentlyBrokenTtlMs())) return;
        service.addProgress(new CollectionProgressContext().playerUuid(event.getPlayer().getUniqueId()).townId(townId).collectionId(def.id()).amount(1L).source(CollectionSource.NATURAL_BLOCK_BREAK).location(event.getBlock().getLocation()).reason("block-break:" + material));
    }

    private void tryAddBlockDrop(CollectionDefinition def, UUID townId, BlockDropItemEvent event, ClaimRelation claimRelation, Material material, long amount) {
        SourceRule rule = def.sourceRules().get(CollectionSource.NATURAL_BLOCK_BREAK);
        if (rule == null) return; // material-driven sources are always fail-closed
        if (!rule.worldAllowed(event.getBlockState().getLocation().getWorld().getName())) return;
        if (!claimAllowed(rule, claimRelation)) return;
        if (rule.denyPlayerPlacedBlocks() && antiExploit.consumePlayerPlaced(event.getBlockState().getLocation())) return;
        if (rule.denyRecentlyBrokenBlocks() && antiExploit.recentlyBroken(event.getBlockState().getLocation(), service.settings().recentlyBrokenTtlMs())) return;
        service.addProgress(new CollectionProgressContext()
                .playerUuid(event.getPlayer().getUniqueId())
                .townId(townId)
                .collectionId(def.id())
                .amount(amount)
                .source(CollectionSource.NATURAL_BLOCK_BREAK)
                .location(event.getBlockState().getLocation())
                .reason("block-drop:" + material));
    }

    private void tryAddCropDrop(CollectionDefinition def, UUID townId, BlockDropItemEvent event, ClaimRelation claimRelation, Material material, long amount) {
        SourceRule rule = def.sourceRules().get(CollectionSource.NATURAL_CROP_HARVEST);
        if (rule == null) return; // material-driven sources are always fail-closed
        if (!rule.worldAllowed(event.getBlockState().getLocation().getWorld().getName())) return;
        if (!claimAllowed(rule, claimRelation)) return;
        if (rule.denyPlayerPlacedBlocks() && antiExploit.consumePlayerPlaced(event.getBlockState().getLocation())) return;
        if (rule.denyRecentlyBrokenBlocks() && antiExploit.recentlyBroken(event.getBlockState().getLocation(), service.settings().recentlyBrokenTtlMs())) return;
        service.addProgress(new CollectionProgressContext()
                .playerUuid(event.getPlayer().getUniqueId())
                .townId(townId)
                .collectionId(def.id())
                .amount(amount)
                .source(CollectionSource.NATURAL_CROP_HARVEST)
                .location(event.getBlockState().getLocation())
                .reason("crop-drop:" + material));
    }

    private ClaimRelation claimRelation(UUID playerTownId, Location location) {
        var claimedTown = towns.townAt(location);
        if (claimedTown.isEmpty()) return ClaimRelation.WILDERNESS;
        return claimedTown.get().id().equals(playerTownId) ? ClaimRelation.OWN_TOWN : ClaimRelation.FOREIGN_TOWN;
    }

    private boolean claimAllowed(SourceRule rule, ClaimRelation relation) {
        if (relation == ClaimRelation.WILDERNESS) return true;
        if (relation == ClaimRelation.OWN_TOWN) {
            // The new explicit own-town policy intentionally supersedes legacy per-rule false values.
            // Old configs shipped every manual collection with allow_in_town_claims=false, which made
            // ordinary mining inside one's own city silently stop progressing.
            return service.settings().ownTownBlockBreakCollectionEnabled();
        }
        return service.settings().foreignTownBlockBreakCollectionEnabled() && rule.allowInTownClaims();
    }

    private enum ClaimRelation {
        WILDERNESS, OWN_TOWN, FOREIGN_TOWN
    }

    private long droppedAmount(BlockDropItemEvent event) {
        long amount = 0L;
        for (Item item : event.getItems()) {
            ItemStack stack = item.getItemStack();
            if (stack != null && !stack.getType().isAir()) amount += stack.getAmount();
        }
        return amount;
    }

    private long droppedAmount(BlockDropItemEvent event, Material onlyMaterial) {
        long amount = 0L;
        for (Item item : event.getItems()) {
            ItemStack stack = item.getItemStack();
            if (stack != null && stack.getType() == onlyMaterial) amount += stack.getAmount();
        }
        return amount;
    }

    private boolean isOreLike(Material material) {
        if (material == null) return false;
        String name = material.name();
        return name.endsWith("_ORE") || material == Material.ANCIENT_DEBRIS;
    }

    private boolean hasSilkTouch(ItemStack item) {
        return item != null && item.containsEnchantment(Enchantment.SILK_TOUCH);
    }

    private boolean isPickaxe(ItemStack item) {
        return item != null && item.getType().name().endsWith("_PICKAXE");
    }
}
