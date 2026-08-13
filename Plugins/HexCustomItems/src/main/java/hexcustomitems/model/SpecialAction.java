package hexcustomitems.model;

import java.util.Map;
import java.util.Objects;

/** Action implemented by plugin code for mechanics that cannot be expressed as a vanilla command/effect. */
public record SpecialAction(
        String kind,
        Map<String, String> params,
        boolean offensive
) implements ItemAction {

    public SpecialAction {
        kind = Objects.requireNonNull(kind, "kind").trim().toUpperCase(java.util.Locale.ROOT);
        params = Map.copyOf(params == null ? Map.of() : params);
    }
}
