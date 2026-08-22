package hexnpc.model;

import java.util.List;

public record NpcActions(
        List<NpcAction> onClick,
        List<NpcAction> onProximity
) {
    public NpcActions {
        onClick = onClick == null ? List.of() : List.copyOf(onClick);
        onProximity = onProximity == null ? List.of() : List.copyOf(onProximity);
    }

    public static NpcActions empty() {
        return new NpcActions(List.of(), List.of());
    }

    public boolean isEmpty() {
        return onClick.isEmpty() && onProximity.isEmpty();
    }

    public List<NpcAction> forTrigger(InteractionTrigger trigger) {
        return switch (trigger) {
            case CLICK -> onClick;
            case PROXIMITY -> onProximity;
        };
    }

    public NpcActions withOnClick(List<NpcAction> newOnClick) {
        return new NpcActions(newOnClick, onProximity);
    }

    public NpcActions withOnProximity(List<NpcAction> newOnProximity) {
        return new NpcActions(onClick, newOnProximity);
    }
}
