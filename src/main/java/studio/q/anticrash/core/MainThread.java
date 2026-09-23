package studio.q.anticrash.core;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Small helper for safely running work on the main server thread from any
 * context (netty threads, log writer, async tasks).
 */
public final class MainThread {
    private final JavaPlugin plugin;

    public MainThread(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void run(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    /** Schedules work even when already on the main thread (always deferred). */
    public void runDeferred(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    public void runAsync(Runnable task) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
    }
}
