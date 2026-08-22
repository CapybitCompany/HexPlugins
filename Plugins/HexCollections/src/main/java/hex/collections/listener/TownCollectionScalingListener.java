package hex.collections.listener;

import hex.collections.service.CollectionProgressService;
import hex.towns.api.event.TownCoopJoinedEvent;
import hex.towns.api.event.TownCoopLeftEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class TownCollectionScalingListener implements Listener {
    private final CollectionProgressService progress;

    public TownCollectionScalingListener(CollectionProgressService progress) {
        this.progress = progress;
    }

    @EventHandler
    public void onCoopJoined(TownCoopJoinedEvent event) {
        progress.onTownMemberJoined(event.town().id());
    }

    @EventHandler
    public void onCoopLeft(TownCoopLeftEvent event) {
        // Sticky roster: leave/kick never lowers the active target requirement.
    }
}
