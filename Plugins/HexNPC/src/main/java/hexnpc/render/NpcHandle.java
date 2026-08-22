package hexnpc.render;

import hexnpc.model.NpcId;

/**
 * Opaque handle to a spawned NPC, owned by the renderer.
 * Business code only reads {@link #id()} and {@link #entityId()};
 * everything else is renderer-internal.
 */
public interface NpcHandle {
    NpcId id();

    int entityId();
}
