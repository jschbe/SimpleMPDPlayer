# Architecture

This document describes the current implementation. See
[requirements](REQUIREMENTS.md) for product behavior, [development](DEVELOPMENT.md)
for tooling, and [verification](VERIFICATION.md) for historical evidence.

## State ownership

One Kotlin Android module, `:app`, uses Compose Material 3, StateFlow,
coroutines and Preferences DataStore. The application ID and namespace are
`com.github.jschbe.simplempdplayer`.

| Owner | Responsibility |
| --- | --- |
| `MainActivity`, `ServersApp`, screen composables | Lifecycle-aware rendering, navigation, saveable dialog state and transient input. Compose does not own sockets or persistent stores. |
| `ServersViewModel` | Combine settings and confirmed runtime state, coordinate editor writes, selection, connection checks and user actions. |
| `PlayerApplication` | Construct process-owned repository, audio and timer components. A recreated Activity adopts a healthy session. |
| `MpdRepository` / `MpdClient` | One selected interactive server, command/event sockets, confirmed snapshots and cancellable session work. Network IO runs off the main thread. |
| `LocalStreamController` / `LocalStreamService` | Process-local listening authorization and one Media3 player/session while authorized. |
| `SleepTimerController` / `SleepTimerReceiver` | One persisted timer and independent alarm delivery. A receiver-only process can deliver without initializing the interactive repository, Activity or audio service. |

Compose retains a shared horizontal pager for Browser, Player and Queue.
The Player timer shortcut and Settings share a saveable dialog flag. The
shortcut provides an accessible 48 dp minimum hit target with no visual
press indication. Random and Repeat use matching typography and move to
separate centered rows when space is limited. Track-information expansion is persisted.
Seek and volume drag state is local to the view and submits on release.
Appearance wraps the Compose hierarchy and updates system-bar icon contrast
without changing insets. Launcher resources derive an adaptive foreground and
full-bleed gradient from the supplied image using approximate layer separation.
Android applies the mask. An API 33+ monochrome layer and five legacy WebP
densities cover alternate launchers.

## Profiles and persistence

`ServerSettingsStore` uses one application-context DataStore. The
`server_profiles_v1` value is an ordered set of tab-separated rows. Each row
contains UUID, name, host, MPD port and HTTP port, with an optional
`password-v1:<revision>` field. Five-field rows remain readable. Host validation
does no DNS lookup, converts international hostnames to ASCII and normalizes
bracketed IPv6. The store also holds selection, theme, track-information
expansion, corruption marker and `preferred_sleep_minutes_v1`.

Decode discards malformed rows, duplicate IDs, invalid types and dangling
selection while retaining valid data. File corruption recovers to empty
settings with a warning. An ordinary IO read failure retains the last
confirmed settings and session and disables writes until Retry succeeds.
The editor and store share case-insensitive trimmed name validation. The
store repeats the check under the mutation lock, excluding the edited ID.
Legacy duplicate names survive unrelated writes. A stale reorder cannot
restore deleted profiles or drop new ones.

Save commits selection with the profile before a single explicit connection.
Observer-driven connection changes are deferred during writes. Setup opens
on a confirmed empty-list transition. Test closes its independent socket on
completion, rejection or cancellation. Editor and operation tokens govern
late probe results and non-replayed notices. Password drafts are transient
and their visual and editable semantics are masked.

## TCP protocol, synchronization and state

