package hex.events.registration;

import org.bukkit.entity.Player;

public final class EventQueuePriorityResolver {
    public EventQueuePriority resolve(Player player) {
        if (player == null) return EventQueuePriority.NORMAL;
        if (player.hasPermission(EventQueuePriority.MEDIA.permission())) return EventQueuePriority.MEDIA;
        if (player.hasPermission(EventQueuePriority.ELITA.permission())) return EventQueuePriority.ELITA;
        if (player.hasPermission(EventQueuePriority.SVIP.permission())) return EventQueuePriority.SVIP;
        if (player.hasPermission(EventQueuePriority.VIP.permission())) return EventQueuePriority.VIP;
        return EventQueuePriority.NORMAL;
    }
}
