package hex.velocityfallback.model;

import java.util.List;
import java.util.Map;

public record FallbackConfig(
        FallbackTargetConfig defaultTarget,
        Map<String, FallbackTargetConfig> sourceRoutes,
        boolean redirectOnConnectFailure,
        boolean redirectOnEmptyReason,
        List<String> reasonKeywords
) {
}
