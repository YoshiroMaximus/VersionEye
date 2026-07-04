package dev.yoshiro.versioneye;

import java.util.concurrent.TimeUnit;

import org.bukkit.plugin.java.JavaPlugin;

public final class VersionEyePlugin extends JavaPlugin {

    private UpdateCheckService checkService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.checkService = new UpdateCheckService(this);

        getCommand("updatecheck").setExecutor(new UpdateCheckCommand(this, checkService));
        getServer().getPluginManager().registerEvents(new JoinListener(this, checkService), this);

        // Initial check shortly after startup, once all plugins are enabled.
        getServer().getAsyncScheduler().runDelayed(this,
                task -> checkService.runCheckAndLog(), 10, TimeUnit.SECONDS);

        long intervalHours = getConfig().getLong("check-interval-hours", 6);
        if (intervalHours > 0) {
            getServer().getAsyncScheduler().runAtFixedRate(this,
                    task -> checkService.runCheckAndLog(),
                    intervalHours, intervalHours, TimeUnit.HOURS);
        }
    }

    @Override
    public void onDisable() {
        getServer().getAsyncScheduler().cancelTasks(this);
        if (checkService != null) {
            checkService.shutdown();
        }
    }
}
