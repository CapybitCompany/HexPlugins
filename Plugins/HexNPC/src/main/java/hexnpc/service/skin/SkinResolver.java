package hexnpc.service.skin;

import hexnpc.model.NpcSkin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves a player name -> Mojang textures asynchronously. v1 uses
 * java.net.http.HttpClient (no extra deps), caches results in-memory by name,
 * and tolerates rate-limit / network failures (returning the original
 * name-only skin so the NPC still renders as Steve).
 *
 * Threading: HTTP runs off the main thread on HttpClient's internal executor.
 * Callers should resolve, then apply the result back on the main thread via
 * the server scheduler.
 */
public final class SkinResolver {

    private static final String UUID_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";

    private final Logger logger;
    private final HttpClient httpClient;
    private final Map<String, NpcSkin> cache = new ConcurrentHashMap<>();

    public SkinResolver(Logger logger) {
        this(logger, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    public SkinResolver(Logger logger, HttpClient httpClient) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    public Optional<NpcSkin> cached(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.get(name.toLowerCase(Locale.ROOT)));
    }

    /**
     * Returns a future that resolves to a textured NpcSkin, or to a name-only
     * skin if the Mojang lookup failed. Never throws.
     */
    public CompletableFuture<NpcSkin> resolve(String name) {
        if (name == null || name.isBlank()) {
            return CompletableFuture.completedFuture(NpcSkin.ofName(""));
        }
        String key = name.toLowerCase(Locale.ROOT);
        NpcSkin cached = cache.get(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        HttpRequest uuidRequest = HttpRequest.newBuilder()
                .uri(URI.create(UUID_URL + name))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build();
        return httpClient.sendAsync(uuidRequest, HttpResponse.BodyHandlers.ofString())
                .thenCompose(resp -> handleUuidResponse(name, resp))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "SkinResolver: name lookup failed for " + name + ": " + ex.getMessage());
                    return NpcSkin.ofName(name);
                });
    }

    public void shutdown() {
        cache.clear();
    }

    int cacheSize() {
        return cache.size();
    }

    private CompletableFuture<NpcSkin> handleUuidResponse(String name, HttpResponse<String> resp) {
        if (resp.statusCode() == 429) {
            logger.warning("SkinResolver: rate-limited by Mojang while resolving " + name);
            return CompletableFuture.completedFuture(NpcSkin.ofName(name));
        }
        if (resp.statusCode() != 200) {
            logger.warning("SkinResolver: Mojang name lookup returned " + resp.statusCode() + " for " + name);
            return CompletableFuture.completedFuture(NpcSkin.ofName(name));
        }
        String trimmedUuid = extractJsonString(resp.body(), "id");
        if (trimmedUuid == null) {
            return CompletableFuture.completedFuture(NpcSkin.ofName(name));
        }
        HttpRequest profileRequest = HttpRequest.newBuilder()
                .uri(URI.create(PROFILE_URL + trimmedUuid + "?unsigned=false"))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build();
        return httpClient.sendAsync(profileRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(profileResp -> buildSkin(name, profileResp))
                .exceptionally(ex -> {
                    logger.log(Level.WARNING, "SkinResolver: profile lookup failed for " + name + ": " + ex.getMessage());
                    return NpcSkin.ofName(name);
                });
    }

    private NpcSkin buildSkin(String name, HttpResponse<String> profileResp) {
        if (profileResp.statusCode() != 200) {
            logger.warning("SkinResolver: Mojang profile lookup returned " + profileResp.statusCode() + " for " + name);
            return NpcSkin.ofName(name);
        }
        String body = profileResp.body();
        String value = extractTexturesValue(body);
        String signature = extractTexturesSignature(body);
        if (value == null || signature == null) {
            logger.warning("SkinResolver: textures property not found in Mojang response for " + name);
            return NpcSkin.ofName(name);
        }
        NpcSkin resolved = new NpcSkin(name, value, signature);
        cache.put(name.toLowerCase(Locale.ROOT), resolved);
        return resolved;
    }

    // ---- Lightweight JSON extraction. Mojang's responses are deterministic
    // enough that we avoid pulling in a JSON library for two strings.

    private static String extractJsonString(String body, String key) {
        String needle = '"' + key + '"';
        int k = body.indexOf(needle);
        if (k < 0) {
            return null;
        }
        int colon = body.indexOf(':', k);
        if (colon < 0) {
            return null;
        }
        int firstQuote = body.indexOf('"', colon);
        if (firstQuote < 0) {
            return null;
        }
        int lastQuote = body.indexOf('"', firstQuote + 1);
        if (lastQuote < 0) {
            return null;
        }
        return body.substring(firstQuote + 1, lastQuote);
    }

    private static String extractTexturesValue(String body) {
        int texturesIdx = body.indexOf("\"textures\"");
        // Mojang properties array has objects {"name":"textures","value":"...","signature":"..."}
        // We start the search for "value" *after* the "textures" marker so we don't pick up other "value" fields.
        int searchFrom = texturesIdx < 0 ? 0 : texturesIdx;
        return extractAfter(body, searchFrom, "\"value\"");
    }

    private static String extractTexturesSignature(String body) {
        int texturesIdx = body.indexOf("\"textures\"");
        int searchFrom = texturesIdx < 0 ? 0 : texturesIdx;
        return extractAfter(body, searchFrom, "\"signature\"");
    }

    private static String extractAfter(String body, int from, String key) {
        int k = body.indexOf(key, from);
        if (k < 0) {
            return null;
        }
        int colon = body.indexOf(':', k);
        if (colon < 0) {
            return null;
        }
        int firstQuote = body.indexOf('"', colon);
        if (firstQuote < 0) {
            return null;
        }
        int lastQuote = body.indexOf('"', firstQuote + 1);
        if (lastQuote < 0) {
            return null;
        }
        return body.substring(firstQuote + 1, lastQuote);
    }

    /** Test seam: pre-populate the cache. */
    public void seedCache(String name, NpcSkin skin) {
        if (name == null || skin == null) {
            return;
        }
        cache.put(name.toLowerCase(Locale.ROOT), skin);
    }
}
