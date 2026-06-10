package hexnpc.render;

import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * Renders player-skin NPCs. Implementations encapsulate all
 * packet / NMS specific behaviour; business code only sees this interface.
 */
public interface NpcRenderer {

    void start();

    void stop();

    NpcHandle spawn(NpcDefinition definition);

    void despawn(NpcId id);

    void move(NpcDefinition updated);

    void rotate(NpcDefinition updated);

    void showTo(Player player);

    void hideFrom(Player player);

    Optional<NpcHandle> handle(NpcId id);

    /**
     * Reverse-lookup used by the click listener. Returns Optional.empty()
     * if the entityId does not belong to an NPC owned by this renderer.
     */
    Optional<NpcId> lookupByEntityId(int entityId);
}
