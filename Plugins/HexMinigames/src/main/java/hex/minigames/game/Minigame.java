package hex.minigames.game;

import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.UUID;

public interface Minigame {
    String id();

    default MinigameAvailability availability(MinigameDefinition definition, int players) {
        return MinigameAvailability.ok();
    }

    default void prepare(RoundContext context) {
    }

    default void start(RoundContext context) {
    }

    default int countdownSeconds(MinigameDefinition definition, int globalCountdownSeconds) {
        return globalCountdownSeconds;
    }

    default boolean handleCountdownTick(RoundContext context, int ticksRemaining) {
        return false;
    }

    default void handleTick(RoundContext context) {
    }

    default void handlePlayerQuit(RoundContext context, UUID playerId) {
    }

    default RoundResult finish(RoundContext context, RoundEndReason reason) {
        return RoundResult.empty();
    }

    default void reset(RoundContext context) {
    }

    default EventDecision onMove(RoundContext context, PlayerMoveEvent event) { return EventDecision.PASS; }
    default EventDecision onInteract(RoundContext context, PlayerInteractEvent event) { return EventDecision.DENY; }
    default EventDecision onBlockBreak(RoundContext context, BlockBreakEvent event) { return EventDecision.DENY; }
    default EventDecision onBlockPlace(RoundContext context, BlockPlaceEvent event) { return EventDecision.DENY; }
    default EventDecision onDamage(RoundContext context, EntityDamageEvent event) { return EventDecision.DENY; }
    default EventDecision onDropItem(RoundContext context, PlayerDropItemEvent event) { return EventDecision.DENY; }
    default EventDecision onInventoryClick(RoundContext context, InventoryClickEvent event) { return EventDecision.DENY; }
    default EventDecision onInventoryDrag(RoundContext context, InventoryDragEvent event) { return EventDecision.DENY; }
}
