package dev.yoshiro.versioneye;

import java.util.concurrent.TimeUnit;

import org.bukkit.plugin.java.JavaPlugin;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

public final class VersionEyePlugin extends JavaPlugin {

    private UpdateCheckService checkService;
    private ScheduledTask periodicTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.checkService = new UpdateCheckService(this);

        UpdateCheckCommand command = new UpdateCheckCommand(this, checkService);
        getCommand("updatecheck").setExecutor(command);
        getCommand("updatecheck").setTabCompleter(command);
        getServer().getPluginManager().registerEvents(new JoinListener(this, checkService), this);

        // Initial check shortly after startup, once all plugins are enabled.
        getServer().getAsyncScheduler().runDelayed(this,
                task -> checkService.runCheckAndLog(), 10, TimeUnit.SECONDS);

        schedulePeriodicCheck();
    }

    /**
     * Re-reads config.yml and applies it without a restart: cached project
     * resolutions are dropped (so changed overrides take effect) and the
     * periodic check is rescheduled with the configured interval.
     */
    void reload() {
        reloadConfig();
        checkService.clearCaches();
        schedulePeriodicCheck();
    }

    private void schedulePeriodicCheck() {
        if (periodicTask != null) {
            periodicTask.cancel();
            periodicTask = null;
        }
        long intervalHours = getConfig().getLong("check-interval-hours", 6);
        if (intervalHours > 0) {
            periodicTask = getServer().getAsyncScheduler().runAtFixedRate(this,
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
