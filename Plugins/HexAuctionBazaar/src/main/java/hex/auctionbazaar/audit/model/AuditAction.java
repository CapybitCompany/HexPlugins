package hex.auctionbazaar.audit.model;

/** Typy zdarzen zapisywanych w logu audytowym rynku. */
public final class AuditAction {
    public static final String AUCTION_LISTING_CREATED = "AUCTION_LISTING_CREATED";
    public static final String AUCTION_LISTING_BOUGHT = "AUCTION_LISTING_BOUGHT";
    public static final String AUCTION_LISTING_CANCELLED = "AUCTION_LISTING_CANCELLED";
    public static final String AUCTION_LISTING_EXPIRED = "AUCTION_LISTING_EXPIRED";
    public static final String AUCTION_CLEANUP = "AUCTION_CLEANUP";
    public static final String AUCTION_CLAIM_COLLECTED = "AUCTION_CLAIM_COLLECTED";

    public static final String BAZAAR_INSTANT_BUY = "BAZAAR_INSTANT_BUY";
    public static final String BAZAAR_INSTANT_SELL = "BAZAAR_INSTANT_SELL";
    public static final String BAZAAR_BUY_ORDER_PLACED = "BAZAAR_BUY_ORDER_PLACED";
    public static final String BAZAAR_SELL_OFFER_PLACED = "BAZAAR_SELL_OFFER_PLACED";
    public static final String BAZAAR_ORDER_PARTIAL_FILL = "BAZAAR_ORDER_PARTIAL_FILL";
    public static final String BAZAAR_ORDER_FILLED = "BAZAAR_ORDER_FILLED";
    public static final String BAZAAR_ORDER_CANCELLED = "BAZAAR_ORDER_CANCELLED";
    public static final String BAZAAR_REFUND = "BAZAAR_REFUND";

    public static final String ADMIN_RELOAD = "ADMIN_RELOAD";
    public static final String ADMIN_ACTION = "ADMIN_ACTION";

    public static final String MARKET_AUCTION = "AUCTION";
    public static final String MARKET_BAZAAR = "BAZAAR";
    public static final String MARKET_ADMIN = "ADMIN";

    public static final String RESULT_OK = "OK";
    public static final String RESULT_FAILED = "FAILED";
    public static final String RESULT_ROLLBACK = "ROLLBACK";
    public static final String RESULT_REFUND_PENDING = "REFUND_PENDING";

    private AuditAction() {
    }
}
