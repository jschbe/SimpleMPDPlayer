# Development and testing

See [repository rules](../AGENTS.md), [architecture](ARCHITECTURE.md) and
[verification history](VERIFICATION.md). Run the commands below from the
repository root.

## Build and install locally

- Install Android SDK Platform 37 and Android SDK Platform-Tools. Android
  Studio is optional for command-line builds. Configure the SDK with
  `ANDROID_HOME` or a local, untracked `local.properties`.
- Use the checked-in Gradle wrapper and a compatible launcher JDK. JDK 25 can
  run the build directly. The daemon criteria also request JDK 25 and support
  provisioning through Foojay. Java source and target compatibility are 11.
- For device tests, install the Android Emulator and create an AVD. API 36
  Medium Phone was the reference AVD for recent suites. The app's runtime
  minimum is API 31.
- The wrapper pins Gradle 9.6.0 with a distribution checksum. AGP, Kotlin,
  Compose, Media3 and other dependency versions are managed in the
  [version catalog](../gradle/libs.versions.toml). Keep stable releases and
  [review resolved licenses](LICENSING.md#packaging-and-upkeep) when changing
  runtime dependencies.

```sh
./gradlew :app:assembleDebug :app:testDeviceTestUnitTest :app:lintDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. For an intentional
local install, select the intended Android target and run
`adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk`.
This preserves data when Android accepts the update. Keep instrumented tests
on the separate test variant below.

## Instrumented tests

Use the `deviceTest` build type and its app ID suffix `.devicetest`. Default
fixtures use synthetic data, in-memory doubles and loopback sockets. Keep
real profiles and passwords out of test arguments, files and logs. Never
clear or uninstall the normal app for tests. Reuse a suitable running
emulator, or start one and stop that instance after testing. Get authorization
before using physical hardware.

On a host with only the intended emulator connected:

```sh
./gradlew :app:connectedDeviceTestAndroidTest
```

The connected Gradle task can discover multiple targets. For an explicit
target, build both APKs, use `adb devices -l` to identify the emulator and set
`serial` to its observed serial. Confirm its name/API, then install and invoke
the runner directly:

```sh
./gradlew :app:assembleDeviceTest :app:assembleDeviceTestAndroidTest
adb -s "$serial" emu avd name
adb -s "$serial" shell getprop ro.build.version.sdk
adb -s "$serial" install -r app/build/outputs/apk/deviceTest/app-deviceTest.apk
adb -s "$serial" install -r app/build/outputs/apk/androidTest/deviceTest/app-deviceTest-androidTest.apk

app=com.github.jschbe.simplempdplayer.devicetest
base=com.github.jschbe.simplempdplayer
runner="$app.test/androidx.test.runner.AndroidJUnitRunner"

adb -s "$serial" shell am instrument -w -r "$runner"
adb -s "$serial" shell am instrument -w -r \
  -e class "$base.ui.LocalizationUiTest" "$runner"
```

Use an optional `-e class` with a fully qualified class, or add `#method` for
a single case. The JVM task for this configured test build type is
`testDeviceTestUnitTest`. Report real failures and named assumption skips
separately. Some Android test exporters represent skipped assumptions as
failures even when the runner succeeds. Check the runner and individual
results. A set of targeted invocations does not establish a full-suite pass.

## Opt-in integration and process probes

Run opt-in cases only with the matching fixture and a disposable
`.devicetest` app state. The default suite skips these cases. Test preparations
can change the test app's profiles, theme, locale or timer duration.

### Read-only live HTTP audio

[`LiveStreamReadOnlyTest`](../app/src/androidTest/java/com/github/jschbe/simplempdplayer/audio/LiveStreamReadOnlyTest.kt)
requires a separately authorized MPD test endpoint with HTTP audio. Use the
emulator. The test reads HTTP, uses read/idle MPD commands and prepares the
decoder, without persisting the endpoint or issuing transport commands.
It accepts `readOnlyStreamHost` (required), `readOnlyMpdPort` (default 6600)
and `readOnlyStreamPort` (default 8000). It does not supply MPD credentials.
With `fixture_host` set to the authorized test hostname:

```sh
adb -s "$serial" shell am instrument -w -r \
  -e class "$base.audio.LiveStreamReadOnlyTest" \
  -e readOnlyStreamHost "$fixture_host" \
  -e readOnlyMpdPort 6600 -e readOnlyStreamPort 8000 "$runner"
```

HTTP bytes and decoder readiness alone do not establish audible playback.

### Locale and Keystore process persistence

Use fresh disposable test app data for the locale probe so no profile is
selected. Set the class and argument for each probe as shown below, and
run the seed and verify phases against the same test installation:

| Probe | `probe_class` | `probe_arg` |
| --- | --- | --- |
| German locale, non-selected profile and theme | `$base.ui.LocalePersistenceProbeTest` | `localeProbe` |
| Keystore credential persistence | `$base.data.CredentialProcessProbeTest` | `credentialProcessProbe` |

```sh
adb -s "$serial" shell am instrument -w -r \
  -e class "$probe_class" -e "$probe_arg" seed "$runner"
# Proceed after a passing seed. Stop only the isolated test app.
adb -s "$serial" shell am force-stop "$app"
adb -s "$serial" shell am instrument -w -r \
  -e class "$probe_class" -e "$probe_arg" verify "$runner"
```

The verify phase removes the probe's profile and restores its fixture settings
as applicable. Check the emitted `credentialProbePid` values are different for
the Keystore probe. After an interrupted probe, use fresh test-only state
before rerunning.

### Sleep-timer process death

[`SleepTimerProcessProbe`](../app/src/androidTest/java/com/github/jschbe/simplempdplayer/timer/SleepTimerProcessProbe.kt)
takes `timerProcessProbe=prepare` and `fixturePort`. Use a controllable MPD
fake bound to the emulator host's loopback interface, reached through
`10.0.2.2`. The fake must offer a valid greeting, read/idle support and the
ability to change its reported state externally. Start it in Paused or Stopped
and set `fixture_port` to its listening port:

```sh
adb -s "$serial" shell am instrument -w -r \
  -e class "$base.timer.SleepTimerProcessProbe#prepare" \
  -e timerProcessProbe prepare -e fixturePort "$fixture_port" "$runner"
adb -s "$serial" shell am start -n "$app/$base.MainActivity"
```

Check Waiting and the absence of an alarm. Obtain the isolated application's
PID with `adb -s "$serial" shell pidof "$app"`. Finish inspection, send a
normal process termination signal as the test UID with
`adb -s "$serial" shell run-as "$app" kill -TERM "$pid"`, and confirm the
process exited. Use a regular activity launch to restart it. Avoid Force Stop
because it changes alarm behavior.

Switch the fake to Playing while the process is absent, then reopen the app
and verify a new PID and a full countdown starting at observation. If
checking an already active alarm, inspect its generation/deadline and the
OS alarm registration before and after a second process restart. The fake
should receive reads only. Perform cleanup even after a failed inspection,
then stop the fixture:

```sh
adb -s "$serial" shell am instrument -w -r \
  -e class "$base.timer.SleepTimerProcessProbe#cleanup" \
  -e timerProcessProbe cleanup "$runner"
```

### Cold alarm delivery and credential cleanup

[`CredentialCleanupAlarmProbe`](../app/src/androidTest/java/com/github/jschbe/simplempdplayer/timer/CredentialCleanupAlarmProbe.kt)
uses an emulator-host loopback fake and fresh `.devicetest` data. Run `arm`
and `verify` with the same `fixturePort` and `fixtureProtected` arguments:

```sh
adb -s "$serial" shell am instrument -w -r \
  -e class "$base.timer.CredentialCleanupAlarmProbe" \
  -e cleanupAlarmProbe "$phase" -e fixturePort "$fixture_port" \
  -e fixtureProtected "$protected" "$runner"
```

After `arm`, check that its reported process has exited before the alarm
falls due. If it is still running, terminate it as the test UID without
Force Stop. The fixture must observe exactly one Pause on the alarm-started
connection before `verify` launches the Activity. Run separately with
`fixtureProtected=false` and `true`; the protected fixture must require the
synthetic password from the probe before Pause. The fixture reports Stopped
to interactive reads. The verify phase checks the persisted result and that
the orphan survives alarm delivery and interactive Waiting, then checks that a
later interactive start prunes it and leaves any referenced credential usable.

## Diagnostics

In a debug build, `adb -s "$serial" logcat -s MpdConnection` shows bounded
connection/recovery diagnostics. The event category and selection generation
help separate setup, control and idle failure. The logs exclude endpoints,
credentials, raw server replies and exception details. Network and Android
power-management causes require independent evidence.

For a language change that hangs during a fixture, capture the test runner
output and Activity/window state before touching the test process. The G03
history describes the bounded readiness check and the Compose/Espresso wait
it addresses. Preserve strict assertions and report interrupted runs
separately. [G03 history](VERIFICATION.md#package-d-dns-alarm-delivery-and-g03)
