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

    /**
     * Look-At: dreht die gerenderte NPC-Entity fuer alle Viewer temporaer auf die
     * uebergebene Rotation (Head-Look + Body). Aendert NICHT die gespeicherte Location;
     * rein packet-seitig. No-op, wenn der NPC nicht (mehr) gerendert ist.
     */
    void lookAt(NpcId id, float yaw, float pitch);

    /**
     * Setzt die Rotation der gerenderten NPC-Entity fuer alle Viewer auf die gespeicherte
     * yaw/pitch zurueck (z.B. wenn kein Spieler mehr in Range ist). Rein packet-seitig.
     */
    void resetLook(NpcId id);

    void showTo(Player player);

    void hideFrom(Player player);

    Optional<NpcHandle> handle(NpcId id);

    /**
     * Reverse-lookup used by the click listener. Returns Optional.empty()
     * if the entityId does not belong to an NPC owned by this renderer.
     */
    Optional<NpcId> lookupByEntityId(int entityId);
}
