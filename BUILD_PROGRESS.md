# Stratum MC — Build Progress (Temporary)

> Auto-generated during initial bootstrap. Delete once stable CI is green.

| # | Task | Status | Notes |
|---|------|--------|-------|
| 1 | Bootstrap fork (rename, brand, build) | 🔄 Build pending CI | Branding done; needs CI build to confirm |
| 2 | Console theming | ✅ Done | patch 0100 |
| 3 | Performance defaults + stratum.yml | ✅ Done | patch 0101, stratum.yml.default, aikar-flags.txt |
| 4 | /ST command tree | ✅ Done | patch 0102 |
| 5 | Tweaks system (signed modules) | ✅ Done | patch 0103 |
| 5b | Addons system (opt-in, signed) | ✅ Done | patch 0104 |
| 6 | Version / update model | ✅ Done | patch 0105 (UpdateManager + VersionNotifier) |
| 7 | stratum-launcher (supervisor JAR) | ✅ Done | stratum-launcher/ module |
| 8 | stratum-api REST backend | ✅ Done | stratum-api-server/ module |
| 9 | Metrics & diagnostics | ✅ Done | patch 0107 (Prometheus /metrics) |
| 10 | Operator QoL (/ST preset, /ST migrate) | ✅ Done | in patch 0102 |
| 11 | Trust & safety (audit log, startup summary) | ✅ Done | patch 0106 (AuditLog) + 0108 (StratumServer) |
| 12 | CI/CD GitHub Actions matrix | ✅ Done | .github/workflows/build.yml |
| 13 | Docs | ✅ Done | README, CONTRIBUTING, TWEAKS, ADDONS, SECURITY |

## Legend
- ✅ Done  - 🔄 In Progress  - ⏳ Pending  - ❌ Blocked

## Next steps
1. Push to GitHub → CI runs
2. Replace placeholder `stratum_public_key.pem` with your real Ed25519 public key
3. Fix paperweight source-patches issue (see BUILD_ERROR.md) if CI fails
