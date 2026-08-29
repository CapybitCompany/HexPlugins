package hex.events.registration;

import hex.events.model.EventInstance;
import org.bukkit.entity.Player;

public interface RegistrationObserver {
    RegistrationObserver NOOP = new RegistrationObserver() { };
    default void onRegistered(Player player, EventInstance instance, EventQueuePriority priority) { }
    default void onCancelled(Player player, EventInstance instance) { }
    default boolean canCancel(Player player, EventInstance instance) { return true; }
}
