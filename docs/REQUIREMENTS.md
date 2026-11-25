# Implemented requirements

SimpleMPDPlayer is a minimal Android MPD client for Android 12 (API 31) and
later. This document describes product behavior. See the [user guide](USER_GUIDE.md)
for instructions, [architecture](ARCHITECTURE.md) for implementation, and
[verification history](VERIFICATION.md) for evidence and its limits.

## Profiles, settings and authentication

- Store validated profiles with stable IDs, a name, a hostname or IP address,
  and MPD and HTTP ports (defaults 6600 and 8000, valid range 1–65535).
  Name uniqueness ignores case and surrounding whitespace and excludes the
  profile being edited. Preserve existing duplicate names during unrelated
  writes and allow each one to be renamed.
- Open Servers when nothing is selected. Open Add server once after a successful
  load confirms an empty list, or after deleting the last profile. Cancel
  leaves the app usable. Only the drag handle starts a reorder. The header
  dropdown reflects the saved order and switches profiles without confirmation.
  Confirm deletion and disconnect a deleted selection without picking another.
- Test runs an independent greeting, optional authentication and read-only
  status check. It preserves selection, settings, interactive sockets, local
  reception and MPD playback. Parallel probes are prevented. Save persists
  the profile and selection before closing the editor and connecting, including
  on an unchanged endpoint. A storage error leaves the editor open. A connection
  failure keeps the saved profile selected. Stale results cannot issue notices
  for a different editor or server.
- Support passwordless and protected profiles. Mask password drafts, leave
  saved passwords out of the editor, and discard drafts on configuration
  recreation or process death. Preserve spaces, Unicode, quotes and
  backslashes, and reject line breaks and NUL. Distinguish keep, replace and
  explicit removal. Require a new password or explicit removal after a
  control host/port change. An HTTP-port edit, rename, reorder, theme or
  language change does not change the credential binding.
- Authenticate each new control, idle, Test and timer socket separately.
  Distinguish missing or unreadable credentials, rejected passwords, denied
  MPD commands and Android LAN permission. Bound the credential read to five
  seconds. Allow a read timeout to use the existing bounded recovery policy.
  Missing, unreadable or rejected credentials do not automatically retry.
  Preserve the configured password after rejection. Replacing or removing a
  credential invalidates the old session. Deleting a protected profile removes
  its local credential.
- Restore the last valid selection on startup. Recover valid data from malformed
  settings and flag recovery. On ordinary read failure, preserve the last
  confirmed settings and keep any healthy session and enabled local receiver
  usable. Disable settings writes until a successful Retry. A failed cold
  read does not open first-run setup or clear profiles.

## Player, browser and queue

- Keep one interactive server, with one command and one dedicated idle
  connection. MPD is authoritative for playback, metadata, options, mixer
  and queue. Show confirmed readback after commands and external events.
  Stale responses must not update another server's UI.
- Display Browser, Player and Queue in that order with shared tab/swipe state.
  Player is the initial tab with a selection. Settings offers Servers, Sleep
  timer, Appearance, Language and About.
- Display current title or filename, available artist/album, playback state
  and elapsed time. Persist the global expanded/collapsed track-information
  preference. Offer Play/Pause, Stop, Previous, Next, seek when duration is
  known and MPD volume (0–100) where the mixer is supported. Preserve the
  seek thumb while dragging and submit on gesture completion, guarded against
  a known track change. Disable unavailable or pending commands.
- Show Random as a separate switch. Derive Repeat from MPD: None = 0/0,
  Queue = 1/0 and Single = 1/1 for repeat/single. Keep all six combinations
  with Random. Preserve and display one-shot, stop-after-track and unknown
  states until explicit selection. Read current options before changing them
  and refresh confirmed status after success or partial rejection.
- Center the compact Random/Repeat groups together, or each on a separate row
  when wrapping. Match their typography. Place playback and sleep status
  on one row with matching styles and large-font wrapping. The full timer
  status area is an accessible 48 dp minimum button that opens the existing
  timer dialog without pressed-state feedback, including when playback
  controls are unavailable. Opening it changes no timer or MPD state.
