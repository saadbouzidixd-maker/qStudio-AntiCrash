package studio.q.anticrash.protection;

import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;

import studio.q.anticrash.api.Detection;
import studio.q.anticrash.api.Severity;
import studio.q.anticrash.core.ModuleToggles;
import studio.q.anticrash.core.ProtectionEngine;
import studio.q.anticrash.core.ratelimit.RateMeter;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redstone / block-update storm protection.
 *
 * What each check prevents:
 * - Redstone current changes (BlockRedstoneEvent): observer/comparator clocks
 *   that hammer the redstone engine. NOT cancellable in Bukkit; the real lever
 *   is setNewCurrent(0), which neutralizes the current change itself.
 * - BlockPhysicsEvent: physics loops (falling-block chains, sand/gravel
 *   duplicators). Cancellable; cancelling a storm's physics events halts the
 *   chain without touching normal gameplay below thresholds.
 * - Piston extend/retract: piston bolts and storm machines. Cancellable.
 * - Hopper flow (InventoryMoveItemEvent): hopper lag machines. Cancellable.
 * - Dispenser/dropper spam (BlockDispenseEvent): rapid item dispensing. Cancellable.
 *
 * All counters are single-threaded RateMeters (events fire on the main thread).
 * Thresholds are per-window (per second) with a short auto-restore: once a
 * storm is tripped, mitigation stays on for the configured cooldown and then
 * automatically re-enables - farms recover on their own.
 */
public final class RedstoneListener implements Listener {
    private final ProtectionEngine engine;
    private final ModuleToggles toggles;

    private final RateMeter redstone = new RateMeter(1000);
    private final RateMeter physics = new RateMeter(1000);
    private final RateMeter pistons = new RateMeter(1000);
    private final RateMeter hoppers = new RateMeter(1000);
    private final RateMeter dispensers = new RateMeter(1000);

    private volatile int maxRedstonePerSecond = 4000;
    private volatile int maxPhysicsPerSecond = 8000;
    private volatile int maxPistonsPerSecond = 400;
    private volatile int maxHopperMovesPerSecond = 2000;
    private volatile int maxDispensesPerSecond = 200;

    /** Per-world storm state so one blazing machine doesn't mute the whole server. */
    private final Map<String, StormState> storms = new ConcurrentHashMap<>();

    private static final class StormState {
        volatile boolean redstoneMuted;
        volatile long redstoneUntil;
        volatile boolean physicsBlocked;
        volatile long physicsUntil;
        volatile boolean pistonsBlocked;
        volatile long pistonsUntil;
        volatile boolean hoppersBlocked;
        volatile long hoppersUntil;
        volatile boolean dispensersBlocked;
        volatile long dispensersUntil;
    }

    public RedstoneListener(ProtectionEngine engine, ModuleToggles toggles) {
        this.engine = engine;
        this.toggles = toggles;
    }

    public void configure(int maxRedstonePerSecond, int maxPhysicsPerSecond, int maxPistonsPerSecond,
                          int maxHopperMovesPerSecond, int maxDispensesPerSecond, int stormCooldownMs) {
        this.maxRedstonePerSecond = maxRedstonePerSecond;
        this.maxPhysicsPerSecond = maxPhysicsPerSecond;
        this.maxPistonsPerSecond = maxPistonsPerSecond;
        this.maxHopperMovesPerSecond = maxHopperMovesPerSecond;
        this.maxDispensesPerSecond = maxDispensesPerSecond;
        this.stormCooldownMs = stormCooldownMs;
    }

