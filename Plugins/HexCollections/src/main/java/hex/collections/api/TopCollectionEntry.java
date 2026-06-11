package hex.collections.api;

import java.util.UUID;

public record TopCollectionEntry(UUID townId, String collectionId, long amount, int level) {
}
