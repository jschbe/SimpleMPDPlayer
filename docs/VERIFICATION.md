# Verification history

These reports record verification by milestone and review package. The
[requirements](REQUIREMENTS.md) and [architecture](ARCHITECTURE.md)
describe the current implementation, and [development](DEVELOPMENT.md) explains
how to reproduce available checks.

## 1.0.1 Android local network permission

On **2026-11-25**, the previous 1.0.0 `.devicetest` build on Android 17
reproduced the Test error with no editor action or native request. Save
persisted a profile without asking for access. Updating to 1.0.1 preserved
that profile and issued neither a prompt nor an MPD connection on launch.

The permanent Medium Phone API 37 AVD (`medium_phone_api_37`) used the
official Google APIs x86_64 image, SDK package revision 8, with KVM on the
NUC. Real native permission checks used only an isolated loopback MPD
fixture. Test requested permission. The first denial left the editor open,
and the second made App Settings the recovery action. Another Test did
not prompt or redirect. Granting Nearby devices in Android Settings kept
the ordinary draft fields. The fixture saw no sockets until the user
explicitly repeated Test, which sent only `status`. A wrong synthetic MPD
password then produced the separate MPD rejection.

Save requested permission on a fresh install. After granting, the fixture
observed one control and idle pair. A separate Save denial left one saved
profile selected and a voluntary retry in the compact status. Android
terminated the process when access was revoked. After restarting, the
app opened no new socket until access was restored through Android Settings,
then reconnected the saved selection once. These checks used permission
state, process identity and new socket counts. Host-side TCP closure timing
after revocation was not established.

The two Android 17 Compose layout checks passed with Android font size at
200% and all seven locales. On Medium Phone API 36, Test and Save worked
against the loopback fixture without a permission prompt. The focused
`.devicetest` suite passed **28 tests** with zero failures or skips,
including the G03 external-language case. Debug and both device-test APKs
built successfully. The full JVM suite passed **212 tests** with zero failures
or skips. Lint reported **0 errors and 11 existing warnings**. The
optimized Release APK built with a disposable key, passed APK signature
and 16 KiB ZIP alignment checks, and launched on API 37 with the updated
editor and About version. This verification did not use a production key
or test a Play-distributed build.

## Privacy policy integration

On **2026-11-25**, the Debug and device-test APKs built successfully,
**210 JVM tests** passed, and Lint reported **0 errors and 11 existing warnings**.
The [privacy policy](../PRIVACY.md) covers all six app-declared permissions.
The Android 17 runtime permission description was checked against the
existing permission gate and Android documentation.

The offline Medium Phone API 36 `.devicetest` run passed **9 focused Compose
tests** with no failures or skips. The tests checked both About link destinations
through intercepted browser intents, existing offline license navigation, and
the action layout in all seven languages and both themes. One separate layout
test also passed at 200% Android font size. The published GitHub policy URL
returned HTTP 200 in a host check.

## Browser right-swipe navigation

On **2026-11-25**, Debug and the isolated test APKs built successfully. All
**210 JVM tests** passed. Lint reported **0 errors and 11 existing warnings**.
The focused Browser and Player navigation suite passed **25 tests** with no
failures or skips on the offline Medium Phone API 36 emulator using
`.devicetest`. The tests covered single-level parent navigation, the root
no-op, loading and error states, diagonal and vertical drags, item taps and
left/right tab navigation.

## About and offline license presentation

On **2026-11-25**, Debug and both device-test APKs built successfully. All
**210 JVM tests** passed. Android Lint reported **0 errors and 11 warnings**,
all in unchanged manifest or resource files. The two legal resources in
the Debug and device-test APKs matched their source files byte for byte.

The focused Compose run passed **9 tests** with zero failures or skips in
`.devicetest` on Medium Phone API 36 with no default network. The tests
opened both complete legal documents through About, checked their final
paragraphs, and verified Back and Done navigation.
An instrumentation monitor intercepted the source action and checked the
GitHub destination. The About dialog did not display the raw URL.

The layout test checked all seven languages in both themes, including an
additional run with Android's font size set to 200%. It checked the About
action alignment, touch heights, and availability of the grouped overview and
full-text action.

## G2 release preparation for 1.0.0

On **2026-11-25**, G2 started from clean commit `9761509` with both G1 fixes.
The release build uses version name `1.0.0`, version code `1`, minimum SDK
31 and compile/target SDK 37. Version code availability and Production access
still need confirmation in Play Console.

Debug and `.devicetest` built without signing variables. The JVM suite
passed **210 tests**, with zero failures or skips. Debug Lint reported
**0 errors and 20 existing warnings**. The complete Android regression suite
below is the earlier G1 run. G2 tested the optimized Release APK directly.

Release packaging rejected missing inputs, a request with the configuration
cache enabled, and an invalid alias. The optimized AAB and APK used only a
disposable key. Bundletool 1.18.3 validated the AAB, and its manifest
confirmed the production package, SDKs, version, LAN permission, media
playback service type, backup and locale configuration, and absence of
test-only or debuggable flags. The timer receiver and local stream service are
non-exported. Apart from the launcher, the two exported library components
require `BLUETOOTH_PRIVILEGED` or `DUMP`. The packaged backup exclusions,
seven locale declarations, GPL and third-party text were verified. All
eight native libraries have at least 16 KiB ELF load alignment. APK ZIP
alignment, AAB alignment metadata and both test signatures passed.

The first optimized APK failed at application startup with
`IllegalAccessError`. R8 had moved `SequencesKt___SequencesJvmKt` out of
`kotlin.sequences`, away from its package-private superclass. A
[release-only keep rule](../app/src/release/keepRules/kotlin-sequences.keep)
preserves that package name. The final R8 mapping confirmed the hierarchy
stayed together, with code and resource optimization still enabled.

