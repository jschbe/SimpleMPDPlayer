# Requirements

SimpleMPDPlayer is a minimal Android MPD client for Android 12 / API 31 and newer. This file defines behavior; [architecture](ARCHITECTURE.md) describes implementation. UI strings, code comments and documentation are English.

## Implemented: M1 server management

- Add/edit/delete profiles, with deletion confirmation. Stable UUID, required name/host, MPD port default 6600, stored HTTP stream port default 8000. Validate ports 1–65535 and hostname/IPv4/IPv6 syntax without DNS.
- Persist profiles and the last selected ID in DataStore. Reconnect on startup; never automatically choose a different server after deletion or invalid data. Saving any added/edited profile activates it and starts one connection attempt, including name-only edits; failed connections retain the profile and selection.
- Exactly one selected server. Switch/disconnect closes all of its technical connections and clears its player/queue snapshot. Do not synchronize multiple servers.
- Invalid records and storage errors must not crash or silently overwrite valid data. Keep valid records, report recovery and offer retry for IO failures.
- No password support yet: no password fields, authentication, Keystore or encryption dependency. Existing network permissions and Android 17 local-network permission handling remain available.

## Implemented: M2 navigation and player

### Navigation

- Header shows the selected server name, subtle connection status and a Settings overflow menu. Offer manual reconnect when disconnected/failed; show understandable loading and error states.
- Main tabs are **Browser, Player, Queue**, in that order. Player opens by default with a selected server. With no selection, open Servers management; with no profiles, automatically open Add server once per entry into the empty state. Cancel leaves the app usable without reopening the dialog.
- Tabs and horizontal swipes share one state: left from Player opens Queue, right opens Browser, reverse gestures return to Player. Queue scrolls vertically. System Back returns to the parent only while Browser is visible in a subdirectory; otherwise retain existing Back behavior for Servers/dialogs and Android.
- The header server-name dropdown lists stored profiles in order, marks the active one and switches without confirmation. Settings contains Servers, the unavailable Sleep timer and Appearance. Servers is not a main tab.

### Player

- Show current title, optional artist/album, state, elapsed position and known duration. Missing Title falls back to filename; missing artist/album is omitted. Unknown duration has no misleading seek range. An empty queue has an explicit empty state. No cover art.
- Play/Pause sends pause 1 while playing, pause 0 while paused, or play while stopped. Also provide stop, previous, next, seekcur, Random, Repeat and MPD setvol.
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
- Initial loads and errors are bounded. Listener/transport failure stops both connections and offers manual reconnect; no complex automatic retry system or endless spinner. Cached information after failure is marked stale.
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

- Player exposes **Listen on phone**, independent of MPD transport and MPD volume. Enable it manually for the connected active profile. Fresh process startup, every new connection/Save and server switch leave it off; Activity recreation/returning to the app preserves an enabled receiver.
- Receive only the active profile's root HTTP stream using its validated host and stream port. No separate songs, queue, library, download or offline playback. MP3 is the verified format; other formats depend on Media3/platform support. No server/output configuration is changed.
- Turning reception off releases the player, HTTP connection, session and foreground service. It sends no MPD command, especially no pause/stop or disableoutput. Switch/delete/reconnect release the old receiver before starting the next MPD connection. A stream error leaves MPD control connected; MPD connection loss stops local reception.
- Show Off, Connecting, Buffering, Receiving, Waiting for MPD, Android interruption and sanitized errors. Finite connection/read/buffering limits and no automatic retry; enable the switch or use **Retry local audio** to retry.
- MPD status/current song remain authoritative for title, duration, position and all app transport controls. Local output pauses when MPD is paused/stopped/unknown; ready HTTP data is not evidence of music. On MPD resume rejoin the live origin to avoid replaying buffered silence. Latency between MPD status and audio is expected.
- Use Media3 ExoPlayer in a MediaSessionService for background/screen-off playback, audio focus, default Android/Bluetooth routing and becoming-noisy handling. Device volume affects local audio only. Permanent focus loss/noisy events turn reception off without controlling MPD; transient focus loss may suppress and resume local audio through Media3.
- The notification/lock screen displays available MPD title/artist/album and MPD state. No independent HTTP track duration/progress is advertised. The session is read-only: system play/pause/stop/next/previous/seek, item replacement and custom commands are unavailable for all controllers, including the platform/notification controller. Transport remains in the app. Controllers/service restarts cannot authorize local playback.
- No playback resumption receiver, persisted streaming flag or sticky restart. An enabled paused receiver may be reclaimed under Android background limits; foreground/audio-focus restrictions may require opening the app and manually retrying. With reception disabled no audio foreground service remains.
- Explicitly allow cleartext HTTP for arbitrary user-configured hosts through network security configuration. Retain INTERNET and existing API 37 local-network permission behavior (API 36 has no mandatory new runtime LAN permission); add only media foreground-service permissions and WAKE_LOCK. No router/MPD changes.

## Later milestones

- One sleep timer: 15/30/45/60 minutes or custom duration, remaining time, change/cancel. Send **pause 1**, preserving position, to the server bound at start. Use setAndAllowWhileIdle without exact-alarm permission, independently of local audio; delay/failure is possible. No complex multi-server timer infrastructure.

## Privacy and limits

Use a trusted network or separately configured secure tunnel. Direct MPD and ordinary HTTP are unencrypted. No telemetry, cloud services, registration, automatic port forwarding or assumed public exposure. Do not commit real endpoints or credentials. M4 deliberately enables HTTP cleartext for freely configured hosts; it does not provide authentication or encryption. Protocol resource limits and Android background constraints are documented in the architecture.
