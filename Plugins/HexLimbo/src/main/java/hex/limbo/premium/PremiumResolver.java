package hex.limbo.premium;

import java.util.Optional;
import java.util.UUID;

/**
 * Decides whether a name belongs to a verified Mojang/Microsoft premium account.
 *
 * <p>The result is tri-state:
 * <ul>
 *     <li>{@link Status#PREMIUM} – Mojang confirms the name is owned.</li>
 *     <li>{@link Status#NOT_PREMIUM} – Mojang confirms the name is free.</li>
 *     <li>{@link Status#UNKNOWN} – the resolver could not get a definitive answer (network failure,
 *     5xx/429, malformed body, etc). Callers must treat UNKNOWN as a fail-closed signal: deny
 *     login / registration unless explicitly configured to fail-open.</li>
 * </ul>
 */
public interface PremiumResolver {

    enum Status { PREMIUM, NOT_PREMIUM, UNKNOWN }

    record Result(Status status, Optional<UUID> premiumUuid, Optional<String> canonicalName) {
        public static Result premium(UUID uuid, String name) {
            return new Result(Status.PREMIUM, Optional.ofNullable(uuid), Optional.ofNullable(name));
        }

        public static Result notPremium() {
            return new Result(Status.NOT_PREMIUM, Optional.empty(), Optional.empty());
        }

        public static Result unknown() {
            return new Result(Status.UNKNOWN, Optional.empty(), Optional.empty());
        }

        public boolean isPremium() {
            return status == Status.PREMIUM;
        }

        public boolean isNotPremium() {
            return status == Status.NOT_PREMIUM;
        }

        public boolean isUnknown() {
            return status == Status.UNKNOWN;
        }
    }

    Result resolve(String username);
}