The disposable-key Release APK was installed on a fresh Medium Phone API 36
AVD on the NUC. A launch regression check confirmed the initial Add server
dialog. The release UI rejected the wrong test password, connected after
saving the correct one, and showed version `1.0.0` and both offline legal
documents. All seven language options were present, and switching to French
and back to English took effect.

The isolated loopback daemon was MPD **0.24.4** (protocol greeting
`0.24.0`), run from temporary Debian packages without installing a system
service. Direct protocol checks covered denied guest read/control, wrong
password, allowed read and denied Pause for a read-only role, and allowed
Play, Pause and idle notifications for an authenticated control role.
The Release APK sent Play, prepared the daemon's real HTTP stream and
registered its Android media session. Its one-minute inexact AlarmManager
timer authenticated, paused the same daemon, and displayed the acknowledged
result. The corrected Release build produced no fatal app crash in these
checks. The daemon and the temporary emulator were stopped, and the
disposable key and signed artifacts were removed.

API 31 and 37 system images were unavailable. API 37 LAN permission
grant/denial and API 31 runtime are unverified here. The 16 KiB checks were
artifact inspection. Actual Android cloud backup/transfer restore, OEM
power-saving behavior, physical audio routing and audible playback remain
outside this run. Real-key signing and final artifact verification are
separate steps.

## G1 credential deadline and alarm startup

On **2026-11-25**, the G1-01 and G1-02 changes were tested from review base
`1b61db1`. The complete JVM suite passed **210 tests**, with zero failures
or skips. Debug and both device-test APKs built successfully. Android Lint
reported **0 errors and 20 existing warnings**.

The complete Android run on Medium Phone API 36 used only `.devicetest` and
reported **106 passed, 0 failed and 6 opt-in skips** out of 112 discovered.
The first attempt ended early when the emulator process crashed. The complete
rerun used the same AVD with Vulkan disabled. The G03 external-language case
passed in 2.9 seconds in the complete run and 3.9 seconds in a separate run
of all five localization UI tests. The full run included an
AlarmManager delivery on the PIN-locked emulator using the latest password
of a non-selected timer target.

JVM regressions covered unanswered credential reads during initial setup,
Test and isolated idle recovery, cancellation of callers while two workers
remained blocked, stale results after a server switch, the existing timer
budget and cleanup deferral for all pending timer states. The worker stall
was injected.

The opt-in cold-alarm probe passed all four Android phases with actual
AlarmManager delivery and Android Keystore records. Both trials verified that
the process was absent before the alarm and that Android started a new one.
Each trial sent one Pause to a loopback fixture. The protected trial sent
`password` before Pause. The orphan remained after alarm-only startup and
while the interactive timer waited. A later interactive start removed the
orphan and left the referenced credential usable. The emulator started for
this verification was stopped afterward.

## Package E implementation verification and release limits

Package E / R09 was verified on **2026-11-25**.
The final Medium Phone API 36 run used only the isolated `.devicetest` app,
synthetic credentials and loopback fixtures.

| Check | Result |
| --- | --- |
| Debug and both device-test APKs | Built successfully |
| JVM suite | 204 passed, 0 failed, 0 skipped |
| Full Android suite | 106 passed, 0 failed, 5 skipped out of 111 discovered |
| Additional Android tests at the final stand | 9 passed, 0 failed, 0 skipped |
| Android Lint | 0 errors, 20 existing warnings |
| Git whitespace check | Passed |

The five default skips were the opt-in live HTTP case, the two timer probe
phases, the locale probe and the credential process probe. The nine separate
passes covered three duration/editor cases, the two credential probe phases
with different process IDs, three password-editor cases at 2.0 font scale,
and one actual AlarmManager delivery on a PIN-locked emulator. The latter
authenticated with the latest password of the original, non-selected target
and sent one Pause while preserving the original generation and deadline.
The final G03 external-language case passed in about three seconds.

Credential tests exercised real Android Keystore encryption, randomized
records, endpoint binding, corrupt/missing records and synthetic process
persistence. Backup XML and private storage location were inspected.
Restore without credentials was simulated by keeping regular preferences and
removing the local key and ciphertext. **Actual Android cloud restore and
device-to-device transfer were not performed. Real password-protected MPD
interoperability was not tested.** MPD 0.23.12 ACK classification was checked
against source and exercised with loopback fixtures. The historical live
HTTP check below did not authenticate to a protected server.

The first Android full-discovery run for E returned **103 passed, 1 failed,
5 skipped**. A MediaSession test still expected the former generic ACK 4
category. The corrected assertion checks MPD permission denial and retains
the playback readback assertions. A later full run passed 105 with five skips.
The explicit default-duration UI regression was then added, and the final
111-case run above passed. Early targeted runs also exposed an unmasked
Compose semantics value and a disabled lock screen in the AVD. The password
semantics and test setup were corrected before the final runs.

The final automated runtime baseline is the API 36 emulator. Some earlier
controlled UI fixtures ran on the Samsung Galaxy A51 (Android 13 / API 33),
as identified in the individual reports. Installation-only checks provide
no runtime coverage. There is no corresponding API 31 or API 37 runtime
run. API 31/32 locale auto-storage and API 37 LAN permission enforcement
remain unverified at runtime. Real audible playback and accessory routing,
OEM lock-screen media presentation, TalkBack speech and OEM/Doze power-saving
timing still need device acceptance. An emulator alarm or media-key test
cannot establish those behaviors.

## M1–M3 profiles, player, queue and browser

JVM tests additionally cover atomic Save/activate/connect, independent probes, stale notices, empty-list setup transitions, ordering and theme persistence. They also cover profile/storage behavior, protocol parsing, playback/queue commands, repeated/Unicode metadata, empty/duplicate-path queues, interpolation, ACK/transport failures, external idle updates, cancellation and stale replies during server switches. Loopback socket tests verify that idle has no ordinary deadline and does not block commands.

