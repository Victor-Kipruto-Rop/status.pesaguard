# SDK & CLI Platform (Phase 17)

Status: **DOMAIN, SELECTION LOGIC, AND SCHEMA ONLY — NO ENDPOINTS, NO PERSISTENCE
LAYER.** Nothing is queryable by a developer and nothing is stored, because there
is no repository, no controller, and no migration execution. **V17 has never been
run.**

## Scope

Metadata only, as specified. Artefacts are built and stored by the release
pipeline; SDK and CLI source lives in separate repositories. The backend records
enough for a developer to choose a version and verify what they downloaded.

## SDK registry

Six languages: `JAVA`, `PYTHON`, `TYPESCRIPT`, `GO`, `PHP`, `DOTNET` (displayed as
"C#").

**JavaScript is deliberately not a separate entry.** A JavaScript and a TypeScript
SDK are the same artefact at runtime and are published from one source. Listing
both separately would let a maintainer publish the TypeScript release and forget
the JavaScript one, leaving developers on the same major version with two
different builds.

Per release: version, channel, status, API compatibility, checksum,
documentation URL, release notes, deprecation metadata.

## CLI registry

`WINDOWS`, `LINUX`, `MACOS` across `X86_64` and `ARM64`, each with version,
checksum, channel, release notes.

**One record per platform/architecture pair, not one per release.** Checksums are
per artefact, and a single checksum across platforms would mean a developer
verifying the Windows binary was really trusting the Linux one.

## Checksums are the integrity guarantee

This is the part of the phase that matters most, and it drove most of the design.

- **SHA-256 only.** This is the one value standing between a developer and an
  attacker who can write to a package mirror.
- **Validated on construction.** A release or build with an unverifiable checksum
  cannot be created, so no such row can exist.
- **Constrained in the database too** — `checksum ~ '^[0-9a-f]{64}$'` — in case a row
  is written by another route.
- **Compared in constant time** on verification. The comparison is reachable from a
  public endpoint, and a byte-by-byte early exit would leak a digest a character at
  a time.
- **Uppercase accepted, lower-cased on store.** Published checksums differ in case
  by tool; rejecting a correct uppercase digest rejects a correct artefact.

A registry whose checksums are wrong is *worse* than one with none: a developer
who cannot tell a truncated download from a tampered one has no way to know what
they just installed.

## Versions are parsed, never string-compared

`SemanticVersion` implements semver precedence. String comparison gets ordering
wrong in both directions:

- `"1.10.0" < "1.9.0"` lexically — so a naive registry recommends an **older** SDK
- `"1.0.0-rc1"` above `"1.0.0"` lexically — so a naive registry recommends a
  **prerelease** as latest

Both are wrong answers to "which version should I install", which is the entire
purpose of this registry. Pre-release identifiers compare numerically where
numeric and lexically otherwise, and a prerelease always sorts below its own
release.

## Recommendation rules

A release is **recommended** only if downloadable, on a supported channel, and not
deprecated. Deprecated releases stay **downloadable** but stop being advertised:
software already pinned to a version must not stop resolving the moment it is
deprecated, or upgrading it becomes impossible.

`API_KEY_REVOKED`-style "withdraw" is reserved for a release that must stop being
used entirely, such as one with a known vulnerability. Both deprecation and
withdrawal **require a reason** — a developer told to migrate deserves to know
what to, and an unexplained withdrawal generates support load only the reason
could have answered.

`ApiCompatibility` records the supported platform API major range **per release**,
not assumed from the version. A patch release can narrow support, and assuming
otherwise would recommend an SDK against an API it cannot talk to. Ranges are
capped at five majors, because a wide range is nearly always a metadata mistake.

`ReleaseSelection.isFullySupported` is a **release gate**: shipping an API major no
SDK supports would break every developer on it. `unsupportedLanguages` names which
language failed, so the gate is actionable.

## Two bugs found by the tests

**A prerelease could never be selected.** `ReleaseSelection.recommend` filtered
candidates through `isRecommended()`, which requires a *supported* channel — so an
explicit request for a beta was unsatisfiable and silently returned the stable
build instead. A developer who asked for a beta and got stable has been misled,
not helped. Fixed to filter on downloadability, with the channel preference
applied afterwards.

**A test asserted semver backwards.** My own test claimed `rc.2 > rc.10`; the
correct semver answer is `rc.10 > rc.2`, which is exactly what the implementation
returns and exactly what a lexical comparison gets wrong. The test was wrong, not
the code. Worth recording because "the test failed so I changed the code" is the
more common and more dangerous reflex.

## Not implemented

- **No repositories, no controller, no OpenAPI.** Nothing is reachable by a
  developer; the whole phase is domain logic and a migration.
- **No persistence mapping.** No JPA entities exist for `sdk_releases`,
  `cli_releases`, or `platform_api_versions`.
- **No publishing workflow.** Nothing creates a release; the `publish` factories
  exist but are never called.
- **No release pipeline integration.** Checksums are recorded by hand, so the
  integrity guarantee depends on a human copying them correctly. **The real fix is
  for the pipeline to write them**, and that is the single most valuable follow-up
  in this phase.
- **No download endpoint.** `downloadUrl` is a stored string to an external host;
  nothing serves or verifies a download on our side.
- **V17 has never executed**, so every constraint is unverified.

## Decisions to revisit

- **Checksums are entered, not computed.** Until the pipeline supplies them, the
  strongest guarantee this registry offers is a human copying 64 hex characters
  without error.
- **`NEXT` and `BETA` share a stability rank**, so neither is "more stable" than
  the other. That is deliberate, but it means fallback ordering between them is
  undefined and `ReleaseSelection` iterates declaration order.
- **No signature verification.** A checksum proves the download matches what we
  published; it does not prove we published it. Signing artefacts would close that
  gap and is the natural next step.