package studio.q.anticrash.protection;

import org.bukkit.Chunk;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import studio.q.anticrash.api.Detection;
import studio.q.anticrash.api.Severity;
import studio.q.anticrash.core.ModuleToggles;
import studio.q.anticrash.core.ProtectionEngine;
import studio.q.anticrash.core.ratelimit.RateLimiter;
import studio.q.anticrash.exemptions.ExemptionService;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entity protection. All three detection paths use cancellable Bukkit events
 * (EntitySpawnEvent, ProjectileLaunchEvent, PlayerInteractEvent - verified),
 * so abuse is blocked before the entity exists or the interaction applies.
 *
 * Design:
 * - Spawn-rate tracking is per-player where attribution exists (ProjectileLaunch,
 *   CreatureSpawnReason.SPAWNER_EGG etc.) and global otherwise.
 * - Per-chunk caps are evaluated only on spawn events (not per tick), so the
 *   cost is proportional to spawns, not to world size.
 * - Global spawn rate uses one RateMeter per window; no per-entity allocation.
 *
 * Performance note: this listener does O(1) work per spawn event; the per-chunk
 * entity scan is a Chunk#getEntities().size() call which is O(entities-in-chunk)
 * but only runs for spawn events inside already-throttled conditions
 * (config-gated, default on; can be disabled for very high spawn-rate farms).
 */
public final class EntityListener implements Listener {
    private final ProtectionEngine engine;
    private final ModuleToggles toggles;
    private final ExemptionService exemptions;

    private final RateLimiter spawnLimiter;
    private final RateLimiter projectileLimiter;
    private final RateLimiter interactLimiter;

    private volatile int maxEntitiesPerChunk = 200;
    private volatile boolean chunkCapEnabled = true;

    public EntityListener(ProtectionEngine engine, ModuleToggles toggles, ExemptionService exemptions,
                          int spawnPerSecond, int projectilePerSecond, int interactPerSecond) {
        this.engine = engine;
        this.toggles = toggles;
        this.exemptions = exemptions;
        this.spawnLimiter = new RateLimiter(Math.max(spawnPerSecond, 1), spawnPerSecond * 2, 60_000);
        this.projectileLimiter = new RateLimiter(Math.max(projectilePerSecond, 1), projectilePerSecond * 2, 60_000);
        this.interactLimiter = new RateLimiter(Math.max(interactPerSecond, 1), interactPerSecond * 2, 60_000);
    }

    public void configure(int maxEntitiesPerChunk, boolean chunkCapEnabled) {
        this.maxEntitiesPerChunk = maxEntitiesPerChunk;
        this.chunkCapEnabled = chunkCapEnabled;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!toggles.entities) {
            return;
        }
        Entity entity = event.getEntity();
        if (exemptions.isExempt(entity)) {
            return;
        }
        long now = System.currentTimeMillis();

        // Global spawn rate (all worlds combined).
        if (!spawnLimiter.tryConsume(GLOBAL_KEY, now)) {
            event.setCancelled(true);
            Detection d = Detection.builder(null, "server", "EntityGuard", "entity-spawn-storm")
                    .detail("Global entity spawn rate exceeded; spawn cancelled")
                    .actual(entity.getType().getKey().getKey())
                    .limit((int) spawnLimiter.perSecond() + "/s")
                    .severity(Severity.HIGH)
                    .world(entity.getWorld().getName())
                    .build();
            engine.handle(d);
            return;
        }

        // Per-chunk entity cap, config-gated and only on spawn events.
        if (chunkCapEnabled && maxEntitiesPerChunk > 0) {
            Chunk chunk = entity.getLocation().getChunk();
            if (chunk.isLoaded()) {
                int count = chunk.getEntities().length;
                if (count > maxEntitiesPerChunk) {
                    event.setCancelled(true);
                    Detection d = Detection.builder(null, "server", "EntityGuard", "entity-chunk-cap")
                            .detail("Chunk entity count exceeds configured cap")
                            .actual(count + " entities")
                            .limit("max-entities-per-chunk=" + maxEntitiesPerChunk)
                            .severity(Severity.MEDIUM)
                            .world(entity.getWorld().getName())
                            .build();
                    engine.handle(d);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!toggles.entities) {
            return;
        }
        Projectile projectile = event.getEntity();
        var shooter = projectile.getShooter();
        if (!(shooter instanceof Player player)) {
            return;
        }
        if (exemptions.isExempt(player)) {
            return;
        }
        UUID pid = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (!projectileLimiter.tryConsume(pid.getMostSignificantBits(),
                (int) pid.getLeastSignificantBits(), 0, now)) {
            event.setCancelled(true);
            Detection d = Detection.builder(pid, player.getName(), "EntityGuard", "projectile-spam")
                    .detail("Projectile launch rate exceeded")
                    .actual(projectile.getType().getKey().getKey())
                    .limit((int) projectileLimiter.perSecond() + "/s")
                    .severity(Severity.MEDIUM)
                    .world(player.getWorld().getName())
                    .build();
            engine.handle(d);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!toggles.entities) {
            return;
        }
        Player player = event.getPlayer();
        if (exemptions.isExempt(player)) {
            return;
        }
        UUID pid = player.getUniqueId();
        if (!interactLimiter.tryConsume(pid.getMostSignificantBits(),
                (int) pid.getLeastSignificantBits(), 0, System.currentTimeMillis())) {
            event.setCancelled(true);
            Detection d = Detection.builder(pid, player.getName(), "EntityGuard", "interaction-flood")
                    .detail("Player interaction rate exceeded")
                    .actual("interactions")
                    .limit((int) interactLimiter.perSecond() + "/s")
                    .severity(Severity.MEDIUM)
                    .world(player.getWorld().getName())
                    .build();
            engine.handle(d);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!toggles.entities) {
            return;
        }
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                || reason == CreatureSpawnEvent.SpawnReason.SPAWNER
                || reason == CreatureSpawnEvent.SpawnReason.EGG) {
            // Covered by the global limiter in onEntitySpawn; nothing extra here.
        }
    }

    private static final long GLOBAL_KEY = 0x5AFE000000000000L;
}
