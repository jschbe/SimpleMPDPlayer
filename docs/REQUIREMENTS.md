# Requirements

SimpleMPDPlayer is a minimal Android MPD client for Android 12 / API 31 and newer. This file defines behavior; [architecture](ARCHITECTURE.md) describes implementation. English is the UI source/fallback; code comments and documentation are English.

## Implemented: M1 server management

- Add/edit/delete profiles, with deletion confirmation. Stable UUID, required name/host, MPD port default 6600, stored HTTP stream port default 8000. Validate ports 1–65535 and hostname/IPv4/IPv6 syntax without DNS.
- Add/edit requires a unique display name, compared case-insensitively after trimming leading/trailing whitespace and excluding the edited profile by stable ID. Show a localized error at the name field and disable Save for an invalid name. Preserve legacy duplicate names and IDs on load and unrelated writes; each affected profile can be explicitly renamed to a unique name.
- Persist profiles and the last selected ID in DataStore. Reconnect on startup; never automatically choose a different server after deletion or invalid data. Saving any added/edited profile activates it and starts one connection attempt, including name-only edits; failed connections retain the profile and selection.
- Exactly one selected server. Switch/disconnect closes all of its technical connections and clears its player/queue snapshot. Do not synchronize multiple servers.
- Invalid records and storage errors must not crash or silently overwrite valid data. Keep valid records, report recovery and offer retry for IO failures.
- No password support yet: no password fields, authentication, Keystore or encryption dependency. Existing network permissions and Android 17 local-network permission handling remain available.

## Implemented: M2 navigation and player

### Navigation

- Header shows the selected server name, subtle connection status and a Settings overflow menu. Offer manual reconnect when disconnected/failed; show understandable loading and error states.
- Main tabs are **Browser, Player, Queue**, in that order. Player opens by default with a selected server. With no selection, open Servers management; with no profiles, automatically open Add server once per entry into the empty state. Cancel leaves the app usable without reopening the dialog.
- Tabs and horizontal swipes share one state: left from Player opens Queue, right opens Browser, reverse gestures return to Player. Queue scrolls vertically. System Back returns to the parent only while Browser is visible in a subdirectory; otherwise retain existing Back behavior for Servers/dialogs and Android.
- The header server-name dropdown lists stored profiles in order, marks the active one and switches without confirmation. Settings contains Servers, Sleep timer, Appearance, Language and About. Servers is not a main tab.

### Player

- Show current title, optional artist/album, state, elapsed position and known duration. Missing Title falls back to filename; missing artist/album is omitted. Track information is expanded by default; a labeled chevron/tappable row collapses it to the title/filename in smaller type. Persist the expansion as a global DataStore UI preference, independent of title/server changes. Unknown duration has no misleading seek range. An empty queue has an explicit empty state. No cover art.
- Play/Pause sends pause 1 while playing, pause 0 while paused, or play while stopped. Also provide stop, previous, next, seekcur, Random, Repeat and MPD setvol. Place playback status left and compact sleep status right on one row, using identical font size, weight and text style; wrap text at large sizes without overlap. The entire sleep-status area is an accessible, minimum-48-dp-high button opening the existing Settings → Sleep timer dialog, also while playback controls are unavailable. Opening it must not change timer state or send MPD commands. Random has an immediately adjacent On/Off switch; Repeat is a compact dropdown with **None**, **Queue**, **Single**. Share a row when space permits and wrap without clipping on narrow screens or large fonts.
- Repeat is derived solely from MPD status: None = `repeat 0` / `single 0`, Queue = `repeat 1` / `single 0`, Single = `repeat 1` / `single 1`. Random is independent; all six combinations are supported, including Single with Random enabled. No local repeat/shuffle logic or persisted repeat preference.
- The closed Repeat label/value matches Random's font size, weight, text color and vertical alignment. Center Random and Repeat together horizontally, centering each group on its own row when wrapped. Keep compact spacing and large-font wrapping, with the existing Material 3 selection styling inside the opened menu.
- The clickable Player sleep-status area has no ripple, overlay or pressed-state background; its touch target, accessibility semantics and dialog action remain available.
- Preserve external `single: oneshot`, stop-after-track (`repeat 0` / `single 1`) and missing/unexpected repeat/single values on read; display them explicitly rather than claiming a supported mode. An explicit selection may normalize them. Synchronize external changes through existing idle events.
- Read fresh status before Repeat changes and send only differing/unknown required fields. Serialize the whole command sequence and confirmed readback against other app commands. MPD provides no transaction against other clients; after an ACK/partial failure read actual status again. Transport loss closes the session, retains a stale snapshot and recovers by reads only, without replaying the mutation.
- Interpolate progress locally while playing, without per-second network polling. Resynchronize after commands and external changes. Send seek only on gesture completion and preserve the user's thumb position during dragging. Do not seek a different song after a known title change.
- MPD volume is 0–100; missing/negative mixer volume hides its control. It is independent of Android device volume.
- Disable commands while disconnected or pending. Do not optimistically change server playback/options/queue; show confirmed readback. ACK rejection and transport failures remain distinguishable and actionable.