M3 JVM tests cover mixed lsinfo records, missing metadata, skipped playlists, exact special-character commands over loopback sockets, ACK recovery, lazy loading, superseded reads, append/replace/readback, serialization against browser/idle/heartbeat work, partial/uncertain failures and cancellation/stale results on switches. Compose fixtures cover folder/Up/Android Back navigation, pager behavior, action menus, confirmation/cancellation, empty/loading/error/disconnected states, stale dialogs and scroll preservation. A window-level test checks system-bar icon contrast after explicit and simulated system theme changes. No automated browser test uses a live MPD server or the normal app's stored profiles.

Android tests cover the ICU ACK regression, player/queue parsing and controlled Compose fixtures for tabs, swipes, empty state, queue selection/actions, settings and seek dragging. Additional controlled ViewModel/UI fixtures cover setup, probe/save, header selection, handle-only dragging, deletion, appearance and launcher-resource rendering. They do not access a real MPD server or stored profiles.

Tests used isolated `.devicetest` fixtures and kept the normal app outside instrumentation. See [development](DEVELOPMENT.md#instrumented-tests) for current commands. M4 added the two Media3 dependency coordinates and media foreground-service permissions and wake lock.


## M4 local HTTP audio

JVM coverage includes hostname/IPv4/IPv6/IDN URL construction and rejected profiles, manual intent, connect/buffer/ready/off/error state changes, manual retry, stale generation callbacks, metadata updates, connection loss and synchronous release before server switching/reconnect. ViewModel tests prove local enable/disable does not send MPD commands. Android tests use a generated CC0 mono 44.1 kHz / 128 kbit/s MP3 tone over an in-process HTTP server and a read-only MPD fixture. They cover real decoding, buffering, initial MPD pause, pause/resume, HTTP 503, stalled HTTP/read timeout, no retries, Activity recreation without another HTTP request, Home/screen-off reception, foreground/service/socket shutdown, reconnect/deletion, MPD metadata/unknown stream duration, rejected MediaController commands and media keys, transient/permanent audio focus and unauthorized service/controller starts. Compose tests cover the independent listening switch, status/error/retry and disconnected state. Fixtures only write to the isolated test app's profiles.

`LiveStreamReadOnlyTest` is optional and skipped without explicit `readOnlyStreamHost` instrumentation arguments (optional `readOnlyMpdPort` / `readOnlyStreamPort`). The probe leaves its endpoint out of persisted profiles. It passively reads HTTP and starts only repository read/idle traffic, then prepares the local decoder. It never invokes MPD transport/queue/options/volume/output mutations and makes no claim to restore concurrently changed server state. Do not use a physical device for this test. The live server's paused state proves neither audible music nor end-to-end speaker output.

Emulator validation uses the existing Medium Phone AVD, API 36, and the `.devicetest` app. Hardware Bluetooth/headphone switching, actual becoming-noisy delivery and audibility on a phone remain manual acceptance checks; a shell broadcast is not a substitute for a genuine route change. API 31/37 runtime behavior is built/linted but not exercised on those OS versions in this run. HTTP/MPD reachability, encoder support, stream latency, OEM battery policies and long-pause/background focus restrictions remain environmental limits. This M4 verification predates M5 and R09.

Verified on 2026-11-22: debug build, all 90 JVM tests, the complete 36-test Android fixture suite and a subsequent focused eight-test service run passed on Medium Phone / API 36. Lint completed with zero errors and 19 warnings (including the deliberate cleartext configuration and existing dependency/resource warnings); diff whitespace checks passed. The separate opt-in live test also passed: HTTP 200 / audio/mpeg, 16,384 bytes received by Android and Media3 ready in WAITING_FOR_MPD while the server reported PAUSED. A NUC-only passive HTTP check received 65,536 bytes. No live MPD mutations, SSH, physical-device testing or writes to normal app profiles were performed. This does not verify audible music, physical routing, minimum-API runtime behavior or API 37 permission enforcement.


## M5 sleep timer and alarm checks

Verified on 2026-11-22: debug build, 111 JVM tests and Lint passed (0 errors, 19 existing/deliberate warnings). The full Medium Phone API 36 suite executed 42 Android tests successfully; the optional M4 live-LAN check and two host-driven process-probe phases were skipped unless explicitly enabled. The final focused run passed all six M5 Android tests. All active timer tests used loopback fake MPD only; the real server was neither contacted nor modified during M5. No physical device or normal app profile was used.

JVM cases cover duration/default/presets/invalid custom input, one alarm, start/cancel/reset, wall-clock changes and overdue display, same-boot reload without rescheduling, reboot/invalid monotonic references, missing alarm/boot information, original target across selection and duration changes, rename/order versus endpoint edit/delete, old/early/duplicate events, serialized cancellation/edit versus an already claimed dispatch, interrupted registration/delivery, ACK/authentication/network/timeout errors and ordinary storage failure. Disk reload and malformed timer records are checked. Loopback wire tests validate the greeting, exactly one explicit Pause, no other mutation and socket closure.

Android cases cover creation/cancellation/real PendingIntent identity, receiver execution without Activity or local audio, duplicate delivery, stale/cancelled PendingIntents, endpoint invalidation, persisted ACK/read-timeout failures, absence of exact/boot permissions and exported receiver, presets/custom validation, Activity recreation, stored duration, and System/Light/Dark dialog behavior. Receiver tests shorten only their test record's deadline and explicitly send the real PendingIntent; they do not promise a fixed Doze execution delay.

Review R05 added separate tests for actual `setAndAllowWhileIdle` delivery on Medium Phone API 36. A six-second fixture deadline produced a durable DELIVERING claim before exactly one loopback Pause and a durable COMPLETED record. In the baseline lock-stall case, `dumpsys alarm` showed the registration disappear about 64 ms past the deadline. The token still existed after the receiver logged expiration of its 8 s budget, and refresh left the record ACTIVE. In the final focused run, consumption was observed 24 ms past the deadline, refresh persisted FAILED, and the token disappeared with zero commands. Ordinary Pause arrived 53 ms past the deadline. These are test observations on an awake AVD. Controller fixtures inject pre-claim read/write errors and check generation isolation. The runtime tests use real AlarmManager and DataStore, with the mutation lock as the injected fault.

The opt-in `SleepTimerProcessProbe` was also exercised by a host driver with an ephemeral NUC loopback fake (emulator bridge only). After preparation, a normal `.devicetest` Activity process was terminated under its own test UID without Force Stop, then restarted normally: PID changed while UUID, elapsed deadline and OS alarm registration remained unchanged, and Player displayed the restored countdown. The fake received zero mutations; probe cleanup cancelled the timer. This verifies process survival rather than just reconstructing a controller in the same process. Reboot detection is unit-tested; a physical reboot, real Doze/OEM delays and API 31/37 runtime behavior are not exercised here.

The R05 rerun used the existing process probe with two test-UID process kills. WAITING survived the first kill. Observing Playing after restart started the full duration. The second restart preserved the ACTIVE record's generation, target and deadline. The fixture received zero commands that changed MPD, and cleanup removed the test alarm.


## M6 Android media controls

Verified on 2026-11-22 with the existing Medium Phone AVD (`medium_phone`, API 36), dynamically identified as `emulator-5554`. The Gradle wrapper debug build, all 114 JVM tests and Lint passed (0 errors, 19 existing/deliberate warnings); whitespace checks passed. The full isolated `.devicetest` suite passed its 48 active fixtures; the optional live-LAN test and two opt-in process-probe phases were not enabled. Following the final toggle-routing addition, all 15 focused service tests passed again with the final build/JVM/Lint run. No real MPD endpoint was contacted.

New JVM cases verify explicit Play/Pause mapping/no-ops against fresh server status, authorization expiry while waiting for status, revoked receiver generations, unsupported mutations and cancellation before a server switch. Existing stale-response, command/readback and idle tests remain in place. Android service tests exercise real MediaController calls, actual notification PendingIntents/platform-session metadata/actions, Android media-key Play/Pause/Toggle/Next/Previous, fresh-status Toggle before delayed external idle, toggle during decoder focus suppression, all next/previous command variants, stopped Play, ACK rejection with confirmed state retained, continuous HTTP bytes during MPD Pause, external title/artist changes and filename fallback, M5 one-shot Pause reflected through idle, Off during a blocked status read, disconnected/old controllers and server switch. Read-only assertions remain on activation/deactivation, focus loss, errors, unauthorized starts and lifecycle tests. Existing screen-off/background, Activity recreation, HTTP deadline/retry and foreground/service/socket release tests pass.

Hardware Bluetooth/headset delivery, audible playback/latency and Samsung/OEM lock-screen presentation remain manual acceptance; emulator media-key dispatch is not an actual Bluetooth accessory test. API 31/33/37 runtime behavior is not covered by the API 36 suite. Long pauses, Android focus/foreground restrictions and OEM battery policies can end local reception; retry remains manual. A controller may temporarily mask its own requested state until the asynchronous operation finishes, while the session facade retains confirmed MPD state. The M5 alarm and its existing limitations are unchanged. See [manual acceptance](#manual-acceptance-checklist).


After automated verification, the normal debug APK was installed successfully with `adb -s <detected Samsung serial> install -r app/build/outputs/apk/debug/app-debug.apk` on the attached Samsung SM-A515F, Android 13 / API 33. Installation used no uninstall, data clearing, profile changes or resigning. Only package-installation checks were performed on that phone; the app was not launched, no automated tests ran there and no MPD transport/playback action was issued. The emulator started for this run was stopped after testing.


## Bounded connection recovery

Verified on 2026-11-22 using the Gradle wrapper and Medium Phone (`medium_phone`, API 36, dynamically identified as `emulator-5554`). Debug build, all 124 JVM tests and Lint passed (0 errors, 19 existing/deliberate warnings); `git diff --check` passed. The focused service run passed all 18 tests. The subsequent complete `.devicetest` Android suite passed 53 active tests; the optional live-LAN test and two opt-in process-probe phases were intentionally not enabled. Gradle/runner report success; the XML exporter counts those three assumption violations under failures rather than skipped, so use the named assumption results to distinguish them from executed regressions. No live MPD endpoint was contacted.

New virtual-time JVM cases verify exactly five exponential delays/exhaustion, manual budget reset, successive successful recoveries resetting to the first delay, forced status/song/queue reload with unchanged queue version, isolated idle repair retaining control and pending commands, idle exhaustion retaining manual control, escalation to one full-repair loop, offline suspension without consumed attempts, disconnect/switch cancellation, stale foreground failures, single-flight foreground health checks, permanent-error exclusion and no replay of a timed-out mutation. Receiver tests verify retained manual authorization during transient recovery, release after terminal control failure and no automatic local reactivation. Existing partial/uncertain queue-operation reporting remains covered.

Real-socket emulator tests close only the idle socket during Home/screen-off, verify a single replacement idle connection while control/HTTP/session generation remain unchanged, and verify subsequent external metadata updates. Another case closes control while the Activity is stopped/screen locked, resumes the Activity, and verifies complete recovery/metadata/queue while keeping the same HTTP request and session controller; recreation adds no sockets. Other cases check known-offline retry suspension, switching to another loopback target during a paused retry, explicit disconnect cancelling work, manual same-server Reconnect preserving HTTP, and an exhausted-idle warning with enabled Reconnect in Compose. Existing M4/M5/M6 media actions, timers, focus, error, navigation and lifecycle fixtures pass.

These fixtures reproduce the old teardown consequence, not the specific reported Samsung incident or a real OEM/Doze suspension. No production music, real MPD mutations or physical-device tests are part of this verification. Runtime coverage remains API 36; actual handset power-management timing, silent TCP blackholes and process termination remain the documented limits.


After this recovery verification, the normal debug APK was again installed successfully with `install -r` on the explicitly authorized attached Samsung SM-A515F / API 33, using its freshly verified ADB identity. Only package-installation metadata was checked; no app launch, physical-device test, data clearing, profile write or production MPD action was performed. The sole emulator started for this run was stopped afterward.


## M6 waiting timer and compact Player

Verified on 2026-11-22 with the Gradle wrapper: debug build, all 134 JVM tests and Lint passed (0 errors, 19 existing/deliberate warnings). The complete isolated `.devicetest` suite passed 60 active Android tests on Medium Phone (`medium_phone`, API 36, dynamically identified as `emulator-5554`); the default run left the optional live-LAN test and two host-driven process-probe phases disabled. `git diff --check` passed. Recovery repository/network code, manifest permissions and dependencies were unchanged; the existing recovery/media-session tests passed. No real MPD endpoint was contacted.

New JVM coverage exercises On while playing versus paused/stopped/unknown, waiting without deadline/alarm, external Play starting full duration exactly once, later Pause/Stop/Play preserving the deadline, waiting/running duration changes, Off, disconnected/stale/double events, live-state reads after a queued lock wait, current target/boot/profile safeguards, repeated foreground refresh, controller reload and actual disk reload of waiting records. Existing uncertain-delivery/registration, timer receiver and alarm tests remain. UI preference tests verify default-expanded, disk persistence, malformed-type recovery and unrelated writes without losing profiles.

Android tests exercise preset/Custom dropdown, invalid custom input and IME Done while Off, dialog Done application, external idle-driven waiting-to-countdown transition, pause/stop/restart without deadline reset, duration reset to waiting, no waiting PendingIntent, Activity recreation while waiting/running, persistent track-info recreation/status changes, independent listening-info popup, MPD-backed option actions, smaller collapsed title, status/timer alignment and narrow 300 dp / 2x-font layout. One clickable metadata row intentionally merges accessibility text; typography assertions inspect its unmerged child while action/state assertions use the accessible parent. Normal Player waiting/countdown screenshots were also visually inspected with fixture metadata.

The opt-in process probe was then explicitly exercised using a temporary host-loopback read-only MPD fixture and the suffixed app only. After normal Activity startup in WAITING, the background app process was killed under its own test UID (no Force Stop/data clearing); the fixture changed to Playing while no app process existed. Persisted state remained WAITING. A normal Activity restart created a new PID and started the full 15-minute countdown at observation. A second own-UID process kill/restart retained the exact active token/start/deadline and existing alarm registration. The fixture received zero MPD mutations; cleanup cancelled the alarm/deleted the process fixture. This separately validates process behavior, beyond controller reconstruction/rotation. It does not guarantee OEM/Doze timing or detection while dead. Reboot detection remains covered by JVM cases; no physical reboot/automated phone test was performed.


After successful addendum verification, the normal debug APK was installed with `install -r` on the freshly identified, authorized Samsung Galaxy A51 (SM-A515F, Android 13 / API 33). Installation succeeded; only installed-package metadata was checked. No app launch, physical-device automated tests, production MPD commands, uninstall, data clearing or profile replacement occurred. The Medium Phone emulator started for this run was stopped after all fixtures/process checks. See [manual acceptance](#manual-acceptance-checklist).


## Repeat modes

Verified on 2026-11-22 with the Gradle wrapper: debug build, all 145 JVM tests and Lint passed (0 errors, 19 existing/deliberate warnings). The complete isolated `.devicetest` suite passed 65 active Android tests on Medium Phone (`medium_phone`, API 36, identified as `emulator-5554`). Three optional live-LAN/process-probe phases stayed disabled; the XML exporter represents their assumption violations as failures, while the runner and Gradle report success. Whitespace checks passed. No dependency, permission or build-configuration changes were needed.

New JVM fixtures cover every Repeat transition with both Random states, independent Random changes in all three modes, fresh-status decisions without an idle update, no-op writes, external options/oneshot updates, missing/unexpected values, explicit normalization, first/second-command ACK rejection, partial enable/disable readback, lost acknowledgements, read-only recovery without replay, serialization against idle/browser/health/heartbeat work, and cancellation during a server switch. All mutations target in-memory fixtures or the existing loopback test servers, never a real MPD endpoint.

New Compose fixtures verify all six combinations, three dropdown choices and selected state, confirmed display without optimistic changes, external/oneshot/stop-after-track/unknown display, disabled/menu-dismissal behavior, System/Light/Dark themes, compact alignment and 300 dp / 2x-font wrapping. A text-layout assertion found clipping in the initial dropdown implementation; intrinsic preferred width plus constrained text wrapping fixed it, and the final focused and complete suites passed. Existing player/navigation/browser/media/timer/recovery fixtures remain successful. Runtime coverage is API 36; physical-device rendering and other Android API levels were not exercised.

Final ADB discovery showed only the emulator. The Samsung Galaxy A51 was not connected/authorized through ADB, so normal debug APK deployment was skipped as requested. No physical-device tests, app starts, MPD actions, profile changes, uninstall or data clearing were performed. The normal APK is available at `app/build/outputs/apk/debug/app-debug.apk` for a later data-preserving `adb -s <verified Samsung serial> install -r` deployment. The emulator started for this verification was stopped afterward.

## M7.2 localization and licensing

Verified on 2026-11-22 with the project Gradle wrapper: `assembleDebug`,
`testDeviceTestUnitTest`, `assembleDeviceTest`, `assembleDeviceTestAndroidTest`
and `lintDebug` pass. All **148 JVM tests** pass. Lint reports **0 errors and
20 warnings**: 19 existing/deliberate warnings plus the expected API-33-only
`localeConfig` attribute on the retained minimum API 31. No translation,
format-string or plural warnings remain. `git diff --check` passes.

The existing Medium Phone AVD was started for this run and its actual system
reported **API 36**. Android tests were run with an explicitly selected emulator
serial through the AndroidJUnitRunner after wrapper-built `.devicetest` APKs;
this kept the connected physical phone outside test discovery. The complete
suite passed **72 active tests**, with four expected opt-in assumptions (live LAN,
two timer process-probe phases and the locale process probe). After the final
notification/provider changes, all **24 focused service/localization tests**
passed. After the final identical-system-locale selection handling, the five
localization UI tests and the audio/timer locale integration test passed again.
No real MPD endpoint was used.

The explicit locale process probe was then enabled in two separate instrumentation
processes: seed German plus a non-selected loopback profile/theme, stop only the
suffixed test app, and verify restoration in a new process. Both phases passed;
normal profiles were never cleared. Selection of all seven languages, returning
to System, Android 13+ external language changes (including an identical effective
system locale), unsupported-language English fallback, Russian/Ukrainian integer
plurals, build version, offline GPL/notices and retained stream/socket/session/
profile/theme/timer generation/deadline are covered. The media fixture asserts
zero MPD mutations while languages change, plus updated subtitle/channel name.

The translated layout fixture passed again with the emulator's actual Android
font scale set to **2.0**, then restored to **1.0**. Screenshots of German, French,
Russian and Ukrainian Player/listening, Language, timer and About were captured;
representative screenshots were visually inspected. Scrollable long dialogs and
large-font wrapping work; existing intentional Player ellipses remain. Metadata
stays literal. The first fixture run exposed state reuse between synthetic
screens; keying those fixture screens and supplying their resource context fixed
the fixture, and subsequent normal/large-font runs pass.

AAPT2 inspection confirms unqualified English plus exactly de/fr/it/es/uk/ru in
the APK. GPL/notices extracted from the APK match their repository sources byte
for byte. Debug runtime has 111 artifacts; all 108 release runtime artifacts are
included in that audited set. GPL source SHA-256:
`3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986`.

Runtime locale coverage is API 36. Android 12/12L auto-storage/migration and
Android 13 specifically were not exercised on those OS versions; they use the
official AndroidX APIs and were built/linted. Actual device-language changes,
TalkBack speech, Samsung notification/lock-screen rendering, Bluetooth/audio and
translation review by native speakers remain manual acceptance. The supplied
wording is preserved; automated completeness does not establish linguistic
quality. Localized deadlines use Android formatters, but no separate 12/24-hour
settings matrix was run. Before public binary distribution, publish matching
corresponding source and confirm artwork/translation provenance as described in
[licensing](LICENSING.md); this run makes no commits or uploads.

The authorized Samsung Galaxy A51 was freshly discovered as **SM-A515F / API 33**.
The normal debug APK installed successfully with `adb -s <detected serial>
install -r app/build/outputs/apk/debug/app-debug.apk`; `pm path` confirmed the
normal package remained installed and its first-install timestamp was unchanged.
Only installation/package metadata was accessed: no Activity launch, instrumented
test, uninstall, data clearing, system-language/settings change or MPD command was
performed on the Samsung. The single emulator started for this run was stopped
after testing. No commits, tags or pushes were created.

## Server-name validation and Repeat styling verification

Verified on 2026-11-23 with the wrapper: `assembleDebug`,
`testDeviceTestUnitTest`, `assembleDeviceTest`, `assembleDeviceTestAndroidTest`
and `lintDebug` pass. All **155 JVM tests** pass; Lint reports **0 errors and
20 warnings**, matching the previous count. All seven catalogs and the English
CSV remain consistent. `git diff --check` passes.

On Medium Phone (`medium_phone`, API 36), all **74 active Android tests** passed
across separate instrumentation runs; four optional live-LAN/process probes
remained disabled. The initial whole-suite run completed 48 active tests before
hanging in the existing external app-language/lifecycle test. Only the suffixed
test app was stopped; that same test passed in a fresh process, and all 25
remaining tests passed in another run. This is complete test coverage across
runs, not a successful uninterrupted whole-suite run.

New JVM coverage checks duplicate/case/whitespace names, stable-ID exclusion,
conflicting edits, unchanged storage/selection on rejection, legacy duplicates
across disk reload and unrelated writes, explicit renaming, and a competing
write before Save. Compose coverage checks Add/Edit errors and disabled Save,
legacy duplicate repair, matching closed Repeat/Random size, weight, color and
text baseline in all themes and when disabled. Existing repeat selections,
confirmed-state behavior and 300 dp / 2x-font wrapping pass. A German large-font
Player screenshot was also visually inspected. MPD option commands are unchanged.

No real MPD endpoint or physical device was accessed. Tests installed only the
isolated `.devicetest` APKs; the normal app and its profiles were untouched.
Runtime coverage remains API 36; other API levels and physical-device rendering
were not exercised. No dependency, permission, build configuration, commit, tag
or push is part of these corrections.

## Unlock/window-focus recovery verification

Verified on 2026-11-23: wrapper `assembleDebug`, `testDeviceTestUnitTest`,
`assembleDeviceTest`, `assembleDeviceTestAndroidTest` and `lintDebug` pass.
All **160 JVM tests** pass; Lint reports **0 errors and 20 warnings**.
`git diff --check` passes. Earlier server-name/Repeat changes remain intact;
dependencies, permissions, build configuration and timer implementation are unchanged.

The isolated `.devicetest` Android suite covered all 81 discovered cases on
Medium Phone (`medium_phone`, API 36): **77 active tests passed**, and four
optional live-LAN/process probes were skipped. The whole-suite run completed
51 active tests before hanging at the previously documented external app-language
test. Only the suffixed test app was stopped; the same test and all remaining
cases passed in a fresh 26-test instrumentation run. This verifies the full
set across two runs, not an uninterrupted whole-suite pass.

New JVM regressions cover resume/focus bursts with one readback and unchanged
healthy sockets, delayed settings loading before adopting/checking a shared
session, a focus-detected command failure using the existing backoff, pending
check cancellation and server switching during recovery, and a deliberately
late inactive-server failure producing neither UI errors nor notices. The
existing five-attempt test now includes focus after exhaustion and verifies
sanitized diagnostic output without resetting the budget.

Three new loopback Android cases verify a real Dialog focus loss/gain without
ON_RESUME, both focus/resume callback orders, burst coalescing, actual sleep/wake
and keyguard dismissal without recreating the Activity, command-socket recovery
with refreshed metadata/queue/idle, no connections to an inactive saved profile,
and switching during recovery without more old-server attempts. Running timer
records, including their original target/generation/deadline, remain identical.
The lock fixture waits for actual lifecycle/focus loss before waking rather
than assuming a fixed shell-command delay. All new cases assert read-only MPD
traffic; the existing isolated idle repair/media/timer tests also pass.

No production MPD endpoint or physical-device test was used. ADB discovery at
completion found only the emulator, so Samsung deployment was skipped. Normal
app data/profiles were untouched; the debug APK is available under
`app/build/outputs/apk/debug/app-debug.apk`. No commit, tag or push was created.
The Samsung Android 13 overnight incident, genuine Doze/Wi-Fi power loss, silent
idle TCP blackholes and OEM timing are not reproduced by these API 36 fixtures.
Exhausted retry budgets still require manual Reconnect; focus is not a new
automatic retry allowance. The new debug-only diagnostics support distinguishing
those states during a subsequent manual handset observation.

## Centered playback-options verification

Verified on 2026-11-23: debug build, all 11 focused `MpdRepeatTest` JVM cases
and Lint pass (0 errors, 20 warnings). All nine `RepeatOptionsUiTest` and
`CompactPlayerUiTest` Compose cases pass on the explicitly authorized Samsung
Galaxy A51 / Android 13 (API 33), using the isolated `.devicetest` app only.
Geometry assertions check the combined group's center at normal width and each
wrapped group's center at 300 dp / 2x font size. Existing typography/color,
non-clipping, selection and captured-action assertions remain successful.
The fixtures use supplied state and captured callbacks, without MPD connections
or the normal app's profiles. No MPD/state-management code changed for this
alignment correction. `git diff --check` passes.

After testing, the temporary suffixed test APKs were removed and the normal
debug APK was installed on that Samsung with the explicitly authorized
`adb install -r`. Installation succeeded with its original first-install time
unchanged. The normal app was not launched and no production MPD was contacted.

## Player timer-status shortcut verification

Verified on 2026-11-23: debug build, all **160 JVM tests** and Lint pass
(0 errors, 20 warnings). All **25 targeted Android tests** pass on Medium Phone
(`medium_phone`, API 36), using only the isolated `.devicetest` app. This covers
CompactPlayerUiTest, PlayerNavigationTest, RepeatOptionsUiTest, SleepTimerUiTest
and LocalizedLayoutTest. Production MPD endpoints were not accessed.

New checks verify matching status typography/alignment, at least 48 dp of timer
touch height, an actual corner tap outside the text, button/name/current-state
semantics, and access with disabled playback controls. Off, waiting, changing
remaining minutes, due and failed states retain their existing display behavior
and open action at 300 dp / 2x font size without text overflow or overlap.
Navigation fixtures compare the Player shortcut and Settings entry for off,
waiting, running and failed timers, retaining state and sending no timer/MPD
events merely to open/close the preset-duration dialog. Existing centered
Random/Repeat layout and actions remain covered. A German large-font screenshot
was visually inspected; the translated large-font fixture also covers French,
Russian and Ukrainian. Timer scheduling/storage, recovery and media-session
implementation are unchanged.

After verification, fresh ADB discovery identified the authorized Samsung
SM-A515F as Android 13 / API 33. The normal debug APK installed successfully
with `adb install -r`; package presence and an unchanged first-install timestamp
were checked. No physical-device automated test, app launch, profile read/write,
uninstall, data clearing or MPD command was performed in this run. Samsung
rendering and actual TalkBack speech remain manual acceptance. No dependency,
commit, tag or push was added. `git diff --check` passes.

After subsequent explicit authorization to test and deploy the no-indication
correction on the Samsung, all 17 CompactPlayerUiTest/PlayerNavigationTest cases
passed on SM-A515F / API 33 using only the isolated `.devicetest` app. The visual
regression compares pixels before, during and after a held press, including the
scroll container's delayed press interaction; the timer area remains identical
and the click still fires. The normal debug APK installed with `install -r`;
its installed SHA-256 matches the current build and its first-install timestamp
is unchanged. Temporary test APKs were removed afterward. Normal profiles were
not read or modified, the normal app was not launched, and these controlled UI
fixtures opened no MPD connections.

## Review packages A–C

| Package | Date | Scope and recorded Android evidence |
| --- | --- | --- |
| A | 2026-11-23 | Bounded queues and session-bound queue actions. A 53-case targeted run passed 52 and failed one focus-recovery connection-count assertion. Subsequent targeted runs passed 32 navigation/queue and related cases and 22 media cases. |
| B | 2026-11-23 | Recovery and confirmed playback freshness. The first 27-case run passed 26 with a timeout in the MediaSession transport fixture. The final targeted run passed all 28 cases. A separate 14-case run also passed. |
| C | 2026-11-24 | Preserve settings after read errors and simplify the Player UI. Separate fixture runs passed 12 protocol/recovery cases and 59 UI/timer cases. The recorded Gradle checks succeeded. |

These filtered runs do not establish uninterrupted full discovery.

For the reported overnight Samsung incident, missing focus-only checks are provable from the prior Activity implementation. It had only an ON_RESUME hook; a resumed Activity regaining focus without another resume did not request a readback. Its single-flight repository check merged only overlapping IO, not short callback bursts after fast IO. Repository session/generation guards already reject old-server results and cancel old recovery; there is no background loop over stored profiles. A command failure and an independent timer failure for its originally bound target are separate states, not evidence of two interactive servers. An exhausted background retry budget can also explain why process restart helps, but no handset trace establishes which occurred. Doze/Wi-Fi interruption and the initiating socket remain unconfirmed.

## Package D DNS, alarm delivery and G03

### DNS and pre-claim alarm findings

Review R04 reproduced a non-cooperative lookup with a bounded worker gate. The baseline caller was still waiting at 500 ms with a 250 ms client timeout, and again at 500 ms after explicit cancellation. Releasing the gate let it return the existing timeout or cancellation result. The fixed fixture measured 253 ms for timeout and 1 ms for cancellation while the worker was still held. Further tests cover executor saturation, probe timeout classification, and replacement control/idle sessions while an old lookup is held. The old listener receives no TCP connection after release. Android protocol tests used the platform resolver for localhost, IPv4 and IPv6 loopback, and checked numeric IPv6 scope parsing. Profile tests cover IDN normalization. A native Android DNS stall and LAN name service availability remain outside the measured result.

The [M5 alarm section](#m5-sleep-timer-and-alarm-checks) records the baseline
and fixed AlarmManager results, including the stalled claim, durable failure
and zero-MPD-command outcome. The timer process checks cover restart while
WAITING and ACTIVE with fixture cleanup.

### Localization hang and complete regression

Review G03 reproduced a 70 s host timeout in the isolated API 33+ external-language test on Medium Phone API 36. The old test entered Compose waiting immediately after clearing the French override. The captured Java stack showed the runner blocked in `Espresso.onIdle` and the main thread polling `ComposeIdlingResource.isIdleNow` for measure/layout while MainActivity was resumed. A separate baseline run with the locale/audio lifecycle fixture and default-language check passed all three cases. The first readiness check passed the isolated test in 3.6 s. The combined discovery run then recorded a bounded 10 s locale/window readiness failure at the same step, with 95 passed and four opt-in skips out of 100 discovered. The final test waits for the resumed Activity and laid-out window before Compose idling, reads the system language from `LocaleManager.systemLocales` on API 33+, and reports the observed window state on timeout. The final combined targeted run passed 23 tests, including all five localization UI cases and the media/timer locale fixture. The separate locale process probe passed seed, process stop and verification with fixture cleanup. At that point a successful complete discovery run was still pending. AppCompat locale handling and process-owned sessions use the same production paths.

On **2026-11-25**, two uninterrupted runs of the final Package D Android suite
on Medium Phone API 36 each passed **96** active cases with **0 failures and
4 opt-in skips**. G03 passed in 2.93 s and 2.96 s. The debug build and all
191 JVM tests passed. Lint reported 0 errors and 20 existing warnings.
No physical device or production MPD was used. The emulator started for the
run was stopped afterward. The [Package E run](#package-e-implementation-verification-and-release-limits)
later passed G03 again.

## Manual acceptance checklist

These are proposed checks, separate from the evidence above. Use a test MPD
instance with passwordless and protected profiles, and exercise both read-only
and player/queue permissions.

- **Server and queue.** Check Test with correct and wrong credentials, Save
  and reconnect, idle updates from another MPD client, endpoint changes
  requiring re-entry, and the difference between denied read and write
  actions. Check seek, scrolling, replacement without Play, Unicode and
  escaped filenames, inaccessible directories and switches during a read.
- **Listening and system controls.** Verify actual audible output and
  stream latency, screen-off/background playback, notification tap, metadata
  updates and Play/Pause, Previous and Next from Android and compatible
  headset controls. Confirm that MPD changes are reflected from another
  client, including Pause while HTTP still sends data. Check phone volume,
  focus loss, wired/Bluetooth switching, Off, server switch and same-server
  Reconnect. Verify local retry after a stream error.
- **Sleep timer.** With remote playback paused or stopped, enable the timer
  and confirm Waiting with no alarm. Start playback externally and check
  the full countdown. Future pause, stop and resume leave its deadline in
  place. Change duration while playing and while paused. Check Off, original
  target after server switch, and one acknowledged Pause at expiry preserving
  position and queue. Repeat with listening Off, secure screen lock and OEM
  power saving. Check process death while waiting, recreation while waiting
  or active, and that a missed playback episode does not become a countdown.
- **Layout and language.** Try 1× and large fonts with long metadata,
  Random/Repeat and the timer shortcut. Check custom timer Done via keyboard
  and dialog, boundary rejection for 0/1441, and that applying a duration
  while Off does not arm a timer. Check the listening-info dialog and
  retained collapsed track details after a track/profile change or restart.
  Try German, French, Russian and Ukrainian, including settings and
  accessibility. Test explicit language persistence, return to System,
  actual device-language changes, fallback and Android 13+ external app
  language changes. Retain session, stream, timer and theme through the
  change. Check the version, offline legal texts and source link in About.
- **Restore and platform behavior.** Perform a real eligible Android
  cloud restore and direct transfer to confirm ordinary settings, password
  re-entry and absence of old timers/reception. Check API 31 and API 37
  runtime paths, OEM lock-screen rendering and TalkBack speech on hardware.
