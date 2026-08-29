package hex.limbo.prompt;

import hex.limbo.auth.AuthState;
import hex.limbo.config.MessagesConfig;
import net.kyori.adventure.text.Component;

/**
 * Pure translation of an {@link AuthState.Stage} into the message keys used by the limbo prompts.
 *
 * <p>The login-vs-register decision is driven entirely by the auth state that the login pipeline
 * already computed: {@link AuthState.Stage#UNREGISTERED} means the account repository found no row
 * (so {@code /register} is the valid command), everything else that is unauthenticated means the
 * row exists and {@code /login} is valid.
 *
 * <p>All lookups go through {@link MessagesConfig#component(String, Object...)}, so the configured
 * {@code &}-codes are rendered as real colours instead of being printed literally.
 */
public final class PromptMessages {

    private PromptMessages() {}

    /** True when the player must {@code /login} (account exists); false when they must {@code /register}. */
    public static boolean isLoginStage(AuthState.Stage stage) {
        return stage != AuthState.Stage.UNREGISTERED;
    }

    /** Message-key prefix for the given stage: {@code prompts.login.} or {@code prompts.register.}. */
    public static String prefix(AuthState.Stage stage) {
        return isLoginStage(stage) ? "prompts.login." : "prompts.register.";
    }

    public static Component bossbar(MessagesConfig messages, AuthState.Stage stage) {
        return messages.component(prefix(stage) + "bossbar");
    }

    public static Component title(MessagesConfig messages, AuthState.Stage stage) {
        return messages.component(prefix(stage) + "title");
    }

    public static Component subtitle(MessagesConfig messages, AuthState.Stage stage) {
        return messages.component(prefix(stage) + "subtitle");
    }

    public static Component chat(MessagesConfig messages, AuthState.Stage stage) {
        return messages.component(prefix(stage) + "chat");
    }
}
