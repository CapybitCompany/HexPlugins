package hex.limbo.listener;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.util.GameProfile;
import hex.limbo.account.Account;
import hex.limbo.account.AccountRepository;
import hex.limbo.uuid.FakeUuidService;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * For cracked connections we override the offline UUID with the stable UUID stored in MySQL so the
 * backend always sees the same identity. For premium connections Velocity already filled in the
 * Mojang profile; we leave it untouched.
 *
 * <p>The repository lookup happens via {@link EventTask#async(Runnable)} so we never block the
 * netty thread on a database call.
 *
 * <p>Velocity API limitation: {@code GameProfileRequestEvent} is the only event where Velocity
 * lets us mutate the UUID before it reaches the backend. Mutating it after this point (e.g. in
 * {@code LoginEvent}) is not supported and silently does nothing.
 */
public final class GameProfileListener {

    private final AccountRepository repository;
    private final FakeUuidService fakeUuidService;
    private final Logger logger;

    public GameProfileListener(AccountRepository repository, FakeUuidService fakeUuidService, Logger logger) {
        this.repository = repository;
        this.fakeUuidService = fakeUuidService;
        this.logger = logger;
    }

    @Subscribe
    public EventTask onGameProfileRequest(GameProfileRequestEvent event) {
        if (event.isOnlineMode()) {
            return null;
        }
        return EventTask.async(() -> {
            String username = event.getUsername();
            UUID resolvedUuid;
            try {
                Optional<Account> existing = repository.findByUsername(username);
                resolvedUuid = existing.map(Account::uuid).orElseGet(() -> fakeUuidService.forName(username));
            } catch (RuntimeException ex) {
                logger.warn("Could not look up account for '{}'; falling back to deterministic offline UUID. {}", username, ex.getMessage());
                resolvedUuid = fakeUuidService.forName(username);
            }
            GameProfile original = event.getOriginalProfile();
            if (!resolvedUuid.equals(original.getId())) {
                event.setGameProfile(original.withId(resolvedUuid));
            }
        });
    }
}
