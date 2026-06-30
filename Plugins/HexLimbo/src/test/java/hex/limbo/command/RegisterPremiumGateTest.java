package hex.limbo.command;

import hex.limbo.account.InMemoryAccountRepository;
import hex.limbo.auth.AuthService;
import hex.limbo.auth.AuthState;
import hex.limbo.auth.PasswordHasher;
import hex.limbo.config.MessagesConfig;
import hex.limbo.config.PluginConfig;
import hex.limbo.config.RuntimeContext;
import hex.limbo.premium.PremiumResolver;
import hex.limbo.security.RateLimiter;
import hex.limbo.testsupport.TestConfigs;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises the same decision a {@code /register} dispatch makes, without spinning up Velocity:
 * when {@code premium.enabled = false}, the Mojang resolver must NOT be consulted, and the
 * registration falls through to the normal cracked path with {@code nameIsPremium=false}.
 */
class RegisterPremiumGateTest {

    private PluginConfig withPremiumEnabled(boolean enabled) {
        PluginConfig base = TestConfigs.defaultConfig();
        return new PluginConfig(
                base.targetServer(),
                base.loginTimeoutSeconds(),
                base.adminBypassPermission(),
                List.copyOf(base.allowedCommandsUnauthenticated()),
                base.database(),
                base.session(),
                base.security(),
                new PluginConfig.Premium(
                        enabled,
                        base.premium().cacheTtlSeconds(),
                        base.premium().cacheMaxEntries(),
                        base.premium().httpTimeoutMs(),
                        base.premium().failOpenOnCheckError()
                ),
                base.limbo()
        );
    }

    private AuthService authService(RuntimeContext context) {
        return new AuthService(
                new InMemoryAccountRepository(),
                new PasswordHasher(4),
                new RateLimiter(10, 60_000L),
                context,
                LoggerFactory.getLogger(RegisterPremiumGateTest.class)
        );
    }

    private boolean queryResolver(PluginConfig config, PremiumResolver resolver, String name) {
        if (!config.premium().enabled()) {
            return false;
        }
        return resolver.resolve(name).isPremium();
    }

    @Test
    void resolverNotConsultedWhenPremiumDisabled() {
        RuntimeContext context = new RuntimeContext(withPremiumEnabled(false), new MessagesConfig(Map.of()));
        AuthService service = authService(context);
        AtomicInteger resolverCalls = new AtomicInteger();
        PremiumResolver resolver = name -> {
            resolverCalls.incrementAndGet();
            return PremiumResolver.Result.premium(UUID.randomUUID(), name);
        };
        UUID uuid = UUID.nameUUIDFromBytes("Notch".getBytes());
        AuthState state = new AuthState(uuid, "Notch", "ip-hash", AuthState.Stage.UNREGISTERED, hex.limbo.account.AccountType.CRACKED);
        service.trackConnection(state);

        boolean nameIsPremium = queryResolver(context.config(), resolver, "Notch");
        AuthService.RegisterOutcome outcome = service.attemptRegister(uuid, "verylongpw", "verylongpw", nameIsPremium);

        assertEquals(0, resolverCalls.get(), "Resolver must not be consulted in offline-only mode.");
        assertEquals(AuthService.RegisterOutcome.SUCCESS, outcome);
    }

    @Test
    void resolverConsultedWhenPremiumEnabledAndBlocksPremiumName() {
        RuntimeContext context = new RuntimeContext(withPremiumEnabled(true), new MessagesConfig(Map.of()));
        AuthService service = authService(context);
        AtomicInteger resolverCalls = new AtomicInteger();
        PremiumResolver resolver = name -> {
            resolverCalls.incrementAndGet();
            return PremiumResolver.Result.premium(UUID.randomUUID(), name);
        };
        UUID uuid = UUID.nameUUIDFromBytes("Notch2".getBytes());
        AuthState state = new AuthState(uuid, "Notch2", "ip-hash", AuthState.Stage.UNREGISTERED, hex.limbo.account.AccountType.CRACKED);
        service.trackConnection(state);

        boolean nameIsPremium = queryResolver(context.config(), resolver, "Notch2");
        AuthService.RegisterOutcome outcome = service.attemptRegister(uuid, "verylongpw", "verylongpw", nameIsPremium);

        assertEquals(1, resolverCalls.get());
        assertEquals(AuthService.RegisterOutcome.PREMIUM_NAME_PROTECTED, outcome);
    }
}