- Browse one directory at a time with directories first, names/metadata
  fallbacks, a path and Up. Android Back and a clearly horizontal right swipe
  in a subdirectory go to its parent, including during a pending read. A
  right swipe at the root leaves the directory unchanged. A left swipe opens
  Player, and vertical gestures still scroll the listing. Tab changes
  preserve the current listing and scroll. Show loading, empty, disconnected,
  error and oversized states. Skip stored playlists with a notice.
- Append a file or recursive folder with one MPD add. Replace requires
  confirmation and runs clear followed by add without starting playback.
  Distinguish failed, partial and uncertain changes, including an acknowledged
  mutation with failed readback. Other clients can still interleave changes.
- Show a scrollable queue with current-entry highlighting, stable MPD IDs and
  distinct duplicate paths. Locate the current song on first visible load and
  preserve scroll during ordinary updates. Expose play, remove, move up/down
  and confirmed Clear. Bound queue loading and publish only a complete,
  consistent version. Oversized or rapidly changing queues expose Retry.
  Per-entry actions require a complete queue. Playback and confirmed Clear
  of a reported nonempty queue remain available with usable control.
- Invalidate queue confirmations and entry menus when their live control
  session changes. They are not restored on Activity recreation. Validate
  displayed queue version before Clear and current ordering before Move.
  Ordinary MPD command rejections retain honest status/readback.

## Recovery and freshness

- Serialize MPD exchanges. Control failure or server switch closes both
  sockets and cancels old work. An isolated idle failure preserves healthy
  control and repairs only idle. Revalidate player and queue when resubscribing,
  even after reconnecting to an unchanged queue version.
- Retry transient failures at most five times per cycle, with delays of 1, 2,
  4, 8 and 16 seconds. Pause during known offline periods without consuming
  attempts. Manual Reconnect starts a fresh cycle. A qualified foreground
  return or unlock can rearm an exhausted transient cycle once. Ordinary
  focus and network return alone cannot do so. Permanent auth, permission and
  malformed-protocol failures need correction. Never replay a mutation.
- Use a compact status row for connection progress, offline, failure and
  relevant actions. Offer Reconnect, Edit server, Allow local network or Open app settings
  as appropriate. A healthy control connection can remain usable during an
  idle failure. Expose stale status to accessibility.
- On Android 17 and later, offer the native local network permission request
  on Test or Save and an explicit app-settings route for Nearby devices.
  Denial keeps Test drafts available and leaves a saved profile selected.
  Recheck permission on return and reconnect the saved selection after grant.
- Coalesce foreground/focus health checks, including unlock without Activity
  recreation. Freeze stale progress. Interpolation requires confirmed Playing
  and live idle updates. A fresh control read can confirm playback for
  waiting-timer purposes even when idle or queue metadata is unavailable.

## Phone listening and Android media controls

- Start **Listen on phone** Off and require explicit activation. Receive only
  the selected profile's root HTTP stream. No MPD credential is sent over
  HTTP. Keep phone volume separate from MPD volume. An accessible info button
  explains the receiver without toggling it.
- Preserve an enabled receiver through ordinary Activity recreation and
  transient same-server recovery or manual Reconnect. Save, server switch,
  deletion, terminal control failure or manual Off releases the receiver as
  applicable. HTTP failures require manual local-audio retry. Enabling or
  disabling the receiver, audio focus changes and route loss send no MPD
  commands. A new process does not resume reception.
- Local playback follows confirmed MPD state. A ready stream can carry
  silence while MPD is paused. Resume rejoins the live origin. HTTP status,
  decoder buffering and audio focus do not change MPD metadata or status.
- Provide one Media3 session only while local listening is authorized.
  System notification, lock-screen and compatible headset actions offer
  MPD Play/Pause, Previous and Next, with confirmed title/artist/album and
  playback state. The notification opens the app. No system seek, Stop,
  queue editing, artwork or independent HTTP duration. Revoke old controller
  actions on stop or switch and disable unavailable controls during recovery.
  Android determines routing, rendering and background restrictions.

