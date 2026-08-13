package hexcustomitems.listener;

import hexcustomitems.service.SpecialItemActionService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;

public final class CustomItemsMiningListener implements Listener {

    private final SpecialItemActionService specialActions;

    public CustomItemsMiningListener(SpecialItemActionService specialActions) {
        this.specialActions = specialActions;
    }

    @EventHandler
    public void onBlockDrop(BlockDropItemEvent event) {
        specialActions.handleBlockDrops(event);
    }
}
