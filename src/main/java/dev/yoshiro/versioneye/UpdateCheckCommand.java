package dev.yoshiro.versioneye;

import java.util.List;
import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

final class UpdateCheckCommand implements TabExecutor {

    private static final List<String> COMPLETIONS = List.of("-prerelease", "download", "ignore", "unignore", "reload");
    private static final String USAGE = "[-prerelease] | download [plugin] | ignore <plugin> | unignore <plugin> | reload";

    private final VersionEyePlugin plugin;
    private final UpdateCheckService checkService;

    UpdateCheckCommand(VersionEyePlugin plugin, UpdateCheckService checkService) {
        this.plugin = plugin;
        this.checkService = checkService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "reload" -> {
                    if (requirePermission(sender, "versioneye.reload")) {
                        plugin.reload();
                        sender.sendMessage(Component.text("VersionEye config reloaded.",
                                NamedTextColor.GREEN));
                    }
                    return true;
                }
                case "download" -> {
                    if (requirePermission(sender, "versioneye.download")) {
                        download(sender, args.length > 1 ? args[1] : null);
                    }
                    return true;
                }
                case "ignore", "unignore" -> {
                    if (!requirePermission(sender, "versioneye.ignore")) {
                        return true;
                    }
                    if (args.length < 2) {
                        sender.sendMessage(Component.text("Usage: /" + label + " " + args[0]
                                + " <plugin>", NamedTextColor.RED));
                        return true;
                    }
                    String message = args[0].equalsIgnoreCase("ignore")
                            ? checkService.ignoreLatest(args[1])
                            : checkService.unignore(args[1]);
                    sender.sendMessage(Component.text(message, NamedTextColor.YELLOW));
                    return true;
                }
                default -> {
                }
            }
        }

        boolean includePrereleases = plugin.getConfig().getBoolean("include-prereleases", false);
        for (String arg : args) {
            if (arg.equalsIgnoreCase("-prerelease") || arg.equalsIgnoreCase("-pre")) {
                includePrereleases = true;
            } else {
                sender.sendMessage(Component.text("Usage: /" + label + " " + USAGE,
                        NamedTextColor.RED));
                return true;
            }
        }

        final boolean prereleases = includePrereleases;
        sender.sendMessage(Component.text("Checking plugins for updates"
                + (prereleases ? " (including prereleases)" : "") + "...", NamedTextColor.GRAY));
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            List<UpdateResult> results = checkService.checkAll(prereleases);
            report(sender, results);
        });
        return true;
    }

    /** Fetches updates into the update folder, checking first if needed. */
    private void download(CommandSender sender, String pluginName) {
        sender.sendMessage(Component.text("Downloading updates...", NamedTextColor.GRAY));
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            // Reuse the last check's findings when there are any; a full
            // re-scan is only needed when nothing is pending yet.
            if (checkService.downloadablePluginNames().isEmpty()) {
                checkService.checkAll();
            }
            for (String line : checkService.downloadUpdates(pluginName)) {
                sender.sendMessage(Component.text(line,
                        line.startsWith("Downloaded") ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            }
        });
    }

    private boolean requirePermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            String typed = args[0].toLowerCase(Locale.ROOT);
            return COMPLETIONS.stream().filter(s -> s.startsWith(typed)).toList();
        }
        if (args.length == 2) {
            List<String> names = switch (args[0].toLowerCase(Locale.ROOT)) {
                case "download" -> checkService.downloadablePluginNames();
                case "ignore" -> checkService.lastOutdated().stream()
                        .map(UpdateResult::pluginName).toList();
                case "unignore" -> checkService.ignoredPluginNames();
                default -> List.of();
            };
            String typed = args[1].toLowerCase(Locale.ROOT);
            return names.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
        }
        return List.of();
    }

    private void report(CommandSender sender, List<UpdateResult> results) {
        List<UpdateResult> outdated = results.stream()
                .filter(r -> r.status() == UpdateResult.Status.UPDATE_AVAILABLE)
                .toList();
        List<UpdateResult> notFound = results.stream()
                .filter(r -> r.status() == UpdateResult.Status.NOT_FOUND)
                .toList();
        List<UpdateResult> errors = results.stream()
                .filter(r -> r.status() == UpdateResult.Status.ERROR)
                .toList();

        int checked = results.size() - errors.size();
        if (outdated.isEmpty()) {
            if (checked > 0) {
                sender.sendMessage(Component.text("All " + checked
                        + " checked plugins are up to date.", NamedTextColor.GREEN));
            }
        } else {
            sender.sendMessage(Component.text(outdated.size()
                    + " plugin(s) have updates available:", NamedTextColor.GOLD));
            for (UpdateResult r : outdated) {
                sender.sendMessage(Component.text("  " + r.pluginName() + " ", NamedTextColor.YELLOW)
                        .append(Component.text(r.installedVersion(), NamedTextColor.RED))
                        .append(Component.text(" -> ", NamedTextColor.GRAY))
                        .append(Component.text(r.latestVersion(), NamedTextColor.GREEN))
                        .append(Component.text(" [" + r.source() + "]", NamedTextColor.AQUA)
                                .clickEvent(ClickEvent.openUrl(r.projectUrl()))));
            }
        }
        if (!notFound.isEmpty()) {
            sender.sendMessage(Component.text("Not found on " + UpdateResult.ALL_SOURCES + ": "
                    + notFound.stream().map(UpdateResult::pluginName)
                            .reduce((a, b) -> a + ", " + b).orElse(""),
                    NamedTextColor.GRAY));
        }
        if (!errors.isEmpty()) {
            sender.sendMessage(Component.text("Check failed for: "
                    + errors.stream().map(UpdateResult::pluginName)
                            .reduce((a, b) -> a + ", " + b).orElse("")
                    + " (see console)", NamedTextColor.RED));
        }
    }
}