`MpdClient` validates the UTF-8 greeting, quotes arguments and preserves
ordered, repeated response fields. An Android-compatible ACK parser retains
sanitized categories. The client mutex serializes write/flush/read through
`OK` or `ACK`. The 10-second exchange deadline starts after acquiring that
mutex. Setup covers lookup, TCP and greeting with separate five-second
connect/read timeouts. The event socket observes player, playlist, mixer
and options without the normal read/exchange timeout. Closing its socket
unblocks cancellation. TCP keepalive and a 15-second command ping provide
liveness checks. [MPD protocol](https://mpd.readthedocs.io/en/stable/protocol.html)

Interactive hostname resolution uses `InetAddress` and the first address
through at most two blocking workers with no queue. Timeout or cancellation
releases the caller even when native lookup stalls. Numeric endpoints bypass
the worker pool. Saturation is classified as timeout. Timer DNS uses Android's
cancellable `DnsResolver` for hostnames and bypasses DNS for numeric addresses.

The repository operations mutex serializes app commands, browser, heartbeat
and both sockets' refreshes. Setup authenticates, pings and reads control
state, then subscribes on authenticated idle and revalidates. Session and
request guards reject stale commands, replies, errors and notices. A
`status`/`currentsong`/status check can confirm playback while leaving changing
metadata temporarily unavailable. Repeat updates read current options and
write only differing or unknown fields, in repeat/single order. Random is
independent. An ACK stops a command sequence and ordinary readback establishes
actual state. Lost exchanges never replay mutations.

### Queue and browser limits

- A text response may contain up to 65,536 UTF-16 code units per line and
  1,048,576 code units or 10,000 fields per exchange. Overflow closes the
  socket before reuse, using the bounded recovery budget.
- Queue ranges contain 200 entries (`playlistinfo START:END`, exclusive end).
  Each is checked against status version and length. IDs must be unique and
  positions consecutive. Duplicate paths remain distinct. The aggregate budget
  is 20,000 entries, 300,000 fields and 8 Mi UTF-16 code units. Up to three
  consistency attempts share an eight-second budget checked between exchanges.
  An exchange already started keeps its own deadline. Repeated instability
  adds a one-second cooldown. Confirmed matching snapshots can be reused.
- Range overflow suspends nonempty range reads on the same profile until
  explicit Queue Retry, confirmed empty status or an acknowledged clear.
  Oversized current-song metadata is suppressed until the song changes.
  Playback confirmation and Clear remain distinct from metadata availability.
  Clear of a displayed queue checks its version. Move checks ordering and
  version/length against fresh status. Open menus and confirmations belong
  to the live control-session token and are excluded from saved state.
- Browser keeps one `lsinfo` listing. Request numbers reject superseded replies.
  Up, Android Back and Browser's right swipe share the generation-bound parent
  request and can queue it behind a pending exchange without closing a healthy
  socket. The swipe claims a clearly rightward drag at touch slop on the settled
  Browser page and requests the parent on release. Left swipes stay with the
  pager and vertical drags stay with the list. A directory ACK stays
  browser-local. Overflow preserves the requested path through control
  recovery and requires explicit Retry or Up.
- Replace serializes clear/add/readback within the app. Other MPD clients can
  interleave and the protocol has no rollback. An ACK, lost acknowledgement or
  readback failure is reported according to which steps completed. The server
  handles recursive add, which may itself add only part of a folder.

## Recovery and playback freshness

Control failure closes both sockets. Isolated idle failure closes/replaces
only idle and keeps healthy control and permitted pending work. Recovery
revalidates current-song/queue data and idle subscriptions. The single retry
budget uses five delays of 1, 2, 4, 8 and 16 seconds, pausing during known
offline periods. Authentication, permission and malformed-protocol failures
do not automatically retry. Response limits can use control recovery to
replace the closed socket. They do not rearm an exhausted cycle on return.
Manual Reconnect starts a fresh cycle. A completed restoration resets the
budget. A passive network callback accepts LAN-only networks without requesting
a network, binding traffic or requiring internet validation.

A qualified return consumes evidence of a non-configuration Activity stop or
observed screen-off, followed by a resumed, focused, interactive and unlocked
Activity. Short resume/focus bursts merge within 300 ms. Screen-on/user-present
can qualify when the Activity retained focus. An ordinary focus change can
check usable control. It cannot rearm an exhausted cycle. Network return
resumes an already authorized cycle. Selection/session guards prevent a
late check from originating work for a previous server.

A playback confirmation epoch invalidates status on network/control/idle loss
or replacement, player events awaiting readback and foreground reconciliation.
Old in-flight reads cannot satisfy the new epoch. A fresh control read can
confirm playback for five seconds when idle is unavailable. Queue completeness
is independent. Continuous local interpolation requires confirmed Playing
with synchronized idle. A silent idle failure can appear healthy until a later
observation. Foreground reconciliation invalidates confirmation before allowing
a waiting timer to start.

Debug `MpdConnection` events expose trigger, generation, failure category,
connection source and retry state. Logs omit IDs, host/port, credentials,
payloads and raw exception text. They cannot by themselves establish the cause
of an OEM or network suspension. See [development](DEVELOPMENT.md#diagnostics).

## Local audio and media session

The process-owned `LocalStreamController` keeps explicit listening intent
and an increasing generation in memory. The service Intent carries the
generation and the trusted request supplies the root HTTP URL from the selected
profile. The stream URL, headers and metadata exclude the MPD password.
Off and explicit server changes revoke the generation and release
HTTP, decoder and session. Same-server control recovery and manual Reconnect
can keep them while confirmed metadata or controls are unavailable. Terminal
control failure releases them. An idle-only failure can retain healthy
control and audio.

`LocalStreamService` uses one ExoPlayer and MediaSession. Media3 owns the
foreground notification, audio focus, becoming-noisy handling and network wake
mode. HTTP uses eight-second connect/read deadlines, no cross-protocol
redirects, zero automatic load-error retries and a 20-second buffering
watchdog. Errors are sanitized and Media3 source logging is disabled. Local
output follows confirmed MPD Playing and reopens the origin on resume to
discard buffered silence. Permanent focus loss or a noisy-route event stops
local audio. Transient suppression can resume via ExoPlayer. No audio callback
mutates MPD. The service is non-sticky. Active playback can survive task
removal. Paused playback can be reclaimed and background restrictions can
block resume. [Media3 background playback](https://developer.android.com/media/media3/session/background-playback)

`MpdSessionPlayer` uses `SimpleBasePlayer` to publish one descriptive current
MPD item with its own confirmed state. The item has no stream URI, copied queue
or advertised seek position/duration. `MpdMediaTransport` checks the stream
generation and live repository session before queued commands and again
after fresh status when necessary. Play sends `pause 0` or `play` from
paused/stopped. Pause sends `pause 1` while Playing. Next/Previous map the
Media3 navigation commands, and media-key toggle reads fresh MPD status.
New key-down presses act once. Futures wait for repository completion,
preserving confirmed state on failure. Unavailable/revoked controls cannot
send commands. Android versions and accessories determine presentation and
hardware delivery. [Media3 custom Player](https://developer.android.com/media/media3/session/player)

## Sleep timer

`SleepTimerStore` keeps a validated generation, bound profile/endpoint
snapshot, boot count, elapsed start/deadline and sanitized status/error in
`sleep_timer.preferences_pb`. Legacy numeric deadline records remain readable.
WAITING uses an empty deadline and has no alarm. The controller observes
current repository state under the shared profile mutation mutex. Only fresh
Playing for the bound endpoint and credential revision starts a full duration.
A waiting target can request a normal control read outside the timer mutex.
Once ACTIVE, playback changes cannot move its deadline.

Timing uses `elapsedRealtime()` including sleep and `BOOT_COUNT` to reject
old boots. The wall clock only formats the estimated due time. The scheduler
persists ARMING, replaces its one explicit immutable generation-bearing
PendingIntent, then persists ACTIVE. The non-exported receiver uses
`setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)`. An interrupted ARMING state
is cancelled and a valid same-boot ACTIVE record retains the existing alarm.
The PendingIntent's presence alone cannot establish delivery. Normal same-boot
process death can preserve an active deadline. Android scheduling and OEM limits
still apply. [Android AlarmManager](https://developer.android.com/reference/android/app/AlarmManager#setAndAllowWhileIdle(int,long,android.app.PendingIntent))

Under the shared mutex, the receiver validates generation, boot, due time and
the stored endpoint against the current profile, then persists DELIVERING
before sending. It resolves the current credential for that original profile,
using a disposable client with two-second connect/read and 2.5-second exchange
timeouts. Lookup, authentication and the one `pause "1"` exchange share six
seconds. The receiver finishes its `goAsync()` result in finally under an
eight-second coroutine budget. OS/storage stalls can exceed logical deadlines.
A successful empty acknowledgement commits completion. An interrupted claim
becomes uncertain and never replays. A known pre-claim failure records
process-local evidence for the matching generation and can be reconciled to a
persisted failure when storage is available. Process death can lose that
evidence. Later reconciliation cannot cancel a newer timer.

The same lock serializes credential/profile writes with delivery. Host,
either port or deletion invalidates the target. Password-only changes preserve
the deadline and supply the latest credential at delivery. Edits and Off
that win before the claim prevent it. Work after a claim waits for the
bounded attempt. Audio reacts to normal MPD observation only. No exact-alarm
permission, boot receiver or dedicated waiting poller is used.

## Credentials and backup

`AndroidMpdCredentials` stores an immutable AtomicFile record per UUID
revision in `noBackupFilesDir`. It includes a format version, random 12-byte
IV and AES-GCM ciphertext/tag. A separate non-exportable Android Keystore key
per revision works after the first ordinary device unlock, without biometric
authentication or a currently unlocked screen. Associated data binds the
stable ID, normalized control host, MPD port and revision. The revision is
random, independent of the password. Passwords stay out of ordinary profiles,
timer records, intents and logs. JVM strings have no guaranteed erasure.
[Android Keystore](https://developer.android.com/privacy-and-security/keystore)

The store stages and verifies a new record before committing the profile
reference. The committed reference selects exactly one revision. Interrupted
or ambiguous writes can leave both files. Previous reachable records remain
usable, while obsolete records are retired after a confirmed commit. An
Activity start can prune unreferenced files and aliases once per process.
Alarm-only startup and window focus changes do not initiate pruning. Pruning
only proceeds if the mutation lock is immediately available, the timer has no
pending record, and settings are readable and unrecovered. The lock protects
that check and the set of referenced revisions through pruning. Necessary
profile and credential writes still serialize with timer delivery and can
encounter platform or storage delays.

Missing or unreadable referenced records fail closed. Credential resolution
waits at most five seconds per connection. A timed-out read can use the normal
bounded recovery policy. Missing, unreadable or rejected credentials do not
start automatic recovery. The two credential workers have no waiting queue.
A blocking platform call can still occupy a worker after timeout or cancellation,
but its late result cannot start a connection. The timer's six-second network
budget still includes the credential lookup and all socket operations.

Control, idle, Test and timer all use the common opener. It resolves a
credential before dialing, validates the greeting and requires an empty
successful password response before ordinary commands. MPD 0.23.12 uses ACK 3
for password rejection and ACK 4 for denied permissions, including guest roles.
Android LAN permission is separate. Direct MPD sends passwords in plaintext
and provides no server identity verification. The Keystore-backed encryption
protects the local record. [MPD 0.23.12 source](https://www.musicpd.org/download/mpd/0.23/mpd-0.23.12.tar.xz)

| Data | Android cloud backup with encryption capabilities | Android device transfer |
| --- | --- | --- |
| Profiles, stable IDs, auth markers, order, selection, theme, track details and preferred duration | Eligible | Eligible |
| Language | AppCompat/platform behavior | AppCompat/platform behavior |
| Keystore keys and ciphertext under `noBackupFilesDir` | Excluded | Excluded |
| Live timer file and OS alarm registration | Excluded | Excluded |
| MPD sessions and listening authorization | Process-local | Process-local |

The manifest retains `allowBackup`. [Extraction rules](../app/src/main/res/xml/data_extraction_rules.xml)
gate cloud backup with `disableIfNoEncryptionCapabilities` and exclude the live
timer file in both modes. [Legacy rules](../app/src/main/res/xml/backup_rules.xml)
also exclude it. Android 16 QPR2 cross-platform transfer has no configured
partner. Protected profiles survive restore without credentials and require
re-entry or explicit removal. [Android Auto Backup](https://developer.android.com/identity/data/autobackup)

A valid `preferred_sleep_minutes_v1`, including an explicit 30, wins over
legacy minutes in the local timer file. An absent preference can be seeded
idempotently from that file without moving a live deadline or scheduling an
alarm. Failed migration writes leave the old value usable, and failed reads
keep existing timer state. A duration update commits the preference first.
A normal runtime-write failure attempts preference rollback and reports the
failure. Interruption or failed rollback can leave the new preference with an
old live deadline. A restored preference by itself cannot rearm a timer.
Pre-R09 backups lack the preferred duration.

## Android integration, localization and legal resources

Manifest permissions cover internet/network state, Android 17+ local-network
access, foreground media playback and wake lock. Media-session notifications
use Android's exemption from notification runtime permission. The network
security configuration permits cleartext HTTP for arbitrary profile hosts.
See the [user guide](USER_GUIDE.md#connect-to-mpd) for network precautions.

`AppCompatDelegate.setApplicationLocales` and Android own app language.
On API 31/32 AppCompat auto-stores the override, including a small blocking
preferences read. API 33+ uses platform storage. The picker also reads
selection on resume to handle an identical effective system locale. Locale
changes refresh Activity and Media3 notification/provider resources while
retaining healthy sockets, receiver and timer. On API 31/32 the service can
snapshot the recreated Activity's configuration without retaining it.
The existing channel is renamed without changing its ID or user settings.
See [localization](LOCALIZATION.md) for fallback and formatting conventions.

About loads selectable legal text on IO. `GenerateLegalResources` packages
root `LICENSE` and `docs/THIRD_PARTY_NOTICES.txt` directly and the installed
version comes from `BuildConfig.VERSION_NAME`. [Licensing](LICENSING.md)

## Open technical questions

- Minimum supported MPD protocol version and paging/windowing beyond current
  queue limits.
- Optional database-event refresh and paging for exceptionally large
  directory listings.
- Broader codec and device validation, real hardware routing and system media
  presentation, and OEM/Doze and API 31/37 runtime coverage.
