package dev.yoshiro.versioneye;

public record UpdateResult(
        String pluginName,
        String installedVersion,
        String latestVersion,
        String projectUrl,
        String source,
        Status status) {

    public static final String SOURCE_MODRINTH = "Modrinth";
    public static final String SOURCE_HANGAR = "Hangar";

    public enum Status {
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        NOT_FOUND,
        ERROR
    }

    public static UpdateResult notFound(String pluginName, String installedVersion) {
        return new UpdateResult(pluginName, installedVersion, null, null, null, Status.NOT_FOUND);
    }

    public static UpdateResult error(String pluginName, String installedVersion) {
        return new UpdateResult(pluginName, installedVersion, null, null, null, Status.ERROR);
    }
}
