# qStudio AntiCrash

**Production-grade anti-crash / anti-exploit / server protection plugin for Minecraft servers.**

By **qSa3ed** · Brand **qStudio** · Plugin ID `qstudio-anticrash`

> ⚠️ **Honesty statement:** No plugin can guarantee 100% crash protection. Protection depends on
> the Minecraft version, server software, installed plugins, available APIs, and the specific
> attack vector. qStudio AntiCrash implements *verifiable* detections against *real* entry points
> and clearly documents what it cannot do. Every module listed here is actually implemented —
> nothing is decorative.

---

## Overview

qStudio AntiCrash sits between the network and the server, intercepting dangerous input
**before** it reaches expensive server code where the architecture allows it:

| Layer | Entry point | Interception |
|---|---|---|
| PacketEvents (preferred) | Netty packets | Full — cancel/drop before server decode |
| ProtocolLib (fallback) | Netty packets | Rate limiting (size checks unavailable — see limitations) |
| Bukkit events | Server event bus | Cancel before persistence/expansion |
| JVM monitoring | Heap, TPS, MSPT | Alerts + Safe Mode |

## Supported versions

- **Target:** Paper 26.3 (also runs on Spigot/Purpur of the same family)
- **Java:** 21+ at runtime (compiled with `--release 21`; the Paper 26.3 server itself requires Java 25)
- **Build:** Maven 3.9+ ONLY (no Gradle)

## Requirements

