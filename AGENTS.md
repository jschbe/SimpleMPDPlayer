# Development rules

These rules apply throughout this repository. Explicit user instructions determine the current scope.

## Workflow

- Read the relevant existing code, [requirements](docs/REQUIREMENTS.md) and [architecture](docs/ARCHITECTURE.md) before changing behavior. Preserve unrelated work and project settings.
- The current milestone is **M7.2: localization and GPL-3.0-or-later licensing**, retaining implemented M1–M6 behavior (including the sleep timer). No new MPD/audio/queue/browser/timer features; passwords remain excluded.
- Keep the implementation small: one app module, explicit construction, no speculative frameworks or multi-server infrastructure. Record unresolved details rather than inventing elaborate solutions.
- Keep documentation, identifiers and comments in English. Communicate with the user in German. Maintain complete English fallback UI strings in `app/src/main/res/values/strings.xml`, the canonical `docs/strings-en.csv` export, and translations in `values-de/fr/it/es/uk/ru`. Preserve reviewed wording and positional placeholders.
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
- Exactly one interactive server is active. A separate `idle` socket may observe that same server; it must not block commands. Close both sockets on control failure/switch, reject stale results, and repair isolated idle failures without replacing healthy control. Transient failures may use at most five automatic retries per cycle (1/2/4/8/16 seconds), paused while offline. Only a qualified foreground return or unlock may rearm an exhausted transient cycle automatically. Retain manual reconnect.
- **No password support in M1:** no password field, authentication command, Keystore or encryption dependency. Design secure storage and backup exclusions before adding credentials later.
- Only current, confirmed MPD status can start a waiting sleep timer. Progress interpolation also requires ongoing idle synchronization. MPD state and queue remain authoritative. Local HTTP reception starts disabled and must be manually enabled; local Android volume stays separate from MPD volume.
- Local audio uses Media3/ExoPlayer in MediaSessionService. The single sleep timer uses `AlarmManager.setAndAllowWhileIdle()` and sends **`pause 1`**, preserving playback position. Never request exact-alarm permission.
- Replacing the MPD queue must **not** automatically start playback. No multiroom, independent local playlist/library, telemetry, cloud integration or automatic port forwarding.

Product behavior belongs in `docs/REQUIREMENTS.md`; implementation decisions and open questions belong in `docs/ARCHITECTURE.md`.

- App language belongs exclusively to AppCompat/platform per-app locales; never duplicate it in profile DataStore or reset playback for localization. Keep locale config and resource filtering at en/de/fr/it/es/uk/ru.
- Preserve bundled GPL and third-party licenses/notices. Re-audit resolved runtime licenses when dependencies change; do not replace third-party grants with the app license.