### Queue

- Read playlistinfo into a vertically scrollable list with title, optional artist/duration, highlighted current song and stable MPD-ID keys. Duplicate paths remain distinct entries. Long titles wrap/truncate appropriately.
- Locate the current song on first visible load for a server; preserve scrolling after user interactions and ordinary updates.
- Tap to playid, remove via deleteid, clear with confirmation, move up/down via moveid. Resolve moves against a fresh queue; unavailable entries can fail without crashing.
- MPD is the only authoritative queue and playback source. No persistent local queue or separate music library.

### Synchronization and lifecycle

- One command socket and one idle event socket connect to the same selected server. Observe player/playlist/mixer/options changes, refresh confirmed snapshots and prevent idle from blocking commands.
- Never start duplicate listeners through recomposition/Activity recreation. Switching cancels/closes old work, resets state and prevents late results from changing the new server's UI.
- Initial loads and errors are bounded. Control failure closes both connections; isolated listener failure retains healthy control. Use the bounded recovery policy below and retain manual reconnect. Cached information after failure is marked stale.
- No new background service, HTTP audio or local media session in M2.

## Implemented: M2 UI addendum

- Add/edit uses the same dialog: required name/host, host placeholder only (never an input default), validated ports 1–65535 with defaults 6600/8000. Test validates greeting/ping on a temporary connection, closes it, does not save or disturb active playback/control, and reports MPD available/unavailable plus a detailed inline failure. Prevent parallel tests and keep work off the main thread.
- Save persists profile and selection together. Close the dialog only after successful persistence, then show Connecting… outside it. Emit Connected/Connection failed only after the actual current connection result, with stale notifications discarded. A failed storage write keeps the editor open; a failed connection keeps the profile saved/selected.
- Servers has a top-right Add button, drag handles, name/endpoint, active marker and edit/delete actions. Only handle long-press starts dragging; commit order on drop and use it in the header. No server Move up/down buttons. Reordering does not reconnect or change selection.
- Confirm every deletion with “Delete server?” and “Are you sure you want to delete "<name>"?”. Cancel changes nothing. Deleting the active profile disconnects without selecting another; deleting the last opens Add server once, and Cancel can leave it empty.
- Appearance offers System default (initial), Light and Dark radio choices. Persist and apply immediately to all app screens/dialogs/menus; System default follows Android changes. Status/navigation-bar icons follow the effective theme without changing edge-to-edge layout.
- Launcher/settings icons use the provided artwork with adaptive layers, API 33+ monochrome and legacy sizes. Preserve the original asset. The precomposed image requires approximate foreground/background separation; document that limit.

## Implemented: M3 music-library browser

- Browse the active MPD database by folder using `lsinfo` (root without arguments); fetch one level only when Browser first becomes visible or the user navigates/retries. Keep only the current listing, without a recursive library cache.
- List directories before files. Use directory/filename fallbacks, Unicode names and available title/artist/duration metadata. Preserve spaces, quotes and backslashes through protocol escaping. Skip stored-playlist records with an explanatory notice; do not treat unknown types as files.
- Folder taps navigate; show the current path and an Up control. Android Back navigates to the parent while browsing a subdirectory, including during loading. Preserve ordinary horizontal pager swipes. Returning to the current directory from another tab does not reload or reset its scroll.
- File taps and trailing folder menus offer **Append to queue** / **Replace queue**. Append sends one `add <URI>` for either type; MPD handles recursive folders. Confirm every replacement, then serialize `clear` followed by `add <URI>` on the active command connection. Never start playback automatically.
- Update the actual MPD queue through existing readback/idle synchronization; never maintain a separate local playlist. Report success only after acknowledged commands and readback. Report failed/uncertain additions and partial replacement after a successful clear explicitly; recursive additions may themselves be partial. There is no transaction, rollback or exclusion of other MPD clients.
- Handle root/empty/loading/error/disconnected states and inaccessible folders. A directory ACK allows retry/Up without disconnecting playback. Transport/protocol failures retain manual reconnect. Switching/reconnecting clears old listings, menus and confirmations and starts at root; discard old asynchronous results. Do not blindly replay mutations.
- No search, covers, stored-playlist operations, local files/audio, timer or authentication is added.

