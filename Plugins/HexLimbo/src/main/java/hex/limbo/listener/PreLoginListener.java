package hex.limbo.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import hex.limbo.config.RuntimeContext;
import hex.limbo.premium.PremiumResolver;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

/**
 * Decides per-player whether Velocity should treat the connection as online-mode (Mojang verified)
 * or offline-mode (cracked). This is the only safe place to mix modes per player in Velocity 3.3+.
 *
 * <p>The Mojang lookup is wrapped in {@link EventTask#async(Runnable)} so the HTTP call happens
 * off the netty thread. UNKNOWN results are treated as fail-closed (login denied) unless
 * {@code premium.fail-open-on-check-error} is true.
 */
public final class PreLoginListener {

    private final PremiumResolver premiumResolver;
    private final RuntimeContext context;
    private final Logger logger;

    public PreLoginListener(PremiumResolver premiumResolver, RuntimeContext context, Logger logger) {
        this.premiumResolver = premiumResolver;
        this.context = context;
        this.logger = logger;
    }

    @Subscribe
    public EventTask onPreLogin(PreLoginEvent event) {
        if (!event.getResult().isAllowed()) {
            return null;
        }
        String username = event.getUsername();
        if (username == null || username.isBlank()) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    Component.text(context.messages().raw("disconnect.invalid-name"))));
            return null;
        }

        if (!context.config().premium().enabled()) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
            return null;
        }

        return EventTask.async(() -> {
            PremiumResolver.Result resolved = premiumResolver.resolve(username);
            switch (resolved.status()) {
                case PREMIUM -> event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
                case NOT_PREMIUM -> event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
                case UNKNOWN -> {
                    if (context.config().premium().failOpenOnCheckError()) {
                        logger.warn("Premium check UNKNOWN for '{}' – fail-open enabled, treating as cracked.", username);
                        event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
                    } else {
                        event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                                Component.text(context.messages().raw("disconnect.premium-check-unavailable"))));
                    }
                }
            }
        });
    }
}
