package dev.yoshiro.versioneye;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

/**
 * Runs update checks against Modrinth and caches the latest results.
 * <p>
 * Plugins are identified by the sha512 hash of their jar when possible,
 * which matches them to their exact Modrinth project and version. Name
 * matching is only a fallback for files Modrinth does not know.
 * <p>
 * {@link #checkAll()} blocks on network calls and must run off the main
 * thread; individual plugins are checked concurrently on a small pool.
 */
final class UpdateCheckService {

    private static final int MAX_CONCURRENT_CHECKS = 6;

    private final VersionEyePlugin plugin;
    private final ApiHttp http;
    private final ModrinthClient modrinth;
    private final HangarClient hangar;
    private final DiscordWebhook discord;
    private final ExecutorService executor;

    /** Resolved plugin name -> Modrinth project, cached across runs (name fallback path). */
    private final Map<String, ModrinthClient.Project> resolved = new ConcurrentHashMap<>();
    /** Resolved plugin name -> Hangar project, cached across runs (last-resort fallback). */
    private final Map<String, HangarClient.Project> resolvedHangar = new ConcurrentHashMap<>();
    /** Jar sha512 -> the Modrinth version that file belongs to; immutable per file. */
    private final Map<String, ModrinthClient.VersionInfo> knownFiles = new ConcurrentHashMap<>();
    /** Plugin name -> downloadable update found by the latest check. */
    private final Map<String, PendingDownload> pendingDownloads = new ConcurrentHashMap<>();
    /** "name version" keys already fetched into the update folder. */
    private final Set<String> downloadedKeys = ConcurrentHashMap.newKeySet();
    /** "name version" keys already announced to the Discord webhook. */
    private final Set<String> announcedKeys = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean checkRunning = new AtomicBoolean(false);

    private volatile List<UpdateResult> lastResults = List.of();

    /**
     * A downloadable update found by the latest check. Modrinth entries
     * carry the URL and hash directly; Hangar entries only carry the
     * project slug, and the download details are fetched lazily when a
     * download is actually requested.
     */
    private record PendingDownload(String pluginName, String version, String url,
            String expectedHash, String hashAlgorithm, Path installedJar, String hangarSlug) {
    }

