# Privacy Policy – SimpleMPDPlayer

**Effective date:** 25 November 2026

This privacy policy applies to SimpleMPDPlayer for Android
(`com.github.jschbe.simplempdplayer`), an open-source client for Music Player
Daemon (MPD) maintained by the developer of the
[jschbe/SimpleMPDPlayer project](https://github.com/jschbe/SimpleMPDPlayer).

## Data collection and sharing

SimpleMPDPlayer does not collect or transmit user data to the app developer.
It has no developer-operated backend, user accounts, advertising, analytics,
tracking or integrated remote crash-reporting service. The developer does not
sell or share app user data for advertising or other commercial purposes.

## Data processed by the app

To provide its functionality, the app processes:

- Server profiles: names, hostnames or IP addresses, ports and optional MPD
  passwords.
- Music library information, track metadata, queue contents and playback
  status received from the configured MPD server.
- Audio received from the configured HTTP streaming endpoint when you enable
  listening on your device.
- App preferences, including server selection and order, appearance, language,
  track display and sleep timer settings.

Profiles and preferences are stored locally on your device. Playback and
library information are used to display and control your MPD server. When
local listening is enabled, playback information may also be displayed by
Android's media controls, including on the lock screen and compatible connected
accessories. The app does not access your contacts, microphone, camera or
precise location.

## Network communication and security

The app sends playback and library commands to the selected MPD server. Testing
a profile can connect to the endpoint entered in the editor before saving.
A sleep timer may connect to its original target server in the background to
send Pause. The app uses an MPD password only to authenticate an MPD connection.
When you enable listening on your device, the app requests audio from the
configured HTTP endpoint and may follow redirects to another HTTP URL. Your
server and network operators may process connection information, such as your
IP address, and retain logs according to their own policies and configuration.

MPD passwords are encrypted on the device using AES-GCM and Android Keystore.
Ordinary MPD connections and HTTP audio streams are not encrypted in transit;
MPD passwords are transmitted in cleartext and the app does not authenticate
server identities. Use a trusted network or a separately configured secure
tunnel. Local credential encryption does not protect network traffic.

## Android permissions

- `INTERNET` allows the app to connect to MPD servers and receive optional HTTP
  audio.
- `ACCESS_NETWORK_STATE` lets the app detect network availability and pause
  automatic reconnection attempts while offline.
- `ACCESS_LOCAL_NETWORK` allows connections to local MPD servers and HTTP audio
  endpoints. Android 17 (API 37) and later require a runtime permission for
  this access. The app can request it when you choose **Test**, **Save** or
  **Allow local network**. **Open app settings** lets you enable **Nearby
  devices** directly from the app. The app does not request separate local
  network approval on Android 16 or earlier.
- `WAKE_LOCK` helps the audio player keep the device and network available
  while you listen, including when the screen is off.
- `FOREGROUND_SERVICE` allows the app to run its listening service when you
  turn on **Listen on phone**.
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` allows the listening service to keep
  playing audio while you use another app or turn off the screen.

## Android backup and device transfer

Depending on your Android settings and available backup services, Android may
back up server profiles (including names, addresses, ports and password-configured
markers) and ordinary app preferences or transfer them to another device.
Cloud backup is permitted only when Android reports encryption capabilities.
Language restoration depends on Android and AppCompat behavior.

Stored MPD passwords, their encryption keys, and live sleep timer records are
excluded from backup and device transfer. Restored password-protected profiles
require you to enter the password again or explicitly remove password protection.
The app developer does not receive or operate these backups. Backup retention,
restoration and deletion are managed through Android and your backup provider.

## Retention and deletion

The developer does not retain app user data on a server. Local profiles and
preferences remain on your device until you change or delete them, clear the
app's storage in Android settings, or uninstall the app. You can delete a
server profile or remove its saved password in the app. Superseded encrypted
credential records may remain temporarily until local cleanup; clearing app
storage removes the app's local data.

Uninstalling the app or clearing its storage does not necessarily delete
existing Android backups or data held by your MPD server. Manage those separately
through your backup provider and MPD server. No app account deletion procedure
is necessary because the app does not create accounts.

## External services

Opening **Privacy policy** or **Open source repository** from About opens
GitHub using Android's link handler, typically your browser. GitHub handles
that visit under its own privacy policy. Google Play and Android system
services may process data independently of the app under their applicable
privacy policies.

## Contact and changes

For privacy questions or requests, contact the developer using the developer
contact email listed on the SimpleMPDPlayer Google Play store page. You can also
use the [project's issue tracker](https://github.com/jschbe/SimpleMPDPlayer/issues)
for general questions. GitHub issues are public; do not post passwords, private
server addresses or other personal information there.

This policy may be updated to reflect changes in the app or its data handling.
The current version is available in the public source repository.
