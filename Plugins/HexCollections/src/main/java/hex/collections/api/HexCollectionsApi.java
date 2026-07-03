package hex.collections.api;

import java.util.Map;
import java.util.UUID;

public interface HexCollectionsApi {
    long getAmount(UUID townId, String collectionId);
    int getLevel(UUID townId, String collectionId);
    double getProgressPercent(UUID townId, String collectionId);
    boolean hasUnlocked(UUID townId, String collectionId, int level);
    int getMaxLevel(String collectionId);
    CollectionAddResult addProgress(CollectionProgressContext context);
    void loadTown(UUID townId);
    void flushTown(UUID townId);
    void unloadTown(UUID townId);
    void deleteTownCollectionData(UUID townId);
    Map<String, CollectionProgress> getAllCollections(UUID townId);
}