    UpdateCheckService(VersionEyePlugin plugin) {
        this.plugin = plugin;
        this.http = new ApiHttp(plugin.getPluginMeta().getVersion(), plugin.getLogger());
        this.modrinth = new ModrinthClient(http);
        this.hangar = new HangarClient(http);
        this.discord = new DiscordWebhook(http);
        this.executor = Executors.newFixedThreadPool(MAX_CONCURRENT_CHECKS, runnable -> {
            Thread thread = new Thread(runnable, "VersionEye-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    void shutdown() {
        executor.shutdownNow();
    }

    List<UpdateResult> lastResults() {
        return lastResults;
    }

    /** Drops cached project resolutions, so config override changes take effect. */
    void clearCaches() {
        resolved.clear();
        resolvedHangar.clear();
        knownFiles.clear();
    }

    List<UpdateResult> lastOutdated() {
        return lastResults.stream()
                .filter(r -> r.status() == UpdateResult.Status.UPDATE_AVAILABLE)
                .toList();
    }

    /** Checks every installed plugin with the configured prerelease setting. */
    List<UpdateResult> checkAll() {
        return checkAll(plugin.getConfig().getBoolean("include-prereleases", false));
    }

    /**
     * Checks every installed plugin. If a check is already in progress,
     * returns the previous results instead of starting a second one.
     */
    List<UpdateResult> checkAll(boolean includePrereleases) {
        if (!checkRunning.compareAndSet(false, true)) {
            return lastResults;
        }
        try {
            // A host that was down last run gets a fresh chance.
            http.resetHostFailures();
            Set<String> excluded = plugin.getConfig().getStringList("exclude").stream()
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            String gameVersion = plugin.getConfig().getBoolean("require-matching-game-version", false)
                    ? plugin.getServer().getMinecraftVersion()
                    : null;
            boolean checkHangar = plugin.getConfig().getBoolean("check-hangar", true);

            List<CompletableFuture<UpdateResult>> futures = new ArrayList<>();
            for (Plugin target : plugin.getServer().getPluginManager().getPlugins()) {
                String name = target.getPluginMeta().getName();
                if (excluded.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String installedVersion = target.getPluginMeta().getVersion();
                Path jar = pluginJar(target);
                futures.add(CompletableFuture.supplyAsync(
                        () -> checkOne(name, installedVersion, jar, gameVersion,
                                includePrereleases, checkHangar),
                        executor));
            }

            List<UpdateResult> results = new ArrayList<>();
            for (CompletableFuture<UpdateResult> future : futures) {
                try {
                    results.add(future.join());
                } catch (RuntimeException e) {
                    // Only happens if the executor is shutting down; drop the entry.
                }
            }
            results.sort((a, b) -> a.pluginName().compareToIgnoreCase(b.pluginName()));
            lastResults = mergeWithPrevious(lastResults, results);
            return List.copyOf(results);
        } finally {
            checkRunning.set(false);
        }
    }

    /**
     * The cached state keeps the last successful result for plugins whose
     * check failed this run, so a transient API outage doesn't wipe what
     * is already known (join notifications, ignore and download targets).
     * The failure is still reported through the fresh results.
     */
    private static List<UpdateResult> mergeWithPrevious(List<UpdateResult> previous,
            List<UpdateResult> fresh) {
        Map<String, UpdateResult> known = new HashMap<>();
        for (UpdateResult r : previous) {
            if (r.status() != UpdateResult.Status.ERROR) {
                known.put(r.pluginName().toLowerCase(Locale.ROOT), r);
            }
        }
        return fresh.stream()
                .map(r -> r.status() == UpdateResult.Status.ERROR
                        ? known.getOrDefault(r.pluginName().toLowerCase(Locale.ROOT), r)
                        : r)
                .toList();
    }

    private UpdateResult checkOne(String name, String installedVersion, Path jar,
            String gameVersion, boolean includePrereleases, boolean checkHangar) {
        // Kept aside so a transient API failure doesn't lose a download the
        // previous check already found.
        PendingDownload previous = pendingDownloads.remove(name);
        try {
            // A configured override always wins over automatic matching.
            String override = override(name);
            Optional<UpdateResult> byHash = override == null && jar != null
                    ? checkByHash(name, installedVersion, jar, gameVersion, includePrereleases)
                    : Optional.empty();
            UpdateResult result = byHash.isPresent()
                    ? byHash.get()
                    : checkByName(name, installedVersion, jar, override, gameVersion,
                            includePrereleases, checkHangar);

            // An update the admin chose to skip stays muted until something
            // newer appears.
            if (result.status() == UpdateResult.Status.UPDATE_AVAILABLE
                    && result.latestVersion().equalsIgnoreCase(
                            configValue("ignored-versions", name))) {
                pendingDownloads.remove(name);
                result = withStatus(result, UpdateResult.Status.IGNORED);
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            restorePendingDownload(name, previous);
            return UpdateResult.error(name, installedVersion);
        } catch (ApiHttp.HostDownException e) {
            // The breaker already logged one warning for the whole outage.
            restorePendingDownload(name, previous);
            return UpdateResult.error(name, installedVersion);
        } catch (Exception e) {
            plugin.getLogger().warning("Update check failed for " + name + ": " + e.getMessage());
            restorePendingDownload(name, previous);
            return UpdateResult.error(name, installedVersion);
        }
    }

    private void restorePendingDownload(String name, PendingDownload previous) {
        if (previous != null) {
            pendingDownloads.putIfAbsent(name, previous);
        }
    }

    private static UpdateResult withStatus(UpdateResult result, UpdateResult.Status status) {
        return new UpdateResult(result.pluginName(), result.installedVersion(),
                result.latestVersion(), result.projectUrl(), result.source(), status);
    }

    /**
     * Exact identification by file hash. Empty if Modrinth does not know
     * this jar, in which case the caller falls back to name matching.
     */
    private Optional<UpdateResult> checkByHash(String name, String installedVersion, Path jar,
            String gameVersion, boolean includePrereleases) throws Exception {
        String hash = sha512(jar);
        Optional<ModrinthClient.VersionInfo> latestFound = modrinth.latestFromHash(hash, gameVersion);
        if (latestFound.isEmpty()) {
            return Optional.empty();
        }
        ModrinthClient.VersionInfo latest = latestFound.get();

        // The update-by-hash endpoint ignores release channels; if it points
        // at a prerelease we don't want, fall back to the newest release.
        if (!includePrereleases && !latest.isRelease()) {
            Optional<ModrinthClient.VersionInfo> release =
                    modrinth.latestVersion(latest.projectId(), gameVersion, false);
            if (release.isEmpty()) {
                // The project has no stable release at all; nothing to offer.
                return Optional.of(upToDate(name, installedVersion, latest));
            }
            latest = release.get();
        }

        if (latest.containsFile(hash)) {
            return Optional.of(upToDate(name, installedVersion, latest));
        }

        // Running a different file than the latest. Compare publish dates so
        // that e.g. a dev build newer than the latest release is not flagged.
        ModrinthClient.VersionInfo installed = knownFiles.get(hash);
        if (installed == null) {
            installed = modrinth.versionFromHash(hash).orElse(null);
            if (installed != null) {
                knownFiles.put(hash, installed);
            }
        }
        if (installed != null && !installed.datePublished().isBefore(latest.datePublished())) {
            return Optional.of(upToDate(name, installedVersion, latest));
        }

        String shownInstalled = installed != null ? installed.versionNumber() : installedVersion;
        registerModrinthDownload(name, latest, jar);
        return Optional.of(new UpdateResult(name, shownInstalled, latest.versionNumber(),
                modrinthUrl(latest.projectId()), UpdateResult.SOURCE_MODRINTH,
                UpdateResult.Status.UPDATE_AVAILABLE));
    }

    private static UpdateResult upToDate(String name, String installedVersion,
            ModrinthClient.VersionInfo latest) {
        return new UpdateResult(name, installedVersion, latest.versionNumber(),
                modrinthUrl(latest.projectId()), UpdateResult.SOURCE_MODRINTH,
                UpdateResult.Status.UP_TO_DATE);
    }

    private static String modrinthUrl(String slugOrId) {
        return "https://modrinth.com/plugin/" + slugOrId;
    }

    /**
     * Fallback matching by plugin name (or configured override). Tries
     * Modrinth first; plugins not published there (e.g. ProtocolLib) are
     * looked up on Hangar instead.
     */
    private UpdateResult checkByName(String name, String installedVersion, Path jar, String override,
            String gameVersion, boolean includePrereleases, boolean checkHangar) throws Exception {
        // An override like "hangar:ProtocolLib" pins the plugin to a Hangar project.
        if (override != null && override.toLowerCase(Locale.ROOT).startsWith("hangar:")) {
            return checkOnHangar(name, installedVersion, jar,
                    override.substring("hangar:".length()).strip());
        }

        ModrinthClient.Project project = resolved.get(name);
        if (project == null) {
            Optional<ModrinthClient.Project> found = override != null
                    ? modrinth.fetchProject(override)
                    : modrinth.resolveProject(name);
            if (found.isEmpty()) {
                if (override == null && checkHangar) {
                    return checkOnHangar(name, installedVersion, jar, null);
                }
                return UpdateResult.notFound(name, installedVersion);
            }
            project = found.get();
            resolved.put(name, project);
        }

        Optional<ModrinthClient.VersionInfo> latest =
                modrinth.latestVersion(project.id(), gameVersion, includePrereleases);
        if (latest.isEmpty()) {
            return UpdateResult.notFound(name, installedVersion);
        }
        String latestNumber = latest.get().versionNumber();
        boolean outdated = VersionComparator.compare(latestNumber, installedVersion) > 0;
        if (outdated) {
            registerModrinthDownload(name, latest.get(), jar);
        }
        return new UpdateResult(name, installedVersion, latestNumber,
                modrinthUrl(project.slug()), UpdateResult.SOURCE_MODRINTH,
                outdated ? UpdateResult.Status.UPDATE_AVAILABLE : UpdateResult.Status.UP_TO_DATE);
    }

    /**
     * Last-resort lookup on Hangar. Hangar has no file-hash lookup, so this
     * is name and version-string matching only; only the Release channel is
     * considered.
     */
    private UpdateResult checkOnHangar(String name, String installedVersion, Path jar,
            String overrideSlug) throws Exception {
        HangarClient.Project project = resolvedHangar.get(name);
        if (project == null) {
            Optional<HangarClient.Project> found = overrideSlug != null
                    ? hangar.fetchProject(overrideSlug)
                    : hangar.resolveProject(name);
            if (found.isEmpty()) {
                return UpdateResult.notFound(name, installedVersion);
            }
            project = found.get();
            resolvedHangar.put(name, project);
        }

        Optional<String> latest = hangar.latestReleaseVersion(project.slug());
        if (latest.isEmpty()) {
            return UpdateResult.notFound(name, installedVersion);
        }
        boolean outdated = VersionComparator.compare(latest.get(), installedVersion) > 0;
        if (outdated && jar != null) {
            // Download URL and hash are fetched lazily in downloadOne, so the
            // routine check path doesn't pay an extra request per plugin.
            pendingDownloads.put(name, new PendingDownload(name, latest.get(), null, null,
                    "SHA-256", jar, project.slug()));
        }
        return new UpdateResult(name, installedVersion, latest.get(),
                project.url(), UpdateResult.SOURCE_HANGAR,
                outdated ? UpdateResult.Status.UPDATE_AVAILABLE : UpdateResult.Status.UP_TO_DATE);
    }

    private void registerModrinthDownload(String name, ModrinthClient.VersionInfo latest, Path jar) {
        if (latest.fileUrl() == null || jar == null) {
            return;
        }
        pendingDownloads.put(name, new PendingDownload(name, latest.versionNumber(),
                latest.fileUrl(), latest.fileSha512(), "SHA-512", jar, null));
    }

    /**
     * Locates the jar a plugin was loaded from. Paper may load a remapped
     * copy from plugins/.paper-remapped; the original file is what Modrinth
     * knows, so prefer the sibling with the same name in plugins/.
     */
    private Path pluginJar(Plugin target) {
        try {
            var source = target.getClass().getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            Path path = Path.of(source.getLocation().toURI());
            Path parent = path.getParent();
            if (parent != null && parent.getFileName() != null
                    && parent.getFileName().toString().equals(".paper-remapped")) {
                Path original = parent.getParent().resolve(path.getFileName());
                if (Files.isRegularFile(original)) {
                    return original;
                }
            }
            return Files.isRegularFile(path) ? path : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha512(Path file) throws Exception {
        return fileHash("SHA-512", file);
    }

    private static String fileHash(String algorithm, Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Fetches pending updates into the server's update folder, where Paper
     * applies them on the next restart. The file is saved under the
     * installed jar's name (that is how the update folder matches plugins),
     * verified against the source's published hash when one exists, and
     * only moved into place after a successful download.
     *
     * @param pluginName a single plugin to download, or null for all
     * @return one human-readable line per attempted download
     */
    List<String> downloadUpdates(String pluginName) {
        List<PendingDownload> targets = pendingDownloads.values().stream()
                .filter(d -> pluginName == null || d.pluginName().equalsIgnoreCase(pluginName))
                .sorted((a, b) -> a.pluginName().compareToIgnoreCase(b.pluginName()))
                .toList();
        if (targets.isEmpty()) {
            return List.of(pluginName == null
                    ? "Nothing to download; no updates with a downloadable file."
                    : "No downloadable update for " + pluginName + ".");
        }
        List<String> lines = new ArrayList<>();
        for (PendingDownload target : targets) {
            lines.add(downloadOne(target));
        }
        return lines;
    }

    private String downloadOne(PendingDownload download) {
        String label = download.pluginName() + " " + download.version();
        try {
            // Hangar entries resolve their download URL and hash on demand.
            if (download.url() == null) {
                HangarClient.DownloadInfo info =
                        hangar.downloadInfo(download.hangarSlug(), download.version()).orElse(null);
                if (info == null) {
                    return "No downloadable file for " + label + ".";
                }
                download = new PendingDownload(download.pluginName(), download.version(),
                        info.url(), info.sha256(), download.hashAlgorithm(),
                        download.installedJar(), download.hangarSlug());
            }

            Path updateDir = plugin.getServer().getUpdateFolderFile().toPath();
            Path target = updateDir.resolve(download.installedJar().getFileName().toString());
            if (Files.isRegularFile(target)
                    && (downloadedKeys.contains(label) || matchesHash(download, target))) {
                downloadedKeys.add(label);
                return label + " is already downloaded (restart to apply).";
            }
            Files.createDirectories(updateDir);
            Path temp = target.resolveSibling(target.getFileName() + ".part");
            try {
                http.download(download.url(), temp);
                if (download.expectedHash() != null && !matchesHash(download, temp)) {
                    return "Discarded " + label + ": downloaded file failed hash verification.";
                }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
            downloadedKeys.add(label);
            return "Downloaded " + label + " to " + updateDir.getFileName() + "/"
                    + target.getFileName() + "; it will be applied on the next restart"
                    + (download.expectedHash() == null ? " (externally hosted, hash not verified)" : "")
                    + ".";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Download of " + label + " was interrupted.";
        } catch (Exception e) {
            plugin.getLogger().warning("Download failed for " + label + ": " + e.getMessage());
            return "Download failed for " + label + ": " + e.getMessage();
        }
    }

    private static boolean matchesHash(PendingDownload download, Path file) throws Exception {
        return download.expectedHash() != null
                && fileHash(download.hashAlgorithm(), file).equalsIgnoreCase(download.expectedHash());
    }

    /**
     * Mutes the currently available update for a plugin until something
     * newer is published. Must be called on the main thread (writes config).
     */
    String ignoreLatest(String pluginName) {
        UpdateResult match = lastOutdated().stream()
                .filter(r -> r.pluginName().equalsIgnoreCase(pluginName))
                .findFirst().orElse(null);
        if (match == null) {
            return "No pending update for " + pluginName + " to ignore.";
        }
        plugin.getConfig().set("ignored-versions." + match.pluginName(), match.latestVersion());
        plugin.saveConfig();
        pendingDownloads.remove(match.pluginName());
        lastResults = lastResults.stream()
                .map(r -> r == match ? withStatus(r, UpdateResult.Status.IGNORED) : r)
                .toList();
        return "Ignoring " + match.pluginName() + " " + match.latestVersion()
                + "; you will be notified again when a newer version is published.";
    }

    /** Removes an ignore entry. Must be called on the main thread (writes config). */
    String unignore(String pluginName) {
        String key = configKey("ignored-versions", pluginName);
        if (key == null) {
            return "No ignored version for " + pluginName + ".";
        }
        plugin.getConfig().set("ignored-versions." + key, null);
        plugin.saveConfig();
        return "No longer ignoring updates for " + key + "; the next check will report them again.";
    }

    /** Names of plugins with an ignored version, for tab completion. */
    List<String> ignoredPluginNames() {
        ConfigurationSection ignored = plugin.getConfig().getConfigurationSection("ignored-versions");
        return ignored == null ? List.of() : List.copyOf(ignored.getKeys(false));
    }

    /** Names of plugins with a downloadable pending update, for tab completion. */
    List<String> downloadablePluginNames() {
        return pendingDownloads.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private String override(String pluginName) {
        return configValue("overrides", pluginName);
    }

    /** The actual key in a config section matching the name case-insensitively, or null. */
    private String configKey(String section, String name) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection(section);
        if (sec == null) {
            return null;
        }
        for (String key : sec.getKeys(false)) {
            if (key.equalsIgnoreCase(name)) {
                return key;
            }
        }
        return null;
    }

    /** The value under a case-insensitively matched key in a config section, or null. */
    private String configValue(String section, String name) {
        String key = configKey(section, name);
        return key == null ? null
                : plugin.getConfig().getConfigurationSection(section).getString(key);
    }

    /** Runs a full check and reports the outcome to the console. */
    void runCheckAndLog() {
        List<UpdateResult> results = checkAll();
        List<UpdateResult> outdated = results.stream()
                .filter(r -> r.status() == UpdateResult.Status.UPDATE_AVAILABLE)
                .toList();
        List<UpdateResult> unknown = results.stream()
                .filter(r -> r.status() == UpdateResult.Status.NOT_FOUND)
                .toList();
        List<UpdateResult> failed = results.stream()
                .filter(r -> r.status() == UpdateResult.Status.ERROR)
                .toList();
        int checked = results.size() - failed.size();

        if (outdated.isEmpty()) {
            if (checked > 0) {
                plugin.getLogger().info("All " + checked + " checked plugins are up to date"
                        + (unknown.isEmpty() ? "."
                                : " (" + unknown.size() + " not found on " + UpdateResult.ALL_SOURCES + ")."));
            }
        } else {
            plugin.getLogger().warning(outdated.size() + " plugin(s) have updates available:");
            for (UpdateResult r : outdated) {
                plugin.getLogger().warning("  " + r.pluginName() + ": " + r.installedVersion()
                        + " -> " + r.latestVersion() + " (" + r.projectUrl() + ")");
            }
        }
        if (!unknown.isEmpty()) {
            plugin.getLogger().info("Not found on " + UpdateResult.ALL_SOURCES
                    + " (add to 'overrides' or 'exclude' in config.yml): "
                    + unknown.stream().map(UpdateResult::pluginName).collect(Collectors.joining(", ")));
        }
        if (!failed.isEmpty()) {
            plugin.getLogger().warning("Could not check " + failed.size() + " plugin(s): "
                    + failed.stream().map(UpdateResult::pluginName).collect(Collectors.joining(", ")));
        }

        if (!outdated.isEmpty() && plugin.getConfig().getBoolean("auto-download", false)) {
            for (String line : downloadUpdates(null)) {
                plugin.getLogger().info(line);
            }
        }
        notifyDiscord(outdated);
    }

    /** Posts newly seen updates to the configured Discord webhook, if any. */
    private void notifyDiscord(List<UpdateResult> outdated) {
        String webhookUrl = plugin.getConfig().getString("discord-webhook", "");
        if (webhookUrl == null || webhookUrl.isBlank() || outdated.isEmpty()) {
            return;
        }
        // Announce each plugin+version once, not on every periodic re-check.
        List<UpdateResult> fresh = outdated.stream()
                .filter(r -> announcedKeys.add(r.pluginName() + " " + r.latestVersion()))
                .toList();
        if (fresh.isEmpty()) {
            return;
        }
        try {
            discord.sendUpdates(webhookUrl, fresh);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            plugin.getLogger().warning("Discord webhook notification failed: " + e.getMessage());
        }
    }
}
