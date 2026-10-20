package com.example.shopcore.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Bukkit 调度小工具：异步回调统一切回主线程，插件卸载后不再调度。 */
public final class Scheduler {

    private Scheduler() {
    }

    public static void runSync(Plugin plugin, Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            safeRun(plugin, task);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> safeRun(plugin, task));
        }
    }

    public static void runSyncLater(Plugin plugin, Runnable task, long delayTicks) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> safeRun(plugin, task), Math.max(1, delayTicks));
    }

    public static void runAsync(Plugin plugin, Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> safeRun(plugin, task));
    }

    private static void safeRun(Plugin plugin, Runnable task) {
        try {
            task.run();
        } catch (Throwable ex) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "商店任务执行失败", ex);
        }
    }
}
