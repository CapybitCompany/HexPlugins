package hex.minigames.runtime;

public enum SessionMode {
    EVENT,
    ADMIN_TEST,
    DEVELOPMENT_TEST;

    public String statusLabel() {
        return switch (this) {
            case EVENT -> "PRODUCTION/HexEvents";
            case ADMIN_TEST -> "ADMIN_TEST";
            case DEVELOPMENT_TEST -> "TEST/DEVELOPMENT";
        };
    }
}
