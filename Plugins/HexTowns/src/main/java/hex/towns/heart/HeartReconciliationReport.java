package hex.towns.heart;

import java.util.List;

public record HeartReconciliationReport(
        int chunksScanned,
        int heartEntities,
        int validGroups,
        int orphanGroups,
        int duplicateGroups,
        int malformedGroups,
        int orphanEntitiesRemoved,
        int duplicateEntitiesRemoved,
        int removedEntities,
        List<HeartVisualGroup> groups
) {
    public HeartReconciliationReport {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    public static HeartReconciliationReport empty() {
        return new HeartReconciliationReport(0, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
    }
}
