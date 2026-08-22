package hex.minions.config;

public record DynamicCollectionCost(
        String collectionId,
        double percentOfTarget,
        String resourceId,
        double resourcePerCollectionUnit
) {
    public DynamicCollectionCost {
        collectionId = collectionId == null ? "" : collectionId;
        percentOfTarget = Math.max(0.0D, percentOfTarget);
        resourceId = resourceId == null ? "" : resourceId;
        resourcePerCollectionUnit = Math.max(0.0D, resourcePerCollectionUnit);
    }

    public boolean enabled() {
        return !collectionId.isBlank() && percentOfTarget > 0.0D && !resourceId.isBlank() && resourcePerCollectionUnit > 0.0D;
    }
}
