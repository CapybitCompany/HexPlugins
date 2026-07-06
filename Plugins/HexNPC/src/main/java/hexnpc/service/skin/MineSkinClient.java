package hexnpc.service.skin;

import hexnpc.config.HexNpcConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Duenner MineSkin-v2-Client. Loest eine Skin-PNG-URL bzw. eine vorhandene
 * MineSkin-Skin-UUID zu signierten {@link TextureData} auf.
 *
 * <p>Gemaess MineSkin-Docs wird {@code /v2/generate} vermieden. Stattdessen:
 * <ul>
 *   <li>URL: {@code POST /v2/queue} und anschliessendes Status-Polling
 *       ({@code GET /v2/queue/:jobId}) bis der Job fertig ist, dann Abruf der
 *       Skin ueber die zurueckgegebene Skin-UUID.</li>
 *   <li>UUID: {@code GET /v2/skins/:uuid} direkt.</li>
 * </ul>
 *
 * <p>Der HTTP-Transport ist ueber {@link Http} abstrahiert, damit Tests die API
 * mocken koennen. Alle Fehlerfaelle liefern {@link Optional#empty()} (der Aufrufer
 * behaelt dann den alten/Default-Skin) und werden geloggt — niemals eine Exception
 * nach aussen, damit der Server nicht blockiert/crasht.
 */
public final class MineSkinClient {

    /** HTTP-Transport-Seam. Rueckgabe muss non-null sein und darf nicht werfen. */
    public interface Http {
        CompletableFuture<Response> get(String url, Map<String, String> headers);

        CompletableFuture<Response> post(String url, String jsonBody, Map<String, String> headers);
    }

    public record Response(int status, String body) {
        public boolean isOk() {
            return status >= 200 && status < 300;
        }
    }

    private final HexNpcConfig.Skins.MineSkin config;
    private final Logger logger;
    private final Http http;
    private final Executor delayExecutor;

    public MineSkinClient(HexNpcConfig.Skins.MineSkin config, Logger logger, Http http) {
        this(config, logger, http, CompletableFuture.delayedExecutor(
                Math.max(100L, config == null ? 2000L : config.pollIntervalMillis()),
                TimeUnit.MILLISECONDS));
    }

    MineSkinClient(HexNpcConfig.Skins.MineSkin config, Logger logger, Http http, Executor delayExecutor) {
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.http = Objects.requireNonNull(http, "http");
        this.delayExecutor = Objects.requireNonNull(delayExecutor, "delayExecutor");
    }

    /** Default-Transport auf Basis von {@link java.net.http.HttpClient}. */
    public static Http defaultHttp(HttpClient client, int timeoutSeconds) {
        Objects.requireNonNull(client, "client");
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        return new Http() {
            @Override
            public CompletableFuture<Response> get(String url, Map<String, String> headers) {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET();
                headers.forEach(b::header);
                return send(client, b.build());
            }

            @Override
            public CompletableFuture<Response> post(String url, String jsonBody, Map<String, String> headers) {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
                headers.forEach(b::header);
                return send(client, b.build());
            }

            private CompletableFuture<Response> send(HttpClient c, HttpRequest req) {
                return c.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                        .thenApply(r -> new Response(r.statusCode(), r.body()));
            }
        };
    }

    /** Loest eine vorhandene MineSkin-Skin-UUID ueber {@code GET /v2/skins/:uuid} auf. */
    public CompletableFuture<Optional<TextureData>> fromUuid(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String url = config.baseUrl() + "/v2/skins/" + uuid.trim();
        return http.get(url, headers())
                .thenApply(resp -> mapSkinResponse("skins/" + uuid, resp))
                .exceptionally(ex -> fail("skins/" + uuid, ex));
    }

