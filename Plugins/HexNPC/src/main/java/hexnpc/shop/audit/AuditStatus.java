package hexnpc.shop.audit;

/** Wynik transakcji sklepu w logu audytu. */
public enum AuditStatus {
    SUCCESS,
    DENIED,
    FAILED,
    REFUNDED,
    REFUND_FAILED,
    ROLLED_BACK
}
