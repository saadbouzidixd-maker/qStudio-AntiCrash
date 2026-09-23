package studio.q.anticrash.protection;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import studio.q.anticrash.api.Detection;
import studio.q.anticrash.api.Severity;
import studio.q.anticrash.core.ModuleToggles;
import studio.q.anticrash.core.ProtectionEngine;
import studio.q.anticrash.core.ratelimit.WindowCounter;
import studio.q.anticrash.movement.MovementTracker;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Connection protection.
 *
 * Detected behaviors:
 * - Login bursts from a single IP (bot floods): tracked in a sliding window;
 *   excess logins during a burst are temporarily disallowed. This is a soft,
 *   time-windowed refusal, never a permanent ban: the counter expires by
 *   itself and honest players who reconnect later are unaffected.
 * - Reconnect spam per name (crash-rejoin loops): counted per window; excess
 *   reconnects are temporarily disallowed the same way.
 *
 * Why AsyncPlayerPreLoginEvent: it is async (verified), fires before any
 * world/entity allocation for the player, and disallow() here prevents the
 * join from ever reaching the heavy login path. We never touch the ban list.
 */
public final class ConnectionListener implements Listener {
    private final ProtectionEngine engine;
    private final ModuleToggles toggles;
    private final MovementTracker movement;
    private final ChunkListener chunkListener;
    private final WindowCounter loginsPerIp;
    private final WindowCounter joinsPerName;

    private volatile int maxLoginsPerIpPerWindow = 8;
    private volatile int windowMillis = 10_000;
    private volatile boolean enabled = true;

    public ConnectionListener(ProtectionEngine engine, ModuleToggles toggles, MovementTracker movement,
                              ChunkListener chunkListener,
                              int maxLoginsPerIpPerWindow, int windowMillis) {
        this.engine = engine;
        this.toggles = toggles;
        this.movement = movement;
        this.chunkListener = chunkListener;
        this.maxLoginsPerIpPerWindow = maxLoginsPerIpPerWindow;
        this.windowMillis = windowMillis;
        this.loginsPerIp = new WindowCounter(windowMillis, 120_000);
        this.joinsPerName = new WindowCounter(windowMillis, 120_000);
    }

    public void configure(int maxLoginsPerIpPerWindow, int windowMillis, boolean enabled) {
        this.maxLoginsPerIpPerWindow = maxLoginsPerIpPerWindow;
        this.windowMillis = windowMillis;
        this.enabled = enabled;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!toggles.connections || !enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        InetAddress address = event.getAddress();
        long ipKey = ipKey(address);

        long ipLogins = loginsPerIp.increment(ipKey, now);
        if (ipLogins > maxLoginsPerIpPerWindow) {
            // Soft refusal inside the window; expires automatically. No ban list is touched.
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    net.kyori.adventure.text.Component.text(
                            "qStudio AntiCrash: connection rate limit active, retry shortly"));
            Detection d = Detection.builder(event.getUniqueId(), event.getName(), "ConnectionGuard", "login-flood")
                    .detail("Login burst from a single address exceeds window limit")
                    .actual(ipLogins + " logins/" + (windowMillis / 1000) + "s")
                    .limit(maxLoginsPerIpPerWindow + "/" + (windowMillis / 1000) + "s")
                    .severity(Severity.HIGH)
                    .build();
            engine.handle(d);
            return;
        }

        long nameJoins = joinsPerName.increment(nameKey(event.getName()), now);
        if (nameJoins > maxLoginsPerIpPerWindow) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    net.kyori.adventure.text.Component.text(
                            "qStudio AntiCrash: reconnect rate limit active, retry shortly"));
            Detection d = Detection.builder(event.getUniqueId(), event.getName(), "ConnectionGuard", "reconnect-spam")
                    .detail("Reconnect attempts for one name exceed window limit")
                    .actual(nameJoins + " attempts/" + (windowMillis / 1000) + "s")
                    .limit(maxLoginsPerIpPerWindow + "/" + (windowMillis / 1000) + "s")
                    .severity(Severity.MEDIUM)
                    .build();
            engine.handle(d);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID pid = event.getPlayer().getUniqueId();
        movement.remove(pid);
        chunkListener.remove(pid);
    }

    /** Packs an IPv4/IPv6 address into a long key (best-effort; collisions are acceptable here). */
    private static long ipKey(InetAddress address) {
        if (address == null) {
            return 0L;
        }
        byte[] bytes = address.getAddress();
        long key = 0;
        for (int i = 0; i < Math.min(bytes.length, 8); i++) {
            key = (key << 8) | (bytes[i] & 0xFFL);
        }
        // For IPv4 (4 bytes) this packs fully. For IPv6 the first 8 bytes identify
        // the prefix, which is the right granularity for burst detection.
        return key;
    }

    private static long nameKey(String name) {
        return 0x4E414D45L ^ (name == null ? 0 : name.toLowerCase(Locale.ROOT).hashCode()) * 0x9E3779B97F4A7C15L;
    }

    public int trackedIps() {
        return loginsPerIp.size();
    }
}