## Implemented: M4 optional local HTTP audio

- Player exposes **Listen on phone**, independent of MPD transport and MPD volume. Replace its permanent explanatory text with an independently accessible info button/dialog next to the label; it explains HTTP reception, server transport and local-only Off without toggling the switch. Enable it manually for the connected active profile. Fresh process startup, Save and server switch leave it off; Activity recreation/returning to the app preserves an enabled receiver.
- Receive only the active profile's root HTTP stream using its validated host and stream port. No separate songs, queue, library, download or offline playback. MP3 is the verified format; other formats depend on Media3/platform support. No server/output configuration is changed.
- Turning reception off releases the player, HTTP connection, session and foreground service. It sends no MPD command, especially no pause/stop or disableoutput. Switch/delete/endpoint edits release the old receiver before starting the next MPD connection. Same-server control recovery and manual Reconnect retain an enabled receiver while recovery is pending. A stream error leaves MPD control connected; terminal control failure stops local reception.
- Show Off, Connecting, Buffering, Receiving, Waiting for MPD, Android interruption and sanitized errors. Finite connection/read/buffering limits and no automatic retry; enable the switch or use **Retry local audio** to retry.
- MPD status/current song remain authoritative for title, duration, position and all app transport controls. Local output pauses when MPD is paused/stopped/unknown; ready HTTP data is not evidence of music. On MPD resume rejoin the live origin to avoid replaying buffered silence. Latency between MPD status and audio is expected.
- Use Media3 ExoPlayer in a MediaSessionService for background/screen-off playback, audio focus, default Android/Bluetooth routing and becoming-noisy handling. Device volume affects local audio only. Permanent focus loss/noisy events turn reception off without controlling MPD; transient focus loss may suppress and resume local audio through Media3.
- The notification/lock screen displays available MPD title/artist/album and MPD state. No independent HTTP track duration/progress is advertised. M6 extends this originally read-only session with the bounded MPD transport actions below. Controllers/service restarts cannot authorize local playback.
- No playback resumption receiver, persisted streaming flag or sticky restart. An enabled paused receiver may be reclaimed under Android background limits; foreground/audio-focus restrictions may require opening the app and manually retrying. With reception disabled no audio foreground service remains.
- Explicitly allow cleartext HTTP for arbitrary user-configured hosts through network security configuration. Retain INTERNET and existing API 37 local-network permission behavior (API 36 has no mandatory new runtime LAN permission); add only media foreground-service permissions and WAKE_LOCK. No router/MPD changes.

## Implemented: M5 sleep timer

