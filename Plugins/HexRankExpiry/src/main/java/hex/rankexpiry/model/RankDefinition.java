package hex.rankexpiry.model;

import java.util.Locale;

public record RankDefinition(String permission, String displayName) {
    public RankDefinition {
        permission = permission == null ? "" : permission.toLowerCase(Locale.ROOT).trim();
        displayName = displayName == null ? permission : displayName;
    }
}
