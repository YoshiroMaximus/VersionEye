package dev.yoshiro.versioneye;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Minimal client for the Modrinth v2 API.
 * <p>
 * All methods block and must be called off the main server thread.
 */
final class ModrinthClient {

    private static final String API = "https://api.modrinth.com/v2";
    private static final String LOADERS_JSON = "[\"paper\",\"spigot\",\"bukkit\",\"folia\"]";

    private final HttpClient http;
    private final String userAgent;

    ModrinthClient(String pluginVersion) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        // Modrinth requires a User-Agent identifying the application.
        this.userAgent = "yoshiro/VersionEye/" + pluginVersion + " (https://github.com/YoshiroMaximus/VersionEye)";
    }

    record Project(String id, String slug, String title) {
    }

    record VersionInfo(
            String id,
            String projectId,
            String versionNumber,
            String versionType,
            Instant datePublished,
            Set<String> fileSha512s) {

        boolean isRelease() {
            return "release".equals(versionType);
        }

        boolean containsFile(String sha512) {
            return fileSha512s.contains(sha512.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Identifies the exact Modrinth version a plugin jar belongs to, by the
     * sha512 of the file. Empty if Modrinth does not know this file.
     */
    Optional<VersionInfo> versionFromHash(String sha512) throws IOException, InterruptedException {
        JsonElement body = get(API + "/version_file/" + sha512 + "?algorithm=sha512");
        return body == null ? Optional.empty() : Optional.of(parseVersion(body.getAsJsonObject()));
    }

    /**
     * Asks Modrinth for the newest version of the project that a file hash
     * belongs to. Empty if Modrinth does not know this file.
     */
    Optional<VersionInfo> latestFromHash(String sha512, String gameVersion)
            throws IOException, InterruptedException {
        String gameVersions = gameVersion == null ? "[]" : "[\"" + gameVersion + "\"]";
        String payload = "{\"loaders\":" + LOADERS_JSON + ",\"game_versions\":" + gameVersions + "}";
        JsonElement body = post(API + "/version_file/" + sha512 + "/update?algorithm=sha512", payload);
        return body == null ? Optional.empty() : Optional.of(parseVersion(body.getAsJsonObject()));
    }

    /**
     * Resolves a plugin name to a Modrinth project, first by guessing the
     * slug, then by searching and requiring an exact name match.
     */
    Optional<Project> resolveProject(String pluginName) throws IOException, InterruptedException {
        String slugGuess = pluginName.toLowerCase(Locale.ROOT).replace(' ', '-');
        Optional<Project> bySlug = fetchProject(slugGuess);
        if (bySlug.isPresent()) {
            return bySlug;
        }
        return searchExact(pluginName);
    }

    /** Fetches a project by its exact slug or ID; empty if it does not exist. */
    Optional<Project> fetchProject(String slugOrId) throws IOException, InterruptedException {
        JsonElement body = get(API + "/project/" + encode(slugOrId));
        if (body == null) {
            return Optional.empty();
        }
        JsonObject obj = body.getAsJsonObject();
        return Optional.of(new Project(
                obj.get("id").getAsString(),
                obj.get("slug").getAsString(),
                obj.get("title").getAsString()));
    }

    private Optional<Project> searchExact(String pluginName) throws IOException, InterruptedException {
        String url = API + "/search?limit=10"
                + "&query=" + encode(pluginName)
                + "&facets=" + encode("[[\"project_type:plugin\"]]");
        JsonElement body = get(url);
        if (body == null) {
            return Optional.empty();
        }
        String wanted = normalize(pluginName);
        JsonArray hits = body.getAsJsonObject().getAsJsonArray("hits");
        for (JsonElement hit : hits) {
            JsonObject obj = hit.getAsJsonObject();
            String title = obj.get("title").getAsString();
            String slug = obj.get("slug").getAsString();
            if (normalize(title).equals(wanted) || normalize(slug).equals(wanted)) {
                return Optional.of(new Project(obj.get("project_id").getAsString(), slug, title));
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the most recently published version of a project, optionally
     * restricted to releases declaring support for {@code gameVersion}.
     * Unless {@code includePrereleases} is set, alpha/beta channel uploads
     * are skipped so dev builds are not reported as updates.
     */
    Optional<VersionInfo> latestVersion(String projectId, String gameVersion, boolean includePrereleases)
            throws IOException, InterruptedException {
        String url = API + "/project/" + encode(projectId) + "/version"
                + "?loaders=" + encode(LOADERS_JSON);
        if (gameVersion != null) {
            url += "&game_versions=" + encode("[\"" + gameVersion + "\"]");
        }
        JsonElement body = get(url);
        if (body == null) {
            return Optional.empty();
        }
        JsonArray versions = body.getAsJsonArray();
        // Modrinth returns versions newest-first.
        for (JsonElement element : versions) {
            JsonObject version = element.getAsJsonObject();
            if (includePrereleases || "release".equals(version.get("version_type").getAsString())) {
                return Optional.of(parseVersion(version));
            }
        }
        return Optional.empty();
    }

    private static VersionInfo parseVersion(JsonObject version) {
        Set<String> hashes = new HashSet<>();
        JsonElement files = version.get("files");
        if (files != null && files.isJsonArray()) {
            for (JsonElement file : files.getAsJsonArray()) {
                JsonObject hashObj = file.getAsJsonObject().getAsJsonObject("hashes");
                if (hashObj != null && hashObj.has("sha512")) {
                    hashes.add(hashObj.get("sha512").getAsString().toLowerCase(Locale.ROOT));
                }
            }
        }
        return new VersionInfo(
                version.get("id").getAsString(),
                version.get("project_id").getAsString(),
                version.get("version_number").getAsString(),
                version.get("version_type").getAsString(),
                Instant.parse(version.get("date_published").getAsString()),
                Set.copyOf(hashes));
    }

    private JsonElement get(String url) throws IOException, InterruptedException {
        return send(request(url).GET().build());
    }

    private JsonElement post(String url, String jsonBody) throws IOException, InterruptedException {
        return send(request(url)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build());
    }

    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .timeout(Duration.ofSeconds(15));
    }

    /**
     * Sends the request, retrying briefly when rate-limited. Returns the
     * parsed body, or null on 404. Throws on other failures.
     */
    private JsonElement send(HttpRequest request) throws IOException, InterruptedException {
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
                throw new IOException("Modrinth returned HTTP " + status + " for " + request.uri());
            }
            return JsonParser.parseString(response.body());
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
