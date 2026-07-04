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
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Minimal client for the Hangar v1 API (PaperMC's plugin repository). Used
 * as a fallback source for plugins that are not published on Modrinth,
 * e.g. ProtocolLib.
 * <p>
 * All methods block and must be called off the main server thread.
 */
final class HangarClient {

    private static final String API = "https://hangar.papermc.io/api/v1";

    private final HttpClient http;
    private final String userAgent;

    HangarClient(String pluginVersion) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.userAgent = "yoshiro/VersionEye/" + pluginVersion + " (https://github.com/YoshiroMaximus/VersionEye)";
    }

    record Project(String owner, String slug, String name) {

        String url() {
            return "https://hangar.papermc.io/" + owner + "/" + slug;
        }
    }

    /**
     * Resolves a plugin name to a Hangar project, first by trying the name
     * as a slug, then by searching and requiring an exact name match.
     */
    Optional<Project> resolveProject(String pluginName) throws IOException, InterruptedException {
        Optional<Project> bySlug = fetchProject(pluginName);
        if (bySlug.isPresent()) {
            return bySlug;
        }
        return searchExact(pluginName);
    }

    /** Fetches a project by its exact slug (case-insensitive); empty if it does not exist. */
    Optional<Project> fetchProject(String slug) throws IOException, InterruptedException {
        JsonElement body = getJson(API + "/projects/" + encodePath(slug));
        return body == null ? Optional.empty() : Optional.of(parseProject(body.getAsJsonObject()));
    }

    private Optional<Project> searchExact(String pluginName) throws IOException, InterruptedException {
        JsonElement body = getJson(API + "/projects?limit=10&q=" + encodeQuery(pluginName));
        if (body == null) {
            return Optional.empty();
        }
        String wanted = normalize(pluginName);
        for (JsonElement hit : body.getAsJsonObject().getAsJsonArray("result")) {
            Project project = parseProject(hit.getAsJsonObject());
            if (normalize(project.name()).equals(wanted) || normalize(project.slug()).equals(wanted)) {
                return Optional.of(project);
            }
        }
        return Optional.empty();
    }

    /**
     * The version string of the newest upload in the project's Release
     * channel. Empty if the project does not exist or has no release.
     */
    Optional<String> latestReleaseVersion(String slug) throws IOException, InterruptedException {
        String version = getText(API + "/projects/" + encodePath(slug) + "/latestrelease");
        return version == null || version.isBlank()
                ? Optional.empty()
                : Optional.of(version.strip());
    }

    private static Project parseProject(JsonObject project) {
        JsonObject namespace = project.getAsJsonObject("namespace");
        return new Project(
                namespace.get("owner").getAsString(),
                namespace.get("slug").getAsString(),
                project.get("name").getAsString());
    }

    private JsonElement getJson(String url) throws IOException, InterruptedException {
        String body = getText(url);
        return body == null ? null : JsonParser.parseString(body);
    }

    /** GETs a URL, retrying briefly when rate-limited. Null on 404. */
    private String getText(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
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
            if (status != 200) {
                throw new IOException("Hangar returned HTTP " + status + " for " + request.uri());
            }
            return response.body();
        }
    }

    private static String encodePath(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeQuery(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
