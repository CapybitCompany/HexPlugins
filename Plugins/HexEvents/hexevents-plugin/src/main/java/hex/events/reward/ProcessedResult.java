package hex.events.reward;

import hex.events.api.EventOutcome;
import hex.events.api.ResultSubject;

import java.util.List;
import java.util.Map;

public record ProcessedResult(EventOutcome outcome, List<ResultSubject> subjects, Map<String,String> metadata) {
    public ProcessedResult { subjects = List.copyOf(subjects); metadata = Map.copyOf(metadata); }
}
