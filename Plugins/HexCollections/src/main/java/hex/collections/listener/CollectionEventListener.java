package hex.collections.listener;

import hex.collections.api.CollectionProgressContext;
import hex.collections.api.CollectionSource;
import hex.collections.model.CollectionDefinition;
import hex.collections.model.SourceRule;
import hex.collections.service.AntiExploitService;
import hex.collections.service.CollectionProgressService;
import hex.towns.api.TownsApi;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;

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
        if (!service.registry().matching(CollectionSource.NATURAL_BLOCK_BREAK, material).isEmpty()) antiExploit.markPlaced(event.getBlockPlaced());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        Optional<UUID> townId = towns.townIdOf(event.getPlayer().getUniqueId());
        if (townId.isEmpty()) return;
        boolean inTownClaim = towns.townAt(event.getBlock().getLocation()).isPresent();
        Material material = event.getBlock().getType();
        for (CollectionDefinition def : service.registry().matching(CollectionSource.NATURAL_BLOCK_BREAK, material)) {
            SourceRule rule = def.sourceRules().get(CollectionSource.NATURAL_BLOCK_BREAK);
            if (rule != null) {
                if (!rule.worldAllowed(event.getBlock().getWorld().getName())) continue;
                if (inTownClaim && (!service.settings().blockBreakInTownClaimsEnabled() || !rule.allowInTownClaims())) continue;
                if (rule.denyPlayerPlacedBlocks() && antiExploit.consumePlayerPlaced(event.getBlock().getLocation())) continue;
                if (rule.denyRecentlyBrokenBlocks() && antiExploit.recentlyBroken(event.getBlock().getLocation(), service.settings().recentlyBrokenTtlMs())) continue;
            }
            service.addProgress(new CollectionProgressContext().playerUuid(event.getPlayer().getUniqueId()).townId(townId.get()).collectionId(def.id()).amount(1L).source(CollectionSource.NATURAL_BLOCK_BREAK).location(event.getBlock().getLocation()).reason("block-break:" + material));
        }
    }
}

