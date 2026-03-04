package hex.core.api.db;

public final class SqlException extends RuntimeException {
    public SqlException(String message, Throwable cause) {
        super(message, cause);
    }
}
