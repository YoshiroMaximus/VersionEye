package dev.yoshiro.versioneye;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * HTTP plumbing shared by the API clients: one connection pool, the
 * User-Agent identifying this plugin, and retry and outage handling.
 * <p>
 * Rate-limited requests (429) are retried after the advertised wait.
 * Idempotent requests are also retried once after a transient failure
 * (5xx or a network error), and consecutive transient failures trip a
 * per-host breaker so a full API outage fails fast instead of timing
 * out request by request.
 */
final class ApiHttp {

    private static final int HOST_FAILURE_LIMIT = 4;
    private static final long TRANSIENT_RETRY_MILLIS = 2000;

    private final HttpClient http;
    private final String userAgent;
    private final Logger logger;
    /** Host -> consecutive transient failures; any healthy response resets it. */
    private final Map<String, AtomicInteger> hostFailures = new ConcurrentHashMap<>();

    /** Thrown without a network attempt while a host's breaker is open. */
    static final class HostDownException extends IOException {
        HostDownException(String host) {
            super(host + " appears to be down, request skipped");
        }
    }

    ApiHttp(String pluginVersion, Logger logger) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.logger = logger;
        // Modrinth and Hangar want a User-Agent identifying the application.
        this.userAgent = "yoshiro/VersionEye/" + pluginVersion + " (https://github.com/YoshiroMaximus/VersionEye)";
    }

    /** Reopens tripped host breakers, giving every host a fresh chance. */
    void resetHostFailures() {
        hostFailures.clear();
    }

    /** GETs a URL and returns the response body. Null on 404. */
    String get(String url) throws IOException, InterruptedException {
        return send(request(url).GET().build(), true);
    }

    /**
     * POSTs a JSON body and returns the response body. Null on 404. Only
     * idempotent requests (lookups, not webhook posts) may be retried
     * after a transient failure.
     */
    String post(String url, String jsonBody, boolean idempotent) throws IOException, InterruptedException {
        return send(request(url)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build(), idempotent);
    }

    /** Downloads a URL to a file, following redirects. Overwrites the target. */
    void download(String url, Path target) throws IOException, InterruptedException {
        HttpRequest request = request(url).timeout(Duration.ofMinutes(2)).GET().build();
        HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(target));
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            Files.deleteIfExists(target);
            throw new IOException("HTTP " + status + " from " + request.uri());
        }
    }

    private HttpRequest.Builder request(String url) {
        // Short enough that a retry still beats one long attempt; Modrinth
        // responds within a few seconds even when degraded.
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .timeout(Duration.ofSeconds(10));
    }

    /**
     * Sends the request with the retry and breaker behavior described on
     * the class. Returns the body, or null on 404. Throws on other
     * failures; {@link HostDownException} while the host's breaker is open.
     */
    private String send(HttpRequest request, boolean idempotent) throws IOException, InterruptedException {
        String host = request.uri().getHost();
        for (int attempt = 1; ; attempt++) {
            if (failures(host).get() >= HOST_FAILURE_LIMIT) {
                throw new HostDownException(host);
            }
            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                recordFailure(host);
                if (idempotent && attempt < 2) {
                    Thread.sleep(TRANSIENT_RETRY_MILLIS);
                    continue;
                }
                throw e;
            }
            int status = response.statusCode();
            if (status >= 500) {
                recordFailure(host);
                if (idempotent && attempt < 2) {
                    Thread.sleep(TRANSIENT_RETRY_MILLIS);
                    continue;
                }
                throw new IOException("HTTP " + status + " from " + request.uri());
            }
            failures(host).set(0);
            if (status == 404) {
                return null;
            }
            if (status == 429 && attempt < 3) {
                long waitSeconds = response.headers().firstValueAsLong("Retry-After").orElse(2);
                Thread.sleep(Math.clamp(waitSeconds, 1, 30) * 1000);
                continue;
            }
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status + " from " + request.uri());
            }
            return response.body();
        }
    }

    private AtomicInteger failures(String host) {
        return hostFailures.computeIfAbsent(host, h -> new AtomicInteger());
    }

    private void recordFailure(String host) {
        if (failures(host).incrementAndGet() == HOST_FAILURE_LIMIT) {
            logger.warning(host + " appears to be down (" + HOST_FAILURE_LIMIT
                    + " consecutive failures); skipping requests to it until the next check.");
        }
    }

    /** URL-encodes for use as a path segment or query value. */
    static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Lowercases and strips everything but letters and digits, for lenient name matching. */
    static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
