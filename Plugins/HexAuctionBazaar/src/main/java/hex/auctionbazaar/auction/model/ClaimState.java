package hex.auctionbazaar.auction.model;

/**
 * Lifecycle of a claim row.
 *  PENDING  - waiting to be picked up; eligible for consumption.
 *  CLAIMING - reserved by exactly one in-flight payout attempt.
 *
 * Successful payout DELETEs the row.
 * Failed payout transitions CLAIMING -&gt; PENDING (rollback) so the player
 * can try again. The claim is never lost.
 */
public enum ClaimState {
    PENDING,
    CLAIMING
}
