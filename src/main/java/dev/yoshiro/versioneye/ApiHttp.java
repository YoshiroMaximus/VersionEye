package dev.yoshiro.versioneye;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * HTTP plumbing shared by the API clients: one connection pool, the
 * User-Agent identifying this plugin, and retry-on-rate-limit handling.
 */
final class ApiHttp {

    private final HttpClient http;
    private final String userAgent;

    ApiHttp(String pluginVersion) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        // Modrinth and Hangar want a User-Agent identifying the application.
        this.userAgent = "yoshiro/VersionEye/" + pluginVersion + " (https://github.com/YoshiroMaximus/VersionEye)";
    }

    /** GETs a URL and returns the response body. Null on 404. */
    String get(String url) throws IOException, InterruptedException {
        return send(request(url).GET().build());
    }

    /** POSTs a JSON body and returns the response body. Null on 404. */
    String post(String url, String jsonBody) throws IOException, InterruptedException {
        return send(request(url)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build());
    }

    /** Downloads a URL to a file, following redirects. Overwrites the target. */
    void download(String url, java.nio.file.Path target) throws IOException, InterruptedException {
        HttpRequest request = request(url).timeout(Duration.ofMinutes(2)).GET().build();
        HttpResponse<java.nio.file.Path> response = http.send(request,
                HttpResponse.BodyHandlers.ofFile(target));
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            java.nio.file.Files.deleteIfExists(target);
            throw new IOException("HTTP " + status + " from " + request.uri());
        }
    }

    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .timeout(Duration.ofSeconds(15));
    }

    /**
     * Sends the request, retrying briefly when rate-limited. Returns the
     * body, or null on 404. Throws on other failures.
     */
    private String send(HttpRequest request) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
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

    /** URL-encodes for use as a path segment or query value. */
    static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Lowercases and strips everything but letters and digits, for lenient name matching. */
    static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