    /** Loest eine Skin-PNG-URL ueber {@code POST /v2/queue} + Polling auf. */
    public CompletableFuture<Optional<TextureData>> fromUrl(String skinUrl) {
        if (skinUrl == null || skinUrl.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String url = config.baseUrl() + "/v2/queue";
        String body = "{\"url\":\"" + escapeJson(skinUrl.trim()) + "\",\"visibility\":\"public\"}";
        return http.post(url, body, headers())
                .thenCompose(resp -> handleQueueResponse(skinUrl, resp))
                .exceptionally(ex -> fail("queue url=" + skinUrl, ex));
    }

    private CompletableFuture<Optional<TextureData>> handleQueueResponse(String skinUrl, Response resp) {
        if (!resp.isOk()) {
            logger.warning("MineSkin: /v2/queue returned " + resp.status() + " for url=" + skinUrl);
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String body = resp.body();
        // Manche Antworten enthalten die fertige Skin bereits inline.
        Optional<TextureData> immediate = parseTextureData(body);
        if (immediate.isPresent()) {
            return CompletableFuture.completedFuture(immediate);
        }
        Optional<String> skinUuid = parseSkinUuid(body);
        if (skinUuid.isPresent()) {
            return fromUuid(skinUuid.get());
        }
        Optional<String> jobId = parseJobId(body);
        if (jobId.isEmpty()) {
            logger.warning("MineSkin: /v2/queue gave neither texture, skin uuid nor job id for url=" + skinUrl);
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return pollJob(skinUrl, jobId.get(), config.maxPollAttempts());
    }

    private CompletableFuture<Optional<TextureData>> pollJob(String skinUrl, String jobId, int attemptsLeft) {
        if (attemptsLeft <= 0) {
            logger.warning("MineSkin: job " + jobId + " did not complete in time for url=" + skinUrl);
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String url = config.baseUrl() + "/v2/queue/" + jobId;
        return CompletableFuture
                .supplyAsync(() -> null, delayExecutor)
                .thenCompose(x -> http.get(url, headers()))
                .thenCompose(resp -> {
                    if (!resp.isOk()) {
                        logger.warning("MineSkin: /v2/queue/" + jobId + " returned " + resp.status());
                        return CompletableFuture.completedFuture(Optional.<TextureData>empty());
                    }
                    String body = resp.body();
                    Optional<TextureData> texture = parseTextureData(body);
                    if (texture.isPresent()) {
                        return CompletableFuture.completedFuture(texture);
                    }
                    Optional<String> skinUuid = parseSkinUuid(body);
                    String status = parseStatus(body).orElse("");
                    if (skinUuid.isPresent() && (status.isEmpty() || status.equalsIgnoreCase("completed"))) {
                        return fromUuid(skinUuid.get());
                    }
                    if (status.equalsIgnoreCase("failed")) {
                        logger.warning("MineSkin: job " + jobId + " failed for url=" + skinUrl);
                        return CompletableFuture.completedFuture(Optional.<TextureData>empty());
                    }
                    return pollJob(skinUrl, jobId, attemptsLeft - 1);
                })
                .exceptionally(ex -> fail("queue/" + jobId, ex));
    }

    private Optional<TextureData> mapSkinResponse(String context, Response resp) {
        if (!resp.isOk()) {
            logger.warning("MineSkin: " + context + " returned " + resp.status());
            return Optional.empty();
        }
        Optional<TextureData> texture = parseTextureData(resp.body());
        if (texture.isEmpty()) {
            logger.warning("MineSkin: no texture value/signature in response for " + context);
        }
        return texture;
    }

    private Optional<TextureData> fail(String context, Throwable ex) {
        logger.log(Level.WARNING, "MineSkin: request failed for " + context + ": " + ex.getMessage());
        return Optional.empty();
    }

    private Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        h.put("Content-Type", "application/json");
        h.put("User-Agent", config.userAgent());
        if (config.hasApiKey()) {
            h.put("Authorization", "Bearer " + config.apiKey());
        }
        return h;
    }

    // ---- Parsing. MineSkin v2 liefert JSON wie
    //   { "skin": { "uuid": "...", "texture": { "data": { "value": "...", "signature": "..." } } } }
    //   { "job":  { "id": "...", "status": "..." } }
    // Wir vermeiden eine JSON-Lib und extrahieren robust einzelne String-Felder.

    static Optional<TextureData> parseTextureData(String body) {
        if (body == null) {
            return Optional.empty();
        }
        int from = markerStart(body, "\"data\"", "\"texture\"");
        String value = extractString(body, "\"value\"", from);
        String signature = extractString(body, "\"signature\"", from);
        if (value == null || signature == null) {
            return Optional.empty();
        }
        return Optional.of(new TextureData(value, signature));
    }

    static Optional<String> parseSkinUuid(String body) {
        if (body == null) {
            return Optional.empty();
        }
        int from = markerStart(body, "\"skin\"", null);
        return Optional.ofNullable(extractString(body, "\"uuid\"", from));
    }

    static Optional<String> parseJobId(String body) {
        if (body == null) {
            return Optional.empty();
        }
        int from = markerStart(body, "\"job\"", null);
        return Optional.ofNullable(extractString(body, "\"id\"", from));
    }

    static Optional<String> parseStatus(String body) {
        if (body == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(extractString(body, "\"status\"", 0));
    }

    private static int markerStart(String body, String primary, String secondary) {
        int i = body.indexOf(primary);
        if (i >= 0) {
            return i;
        }
        if (secondary != null) {
            int j = body.indexOf(secondary);
            if (j >= 0) {
                return j;
            }
        }
        return 0;
    }

    private static String extractString(String body, String key, int from) {
        int k = body.indexOf(key, Math.max(0, from));
        if (k < 0) {
            return null;
        }
        int colon = body.indexOf(':', k + key.length());
        if (colon < 0) {
            return null;
        }
        int firstQuote = body.indexOf('"', colon);
        if (firstQuote < 0) {
            return null;
        }
        // Unescaped-Suche des schliessenden Quotes (Base64/Signature enthalten keine Quotes).
        int lastQuote = body.indexOf('"', firstQuote + 1);
        if (lastQuote < 0) {
            return null;
        }
        String v = body.substring(firstQuote + 1, lastQuote);
        return v.isEmpty() ? null : v;
    }

    private static String escapeJson(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
