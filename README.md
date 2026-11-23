# SimpleMPDPlayer

A minimal Android client for Music Player Daemon (MPD), for Android 12 and newer.

## Current milestone: M7.2

- Saved server profiles, header dropdown, persistent ordering, connection status, bounded automatic recovery and manual reconnect.
- First-run setup, independent MPD connection test, and Save that activates/connects the profile.
- Handle-based server drag-and-drop, deletion confirmation and persistent System/Light/Dark appearance.
- Prepared launcher artwork integrated as adaptive, legacy and themed icons.
- Browser–Player–Queue tabs with horizontal swipe navigation; Player opens by default.
- Music-library folder browser with Up/Android Back, one directory listing at a time, and file/folder queue actions.
- Append files or recursive folders; replace the queue only after confirmation, without starting playback. Partial failures are reported explicitly.
- Persistently collapsible title/artist/album, one playback/sleep-status row, progress/seek, previous/next, stop, adjacent Random switch/Repeat dropdown, and supported MPD volume.
- MPD queue with current-song highlighting, play/remove/clear and move up/down actions.
- External changes through a separate MPD idle connection to the same active server.
- Optional **Listen on phone** HTTP audio, Media3 background playback and MPD-backed system media controls with title/artist and playback state.
- One persistent **Sleep timer**, independent of local listening, waiting for confirmed playback before counting down; duration dropdown and custom 1–1440 minutes.
- English fallback plus German, French, Italian, Spanish, Ukrainian and Russian; Settings → Language follows the system by default or persists an explicit app-language choice.
- Settings → About shows the build version, GPL-3.0-or-later license, offline license/notices and public source.

M7.2 has no search, stored-playlist support, password authentication, cover art, downloads, offline playback, accounts, cloud services or analytics. Transport controls operate MPD; local audio is a separate optional receiver.

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
3. Long-press a server drag handle in Settings → Servers to reorder; release to save the order used by the header dropdown. Settings → Appearance applies System default, Light or Dark immediately. Check the server name/status in the header and the Player metadata. Test Play/Pause, Stop, Previous/Next, seek, Random/Repeat and the MPD volume control where supported. Tap the track-information chevron to collapse/expand details; this preference is global and persists across restarts. Playback status and sleep status share one line. Random stays a separate switch. Repeat offers **None**, **Queue** and **Single**; both controls share a row when space permits and wrap for large text. Random remains independent in all three modes. External `single: oneshot`, stop-after-track and unknown states are shown explicitly and are changed only by an explicit selection.
4. Tap tabs or swipe left from Player to Queue and right to Browser. In Queue, tap a song to play; its menu provides move/remove actions. **Clear queue** requires confirmation and changes the actual MPD queue.
5. Change the queue/options or playback from another MPD client; verify updates arrive. Check seek dragging, scrolling, Activity recreation, manual reconnect and server switching. Switching clears the old snapshot and closes both old sockets.
6. In Browser, tap a folder to open it; use Up or Android Back to return to its parent. Tap a file for **Append to queue** / **Replace queue**, or open a folder's trailing menu for the same choices. MPD performs folder recursion. Confirm replacement only when you intend to discard the current queue. Test Unicode, spaces, quotes/backslashes, empty or inaccessible folders, and switching servers while loading. Stored playlists are skipped with a notice.

7. In Player, enable **Listen on phone** to receive `http://<profile host>:<stream port>/`. It starts off after process startup, Save or server switch. Same-server control recovery and manual Reconnect preserve an already enabled receiver where possible. Rotation/recreation and returning to the app preserve a deliberately enabled receiver. Turn it off to close local audio without sending any MPD command. Use the phone/Bluetooth volume buttons for local sound; **MPD volume** changes the server mixer. The information button beside **Listen on phone** explains this without toggling reception.
8. Check Connecting, Buffering, Receiving, Waiting for MPD and failure/retry feedback. HTTP bytes can contain silence while MPD is paused: readiness does not prove audible music. Local output follows confirmed MPD play/pause/stop. On MPD resume, it rejoins the live HTTP origin to discard old buffered silence. Audio can lag behind the displayed MPD title/position.
9. While listening to a playing server, switch apps or turn the screen off. The media notification/lock screen shows MPD metadata and opens the app. System Play/Pause, Previous and Next control MPD through the same repository as the app; Bluetooth/headset media keys use that session too. Seeking, Stop, queue editing and artwork are unavailable in system controls. The confirmed MPD state is displayed even while the HTTP decoder buffers or receives silence. Use the app to enable/disable local listening. Focus loss or unplugging headphones affects local audio only. Permanent focus loss/noisy events turn reception off; transient focus loss can suppress and resume local sound through ExoPlayer. Bluetooth routing uses Android defaults. After a long pause Android may reclaim the service; open the app and enable listening again if needed.