- Java 21+ (the server's own JVM requirement applies)
- Paper / Spigot / Purpur
- **Optional but recommended:** [PacketEvents 2.x](https://github.com/retrooper/packetevents) — enables the full packet layer
- Optional: ProtocolLib 5.x — fallback packet layer (rate limiting only)

## Installation

1. Build or download `qStudio-AntiCrash-1.0.0.jar`
2. Drop it into `plugins/`
3. (Recommended) Install PacketEvents 2.13.0+
4. Restart the server
5. Files are created at `plugins/qStudio-AntiCrash/` (`config.yml`, `messages.yml`, `rules.yml`, `logs/`)

Without PacketEvents/ProtocolLib the plugin still starts and all Bukkit-layer modules work;
packet-level protection is disabled and a clear warning is printed.

## Commands

```
/qanticrash                              help
/qanticrash reload                       reload config + rules
/qanticrash status                       protection & server status
/qanticrash debug player <player>        why modules flag this player
/qanticrash violations <player>          per-module violation levels
/qanticrash reset <player>               reset a player's violations
/qanticrash alerts                       toggle real-time alerts for you
/qanticrash modules                      list protection modules
/qanticrash inspect                      full server diagnostics
/qanticrash test                         live self-test of core components
/qanticrash safemode                     toggle Safe Mode (reversible)
/qanticrash version                      version info
```

Alias: `/qanticrash` → `/qac`

## Permissions

| Permission | Default | Effect |
|---|---|---|
| `qanticrash.admin` | op | Access to `/qanticrash` |
| `qanticrash.reload` | op | Use `reload` |
| `qanticrash.debug` | op | Use `debug` |
| `qanticrash.inspect` | op | Use `inspect` |
| `qanticrash.alerts` | op | Receive real-time alerts |
| `qanticrash.bypass` | false | Full exemption from all protections |

## Modules — what each one actually does

### PacketRate (packet layer; PacketEvents or ProtocolLib)
- **Prevents:** packet floods, burst traffic, interaction spam, command/tab/chat floods, oversized plugin messages, denied payload channels, malformed interaction packets.
- **Where:** netty thread, *before* server decode (PacketEvents cancels packets; ProtocolLib cancels events).
- **How:** one lock-free token-bucket per category (movement, interaction, chat, tab, payload, window clicks, …) + a global per-player bucket. Configurable per-second and burst per category.
- **Cost:** O(1) per packet, one `AtomicLong` CAS per packet, zero allocation after warmup. No tasks per packet.

### Movement (packet layer + Bukkit fallback)
- **Prevents:** NaN/Infinity coordinates or rotation, sustained impossible deltas (with a one-free-large-delta allowance so teleports/pearls/velocity/vehicles never false-positive), extreme rotation spam (>14,400°/s sustained).
- **Never blocks:** legitimate teleports, knockback, velocity, ender pearls, launchers, minigames — the first large delta after a stable state is always allowed; only *consecutive* impossible deltas are flagged.

### BookGuard / SignGuard (Bukkit)
- **Prevents:** book page-count abuse, oversized pages/total/title, oversized sign lines — cancelled at `PlayerEditBookEvent` / `SignChangeEvent` (both verified cancellable) before persistence.

### ChatGuard (Bukkit + packet layer)
- **Prevents:** oversized chat/commands, chat and command rate floods, inventory-click floods, impossible item stack amounts.

### ChunkGuard (Bukkit, monitoring)
- **Prevents:** nothing is *cancelled* here — the Bukkit `ChunkLoadEvent` is not cancellable (verified). Instead it detects and attributes new-chunk generation storms (global + per-player windows) and alerts. Real request-flood mitigation happens upstream via the movement/packet layer and server view-distance settings. This limitation is deliberate and documented.

### EntityGuard (Bukkit)
- **Prevents:** global spawn-rate storms (spawn cancelled), per-chunk entity caps (config-gated), per-player projectile spam, interaction floods — all through verified cancellable events.

### RedstoneGuard / PhysicsGuard / HopperGuard (Bukkit)
- **Prevents:** redstone current-storms via `BlockRedstoneEvent.setNewCurrent(0)` (the event is not cancellable — this is the real lever), physics loops via cancelling `BlockPhysicsEvent` storms, piston storms, hopper-lag machines (`InventoryMoveItemEvent`), dispenser spam. Per-world suppression with automatic restore after the cooldown — farms recover on their own.

### ConnectionGuard (Bukkit, async)
- **Prevents:** login bursts per IP and reconnect spam per name during a sliding window. Soft, time-windowed refusal only — **never** a permanent ban; honest players reconnect normally afterwards.

### MemoryGuard / FreezeDetector (async)
- **Prevents:** crash-by-memory-exhaustion and sustained TPS collapse *conditions* — alerts + automatic **Safe Mode**. Never calls `System.gc()`.

### Safe Mode
Tightens every module (halves effective rate limits, escalates rule actions: KICK→DISCONNECT, LOG→ALERT), blocks malformed input more aggressively, and is always reversible (`/qanticrash safemode` or automatic recovery when metrics recover).

## Rule engine

All detections map to rules in `rules.yml`:

```yaml
rules:
  packet-flood:
    enabled: true
    severity: HIGH      # LOW | MEDIUM | HIGH | CRITICAL
    cooldown-ms: 2000
    actions: [LOG, ALERT]
```

Actions: `LOG, WARN, ALERT, CANCEL, RATE_LIMIT, TEMP_BLOCK, DISCONNECT, KICK, QUARANTINE, REMOVE_ENTITY…`
Severity weights feed per-player, per-module violation levels with automatic decay and cleanup.

## Exemptions

```yaml
exemptions:
  use-bypass-permission: true   # qanticrash.bypass
  players: []                   # UUIDs
  worlds: []                    # world names
```

## Real-time alerts

```
[qStudio AntiCrash]
Player: Steve
Module: PacketRate
Packet/Rule: packet-flood
Actual: interaction rate exceeded
Limit: 100/s burst 30
Violations: 14
Action: [LOG, ALERT]
```

Every value shown is measured or configured — never fabricated. Alerts are throttled
per (rule, player) and delivered on the main thread.

## Incident logging

```
plugins/qStudio-AntiCrash/
├── config.yml
├── messages.yml
├── rules.yml
└── logs/
    ├── incidents/    incidents-YYYY-MM-DD.log
    ├── violations/   violations-YYYY-MM-DD.log
    └── debug/        debug-YYYY-MM-DD.log
```

Records: timestamp, player UUID/name, module, rule, actual behavior, measured value,
configured limit, actions, violation level, server & plugin version. A single daemon
writer thread flushes a bounded queue — the main thread never touches disk.

## Performance

- **No per-tick world/entity/chunk scans.** No per-packet scheduler tasks. No task per player.
- Hot path: 1–2 CAS operations per packet; bucket state packed into a single `AtomicLong`.
- Exactly two periodic tasks: async health sample (default 3s) and async cleanup (default 60s).
- Lazy decay everywhere: violation/rate state decays on access; maps expire via the slow task.
- Safety valves on every map (bucket caps + sampled eviction) so sustained abuse cannot OOM the guard itself.
- Main-thread work per event: a handful of comparisons; expensive work (chunk cap scan) is config-gated.

## Compatibility

- Paper / Spigot / Purpur (same version family)
- ViaVersion & ProtocolSupport (packet layer is protocol-agnostic via PacketEvents)
- ProtocolLib and PacketEvents can coexist; PacketEvents wins when both are present
- Anti-cheats, minigames (BedWars etc.): rate defaults are generous (e.g. 250 movement/s); exemptions via permission/UUID/world; every rule individually disableable

## Architecture

```
src/main/java/studio/q/anticrash/
├── AntiCrashPlugin.java      wiring + lifecycle
├── api/                      Detection, Rule, Severity
├── commands/                 /qanticrash + tab completion
├── config/                   ConfigManager, RuleEngine
├── core/                     ProtectionEngine, SafeMode, MainThread, ModuleRegistry, ratelimit/
├── alerts/                   AlertService (throttled, main-thread delivery)
├── exemptions/               ExemptionService
├── logs/                     IncidentLogger (dedicated writer thread)
├── mitigation/               Action enum, ActionPlan
├── monitoring/               HealthMonitor, FreezeDetector, MemoryGuard
├── movement/                 MovementChecks, MovementTracker
├── packets/                  PacketGuard, PacketEventsListener, ProtocolLibListener, PacketCategory
├── protection/               Bukkit listeners (movement, books/signs, chat, chunks, entities, redstone, connections)
├── violations/               ViolationManager
└── integrations/             IntegrationRegistry
```

## Development

```bash
mvn compile      # compile
mvn test         # run unit tests (37 tests)
mvn package      # produce target/qStudio-AntiCrash-1.0.0.jar
mvn verify       # full verification
```

Unit tests cover the rate limiter, window counters, violation manager, rule/action parsing,
movement classification and all three YAML resources — no server required.

## Known limitations (deliberate, not hidden)

1. **No packet layer installed** → packet-level protection is off; Bukkit modules still run. A startup warning tells the admin.
2. **ProtocolLib layer is rate-limit-only.** ProtocolLib's `PacketEvent` exposes no per-packet size; text/payload size checks require PacketEvents. Documented in code and here.
3. **Chunk request floods cannot be cancelled** from the Bukkit API (`ChunkLoadEvent` is not cancellable). They are *detected and alerted*; prevention is upstream (movement limits) or via server config (view distance).
4. **`BlockRedstoneEvent` is not cancellable** — mitigation neutralizes the current change (`setNewCurrent(0)`) during an active storm window instead.
5. **NBT depth/key-count checks are not implemented.** Modern 1.20.5+ item components replaced raw NBT access in the API; a reliable, non-hacky depth check is not available without NMS internals. The plugin instead caps the *observable* abuse surfaces (books, item amounts, payloads) and cancels malformed packets before decode.
6. **Safe Mode is not a firewall.** It tightens limits and escalates actions but cannot conjure detections that the platform APIs do not expose.

## Troubleshooting

- **"Neither PacketEvents nor ProtocolLib found"** — install PacketEvents 2.x for full protection.
- **False movement flags** — raise `packets.limits.max-per-second.movement`, or exempt the player/world.
- **Redstone farm stutter** — raise `redstone.max-changes-per-second` / `max-physics-per-second`.
- **Too many alerts** — raise `alerts.throttle-ms` or `/qanticrash alerts` to unsubscribe yourself.
- **Inspect first:** `/qanticrash inspect` and `/qanticrash test` localize most issues.

## License

MIT — see [LICENSE](LICENSE).
