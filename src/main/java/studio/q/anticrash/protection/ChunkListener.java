package studio.q.anticrash.protection;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import studio.q.anticrash.api.Detection;
import studio.q.anticrash.api.Severity;
import studio.q.anticrash.core.ModuleToggles;
import studio.q.anticrash.core.ProtectionEngine;
import studio.q.anticrash.core.ratelimit.RateMeter;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chunk protection. Chunk requests cannot be cancelled from the Bukkit API
 * (the ChunkLoadEvent is not cancellable - verified), so this module
 * monitors and attributes load storms: it tracks new-chunk generation per
 * player over a rolling window and reports abuse patterns. Real mitigation
 * for request floods happens in the packet layer (movement flood = fewer
 * chunk requests) and via server software settings (view distance, Paper's
 * chunk system config), as documented in the README.
 */
public final class ChunkListener implements Listener {
    private final ProtectionEngine engine;
    private final ModuleToggles toggles;
    private final RateMeter newChunks = new RateMeter(10_000);
    private final RateMeter allChunks = new RateMeter(10_000);
    private final ConcurrentHashMap<UUID, RateMeter> perPlayer = new ConcurrentHashMap<>();
    private volatile int perPlayerNewChunksPer10s = 80;
    private volatile int globalNewChunksPer10s = 600;

    public ChunkListener(ProtectionEngine engine, ModuleToggles toggles) {
        this.engine = engine;
        this.toggles = toggles;
    }

    public void configure(int perPlayerNewChunksPer10s, int globalNewChunksPer10s) {
        this.perPlayerNewChunksPer10s = perPlayerNewChunksPer10s;
        this.globalNewChunksPer10s = globalNewChunksPer10s;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!toggles.chunks) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean isNew = event.isNewChunk();
        long globalNew = newChunks.increment(now);
        allChunks.increment(now);

        if (isNew && globalNew > globalNewChunksPer10s) {
            // Report at most once per window.
            if (globalNew == globalNewChunksPer10s + 1) {
                Detection d = Detection.builder(null, "server", "ChunkGuard", "chunk-generation-storm")
                        .detail("Global new-chunk generation rate exceeded")
                        .actual(globalNew + " new chunks/10s")
                        .limit(globalNewChunksPer10s + "/10s")
                        .severity(Severity.HIGH)
                        .world(event.getWorld().getName())
                        .build();
                engine.handle(d);
            }
        }
    }

    /** Called from the connection listener on movement to a new chunk. */
    public void notePlayerMovement(UUID playerId, String name, int chunkX, int chunkZ, long now) {
        if (!toggles.chunks) {
            return;
        }
        RateMeter m = perPlayer.computeIfAbsent(playerId, k -> new RateMeter(10_000));
        long c = m.increment(now);
        if (c == perPlayerNewChunksPer10s + 1) {
            Detection d = Detection.builder(playerId, name, "ChunkGuard", "chunk-request-storm")
                    .detail("Per-player chunk request rate exceeded")
                    .actual(c + " chunk visits/10s")
                    .limit(perPlayerNewChunksPer10s + "/10s")
                    .severity(Severity.MEDIUM)
                    .build();
            engine.handle(d);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        // Monitoring hook: unload storms are not mitigated here; counted only.
        allChunks.increment(System.currentTimeMillis());
    }

    public int trackedPlayers() {
        return perPlayer.size();
    }

    /** Removes tracking for a player who left. */
    public void remove(UUID playerId) {
        perPlayer.remove(playerId);
    }

    public long globalNewChunks() {
        return newChunks.total();
    }
}
