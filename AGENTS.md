# Development rules

These rules apply throughout the repository. Explicit user instructions set
the current scope.

- Read the relevant code, [requirements](docs/REQUIREMENTS.md) and
  [architecture](docs/ARCHITECTURE.md) before changing behavior. Preserve
  existing user changes and unrelated settings. Keep implementation small
  with one app module and explicit construction.
- Use English for documentation, identifiers and comments. Report to the
  user in German. Preserve complete English fallback, the
  [English CSV catalog](docs/strings-en.csv) and all six translation catalogs.
  Follow the [localization conventions](docs/LOCALIZATION.md).
- Preserve the namespace, application ID and minimum SDK 31. Manage stable
  dependencies in `gradle/libs.versions.toml`. Re-audit resolved runtime
  licenses when dependencies change. Preserve GPL, notices and attributions.
- Keep real endpoints, credentials and machine-specific or signing data out
  of Git. Keep secrets and raw server replies out of logs and UI errors.
  Store MPD passwords in Keystore-backed `noBackupFilesDir` records, with only
  non-secret references in profiles. Exclude live timer state from backup.
- Compose renders StateFlow and sends events. Keep network and persistence
  work off the UI thread. Treat malformed settings as recoverable. Preserve
  valid settings and healthy sessions across ordinary read failures.
- Use `MpdRepository` for MPD access. Keep one interactive server, serialize
  exchanges and reject stale work after a switch. Keep isolated idle repair,
  bounded recovery and manual reconnect. Never replay MPD mutations or start
  playback when replacing the queue. Only current confirmed status can start
  a waiting timer. Expiry sends one explicit Pause through an inexact alarm.
  Local HTTP reception requires manual activation and stays separate from
  MPD volume and authentication.
- For code changes, use the relevant JVM tests, debug build and Android Lint.
  Follow the [emulator and test isolation rules](docs/DEVELOPMENT.md#instrumented-tests).
  Use only the `.devicetest` variant for instrumented tests. Preserve normal
  app data. Documentation-only changes need no build or runtime test.
- Keep current behavior in [requirements](docs/REQUIREMENTS.md), implementation
  decisions in [architecture](docs/ARCHITECTURE.md), and dated results and
  limits in [verification](docs/VERIFICATION.md). Inspect `git diff --check`
  and status before finishing. Commits, tags or pushes require an explicit
  request.
