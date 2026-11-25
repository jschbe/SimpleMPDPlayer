# User guide

[Back to the project overview](../README.md)

## Connect to MPD

1. Make the MPD control port reachable from the phone on a trusted LAN or
   through a separately configured secure tunnel. Ordinary MPD and HTTP use
   cleartext. MPD sends passwords in plaintext and cannot verify server
   identity. The app neither changes the server configuration nor opens router
   ports. [MPD permissions and passwords](https://mpd.readthedocs.io/en/stable/user.html#permissions-and-passwords)
2. Open **Settings → Servers → Add server**. A successful first load with an
   empty list opens the same editor automatically. Give the profile a unique
   name and enter a hostname, IPv4 or IPv6 address without a scheme, path or
   embedded port. The defaults are MPD **6600** and HTTP stream **8000**.
   Each port accepts 1–65535.
3. Provide the optional MPD password. MPD uses a password without a username.
   **Test** checks the greeting, authentication if configured, and read-only
   status on a separate connection. Read access alone does not guarantee
   permission to play, use idle updates, browse or change the queue.
4. **Save** stores and selects the profile and starts a connection. A connection
   failure leaves the saved profile available to edit. On Android 17 (API 37)
   or later, grant **Allow local network** when prompted. If the prompt no longer
   appears, enable the app's Nearby devices permission in Android settings.

Give the MPD role access to status, current song, queue reads, idle and directory
listing, plus the playback and queue commands you intend to use. Check server
permissions if Test succeeds but a feature is denied.

## Manage profiles, passwords and backup

Use the server-name dropdown to switch profiles. In **Settings → Servers**, use
the row's edit or delete action. Long-press and drag its handle to reorder the
list. Deleting the selected profile disconnects it without choosing a substitute.

When editing a protected profile, choose **Keep saved password**, **Replace
password** or **Use no password**. The editor never pre-fills an existing
password. An empty draft does not remove it. Changing the host or MPD port
requires a replacement or an explicit choice to connect without a password.
Changing only the HTTP port, renaming or reordering does not change the password
binding. Correct a rejected password or re-enter a missing or unreadable one in
**Edit server**. A failed connection does not erase the password.

Android can back up or transfer ordinary preferences and profiles, including
their order, selection, password-configured markers and preferred timer duration.
Cloud backup requires encryption capabilities. Direct Android device transfer has
its own rules. App language follows AppCompat and Android behavior. Whether a
restore happens depends on Android, the user and the backup transport.

Passwords and live timer state stay off backups and transfers. On a restored
device, re-enter each required MPD password or explicitly remove it. A restored
profile remains available to edit, and local listening and the timer require
manual activation. A backup made before the preferred duration was eligible
cannot supply that value.

## Play, browse and manage the queue

Tap **Browser**, **Player** or **Queue**, or swipe between them. Player opens by
default when a server is selected. MPD owns playback, the queue and the mixer.
The app displays confirmed status and refreshes it after commands or external
changes. Position advances locally while Playing with synchronized idle updates.
If status is stale or idle synchronization is lost, the displayed position
freezes until a new readback.

- **Player** offers Play/Pause, Stop, Previous, Next, seek when duration is known,
  and MPD volume where supported. The track-information chevron remembers
  whether title, artist and album are expanded. Phone volume is separate.
- **Random** is independent of **Repeat**. Repeat offers **None**, **Queue** and
  **Single**. External stop-after-track, one-shot and unknown modes are displayed
  without silently changing them.
- **Browser** loads the selected directory. Use **Up** or Android Back to go to
  its parent. A file tap or a folder's action menu offers **Append to queue** and
  **Replace queue**. MPD handles folder recursion. Stored playlists are skipped.
- **Queue** highlights the current entry. Tap an entry to play it or use its
  menu to remove it or move it up or down. **Clear queue** requires confirmation.
  Reconnects and server switches dismiss old queue menus and confirmations.

Replacing the queue asks for confirmation and does not start playback. If MPD
clears the queue and an add fails, the queue can be empty or partially populated.
An interrupted exchange may leave the outcome uncertain. Check MPD before
trying again. Other clients can change the same queue during these operations.

For a large or rapidly changing queue, **Retry** fetches a fresh snapshot.
The app loads batches of 200 within a limit of 20,000 tracks and separate time
and metadata budgets. Playback controls can remain available, and **Clear queue**
can still be offered when MPD reports entries. Individual entry actions require
a complete queue. If a directory exceeds the response limit, use **Up** or
**Retry** after control reconnects.

## Listen on the phone

### Configure an HTTP output

Enable an MPD `httpd` output on the same host as the profile's MPD connection.
This example uses a documentation-only address. Replace it with a trusted
interface address on your server. Use an MPD build with the LAME encoder.

```conf
audio_output {
    type            "httpd"
    name            "Phone audio"
    bind_to_address "192.0.2.10"
    port            "8000"
    encoder         "lame"
    bitrate         "128"
    format          "44100:16:2"
}
```

Set the profile's **HTTP stream port** to the output port. The app opens the
root HTTP path on that host. MP3 has been verified. Other formats depend on
encoder, decoder and device support. MPD passwords are used only on the MPD
control connection. The HTTP receiver has no HTTP authentication.
[MPD HTTP output](https://mpd.readthedocs.io/en/stable/plugins.html#httpd)
and [LAME encoder](https://mpd.readthedocs.io/en/stable/plugins.html#lame)

### Listen and use Android media controls

Enable **Listen on phone** in Player after connecting. It starts Off on fresh
process startup, Save and server switch. Rotation and ordinary returns to the app
preserve an enabled receiver. Same-server recovery or manual **Reconnect** can
preserve it. The info button explains listening without toggling it.

- The receiver can be ready while MPD is paused, and HTTP may still send silent
  audio frames. Confirmed Playing enables local output. On resume it rejoins
  the live stream, and buffering can put the audio behind the displayed track.
- While listening, Android's media notification and lock screen can show MPD
  metadata and Play/Pause, Previous and Next. Compatible headset buttons use the
  same MPD actions. Play/Pause acts on MPD, and tapping the notification opens
  the app. System controls do not provide seeking, Stop or queue editing.
- Phone and Bluetooth volume affect local sound only. The MPD volume control
  affects the server mixer. Android owns accessory routing and media UI.
- Transient focus loss can temporarily suppress audio. Permanent focus loss or
  an unplug event can turn reception off. These events and switching listening
  Off leave MPD playback unchanged.
- If Android reclaims the receiver after a long pause or restricts a background
  resume, open the app and re-enable reception. Use **Retry local audio** after
  an HTTP or decoding failure. A terminal MPD control failure stops reception
  and requires reconnecting before listening again.

## Set a sleep timer

Open **Settings → Sleep timer** or tap the sleep status in Player. The presets
are **15**, **30**, **45** and **60** minutes, with **30** as the default.
**Custom…** accepts 1–1440 whole minutes. Keyboard **Done** and dialog **Done**
can apply a valid changed value. An unavailable or failed write leaves the
draft available for retry or cancellation. Applying a duration leaves an Off
timer Off.

- Switching On binds the selected server. Confirmed Playing starts the full
  countdown. Paused, stopped, disconnected or unconfirmed playback shows
  **Waiting for playback**, with no deadline or alarm. Local listening is optional.
- Once the countdown starts, Pause/Stop/Play does not move its deadline.
  Changing duration restarts the countdown on the original server if it is
  confirmed Playing, or waits for playback again. Off cancels waiting or the
  alarm. Turn Off and On to bind a different selection.
- Switching servers does not retarget the timer. Deleting the bound profile
  or changing its host or either port cancels it. Renaming, reordering and
  changing only the password leave the deadline in place. Expiry uses the
  current credential for the original server.
- At expiry the app attempts one explicit MPD Pause, which preserves the
  playback position. Success requires acknowledgement. Failure is shown in
  the timer dialog and signaled in Player. If delivery was interrupted after it
  was claimed, the outcome may be uncertain. There is no automatic replay.
  A cancellation after the claim cannot recall a command already sent.

The alarm is inexact and Android power saving can delay it substantially. Zero
remaining time can mean the alarm is due and still waiting for Android. Normal
process or Activity restart within the same device boot can retain the waiting
state or active deadline. System clock changes affect only the estimated local
time. Reboot cancels the old timer on the next app start. Force Stop, missing
permission, an unavailable network or server, and Android background limits can
prevent delivery.

Waiting relies on confirmed status from the selected server. Playback can go
unnoticed while the app process is stopped or suspended, or another server is
selected. On return, reconnect or reselect the bound server. If Playing is then
confirmed, the full duration starts from that observation. An earlier episode
that already ended can be missed.

## Recovery and troubleshooting

| Symptom | What to check |
| --- | --- |
| Network unavailable or Reconnecting | Check LAN or tunnel availability. Transient failures retry up to five times after 1, 2, 4, 8 and 16 seconds. Known offline periods pause the cycle. After exhaustion, use **Reconnect**. A qualified return or unlock can authorize a fresh cycle. |
| MPD password needed, rejected or access denied | Use **Edit server** to re-enter or correct the password. Check the MPD role's permissions for the denied action. A restored profile may have its password marker but lack the local credential. |
| Allow local network | Grant the Android app permission or check Nearby devices in Android settings. |
| Could not load saved servers | Use **Retry** in Servers. An existing session can remain usable. Editing stays unavailable until a successful load. |
| MPD works but phone audio fails | Check the enabled `httpd` output, stream port, encoder and Android volume or audio route. Use **Retry local audio**. |
| Progress stopped advancing or queue unavailable | Wait for fresh status or use **Reconnect**. Use **Retry** in Queue for a large or changing queue. Healthy control can survive an idle-only failure. |
| Timer waiting, due or failed | For Waiting, confirm the bound server is selected and Playing. For Due, allow for Android's alarm delay. For Failed, inspect the timer dialog before setting another timer. |

## Language, theme and About

In **Settings → Language** choose System default or an explicit language.
Android 13 and newer also expose the same app-language choice in Android
settings. Unsupported languages fall back to English. Changing language keeps
the connection, listening and timer state. Android-owned controls may follow
the system language.

**Settings → Appearance** offers System default, Light and Dark. **Settings →
About** shows the installed version, source link, GPL and third-party notices.
The legal texts are available offline.
