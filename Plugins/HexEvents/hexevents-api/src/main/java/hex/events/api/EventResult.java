package hex.events.api;

import java.util.List;
import java.util.Map;

public record EventResult(EventOutcome outcome, List<ResultSubject> subjects, Map<String, String> metadata) {
    public EventResult {
        subjects = subjects == null ? List.of() : List.copyOf(subjects);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
    public static EventResult success(List<ResultSubject> subjects) { return new EventResult(EventOutcome.SUCCESS, subjects, Map.of()); }
}
