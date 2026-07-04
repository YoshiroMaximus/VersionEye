package dev.yoshiro.versioneye;

import java.io.IOException;
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

    private final ApiHttp http;

    ModrinthClient(ApiHttp http) {
        this.http = http;
    }

    record Project(String id, String slug, String title) {
    }

    record VersionInfo(
            String id,
            String projectId,
            String versionNumber,
            String versionType,
            Instant datePublished,
            Set<String> fileSha512s,
            String fileUrl,
            String fileSha512) {

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
        JsonElement body = getJson(API + "/version_file/" + sha512 + "?algorithm=sha512");
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
        String body = http.post(API + "/version_file/" + sha512 + "/update?algorithm=sha512", payload);
        return body == null
                ? Optional.empty()
                : Optional.of(parseVersion(JsonParser.parseString(body).getAsJsonObject()));
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
        JsonElement body = getJson(API + "/project/" + ApiHttp.encode(slugOrId));
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
                + "&query=" + ApiHttp.encode(pluginName)
                + "&facets=" + ApiHttp.encode("[[\"project_type:plugin\"]]");
        JsonElement body = getJson(url);
        if (body == null) {
            return Optional.empty();
        }
        String wanted = ApiHttp.normalize(pluginName);
        JsonArray hits = body.getAsJsonObject().getAsJsonArray("hits");
        for (JsonElement hit : hits) {
            JsonObject obj = hit.getAsJsonObject();
            String title = obj.get("title").getAsString();
            String slug = obj.get("slug").getAsString();
            if (ApiHttp.normalize(title).equals(wanted) || ApiHttp.normalize(slug).equals(wanted)) {
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
        String url = API + "/project/" + ApiHttp.encode(projectId) + "/version"
                + "?loaders=" + ApiHttp.encode(LOADERS_JSON);
        if (gameVersion != null) {
            url += "&game_versions=" + ApiHttp.encode("[\"" + gameVersion + "\"]");
        }
        JsonElement body = getJson(url);
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
        JsonObject primary = null;
        JsonElement files = version.get("files");
        if (files != null && files.isJsonArray()) {
            for (JsonElement element : files.getAsJsonArray()) {
                JsonObject file = element.getAsJsonObject();
                JsonObject hashObj = file.getAsJsonObject("hashes");
                if (hashObj != null && hashObj.has("sha512")) {
                    hashes.add(hashObj.get("sha512").getAsString().toLowerCase(Locale.ROOT));
                }
                JsonElement primaryFlag = file.get("primary");
                if (primary == null
                        || (primaryFlag != null && !primaryFlag.isJsonNull() && primaryFlag.getAsBoolean())) {
                    primary = file;
                }
            }
        }
        String fileUrl = primary != null ? primary.get("url").getAsString() : null;
        String fileSha512 = primary != null && primary.getAsJsonObject("hashes").has("sha512")
                ? primary.getAsJsonObject("hashes").get("sha512").getAsString().toLowerCase(Locale.ROOT)
                : null;
        return new VersionInfo(
                version.get("id").getAsString(),
                version.get("project_id").getAsString(),
                version.get("version_number").getAsString(),
                version.get("version_type").getAsString(),
                Instant.parse(version.get("date_published").getAsString()),
                Set.copyOf(hashes),
                fileUrl,
                fileSha512);
    }

    private JsonElement getJson(String url) throws IOException, InterruptedException {
        String body = http.get(url);
        return body == null ? null : JsonParser.parseString(body);
    }
}
