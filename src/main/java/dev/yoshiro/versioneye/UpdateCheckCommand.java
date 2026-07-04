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

    private static final List<String> COMPLETIONS = List.of("-prerelease", "reload");

    private final VersionEyePlugin plugin;
    private final UpdateCheckService checkService;

    UpdateCheckCommand(VersionEyePlugin plugin, UpdateCheckService checkService) {
        this.plugin = plugin;
        this.checkService = checkService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("versioneye.reload")) {
                sender.sendMessage(Component.text("You don't have permission to reload VersionEye.",
                        NamedTextColor.RED));
                return true;
            }
            plugin.reload();
            sender.sendMessage(Component.text("VersionEye config reloaded.", NamedTextColor.GREEN));
            return true;
        }

        boolean includePrereleases = plugin.getConfig().getBoolean("include-prereleases", false);
        for (String arg : args) {
            if (arg.equalsIgnoreCase("-prerelease") || arg.equalsIgnoreCase("-pre")) {
                includePrereleases = true;
            } else {
                sender.sendMessage(Component.text("Usage: /" + label + " [-prerelease] or /"
                        + label + " reload", NamedTextColor.RED));
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String typed = args[0].toLowerCase(Locale.ROOT);
        return COMPLETIONS.stream().filter(s -> s.startsWith(typed)).toList();
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

        if (outdated.isEmpty()) {
            sender.sendMessage(Component.text("All " + results.size()
                    + " checked plugins are up to date.", NamedTextColor.GREEN));
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
