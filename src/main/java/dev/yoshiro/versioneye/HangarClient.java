package dev.yoshiro.versioneye;

import java.io.IOException;
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

    private final ApiHttp http;

    HangarClient(ApiHttp http) {
        this.http = http;
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
        JsonElement body = getJson(API + "/projects/" + ApiHttp.encode(slug));
        return body == null ? Optional.empty() : Optional.of(parseProject(body.getAsJsonObject()));
    }

    private Optional<Project> searchExact(String pluginName) throws IOException, InterruptedException {
        JsonElement body = getJson(API + "/projects?limit=10&q=" + ApiHttp.encode(pluginName));
        if (body == null) {
            return Optional.empty();
        }
        String wanted = ApiHttp.normalize(pluginName);
        for (JsonElement hit : body.getAsJsonObject().getAsJsonArray("result")) {
            Project project = parseProject(hit.getAsJsonObject());
            if (ApiHttp.normalize(project.name()).equals(wanted)
                    || ApiHttp.normalize(project.slug()).equals(wanted)) {
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
        String version = http.get(API + "/projects/" + ApiHttp.encode(slug) + "/latestrelease");
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
        String body = http.get(url);
        return body == null ? null : JsonParser.parseString(body);
    }
}
