package hexnpc.guide.model;

import java.util.Objects;

public record GuideEntry(String id, int slot, GuideIcon icon, String target) {
    public GuideEntry {
        id = Objects.requireNonNull(id, "id");
        icon = Objects.requireNonNull(icon, "icon");
        target = target == null || target.isBlank() ? null : target.trim().toLowerCase();
    }

    public boolean navigates() {
        return target != null;
    }
}
