# SimpleMPDPlayer

A minimal Android client for Music Player Daemon (MPD), for Android 12 and newer.

## Current milestone: M2

- Saved server profiles, header dropdown, persistent ordering, connection status and manual reconnect.
- First-run setup, independent MPD connection test, and Save that activates/connects the profile.
- Handle-based server drag-and-drop, deletion confirmation and persistent System/Light/Dark appearance.
- Prepared launcher artwork integrated as adaptive, legacy and themed icons.
- Browser–Player–Queue tabs with horizontal swipe navigation; Player opens by default.
- Current title/artist/album, playback state, progress/seek, previous/next, stop, Random/Repeat and supported MPD volume.
- MPD queue with current-song highlighting, play/remove/clear and move up/down actions.
- External changes through a separate MPD idle connection to the same active server.
- English Material 3 UI; Servers management lives in the Settings overflow menu.

The Browser and Sleep timer are marked as coming soon. M2 has no password authentication, HTTP audio, cover art, accounts, cloud services or analytics. Controls operate the server; this milestone does not play audio on the phone.

## Build and test

Use Android Studio with Android SDK 37 and the configured Gradle JDK 25 toolchain. Minimum device API is 31. The existing wrapper and stable dependencies are retained:

```sh
./gradlew :app:assembleDebug :app:testDeviceTestUnitTest :app:lintDebug
./gradlew :app:connectedDeviceTestAndroidTest
```

Device tests use a separate `.devicetest` app ID and controlled fixtures/loopback servers, preserving the normal app's profiles. No real MPD is required for automated tests. Do not clear/uninstall your normal app to run them.

Install the normal debug APK without clearing its data:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Use with MPD

1. Make MPD reachable from the phone on a trusted LAN/VPN, normally TCP port 6600. M2 requires unauthenticated permissions for ping, status, currentsong, playlistinfo, idle and the player/queue commands. Use a populated queue for playback tests; HTTP output is not required.
2. With no profiles, Add server opens automatically; Cancel leaves the app usable. Otherwise choose a server from the header dropdown or use Settings (three dots) → **Servers**. Enter a hostname or IP without scheme/path/embedded port. **Test** checks MPD without saving or changing the active connection; **Save** stores, activates and connects the profile, retaining it if connection fails. On Android 17+, grant **Allow local network** when requested; if permanently denied, enable Nearby devices in the app's Android settings.
3. Long-press a server drag handle in Settings → Servers to reorder; release to save the order used by the header dropdown. Settings → Appearance applies System default, Light or Dark immediately. Check the server name/status in the header and the Player metadata. Test Play/Pause, Stop, Previous/Next, seek, Random/Repeat and the MPD volume control where supported.
4. Tap tabs or swipe left from Player to Queue and right to Browser. In Queue, tap a song to play; its menu provides move/remove actions. **Clear queue** requires confirmation and changes the actual MPD queue.
5. Change the queue/options or playback from another MPD client; verify updates arrive. Check seek dragging, scrolling, Activity recreation, manual reconnect and server switching. Switching clears the old snapshot and closes both old sockets.

MPD remains the only playlist and playback authority. Local progress interpolates between server updates; failed commands do not pretend to succeed. Unusually large queues, continuous concurrent edits or a failed idle connection can require manual reconnect; see the architecture for bounds.

Plain MPD TCP can be unencrypted. The app never opens router ports or configures MPD. Never commit your real server endpoints or credentials.

The original icon image is unchanged. Its baked rounded background was replaced with a full-bleed gradient so Android applies only one launcher mask; foreground separation is approximate. API 33+ themed icons use a monochrome silhouette.

## Technology and later work

One app module, Kotlin, Compose/Material 3, ViewModel/StateFlow, Coroutines and Preferences DataStore. No new dependency coordinates are needed for M2's pager, queue controls or tests. The custom TCP client retains the tested Android-compatible ACK parser.

M3 adds directory browsing and recursive append/replace, without automatic play after replacement. Later milestones add manually enabled Media3/MediaSessionService HTTP reception and one inexact timer sending `pause 1`. Local audio/Android volume will stay separate from MPD control; buffered audio may lag behind displayed server status.

## Documentation

- [Requirements](docs/REQUIREMENTS.md)
- [Architecture and open decisions](docs/ARCHITECTURE.md)
- [Development rules](AGENTS.md)
