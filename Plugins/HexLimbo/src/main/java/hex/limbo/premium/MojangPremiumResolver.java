package hex.limbo.premium;

import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Calls {@code https://api.mojang.com/users/profiles/minecraft/<name>} to find out whether a
 * username is a premium account. Tri-state semantics:
 * <ul>
 *     <li>HTTP 200 with a parseable body containing {@code id} → PREMIUM.</li>
 *     <li>HTTP 200 with a body we cannot parse → UNKNOWN (fail-closed).</li>
 *     <li>HTTP 204 / 404 → NOT_PREMIUM (Mojang's free-name signal).</li>
 *     <li>5xx, 429, timeout, network error, malformed response → UNKNOWN.</li>
 * </ul>
 * Callers (PreLogin, RegisterCommand) decide what to do with UNKNOWN. The historical fail-open
 * behaviour was a security hole: during a Mojang outage a cracked client could register a premium
 * name.
 */
public final class MojangPremiumResolver implements PremiumResolver {

    private static final String API = "https://api.mojang.com/users/profiles/minecraft/";

    private final HttpClient httpClient;
    private final Duration timeout;
    private final Logger logger;

    public MojangPremiumResolver(long httpTimeoutMs, Logger logger) {
        this.timeout = Duration.ofMillis(Math.max(500L, httpTimeoutMs));
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
    }

    @Override
    public Result resolve(String username) {
        if (username == null || username.isBlank()) {
            return Result.notPremium();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API + username))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 200) {
                MojangProfileParser.Profile profile = MojangProfileParser.parse(response.body());
                if (profile.id().isPresent()) {
                    return Result.premium(profile.id().get(), profile.name().orElse(username));
                }
                logger.warn("Mojang premium check for '{}' returned 200 but body did not parse; treating as UNKNOWN.", username);
                return Result.unknown();
            }
            if (status == 204 || status == 404) {
                return Result.notPremium();
            }
            logger.warn("Mojang premium check for '{}' returned unexpected status {}; treating as UNKNOWN.", username, status);
            return Result.unknown();
        } catch (Exception ex) {
            logger.warn("Mojang premium check failed for '{}': {} -> UNKNOWN", username, ex.getMessage());
            return Result.unknown();
        }
    }
}
