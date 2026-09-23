# qStudio AntiCrash

**Production-grade anti-crash / anti-exploit / server protection plugin for Minecraft servers.**

By **qSa3ed** · Brand **qStudio** · Plugin ID `qstudio-anticrash`

> ⚠️ **Honesty statement:** No plugin can guarantee 100% crash protection. Protection depends on
> the Minecraft version, server software, installed plugins, available APIs, and the specific
> attack vector. qStudio AntiCrash implements focused detections against real entry points and
> clearly documents what it cannot do. Every module listed below is implemented in the plugin.

---

## Overview

qStudio AntiCrash runs as a regular server plugin inside the server process. It inspects
dangerous input as early as the available APIs allow — on the packet layer when PacketEvents or
ProtocolLib is installed, and on the Bukkit event bus otherwise — so abusive input is rejected
before it reaches expensive server code:

| Layer | Entry point | Interception |
|---|---|---|
| PacketEvents (preferred) | Client packets | Full — flooded, oversized, or malformed packets are cancelled before the server processes them |
| ProtocolLib (fallback) | Client packets | Rate limiting (size checks unavailable — see [limitations](#known-limitations-deliberate-not-hidden)) |
| Bukkit events | Server event bus | Abusive events cancelled before persistence |
| JVM monitoring | Heap, TPS, MSPT | Alerts + Safe Mode |

## Supported versions

- **Target:** Paper 26.3 (also runs on Spigot/Purpur of the same family)
- **Java:** 21+ at runtime (compiled with `--release 21`; the Paper 26.3 server itself requires a newer JVM)
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
- **Where:** packet layer, before the server processes the packet.
- **How:** lightweight, low-overhead rate limiting designed for high packet throughput — a configurable per-second limit and burst allowance per packet category, plus a combined per-player limit — and size checks for chat/command/tab text and plugin-message payloads.

### Movement (packet layer + Bukkit fallback)
- **Prevents:** NaN/Infinity coordinates or rotation, sustained physically impossible movement, and extreme sustained rotation spam.
- **Never blocks:** legitimate teleports, knockback, velocity, ender pearls, launchers, minigames — isolated large position changes are tolerated, and only *sustained* impossible movement is flagged.

### BookGuard / SignGuard (Bukkit)
- **Prevents:** book page-count abuse, oversized pages/total/title, oversized sign lines — cancelled at `PlayerEditBookEvent` / `SignChangeEvent` (both verified cancellable) before persistence.

### ChatGuard (Bukkit + packet layer)
- **Prevents:** oversized chat/commands/tab input, chat and command rate floods, inventory-click floods, impossible item stack amounts.

### ChunkGuard (Bukkit, monitoring)
- **Prevents:** nothing is *cancelled* here — the Bukkit `ChunkLoadEvent` is not cancellable (verified). Instead it monitors new-chunk generation rates and alerts on generation storms. Real request-flood mitigation happens upstream via the movement/packet layer and server view-distance settings. This limitation is deliberate and documented.

### EntityGuard (Bukkit)
- **Prevents:** global spawn-rate storms (spawn cancelled), per-chunk entity caps (config-gated), per-player projectile spam, interaction floods — all through verified cancellable events.

### RedstoneGuard / PhysicsGuard / HopperGuard (Bukkit)
- **Prevents:** redstone current-storms (the event is not cancellable, so the current change is neutralized during an active storm window), physics loops via cancelling `BlockPhysicsEvent` storms, piston storms, hopper-lag machines (`InventoryMoveItemEvent`), dispenser spam. Suppression is per-world with automatic restore after the cooldown — farms recover on their own.

### ConnectionGuard (Bukkit, async)
- **Prevents:** login bursts per IP and reconnect spam per name during a sliding window. Soft, time-windowed refusal only — **never** a permanent ban; honest players reconnect normally afterwards.

### MemoryGuard / FreezeDetector (async)
- **Prevents:** crash-by-memory-exhaustion and sustained TPS collapse *conditions* — alerts + automatic **Safe Mode**. Never calls `System.gc()`.

### Safe Mode
Tightens the protection posture while active: detections weigh more heavily toward violation
levels and rule actions escalate (logging-only rules also alert; kick-class actions escalate to
disconnect). Always reversible via `/qanticrash safemode`, and automatic Safe Mode deactivates
itself when server metrics recover.

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

Actions: engine-side actions (`LOG`, `WARN`, `ALERT`, `DISCONNECT`/`KICK`) are executed by the
protection engine; event-level actions (`CANCEL`, `RATE_LIMIT`, `TEMP_BLOCK`, `REMOVE_ENTITY`,
`QUARANTINE`, …) are returned to the module that owns the underlying event or packet.
Severity weights feed per-player, per-module violation levels with automatic decay and cleanup.

## Configuration

Every module can be enabled/disabled independently and missing keys fall back to safe defaults,
so a partial `config.yml` is always valid. The shipped `config.yml` documents every key inline.
Main areas:

- `packets` — packet-layer toggle, payload size cap, denied plugin-message channels, per-category limits
- `movement`, `books`, `signs`, `chat`, `commands`, `items` — size caps and Bukkit-layer rates
- `chunks`, `entities`, `redstone`, `connections` — monitoring/storm thresholds per area
- `performance` — health-sample and cleanup cadence, freeze-detector sensitivity
- `violations`, `alerts`, `logging` — decay, throttling, log files
- `exemptions`, `safemode`, `debug` — exemptions and operational switches

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
Limit: <configured per-second>/s burst <configured burst>
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
configured limit, actions, violation level, server & plugin version. A dedicated writer
thread flushes a bounded queue — the main thread never touches disk.

## Performance

- **No per-tick world/entity/chunk scans.** No scheduler task per packet or per player.
- Packet-layer checks are lightweight and run on the network thread, designed for high packet throughput.
- Main-thread work per event is a handful of comparisons; the only heavier check (chunk entity cap) is config-gated.
- Exactly two periodic tasks: an async health sample (default every 3s) and an async cleanup pass (default every 60s).
- Internal state is bounded and expired periodically, so sustained abuse cannot exhaust the plugin's memory.
- Violation levels decay automatically over time; no per-player maintenance tasks.

## Compatibility

- Paper / Spigot / Purpur (same version family)
- ViaVersion & ProtocolSupport (packet layer is protocol-agnostic via PacketEvents)
- ProtocolLib and PacketEvents can coexist; PacketEvents wins when both are present
- Anti-cheats, minigames (BedWars etc.): rate defaults are generous for legitimate gameplay; exemptions via permission/UUID/world; every rule individually disableable

## Architecture

```
src/main/java/studio/q/anticrash/
├── AntiCrashPlugin.java      wiring + lifecycle
├── api/                      Detection, Rule, Severity
├── commands/                 /qanticrash + tab completion
├── config/                   ConfigManager, RuleEngine
├── core/                     ProtectionEngine, SafeMode, MainThread, ModuleRegistry, ModuleToggles, ratelimit/
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
4. **`BlockRedstoneEvent` is not cancellable** — mitigation neutralizes the current change during an active storm window instead.
5. **NBT depth/key-count checks are not implemented.** Modern 1.20.5+ item components replaced raw NBT access in the API; a reliable, non-hacky depth check is not available without NMS internals. The plugin instead caps the *observable* abuse surfaces (books, item amounts, payloads) and cancels malformed packets before decode.
6. **Safe Mode is not a firewall.** It escalates actions and violation weighting but cannot conjure detections that the platform APIs do not expose.

## Troubleshooting

- **"Neither PacketEvents nor ProtocolLib found"** — install PacketEvents 2.x for full protection.
- **False movement flags** — raise `packets.limits.max-per-second.movement`, or exempt the player/world.
- **Redstone farm stutter** — raise `redstone.max-changes-per-second` / `max-physics-per-second`.
- **Too many alerts** — raise `alerts.throttle-ms` or `/qanticrash alerts` to unsubscribe yourself.
- **Inspect first:** `/qanticrash inspect` and `/qanticrash test` localize most issues.

## License

MIT — see [LICENSE](LICENSE).
