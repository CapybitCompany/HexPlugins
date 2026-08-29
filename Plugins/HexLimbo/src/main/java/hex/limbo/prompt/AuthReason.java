package hex.limbo.prompt;

/**
 * Why a player counts as authenticated. Recorded explicitly at the exact moment authentication
 * succeeds so the lobby greeting can be chosen without guessing from side effects: by the time the
 * player reaches the target server, {@link hex.limbo.auth.AuthState.Stage} can no longer tell a
 * manual {@code /login} apart from a session auto-login (both end in {@code AUTHENTICATED_CRACKED}).
 *
 * <p>Each constant maps to its own block of message keys, so every path can be reworded
 * independently in {@code messages.yml}. The two limbo-skipping paths ({@link #PREMIUM} and
 * {@link #ADMIN_BYPASS}) additionally have their own on/off switch in {@code config.yml}; see
 * {@code prompts.premium-success-enabled} and {@code prompts.admin-bypass-success-enabled}.
 */
public enum AuthReason {

    /** Player typed {@code /login <hasło>} and the password verified. */
    MANUAL_LOGIN("prompts.success."),

    /** Player completed {@code /register <hasło> <hasło>}. Shares the wording of a manual login. */
    REGISTER("prompts.success."),

    /** A still-valid session for this UUID + IP hash auto-authenticated the player on join. */
    SESSION("prompts.session-success."),

    /** Mojang-verified premium player; Velocity's online-mode handshake did the authentication. */
    PREMIUM("prompts.premium-success."),

    /** Player holds the configured admin-bypass permission and skipped the auth flow entirely. */
    ADMIN_BYPASS("prompts.premium-skip.");

    private final String messagePrefix;

    AuthReason(String messagePrefix) {
        this.messagePrefix = messagePrefix;
    }

    /** Message-key prefix for this path, e.g. {@code prompts.session-success.}. */
    public String messagePrefix() {
        return messagePrefix;
    }

    /** Key of the chat line shown once the player reaches the target server. */
    public String chatKey() {
        return messagePrefix + "chat";
    }

    /** Key of the center-screen title shown once the player reaches the target server. */
    public String titleKey() {
        return messagePrefix + "title";
    }

    /** Key of the subtitle shown once the player reaches the target server. */
    public String subtitleKey() {
        return messagePrefix + "subtitle";
    }

}