10. Open Settings → **Sleep timer**. Default duration is 30 minutes. Choose **15 minutes**, **30 minutes**, **45 minutes**, **60 minutes** or **Custom…** from the duration dropdown. Custom accepts 1–1440 whole minutes; keyboard **Done** or the dialog **Done** applies a valid value. Invalid input stays unapplied. Applying a duration never turns an Off timer On; the On/Off switch uses the last applied duration. On binds the selected server: confirmed MPD **Playing** starts the countdown immediately, otherwise **Waiting for playback** has no deadline/alarm. The next observed **Playing** starts the full chosen duration. Later Pause/Stop/Play do not move an existing deadline. Changing the applied duration resets an enabled timer on its original server: count down now if that server is confirmed playing, otherwise wait again. Off cancels both waiting/countdown and any alarm. The dialog shows the bound server and waiting/countdown state; Player shows compact status beside MPD status.
11. Selecting another server does not move the timer. Deleting the bound profile or changing its host, MPD port or HTTP stream port cancels it; renaming and sorting do not. At expiry an independent short connection sends only `pause 1`, never Stop/Play or queue commands. MPD acknowledgement is required for success. Failure is saved and shown under Sleep timer with a Player attention hint; no automatic retry runs.
12. Android power saving can delay this **inexact** alarm substantially. Clock changes do not move its monotonic deadline; the estimated wall time follows clock changes. Normal process/Activity restart preserves an existing countdown/alarm within the same device boot. A waiting timer is persisted too, but cannot detect playback while the process is dead or its bound server is not the interactive selection. On the next resume/reconnect/selection of that server, confirmed Playing starts the full duration from detection, not retrospectively. A brief playback period that ended before observation can be missed. No timer-only listener/service/polling is added. Reboot cancels the old timer on the next app start, without automatic rearming. Force Stop, missing permission, unavailable LAN/server or Android background restrictions can prevent delivery. If interrupted mid-delivery, the outcome is uncertain and is not replayed. A cancellation/profile edit arriving after delivery has been claimed waits for that bounded attempt; a command already sent cannot be recalled.

MPD remains the only playlist and playback authority. Local progress interpolates between server updates; failed commands do not pretend to succeed. Replacement serializes clear/add against this app's other commands, but cannot lock out other clients or roll back MPD changes. A failed recursive add can leave partial content; an interrupted exchange can leave its outcome uncertain. Unusually large directories/queues, continuous concurrent edits or a failed idle connection can require manual reconnect; see the architecture for bounds.

Plain MPD TCP can be unencrypted. The app never opens router ports or configures MPD. Never commit your real server endpoints or credentials.

The original icon image is unchanged. Its baked rounded background was replaced with a full-bleed gradient so Android applies only one launcher mask; foreground separation is approximate. API 33+ themed icons use a monochrome silhouette.

## Connection recovery

Transient MPD setup/control failures retry at most five times after 1, 2, 4, 8 and 16 seconds. A successfully restored connection resets the budget. An isolated idle/event-socket failure repairs only that socket, retaining healthy control and restoring status, current song, queue and subscriptions. No playback, queue or output mutation is automatically replayed. Protocol/permission errors require correction and manual Reconnect.

The header distinguishes connection setup, control and idle failures, shows retry progress/network unavailability and offers **Reconnect** after exhaustion. A passive Android network callback suspends attempts while no network is known; LAN-only Wi-Fi does not require Internet validation. Network return resumes the remaining budget. Returning to the foreground or regaining window focus while resumed (including unlock) requests one serialized ping/readback check. Resume and focus work in either order; events within 300 ms merge. Checks neither replace healthy sockets nor start another retry loop. Foreground/focus/network events do not reset an exhausted budget. A manual Reconnect starts a fresh budget. Switching/deleting/disconnecting cancels old retries and queued foreground checks immediately. Only the selected server is monitored; the independent timer's original target/deadline remain unchanged.

Debug builds provide sparse `MpdConnection` diagnostics (`adb logcat -s MpdConnection`): process-local selection generation, check trigger, failure source/category, offline/recovery state and retry count. They contain no server names, profile IDs, addresses, credentials, exception text or MPD contents. A control failure, isolated idle failure and exhausted budget are distinguishable; these logs alone cannot establish whether Android Doze, Wi-Fi power saving or another network condition caused the socket loss.

Recovery of the same server preserves existing local HTTP playback and its MediaSession without recreating ExoPlayer. System controls are disabled while control connectivity is unavailable. The last confirmed playback state may be stale during an outage. Terminal control failure ends local listening; a healthy control connection with exhausted idle repair can retain it. HTTP/audio failures still require a manual local-audio retry and never restart automatically. No recovery enables **Listen on phone** on its own.

Recovery is process-local: no new service, persistent work, additional polling, Wi-Fi lock or wake lock is added. Android can delay coroutine timers or kill the process in the background; the backoff is a minimum delay, not a screen-off availability guarantee. The existing local audio service remains the only foreground service. `ACCESS_NETWORK_STATE` is added solely for passive availability hints; no new runtime permission or dependency is needed.

## Technology and later work

