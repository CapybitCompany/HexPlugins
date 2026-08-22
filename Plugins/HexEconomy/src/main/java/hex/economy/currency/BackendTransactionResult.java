package hex.economy.currency;

/** Normalized result from the integer-only HEX_COINS backend. */
public record BackendTransactionResult(boolean success, int balance, String reason) {
    public static BackendTransactionResult ok(int balance) {
        return new BackendTransactionResult(true, balance, "OK");
    }
    public static BackendTransactionResult fail(int balance, String reason) {
        return new BackendTransactionResult(false, balance, reason);
    }
}