- Exactly one global, one-shot timer in Settings → **Sleep timer**. On/Off, a duration dropdown with **15 minutes**, **30 minutes**, **45 minutes**, **60 minutes**, **Custom…**; custom whole minutes 1–1440, default 30. Persist the last applied duration. No selected server means On is unavailable; enabling while disconnected is allowed but waits for confirmed playback. Local audio is not required.
- On binds the selected profile. If its current confirmed MPD status is Playing, start the full duration now. Paused, Stopped, unknown/disconnected or unobserved target enters persistent **Waiting for playback**, with no deadline or scheduled alarm. On the next valid Playing observation of that same endpoint, begin a new countdown with the full chosen duration from observation time. App and external-client starts use the same status/idle source. Disconnects/stale snapshots are not playback starts.
- Once started, Pause/Stop/Play never suspend, extend or restart the deadline. Off cancels waiting/countdown and the OS alarm. Changing the applied duration replaces the old generation/deadline on its original target: start now if confirmed playing, otherwise wait again. Choosing an unchanged duration is idempotent. Only Off then On binds a new selection.
- Keyboard IME Done and the dialog Done apply a valid custom duration through the same event; there is no separate Custom Apply button. Invalid input is rejected and explained. Duration application never enables a previously Off timer. On/Off uses the last applied duration. Preserve in-progress custom editing across Activity recreation without replaying an event.
- Display waiting separately from Off and countdown, with target name. Running countdown shows remaining/estimated due time in the dialog and compact minutes in Player; waiting has neither remaining time nor estimated end.
- Persist the profile ID and endpoint snapshot at start. Selection changes preserve the original target. Deleting it or changing host, MPD port or HTTP stream port cancels it. Rename/order/theme changes do not alter its alarm or deadline. Revalidate the saved endpoint again before sending.
- Expiry opens a short, independent MPD connection, validates the greeting and sends only explicit **pause 1**. Never toggle pause, stop, play, seek or change queue/options/volume/outputs. A paused/stopped server is never started; playback position is preserved. Close the connection reliably. Local audio follows ordinary MPD idle updates only; the timer never activates Media3.
- Use an explicit immutable PendingIntent and non-exported BroadcastReceiver with AlarmManager.setAndAllowWhileIdle and ELAPSED_REALTIME_WAKEUP. No exact-alarm permission/request, boot receiver, polling service or additional foreground service. Receiver IO is bounded and uses goAsync/finally finish.
- Retain waiting state or the monotonic countdown deadline and boot count across process/Activity restart without rescheduling an existing alarm. System clock changes affect only the estimated wall time. A different boot or invalid monotonic reference cancels stale state on the next app start; never rearm after reboot. Timer storage is excluded from backup/device transfer.
- Persist terminal success, cancellation and sanitized error states. Success means acknowledged Pause; network/timeout/ACK/authentication/permission/protocol failures are unconfirmed and never retried automatically. Interrupted registration or a missing PendingIntent is actionable; interrupted delivery is uncertain and is not replayed. Ordinary storage read failures must not overwrite stored settings.
- Unique generation per start/restart rejects stale/early/duplicate events. Serialize cancellation, replacement, profile writes and a claimed delivery. If cancellation/restart/profile change wins before claim, the old timer sends nothing. Once delivery has been claimed, changes wait for that short attempt; an already sent command cannot be recalled. A durable claim prevents replay after process death.
- Waiting relies on the existing interactive repository while the process lives. No server-side detection guarantee while the process is dead/suspended or another server is selected; no second interactive server, permanent timer listener, service, wake lock or network polling. Reconcile on resume/reconnect/return to the bound server. If Playing is first observed later, begin the full duration then; an intervening play/pause episode may be missed. Apply reboot/endpoint invalidation to waiting records as well as countdowns.
- Explain Android power-saving delays; zero remaining time means Due/Waiting for Android, never fabricated completion. Doze/Battery Saver may delay execution substantially; Force Stop, background restrictions, permissions and network failure prevent reliable delivery. No minute-exact or guaranteed execution, automatic retry loop or background-only toast error handling.

## Implemented: M6 Android system media controls

- Extend the existing local-audio MediaSessionService only. A session exists only for manually enabled **Listen on phone**; enabling/disabling it or constructing a session sends no MPD transport commands. Off closes local reception/session without changing MPD. No persistent control-only service or resumption receiver. Control recovery follows the bounded policy below.
- Standard notification/lock-screen controls and Bluetooth/headset media keys offer Play/Pause, Next and Previous. All use the existing repository's serialized MPD operations and actual readback/idle updates. Explicit Play reads fresh status and sends `pause 0` when paused or `play` when stopped; already-playing Play is a no-op. Pause sends `pause 1` only while playing. Toggle uses confirmed MPD state, never HTTP decoder state. Next/Previous send `next`/`previous` without copying/changing the queue locally.
- Display MPD title/artist/album and confirmed playback state from status/current-song/idle, including external clients and sleep-timer Pause. Missing title falls back to filename or server name; missing artist/album is omitted. No artwork, local seek, Stop, playlist editing, stream-item replacement or custom commands in system controls. App controls remain unchanged.
- An HTTP stream receiving bytes/buffering/suppressed by Android does not define MPD playback state. Local audio focus/route loss and stream failures affect only the receiver. HTTP audio may lag behind metadata; no precise lock-screen position/duration is promised.
- Bind asynchronous actions to the manually enabled stream generation and current repository session. Recheck authorization after waiting for a lock/status response. Off, explicit disconnect, terminal control failure or server change revoke old commands/controllers and discard old results; temporary same-server control recovery retains the session but disables its unavailable controls; an already transmitted MPD command cannot be recalled. ACK/transport failures do not fabricate success; normal in-app command errors remain available.
- Android/OEM versions decide the exact media UI layout and available accessory buttons. Notification tap opens the app. Long pauses/background restrictions can reclaim the session; manual activation is required again. No permission, dependency, MPD output, timer or backup changes.

## Implemented: bounded connection recovery

