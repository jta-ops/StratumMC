# Stratum MC

Stratum is a high-performance Minecraft server software built on top of Paper, designed for server owners who want more control, better performance, and a smarter management experience.

---

## What Stratum Does

### Performance
Stratum inherits all of Paper's optimisations and adds its own on top — faster chunk loading, optimised entity handling, and tunable settings that let you dial in performance for your specific use case.

### Presets
Apply a complete performance profile in one command:
- **Survival** — balanced settings for standard survival servers
- **Skyblock** — optimised for high player density and custom generation
- **Minigame** — maximum throughput for fast-paced gamemodes

### Addon System
Stratum has its own addon ecosystem. Addons are signed and verified — only official, trusted addons load. Drop a `.jar` and `.sig` file into your `addons/` folder and Stratum handles the rest. Addons tagged as `plugin` are automatically installed as Bukkit plugins.

### Tweaks
Lightweight server-side tweaks that modify game behaviour without requiring plugins. All tweaks are signature-verified to ensure they come from trusted sources.

### Auto-Updates
Stratum can update itself. Schedule a restart for later, wait for the server to empty, or trigger an immediate update — all from the console.

### Built-in Diagnostics
`/st diag` gives you a live snapshot of your server: TPS, MSPT, RAM, uptime, loaded chunks, and online players — no extra tools needed.

### Smart Plugin Management
Stratum automatically downloads and keeps its bootstrap plugin up to date from `stratumserver.net` on every startup. No manual plugin management.

### Paper/Purpur Migration
Moving from Paper or Purpur? `/st migrate` reads your existing config files and imports relevant settings into Stratum automatically.

---

## Commands

| Command | What it does |
|---|---|
| `/st version` | Show build info |
| `/st diag` | Live server health snapshot |
| `/st tweaks` | List loaded tweaks |
| `/st addons list` | List installed addons |
| `/st addons enable/disable <id>` | Toggle an addon |
| `/st preset <name>` | Apply a performance preset |
| `/st update [now\|<time>]` | Schedule or trigger an update |
| `/st migrate` | Import settings from Paper/Purpur |

---

## Website
**stratumserver.net**
