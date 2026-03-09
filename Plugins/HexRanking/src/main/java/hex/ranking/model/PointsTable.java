package hex.ranking.model;

import java.util.Locale;

public enum PointsTable {
    GLOBAL("global", "global_points"),
    SEASON("season", "season_points");

    private final String argument;
    private final String column;

    PointsTable(String argument, String column) {
        this.argument = argument;
        this.column = column;
    }

    public String argument() {
        return argument;
    }

    public String column() {
        return column;
    }

    public static PointsTable fromArgument(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException("Tabela nie moze byc pusta. Uzyj: global albo season.");
        }

        String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "global", "global_points" -> GLOBAL;
            case "season", "season_points" -> SEASON;
            default -> throw new IllegalArgumentException("Nieznana tabela '" + rawValue + "'. Uzyj: global albo season.");
        };
    }
}
