package hex.limbo.config;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the currently-active {@link PluginConfig} and {@link MessagesConfig} behind atomic
 * references so {@code /hexlimbo reload} actually swaps the live values seen by listeners and
 * commands. Components that need to react to config changes read through {@link #config()} and
 * {@link #messages()} on every invocation rather than caching the references in their fields.
 *
 * <p>Only fields that can be safely hot-reloaded live here. Database connection settings are not
 * – those require a full plugin restart and that is documented in {@code README.md}.
 */
public final class RuntimeContext {

    private final AtomicReference<PluginConfig> config;
    private final AtomicReference<MessagesConfig> messages;

    public RuntimeContext(PluginConfig config, MessagesConfig messages) {
        this.config = new AtomicReference<>(config);
        this.messages = new AtomicReference<>(messages);
    }

    public PluginConfig config() {
        return config.get();
    }

    public MessagesConfig messages() {
        return messages.get();
    }

    public void update(PluginConfig newConfig, MessagesConfig newMessages) {
        this.config.set(newConfig);
        this.messages.set(newMessages);
    }
}
