<div align="center">

```
  ███████╗████████╗██████╗  █████╗ ████████╗██╗   ██╗███╗   ███╗
  ██╔════╝╚══██╔══╝██╔══██╗██╔══██╗╚══██╔══╝██║   ██║████╗ ████║
  ███████╗   ██║   ██████╔╝███████║   ██║   ██║   ██║██╔████╔██║
  ╚════██║   ██║   ██╔══██╗██╔══██║   ██║   ██║   ██║██║╚██╔╝██║
  ███████║   ██║   ██║  ██║██║  ██║   ██║   ╚██████╔╝██║ ╚═╝ ██║
  ╚══════╝   ╚═╝   ╚═╝  ╚═╝╚═╝  ╚═╝   ╚═╝    ╚═════╝ ╚═╝     ╚═╝
```

**Stratum MC 2.0** — High-performance Minecraft server software built on Paper.

[![Build](https://github.com/jta-ops/StratumMC/actions/workflows/build.yml/badge.svg)](https://github.com/jta-ops/StratumMC/actions/workflows/build.yml)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2-blue?style=flat-square)](https://stratumserver.net)
[![Website](https://img.shields.io/badge/Website-stratumserver.net-00d4ff?style=flat-square)](https://stratumserver.net)

</div>

---

## Install

```bash
curl stratumserver.net/cli | bash
```

---

## What is Stratum?

Stratum is a drop-in Paper replacement that adds smart server management, a verified addon ecosystem, and built-in diagnostics — without touching your plugins or world data.

---

## Features

| Feature | Free | Pro |
|---|:---:|:---:|
| All Paper optimisations | ✓ | ✓ |
| `/st diag` — live TPS/RAM/chunk snapshot | ✓ | ✓ |
| Signed addon + tweak verification | Tweaks only | ✓ Full |
| Addon Marketplace (`/st addons install`) | Community | All |
| Addon hot-reload (`/st addons reload`) | — | ✓ |
| Proxy network (`/st connect`) | 3 servers | 10 servers |
| World snapshots (`/st snapshot`) | With cooldown | Unlimited |
| Dashboard console | Read-only | Full |
| Scheduled restarts | 1/week | Unlimited |
| Webhook alerts | ✓ | ✓ |
| Vanish system | ✓ | ✓ |
| Performance watchdog | ✓ | ✓ |
| Smart whitelist | — | ✓ |
| Crash recovery | — | ✓ |

---

## Commands

```
/st version          Build info
/st diag             Live server health snapshot
/st addons list      List installed addons
/st addons install   Install from marketplace
/st addons reload    Hot-reload an addon
/st snapshot save    Save a world snapshot
/st preset           Apply performance preset
/st update           Schedule or trigger update
/st migrate          Import Paper/Purpur config
/st connect          Link servers into a proxy network

/stb vanish          Toggle staff vanish
/stb snapshot        Take/list world snapshots
/stb restart         Schedule a restart with countdown
/stb whitelist       Toggle smart whitelist
```

---

## Addon System

Addons are signed with Ed25519 — only verified addons load. Drop a `.jar` and `.sig` in your `addons/` folder, or install directly from the marketplace:

```
/st addons install <id>
/st addons search <query>
```

---

## Build from source

```bash
git clone https://github.com/jta-ops/StratumMC
cd StratumMC
./gradlew applyPatches
./gradlew build createPaperclipJar
```

---

## Pricing

| | Free | Pro (1 server) | Pro (5 servers) |
|---|:---:|:---:|:---:|
| Price | $0 | $3/mo | $9/mo |
| Core server | ✓ | ✓ | ✓ |
| Full marketplace | — | ✓ | ✓ |
| All Pro features | — | ✓ | ✓ |

[View full pricing →](https://stratumserver.net/pricing)

---

<div align="center">

**[stratumserver.net](https://stratumserver.net)**

Built on [PaperMC](https://papermc.io)

</div>
