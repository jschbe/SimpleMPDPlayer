# Development rules

These rules apply throughout this repository. Explicit user instructions determine the current scope.

## Workflow

- Read the relevant existing code, [requirements](docs/REQUIREMENTS.md) and [architecture](docs/ARCHITECTURE.md) before changing behavior. Preserve unrelated work and project settings.
- The current milestone is **M3: MPD folder browser and queue append/replace**, retaining M1 server management and M2 player/queue/navigation. HTTP audio and the sleep timer remain outside this milestone.
- Keep the implementation small: one app module, explicit construction, no speculative frameworks or multi-server infrastructure. Record unresolved details rather than inventing elaborate solutions.
- Keep documentation, identifiers, comments and UI in English. Communicate with the user in German. Put every UI string in `app/src/main/res/values/strings.xml`.
- Never commit real server addresses, credentials, signing material or machine-specific settings. Do not expose raw server responses in UI errors or logs.
- Do not create commits, tags or pushes without an explicit request. Do not discard existing changes.
- For code changes, run focused JVM tests, the debug build and Android Lint when available, then inspect `git diff --check` and `git status`. Pure documentation changes do not need builds or tests.
- Run instrumented tests with the isolated `deviceTest` build (`.devicetest` application ID). Never clear or uninstall the normal app to run tests; preserve its stored server profiles.
- Update documentation when behavior changes. Separate implemented behavior from future requirements and report verification limits honestly.

## Technical constraints

- Kotlin, Jetpack Compose with Material 3, Gradle Kotlin DSL and a single `:app` module. Preserve `minSdk = 31`, the application ID and namespace.
- Manage dependencies in `gradle/libs.versions.toml`; use stable releases only. Add dependencies only for the requested functionality; avoid unsolicited upgrades.
- ViewModels expose StateFlow; composables do not own network connections or perform persistence. Never perform network operations on the main thread.
- Preferences DataStore stores validated profiles and the last selection. Treat malformed persisted input as recoverable; do not overwrite settings after an ordinary read failure.
- MPD access goes through `MpdRepository`. Serialize each complete socket write/read exchange. Cancellation, timeout and server switching must close the old socket and reject stale results.
- Exactly one interactive server is active. A separate `idle` socket may observe that same server; it must not block commands. Close both sockets on failure/switch, reject stale results and use manual reconnect only.
- **No password support in M1:** no password field, authentication command, Keystore or encryption dependency. Design secure storage and backup exclusions before adding credentials later.
- Future MPD state and queue remain authoritative. Local HTTP reception starts disabled and must be manually enabled; local Android volume stays separate from MPD volume.
- Future audio uses Media3/ExoPlayer in MediaSessionService. A single future sleep timer uses `AlarmManager.setAndAllowWhileIdle()` and sends **`pause 1`**, preserving playback position. Never request exact-alarm permission.
- Replacing the MPD queue must **not** automatically start playback. No multiroom, independent local playlist/library, telemetry, cloud integration or automatic port forwarding.

Product behavior belongs in `docs/REQUIREMENTS.md`; implementation decisions and open questions belong in `docs/ARCHITECTURE.md`.
