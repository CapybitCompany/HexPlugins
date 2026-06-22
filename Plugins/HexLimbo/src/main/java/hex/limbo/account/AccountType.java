package hex.limbo.account;

/**
 * Account type. PREMIUM accounts are owned by verified Mojang players and keep their real UUID.
 * CRACKED accounts authenticate via password and receive a stable fake UUID.
 * PENDING_MIGRATION marks a cracked account that has requested an upgrade to premium via
 * {@code /premium} and will be migrated on the next successful premium login.
 */
public enum AccountType {
    PREMIUM,
    CRACKED,
    PENDING_MIGRATION
}
