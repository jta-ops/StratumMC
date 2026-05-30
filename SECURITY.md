# Security & Signing — Stratum MC

## Trust model

Stratum uses **Ed25519** asymmetric signatures to verify every tweak, addon, and release JAR before loading or executing it.

```
Private key (yours, offline)  →  signs releases, tweaks, addons
Public key (bundled in JAR)   →  verified by server at runtime
```

Nothing loads without a valid signature. Failed verification is logged, audited, and the item is rejected.

## Key types

| Key | Purpose |
|---|---|
| Primary Ed25519 key | Signs all release JARs, tweaks, and standard addons |
| Tier-2 Ed25519 key | Required additionally for addons that touch networking or file I/O (`requiresTier2Key: true` in addon.json) |

## Generating keys

```bash
# Generate primary key pair
openssl genpkey -algorithm ed25519 -out stratum_private.pem
openssl pkey -in stratum_private.pem -pubout -out stratum_public_key.pem

# Generate tier-2 key pair (for sensitive addons)
openssl genpkey -algorithm ed25519 -out stratum_tier2_private.pem
openssl pkey -in stratum_tier2_private.pem -pubout -out stratum_tier2_key.pem
```

Store private keys **offline and encrypted**. Never commit them to the repo.

## Signing a JAR

```bash
# Sign server JAR
openssl pkeyutl -sign -inkey stratum_private.pem -rawin \
    -in stratum-server.jar | base64 > stratum-server.jar.sig

# Sign a tweak or addon
openssl pkeyutl -sign -inkey stratum_private.pem -rawin \
    -in my-tweak.jar | base64 > my-tweak.jar.sig
```

## Bundling the public key

`stratum_public_key.pem` must be placed in `paper-server/src/main/resources/` before building. It is compiled into the JAR and used at runtime for all signature checks.

## Startup audit

On every startup, Stratum logs a summary:

```
✔ 3 tweak(s) + 2 addon(s) loaded, all signature-verified
```

Any rejected item is listed with the reason. A tamper-evident audit log is written to `logs/stratum-audit.log`.

## Reporting vulnerabilities

Open a **private** security advisory at https://github.com/jta-ops/StratumMC/security/advisories/new.
Do not open a public issue for security vulnerabilities.
