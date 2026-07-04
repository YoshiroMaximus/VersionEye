package dev.yoshiro.versioneye;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
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
    private final ModrinthClient modrinth;
    private final HangarClient hangar;
    private final ExecutorService executor;

    /** Resolved plugin name -> Modrinth project, cached across runs (name fallback path). */
    private final Map<String, ModrinthClient.Project> resolved = new ConcurrentHashMap<>();
    /** Resolved plugin name -> Hangar project, cached across runs (last-resort fallback). */
    private final Map<String, HangarClient.Project> resolvedHangar = new ConcurrentHashMap<>();
    /** Jar sha512 -> the Modrinth version that file belongs to; immutable per file. */
    private final Map<String, ModrinthClient.VersionInfo> knownFiles = new ConcurrentHashMap<>();
    private final AtomicBoolean checkRunning = new AtomicBoolean(false);

    private volatile List<UpdateResult> lastResults = List.of();

    UpdateCheckService(VersionEyePlugin plugin) {
        this.plugin = plugin;
        ApiHttp http = new ApiHttp(plugin.getPluginMeta().getVersion());
        this.modrinth = new ModrinthClient(http);
        this.hangar = new HangarClient(http);
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
            lastResults = List.copyOf(results);
            return lastResults;
        } finally {
            checkRunning.set(false);
        }
    }

    private UpdateResult checkOne(String name, String installedVersion, Path jar,
            String gameVersion, boolean includePrereleases, boolean checkHangar) {
        try {
            // A configured override always wins over automatic matching.
            String override = override(name);
            if (override == null && jar != null) {
                Optional<UpdateResult> byHash =
                        checkByHash(name, installedVersion, jar, gameVersion, includePrereleases);
                if (byHash.isPresent()) {
                    return byHash.get();
                }
            }
            return checkByName(name, installedVersion, override, gameVersion,
                    includePrereleases, checkHangar);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return UpdateResult.error(name, installedVersion);
        } catch (Exception e) {
            plugin.getLogger().warning("Update check failed for " + name + ": " + e.getMessage());
            return UpdateResult.error(name, installedVersion);
        }
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
    private UpdateResult checkByName(String name, String installedVersion, String override,
            String gameVersion, boolean includePrereleases, boolean checkHangar) throws Exception {
        // An override like "hangar:ProtocolLib" pins the plugin to a Hangar project.
        if (override != null && override.toLowerCase(Locale.ROOT).startsWith("hangar:")) {
            return checkOnHangar(name, installedVersion, override.substring("hangar:".length()).strip());
        }

        ModrinthClient.Project project = resolved.get(name);
        if (project == null) {
            Optional<ModrinthClient.Project> found = override != null
                    ? modrinth.fetchProject(override)
                    : modrinth.resolveProject(name);
            if (found.isEmpty()) {
                if (override == null && checkHangar) {
                    return checkOnHangar(name, installedVersion, null);
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
        return new UpdateResult(name, installedVersion, latestNumber,
                modrinthUrl(project.slug()), UpdateResult.SOURCE_MODRINTH,
                outdated ? UpdateResult.Status.UPDATE_AVAILABLE : UpdateResult.Status.UP_TO_DATE);
    }

    /**
     * Last-resort lookup on Hangar. Hangar has no file-hash lookup, so this
     * is name and version-string matching only; only the Release channel is
     * considered.
     */
    private UpdateResult checkOnHangar(String name, String installedVersion, String overrideSlug)
            throws Exception {
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
        return new UpdateResult(name, installedVersion, latest.get(),
                project.url(), UpdateResult.SOURCE_HANGAR,
                outdated ? UpdateResult.Status.UPDATE_AVAILABLE : UpdateResult.Status.UP_TO_DATE);
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
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String override(String pluginName) {
        ConfigurationSection overrides = plugin.getConfig().getConfigurationSection("overrides");
        if (overrides == null) {
            return null;
        }
        for (String key : overrides.getKeys(false)) {
            if (key.equalsIgnoreCase(pluginName)) {
                return overrides.getString(key);
            }
        }
        return null;
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

        if (outdated.isEmpty()) {
            plugin.getLogger().info("All " + results.size() + " checked plugins are up to date"
                    + (unknown.isEmpty() ? "."
                            : " (" + unknown.size() + " not found on " + UpdateResult.ALL_SOURCES + ")."));
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
    }
}
