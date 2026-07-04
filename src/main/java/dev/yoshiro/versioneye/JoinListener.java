package dev.yoshiro.versioneye;

import java.util.List;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

final class JoinListener implements Listener {

    private final VersionEyePlugin plugin;
    private final UpdateCheckService checkService;

    JoinListener(VersionEyePlugin plugin, UpdateCheckService checkService) {
        this.plugin = plugin;
        this.checkService = checkService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("notify-on-join", true)) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("versioneye.notify")) {
            return;
        }
        List<UpdateResult> outdated = checkService.lastOutdated();
        if (outdated.isEmpty()) {
            return;
        }
        player.sendMessage(Component.text("[VersionEye] ", NamedTextColor.AQUA)
                .append(Component.text(outdated.size() + " plugin(s) have updates available:",
                        NamedTextColor.GOLD)));
        for (UpdateResult r : outdated) {
            player.sendMessage(Component.text("  " + r.pluginName() + " ", NamedTextColor.YELLOW)
                    .append(Component.text(r.installedVersion(), NamedTextColor.RED))
                    .append(Component.text(" -> ", NamedTextColor.GRAY))
                    .append(Component.text(r.latestVersion(), NamedTextColor.GREEN))
                    .append(Component.text(" [Modrinth]", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.openUrl(r.projectUrl()))));
        }
    }
}
