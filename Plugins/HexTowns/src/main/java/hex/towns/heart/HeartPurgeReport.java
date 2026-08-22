package hex.towns.heart;

import java.util.UUID;

public record HeartPurgeReport(
        UUID townId,
        int matchedEntities,
        int removedEntities,
        boolean dryRun,
        HeartReconciliationReport scan
) {
}