## Sleep timer

- Support one global, independent one-shot timer. Offer 15, 30, 45 and 60
  minutes or Custom (1–1440 whole minutes, default 30). Save the preferred
  duration, including an explicitly selected default preset. Apply a valid
  changed custom value via keyboard or dialog Done. Preserve a failed or
  unavailable draft for retry/cancel and retain it across Activity recreation
  without resubmitting. Applying a duration leaves an Off timer Off.
- On binds the selected profile. Confirmed Playing starts the full duration.
  Missing, stale, paused or stopped status waits with no deadline or alarm.
  Enabling or changing duration can request a fresh read from the selected
  target with usable control. Only current Playing for the bound endpoint
  starts a waiting timer. Waiting cannot observe playback while the app is
  inactive or the bound profile is unselected. Start from the next valid
  Playing observation, without reconstructing prior playback.
- Once counting down, later playback changes do not move the deadline.
  Changing duration replaces the generation on the original target, and
  either counts down immediately or waits again. Off cancels it. Selection
  changes preserve the target. Host, either port or deletion cancels it.
  Rename, reorder, theme, language or password-only changes preserve it.
- Retain waits or monotonic deadlines through normal restarts in the same
  boot. Reboot or an invalid elapsed reference cancels them. Use one inexact
  Android alarm without exact-alarm permissions. Show Waiting, countdown,
  Due and terminal states separately. The Android delivery time can lag
  behind the deadline. Wall clock changes affect the estimated end only.
- Revalidate the original profile and latest credential. A durable generation
  claim permits one independent, bounded explicit `pause 1` attempt. Acknowledge
  before reporting success. Early, stale or duplicate events send nothing.
  Changes before the claim can cancel the attempt. Changes after the claim
  wait for it. A known pre-claim failure is reconciled when possible.
  Interrupted claimed delivery remains uncertain and is not replayed.
  Optional orphan credential cleanup runs only on interactive start and is
  deferred while a timer is waiting, arming, active or delivering.
  Persist sanitized failures for inspection. No timer-only polling, second
  interactive server or background service.

## Languages, appearance, privacy and scope

- Support English, German, French, Italian, Spanish, Ukrainian and Russian.
  Use complete English fallback and AppCompat/platform per-app locale storage.
  Follow system language by default or apply a persistent explicit choice.
  Language changes preserve healthy connections, reception, timer and theme.
  Keep metadata literal. Use Android formatting for estimated timer dates.
  Support large text and localized labels, placeholders and plurals. See the
  [translation reference](LOCALIZATION.md).
- Persist System default, Light or Dark and keep window icon contrast in sync.
  Provide adaptive, legacy and API 33+ themed launcher resources. About
  includes the version, links to the source repository and privacy policy,
  and offline GPL and third-party notices.
  Preserve the [licensing and corresponding-source terms](LICENSING.md).
- Android cloud backup is allowed when encryption capabilities are present.
  Profiles, stable IDs, password-configured markers, selection/order and
  ordinary preferences including the preferred sleep duration are eligible
  for backup and direct device transfer. Language follows platform behavior.
  Passwords and live timer records are excluded. Restored protected profiles
  require local password re-entry or explicit removal. Restoration does not
  resume listening, rearm a timer or replay MPD mutations.
- Use a trusted network or a separately configured secure tunnel. Ordinary
  MPD and HTTP are unencrypted. Device-local credential encryption does not
  protect transport or verify server identity.

### Deferred or out of scope

Search, stored-playlist support, cover art, downloads, offline playback, a
local queue/library, multi-server synchronization, multiroom, HTTP
authentication, accounts, telemetry, a custom cloud service and automatic
port forwarding are outside the current scope. Wider protocol, codec and
device coverage and optional browsing/queue improvements are recorded in
[architecture](ARCHITECTURE.md#open-technical-questions).
