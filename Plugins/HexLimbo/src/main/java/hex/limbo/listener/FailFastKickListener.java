package hex.limbo.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import net.kyori.adventure.text.Component;

import java.util.function.Supplier;

/**
 * Standalone listener used only when HexLimbo has refused to start (database fail-fast, or a fatal
 * initialization error). It denies every {@link PreLoginEvent} with a clear message so the proxy
 * never lets unauthenticated players slip past a broken auth layer.
 *
 * <p>The message is read through a {@link Supplier} so the live messages.yml value is used when
 * {@code RuntimeContext} is available, and a hard-coded fallback is used when it isn't.
 */
public final class FailFastKickListener {

    private final Supplier<Component> reasonSupplier;

    public FailFastKickListener(Supplier<Component> reasonSupplier) {
        this.reasonSupplier = reasonSupplier;
    }

    @Subscribe
    public EventTask onPreLogin(PreLoginEvent event) {
        if (!event.getResult().isAllowed()) {
            return null;
        }
        return EventTask.async(() -> event.setResult(
                PreLoginEvent.PreLoginComponentResult.denied(reasonSupplier.get())));
    }
}
