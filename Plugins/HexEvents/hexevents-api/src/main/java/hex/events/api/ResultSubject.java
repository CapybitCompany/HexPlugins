package hex.events.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record ResultSubject(
        ResultSubjectType type,
        UUID id,
        Map<String, Double> metrics,
        Set<String> tags
) {
    public ResultSubject {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }
}