- Retry transient setup/control/idle EOF, socket, network, refusal/unreachable and timeout errors at most five times, after 1/2/4/8/16 seconds. Reset retry state after successful setup/readback and restored idle subscription. Never automatically retry protocol/permission/MPD ACK errors or replay mutations with uncertain outcomes. The independent M5 one-shot timer still has no retries.
- An isolated idle failure closes/replaces only the event socket and retains the healthy command socket and pending app command. Restore subscriptions and read status/current song/complete queue again, even when the queue version is unchanged. A command/readback/heartbeat failure closes both sockets and triggers full recovery under the same single retry budget.
- Cancel old retries/connections immediately on explicit disconnect, profile switch/edit/delete or manual restart; discard stale async results. No concurrent repair loops. Network and foreground events never reset an exhausted retry budget. Manual **Reconnect** starts a new one.
- Pause attempts without consuming budget while Android knows there is no available network. A passive callback accepts LAN-only Wi-Fi/VPN and does not require Internet validation, bind routing or request a network. Returning to the foreground performs one serialized ping and snapshot check, recovering a failed control connection through the same bounded loop. Do not replace healthy sockets on Activity recreation.
- Also check when the resumed Activity regains window focus, including unlock without Activity recreation. Resume and focus are independent signals: either callback order must work. Merge short event bursts, cancel queued checks on server changes, and use only the selected server's session. Checks never reset an exhausted retry budget or restart an ongoing reconnect; inactive profiles have no monitoring/retry jobs or connection errors. The independent sleep timer retains its original target and deadline.
- Show setup/control/idle failure source, retry count and known network unavailability. After five unsuccessful repairs show an explicit stopped-recovery error and retain **Reconnect**, including when healthy control survives an exhausted idle repair. Mark cached snapshots stale while control or idle updates are unavailable.
- Preserve already enabled same-server HTTP playback/session during transient control repair and manual Reconnect; use confirmed readback after restoration without restarting ExoPlayer for unchanged playing state. Never auto-enable/restart local listening. Explicit Off/switch and terminal control failure still release it; an HTTP/audio failure remains local and manually retried. Metadata can be stale during loss of control/events.
- Keep retries process-local with cancellable coroutine delays and passive callbacks. No permanent background service or additional polling solely for recovery. Existing heartbeat/audio lifecycle remains; Android power restrictions or process death can delay/prevent recovery. Add only the normal `ACCESS_NETWORK_STATE` permission; no dependency, runtime prompt or MPD configuration change.

## Implemented: M7.2 localization and licensing

- Support exactly English (`en`), German (`de`), French (`fr`), Italian (`it`),
  Spanish (`es`), Ukrainian (`uk`) and Russian (`ru`). Complete unqualified
  English resources provide fallback; unsupported language requests do not crash.
- Settings → Language defaults to System default. Display native language names;
  persist explicit selections, apply immediately and clear the override when
  returning to System default. Android 13+ system app-language settings and the
  picker share one authoritative selection. Follow Android's effective locale
  list, including device-language changes when following the system.
- A language configuration change preserves profiles, selection, theme, retained
  MPD control/event connections, manually enabled local audio and timer records.
  Do not send playback/queue commands or restart streaming just to translate UI.
  Refresh app-owned toast/notification text; inherited/system rendering remains
  Android's responsibility. Server/user metadata, paths and protocol tokens stay
  literal. Estimated timer dates/times follow the effective resource locale and
  device 12/24-hour preference; playback duration semantics stay unchanged.
- Preserve the supplied translation catalogs. Validate completeness, XML/AAPT2
  escaping, argument positions/types, plural forms and numeric bounds against
  English and `strings-en.csv`. Support scrolling/wrapping and large fonts in
  language/legal dialogs; no arbitrary character-count limits.
- License the application under **GPL-3.0-or-later**, with the complete canonical
  GPL v3 text at repository root. Settings → About displays the build version,
  license identifier, warranty summary, offline GPL/third-party texts and public
  source URL. Third-party grants/notices remain applicable. Do not publish,
  commit or alter signing as part of this milestone.

## Future work

Password authentication/storage and other features remain excluded from M7.2.

## Privacy and limits

Use a trusted network or separately configured secure tunnel. Direct MPD and ordinary HTTP are unencrypted. No telemetry, cloud services, registration, automatic port forwarding or assumed public exposure. Do not commit real endpoints or credentials. M4 deliberately enables HTTP cleartext for freely configured hosts; it does not provide authentication or encryption. Protocol resource limits and Android background constraints are documented in the architecture.
