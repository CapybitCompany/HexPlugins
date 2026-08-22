package hexnpc.guide.model;

import java.util.Map;
import java.util.Objects;

public record GuideMenu(String id, String title, int size, String parent, int backSlot,
                        GuideBackground background, Map<Integer, GuideEntry> entries) {
    public GuideMenu {
        id = Objects.requireNonNull(id, "id");
        title = title == null ? "" : title;
        parent = parent == null || parent.isBlank() ? null : parent.trim().toLowerCase();
        background = background == null ? GuideBackground.defaults() : background;
        entries = entries == null ? Map.of() : Map.copyOf(entries);
    }

    public boolean hasParent() {
        return parent != null;
    }
}
