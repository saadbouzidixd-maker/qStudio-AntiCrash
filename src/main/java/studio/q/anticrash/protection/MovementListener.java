package studio.q.anticrash.protection;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;

import studio.q.anticrash.api.Detection;
import studio.q.anticrash.api.Severity;
import studio.q.anticrash.core.MainThread;
import studio.q.anticrash.core.ModuleToggles;
import studio.q.anticrash.core.ProtectionEngine;
import studio.q.anticrash.movement.MovementChecks;
import studio.q.anticrash.movement.MovementTracker;

import java.util.Locale;

/**
 * Bukkit movement listener (layer 2). The packet layer cancels malformed
 * packets before the server processes them; this listener is a defense-in-
 * depth net for servers without a packet layer and for movement that enters
 * through other paths (vehicle dismount, plugin-forced positions).
 *
 * It never cancels teleports or velocity: it only reacts to clearly invalid
 * resulting positions (NaN/Infinity), which are unambiguous client faults or
 * plugin bugs; cancelling the move to the previous location is the standard,
 * safe mitigation.
 */
public final class MovementListener implements Listener {
    private final ProtectionEngine engine;
    private final ModuleToggles toggles;
    private final MovementTracker tracker;
    private final MainThread mainThread;

    public MovementListener(ProtectionEngine engine, ModuleToggles toggles,
                            MovementTracker tracker, MainThread mainThread) {
        this.engine = engine;
        this.toggles = toggles;
        this.tracker = tracker;
        this.mainThread = mainThread;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!toggles.movement) {
            return;
        }
        var to = event.getTo();
        if (to == null) {
            return;
        }
        double x = to.getX();
        double y = to.getY();
        double z = to.getZ();
        if (!MovementChecks.isFinite(x, y, z)) {
            event.setTo(event.getFrom());
            Detection d = Detection.builder(event.getPlayer().getUniqueId(), event.getPlayer().getName(),
                            "Movement", "movement-invalid")
                    .detail("Non-finite coordinates in move event")
                    .actual(MovementChecks.describeInvalid(x, y, z))
                    .limit("finite coordinates")
                    .severity(Severity.CRITICAL)
                    .world(to.getWorld() == null ? "" : to.getWorld().getName())
                    .build();
            engine.handle(d);
            return;
        }
        var from = event.getFrom();
        MovementTracker.Sample s = new MovementTracker.Sample(x, y, z,
                to.getYaw(), to.getPitch(), System.currentTimeMillis());
        MovementTracker.Analysis a = tracker.observe(event.getPlayer().getUniqueId(), s);
        if (a.impossibleDelta()) {
            Detection d = Detection.builder(event.getPlayer().getUniqueId(), event.getPlayer().getName(),
                            "Movement", "movement-extreme-delta")
                    .detail("Move event delta exceeds physical bounds")
                    .actual(a.describe())
                    .limit(a.limit())
                    .severity(Severity.HIGH)
                    .world(to.getWorld() == null ? "" : to.getWorld().getName())
                    .build();
            engine.handle(d);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        // Teleports legitimately produce large deltas; the packet layer already
        // grants one free large delta. Here we only record the reset hint.
        if (toggles.movement) {
            tracker.remove(event.getPlayer().getUniqueId());
        }
    }
}
