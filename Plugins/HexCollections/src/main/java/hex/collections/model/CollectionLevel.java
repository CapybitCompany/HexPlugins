package hex.collections.model;

import java.util.List;
import java.util.Map;

public record CollectionLevel(int level, long required, String displayName, Map<String, String> guiMaterials, List<Map<String, Object>> rewards) {
}

