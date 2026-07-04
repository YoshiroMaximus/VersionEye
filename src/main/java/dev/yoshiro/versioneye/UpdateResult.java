package dev.yoshiro.versioneye;

public record UpdateResult(
        String pluginName,
        String installedVersion,
        String latestVersion,
        String projectSlug,
        Status status) {

    public enum Status {
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        NOT_FOUND,
        ERROR
    }

    public static UpdateResult notFound(String pluginName, String installedVersion) {
        return new UpdateResult(pluginName, installedVersion, null, null, Status.NOT_FOUND);
    }

    public static UpdateResult error(String pluginName, String installedVersion) {
        return new UpdateResult(pluginName, installedVersion, null, null, Status.ERROR);
    }

    public String projectUrl() {
        return projectSlug == null ? null : "https://modrinth.com/plugin/" + projectSlug;
    }
}
