# SimpleMPDPlayer

A minimal Android client for Music Player Daemon (MPD), for Android 12 and newer.

## Current milestone: M4

- Saved server profiles, header dropdown, persistent ordering, connection status and manual reconnect.
- First-run setup, independent MPD connection test, and Save that activates/connects the profile.
- Handle-based server drag-and-drop, deletion confirmation and persistent System/Light/Dark appearance.
- Prepared launcher artwork integrated as adaptive, legacy and themed icons.
- Browser–Player–Queue tabs with horizontal swipe navigation; Player opens by default.
- Music-library folder browser with Up/Android Back, one directory listing at a time, and file/folder queue actions.
- Append files or recursive folders; replace the queue only after confirmation, without starting playback. Partial failures are reported explicitly.
- Current title/artist/album, playback state, progress/seek, previous/next, stop, Random/Repeat and supported MPD volume.
- MPD queue with current-song highlighting, play/remove/clear and move up/down actions.
- External changes through a separate MPD idle connection to the same active server.
- Optional **Listen on phone** HTTP audio, Media3 background playback and a read-only media session showing MPD metadata.
- English Material 3 UI; Servers management lives in the Settings overflow menu.

The Sleep timer remains marked as coming soon. M4 has no search, stored-playlist support, password authentication, cover art, downloads, offline playback, accounts, cloud services or analytics. Transport controls operate MPD; local audio is a separate optional receiver.

## Build and test

Use Android Studio with Android SDK 37 and the configured Gradle JDK 25 toolchain. Minimum device API is 31. The existing wrapper and stable dependencies are retained:

```sh
./gradlew :app:assembleDebug :app:testDeviceTestUnitTest :app:lintDebug
./gradlew :app:connectedDeviceTestAndroidTest
```

Device tests use a separate `.devicetest` app ID and controlled fixtures/loopback servers, preserving the normal app's profiles. No real MPD is required for the default automated tests. The optional `LiveStreamReadOnlyTest` requires explicit instrumentation arguments and never persists its endpoint; see the architecture. Do not clear/uninstall your normal app to run them.

Install the normal debug APK without clearing its data:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Use with MPD

1. Make MPD reachable from the phone on a trusted LAN/VPN, normally TCP port 6600. Remote control requires unauthenticated permissions for ping, status, currentsong, playlistinfo, idle, lsinfo, add, clear and the player/queue commands. Local listening additionally requires an enabled MPD `httpd` output (MP3 tested) on the configured stream port, normally 8000. Remote control works without it.
2. With no profiles, Add server opens automatically; Cancel leaves the app usable. Otherwise choose a server from the header dropdown or use Settings (three dots) → **Servers**. Enter a hostname or IP without scheme/path/embedded port. **Test** checks MPD without saving or changing the active connection; **Save** stores, activates and connects the profile, retaining it if connection fails. On Android 17+, grant **Allow local network** when requested; if permanently denied, enable Nearby devices in the app's Android settings.
3. Long-press a server drag handle in Settings → Servers to reorder; release to save the order used by the header dropdown. Settings → Appearance applies System default, Light or Dark immediately. Check the server name/status in the header and the Player metadata. Test Play/Pause, Stop, Previous/Next, seek, Random/Repeat and the MPD volume control where supported.
4. Tap tabs or swipe left from Player to Queue and right to Browser. In Queue, tap a song to play; its menu provides move/remove actions. **Clear queue** requires confirmation and changes the actual MPD queue.
5. Change the queue/options or playback from another MPD client; verify updates arrive. Check seek dragging, scrolling, Activity recreation, manual reconnect and server switching. Switching clears the old snapshot and closes both old sockets.
6. In Browser, tap a folder to open it; use Up or Android Back to return to its parent. Tap a file for **Append to queue** / **Replace queue**, or open a folder's trailing menu for the same choices. MPD performs folder recursion. Confirm replacement only when you intend to discard the current queue. Test Unicode, spaces, quotes/backslashes, empty or inaccessible folders, and switching servers while loading. Stored playlists are skipped with a notice.

7. In Player, enable **Listen on phone** to receive `http://<profile host>:<stream port>/`. It starts off after process startup, reconnect, Save or server switch. Rotation/recreation and returning to the app preserve a deliberately enabled receiver. Turn it off to close local audio without sending any MPD command. Use the phone/Bluetooth volume buttons for local sound; **MPD volume** changes the server mixer.
8. Check Connecting, Buffering, Receiving, Waiting for MPD and failure/retry feedback. HTTP bytes can contain silence while MPD is paused: readiness does not prove audible music. Local output follows confirmed MPD play/pause/stop. On MPD resume, it rejoins the live HTTP origin to discard old buffered silence. Audio can lag behind the displayed MPD title/position.
9. While listening to a playing server, switch apps or turn the screen off. The media notification/lock screen shows MPD metadata and opens the app. System transport/seek controls are deliberately unavailable; use the app to control MPD and to enable/disable local listening. Focus loss or unplugging headphones affects local audio only. Permanent focus loss/noisy events turn reception off; transient focus loss can suppress and resume local sound through ExoPlayer. Bluetooth routing uses Android defaults. After a long pause Android may reclaim the service; open the app and enable listening again if needed.

MPD remains the only playlist and playback authority. Local progress interpolates between server updates; failed commands do not pretend to succeed. Replacement serializes clear/add against this app's other commands, but cannot lock out other clients or roll back MPD changes. A failed recursive add can leave partial content; an interrupted exchange can leave its outcome uncertain. Unusually large directories/queues, continuous concurrent edits or a failed idle connection can require manual reconnect; see the architecture for bounds.

Plain MPD TCP can be unencrypted. The app never opens router ports or configures MPD. Never commit your real server endpoints or credentials.

The original icon image is unchanged. Its baked rounded background was replaced with a full-bleed gradient so Android applies only one launcher mask; foreground separation is approximate. API 33+ themed icons use a monochrome silhouette.

## Technology and later work

One app module, Kotlin, Compose/Material 3, ViewModel/StateFlow, Coroutines and Preferences DataStore. M4 adds only Media3 ExoPlayer and Session 1.11.1. The custom TCP client retains the tested Android-compatible ACK parser. System-bar icon contrast follows the selected theme, including live changes.

M5 remains one inexact sleep timer sending `pause 1`; no timer or password infrastructure is implemented. Arbitrary configured HTTP hosts require a deliberate application-wide cleartext network-security opt-in. INTERNET and the existing API 37 local-network permission remain; media playback adds FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK and WAKE_LOCK. No notification runtime permission is requested: Android exempts media-session notifications. See the architecture for service and audio-focus limits.

## Documentation

- [Requirements](docs/REQUIREMENTS.md)
- [Architecture and open decisions](docs/ARCHITECTURE.md)
- [Development rules](AGENTS.md)
