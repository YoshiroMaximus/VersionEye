package dev.yoshiro.versioneye;

import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

final class UpdateCheckCommand implements CommandExecutor {

    private final VersionEyePlugin plugin;
    private final UpdateCheckService checkService;

    UpdateCheckCommand(VersionEyePlugin plugin, UpdateCheckService checkService) {
        this.plugin = plugin;
        this.checkService = checkService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage(Component.text("Checking plugins for updates...", NamedTextColor.GRAY));
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            List<UpdateResult> results = checkService.checkAll();
            report(sender, results);
        });
        return true;
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
            sender.sendMessage(Component.text("Not found on Modrinth or Hangar: "
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