    private volatile int stormCooldownMs = 5000;

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        if (!toggles.redstone) {
            return;
        }
        long now = System.currentTimeMillis();
        redstone.increment(now);
        StormState st = storms.computeIfAbsent(event.getBlock().getWorld().getName(), k -> new StormState());
        if (st.redstoneMuted && now < st.redstoneUntil) {
            // Neutralize the current change while the storm window is open.
            event.setNewCurrent(0);
            return;
        }
        if (st.redstoneMuted) {
            st.redstoneMuted = false;
        }
        int limit = maxRedstonePerSecond;
        if (limit > 0 && redstone.current(now) > limit) {
            st.redstoneMuted = true;
            st.redstoneUntil = now + stormCooldownMs;
            redstone.reset();
            Detection d = Detection.builder(null, "server", "RedstoneGuard", "redstone-storm")
                    .detail("Redstone current-change rate exceeded; current neutralized for "
                            + stormCooldownMs + "ms")
                    .actual(redstone.total() + " total changes")
                    .limit(limit + "/s")
                    .severity(Severity.HIGH)
                    .world(event.getBlock().getWorld().getName())
                    .build();
            engine.handle(d);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        if (!toggles.physics) {
            return;
        }
        long now = System.currentTimeMillis();
        physics.increment(now);
        StormState st = storms.computeIfAbsent(event.getBlock().getWorld().getName(), k -> new StormState());
        if (st.physicsBlocked && now < st.physicsUntil) {
            event.setCancelled(true);
            return;
        }
        if (st.physicsBlocked) {
            st.physicsBlocked = false;
        }
        int limit = maxPhysicsPerSecond;
        if (limit > 0 && physics.current(now) > limit) {
            st.physicsBlocked = true;
            st.physicsUntil = now + stormCooldownMs;
            physics.reset();
            Detection d = Detection.builder(null, "server", "PhysicsGuard", "physics-storm")
                    .detail("Block physics rate exceeded; physics suppressed for " + stormCooldownMs + "ms")
                    .actual(physics.total() + " total physics events")
                    .limit(limit + "/s")
                    .severity(Severity.HIGH)
                    .world(event.getBlock().getWorld().getName())
                    .build();
            engine.handle(d);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        checkPiston(event.getBlock().getWorld().getName(), event, event.getBlocks().size());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        checkPiston(event.getBlock().getWorld().getName(), event, event.getBlocks().size());
    }

    private void checkPiston(String world, org.bukkit.event.Cancellable event, int blocks) {
        if (!toggles.redstone) {
            return;
        }
        long now = System.currentTimeMillis();
        pistons.increment(now);
        StormState st = storms.computeIfAbsent(world, k -> new StormState());
        if (st.pistonsBlocked && now < st.pistonsUntil) {
            event.setCancelled(true);
            return;
        }
        if (st.pistonsBlocked) {
            st.pistonsBlocked = false;
        }
        int limit = maxPistonsPerSecond;
        if (limit > 0 && pistons.current(now) > limit) {
            st.pistonsBlocked = true;
            st.pistonsUntil = now + stormCooldownMs;
            pistons.reset();
            Detection d = Detection.builder(null, "server", "RedstoneGuard", "piston-storm")
                    .detail("Piston movement rate exceeded; pistons blocked for " + stormCooldownMs + "ms")
                    .actual(pistons.total() + " total piston moves")
                    .limit(limit + "/s")
                    .severity(Severity.HIGH)
                    .world(world)
                    .build();
            engine.handle(d);
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        if (!toggles.hopperFlow) {
            return;
        }
        long now = System.currentTimeMillis();
        hoppers.increment(now);
        if (hoppers.current(now) > maxHopperMovesPerSecond && maxHopperMovesPerSecond > 0) {
            event.setCancelled(true);
            if (hoppers.current(now) == maxHopperMovesPerSecond + 1) {
                Detection d = Detection.builder(null, "server", "HopperGuard", "hopper-storm")
                        .detail("Hopper item-move rate exceeded; moves cancelled while storm active")
                        .actual(hoppers.total() + " total moves")
                        .limit(maxHopperMovesPerSecond + "/s")
                        .severity(Severity.MEDIUM)
                        .build();
                engine.handle(d);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (!toggles.redstone) {
            return;
        }
        long now = System.currentTimeMillis();
        dispensers.increment(now);
        StormState st = storms.computeIfAbsent(event.getBlock().getWorld().getName(), k -> new StormState());
        if (st.dispensersBlocked && now < st.dispensersUntil) {
            event.setCancelled(true);
            return;
        }
        if (st.dispensersBlocked) {
            st.dispensersBlocked = false;
        }
        int limit = maxDispensesPerSecond;
        if (limit > 0 && dispensers.current(now) > limit) {
            st.dispensersBlocked = true;
            st.dispensersUntil = now + stormCooldownMs;
            dispensers.reset();
            Detection d = Detection.builder(null, "server", "RedstoneGuard", "dispenser-storm")
                    .detail("Dispense rate exceeded; dispensing blocked for " + stormCooldownMs + "ms")
                    .actual(dispensers.total() + " total dispenses")
                    .limit(limit + "/s")
                    .severity(Severity.MEDIUM)
                    .world(event.getBlock().getWorld().getName())
                    .build();
            engine.handle(d);
            event.setCancelled(true);
        }
    }

    public void resetStorms() {
        storms.clear();
        redstone.reset();
        physics.reset();
        pistons.reset();
        hoppers.reset();
        dispensers.reset();
    }
}
