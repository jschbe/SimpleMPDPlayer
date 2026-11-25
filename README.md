# SimpleMPDPlayer

SimpleMPDPlayer is a minimalist Android client for Music Player Daemon (MPD)
with optional local HTTP audio reception. MPD owns playback and the queue.
To hear the server's audio on your phone, turn on **Listen on phone** in Player.

## Features

- Save and switch between MPD server profiles with optional password
  authentication.
- Play, pause, seek, skip tracks and adjust MPD volume. Random and Repeat
  (None, Queue or Single) are independent.
- Browse the MPD library by directory and append or replace the MPD queue.
  Play, remove, reorder or clear entries directly in Queue.
- Listen on your phone through MPD's HTTP audio output. While listening,
  Android media controls offer Play/Pause, Previous and Next.
- Set a sleep timer that counts down from confirmed playback and pauses the
  bound server at expiry. Choose a system, light or dark theme.
- Use English, German, French, Italian, Spanish, Ukrainian or Russian.
  Follow the system language or choose one in Settings.

## Getting started

You need **Android 12 (API 31) or later** and a reachable MPD server. To listen
on your phone, also enable an MPD HTTP audio output. Remote control works
without the audio output.

In **Add server**, enter the MPD host and port. If the server requires a
password, enter it there. Use **Test** to check the connection and read access,
then **Save** to select the server. See the [user guide](docs/USER_GUIDE.md) for
server and stream setup, backup and restore, and troubleshooting.

Ordinary MPD and HTTP traffic is unencrypted, including MPD passwords in
transit. Use a trusted network or a separately configured secure tunnel.

## Build and install

The source can be built and installed locally as a debug APK. See
[development](docs/DEVELOPMENT.md#build-and-install-locally) for the SDK,
Gradle commands and local installation instructions. Maintainers can also
[build and sign the 1.0.0 bundle](docs/DEVELOPMENT.md#sign-and-verify-the-play-release).

## Documentation

- [User guide](docs/USER_GUIDE.md)
- [Development and testing](docs/DEVELOPMENT.md)
- [Implemented requirements](docs/REQUIREMENTS.md) and [architecture](docs/ARCHITECTURE.md)
- [Verification history and limits](docs/VERIFICATION.md)
- [Localization and translation reference](docs/LOCALIZATION.md)

## License

SimpleMPDPlayer is free software under the **GNU GPL version 3 or any later
version** (`GPL-3.0-or-later`). See [LICENSE](LICENSE). Anyone distributing
a binary must provide its exact corresponding source and build files under
the GPL.

Third-party components retain their own terms. The app includes the license
and [third-party notices](docs/THIRD_PARTY_NOTICES.txt) in **Settings → About**.
See the [licensing audit](docs/LICENSING.md) for details.