One app module, Kotlin, Compose/Material 3, ViewModel/StateFlow, Coroutines and Preferences DataStore. M4 adds only Media3 ExoPlayer and Session 1.11.1. The custom TCP client retains the tested Android-compatible ACK parser. System-bar icon contrast follows the selected theme, including live changes.

M6 retains Media3 1.11.1 and adds no dependencies or permissions. System controls exist only while manually enabled local listening has a session; there is no playback resumption after shutdown. Android/OEM versions and accessories determine the exact notification/lock-screen layout and button delivery.

M5 adds no dependencies or permissions: one non-exported receiver uses `setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)`. No exact-alarm permission, boot receiver or additional foreground service is used. Password support remains excluded. Timer state uses a separate Preferences DataStore excluded from cloud backup and device transfer, so a device-bound alarm cannot be restored elsewhere. Arbitrary configured HTTP hosts require a deliberate application-wide cleartext network-security opt-in. INTERNET and the existing API 37 local-network permission remain; media playback adds FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK and WAKE_LOCK. No notification runtime permission is requested: Android exempts media-session notifications. See the architecture for service and audio-focus limits.

## Documentation

- [Requirements](docs/REQUIREMENTS.md)
- [Architecture and open decisions](docs/ARCHITECTURE.md)
- [Development rules](AGENTS.md)

## M6 manual acceptance on the phone

1. Start MPD music from another client, then manually enable **Listen on phone** in this app.
2. Check title/artist/state and the standard notification/lock screen, with no artwork or seek bar. Tap the notification to open the app.
3. Test system Play/Pause, Next and Previous, then Bluetooth/headset play/pause/toggle and track buttons. Confirm the other MPD client sees those changes; phone volume remains separate.
4. Change title and playback state from the other client. Verify the system display follows, including Pause while HTTP may still transmit silence.
5. Disable **Listen on phone**. Confirm reception/session disappear and MPD continues unchanged. Check server switch clears the old session and leaves listening off; same-server Reconnect should preserve an active receiver.
6. Repeat M4 listening checks: actual sound, screen-off/background reception, stream resume/latency, device volume, focus loss, headphone/Bluetooth route changes and failure/manual retry without unintended MPD commands.
7. Repeat M5 timer checks: explicit pause at expiry reflected in the system display, timer without local listening, cancel/change duration, original target after selection changes and delayed delivery under power saving. Use a short custom timer; do not assume minute-exact delivery.

## Timer and compact Player acceptance

1. With MPD paused/stopped, enable Sleep timer: **Waiting for playback**, switch On, no countdown. Start playback from another client: verify the full duration begins. Pause/stop/resume again and confirm the deadline does not reset.
2. Change duration while playing, then while paused: the first restarts the countdown; the second replaces it with waiting. Turn Off in both states. Confirm expiry still sends only Pause and preserves queue/position.
3. With timer Off, select **Custom…**, enter a valid value and press keyboard **Done**: duration changes, timer stays Off. Check 0/1441 rejection and dialog **Done** applying a valid value without enabling. Rotate while waiting/counting down and verify no reset/duplicate alarm.
4. Check status/timer alignment, Random/Repeat groups and long titles at normal and large font sizes. Open the listening-info popup and confirm reception does not toggle. Collapse track details, change tracks/server, recreate/restart the app, and verify the saved preference remains.
5. Let Android stop the process while waiting, then start MPD externally: no timer-only background detection is promised. On returning/reconnecting to the bound server, Playing starts a fresh full countdown. Repeat the existing media-control, local-audio and recovery checks.

## License

SimpleMPDPlayer is free software licensed under **GNU GPL version 3 or, at your
option, any later version** (SPDX: `GPL-3.0-or-later`). See the complete [LICENSE](LICENSE).
It is provided without warranty, as described there. Public project source:
[github.com/jschbe/SimpleMPDPlayer](https://github.com/jschbe/SimpleMPDPlayer).
The exact corresponding source and build files for any distributed binary must
be made available under the GPL; local uncommitted changes are not published by
this milestone. No commits or uploads are performed automatically.

Third-party software retains its own licenses. [Notices and complete license
texts](docs/THIRD_PARTY_NOTICES.txt) ship in the APK and are available offline in
Settings → About, alongside the GPL. See the [dependency audit](docs/LICENSING.md).

## Language acceptance on the phone

1. Open Settings → Language. Try German, French, Russian and Ukrainian; check
   Player, Browser, Queue, server editing, timer, dialogs and accessibility labels
   with long metadata and a large system font. User names, paths and song tags
   remain literal. Native language names stay the same in every language.
2. Restart the app: an explicit language should survive. Select System default,
   change the device language, and return: supported languages should follow it;
   unsupported languages fall back to English. On Android 13+, also change the
   app language from Android settings and check the in-app selection.
3. With local listening/timer already enabled, switch language and check that
   connection, stream, timer deadline and theme remain intact. Verify translated
   media controls/subtitle where Android renders them; OS-owned UI can differ.
4. Open About: check the installed version, GPL-3.0-or-later, full license and
   third-party notices without network access, and the source link in a browser.
