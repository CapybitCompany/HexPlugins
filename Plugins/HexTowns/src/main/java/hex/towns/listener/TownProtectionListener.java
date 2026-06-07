package hex.towns.listener;

import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
import hex.towns.model.Town;
import hex.towns.service.TownsService;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class TownProtectionListener implements Listener {
    private static final long DENY_MESSAGE_COOLDOWN_MS = 1500L;

    private final HexApi api;
    private final TownsService service;
    private final ConcurrentMap<UUID, Long> nextDenyMessageAt = new ConcurrentHashMap<>();

    public TownProtectionListener(HexApi api, TownsService service) {
        this.api = api;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!allow(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!allow(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || !(block.getState() instanceof Container)) {
            return;
        }
        if (!allow(event.getPlayer(), block.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!allow(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!allow(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    private boolean allow(Player player, Location location) {
        Optional<Town> town = service.townAt(location);
        if (town.isEmpty() || service.isMember(player.getUniqueId(), town.get().id())) {
            return true;
        }
        sendDenyMessage(player, town.get());
        return false;
    }

    private void sendDenyMessage(Player player, Town town) {
        long now = System.currentTimeMillis();
        Long next = nextDenyMessageAt.get(player.getUniqueId());
        if (next != null && now < next) {
            return;
        }
        nextDenyMessageAt.put(player.getUniqueId(), now + DENY_MESSAGE_COOLDOWN_MS);
        api.ui().send(player, "towns.protect.no-build", UiTokens.of("town", town.name()));
    }
}