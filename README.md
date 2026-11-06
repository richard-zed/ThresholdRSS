# ThresholdRSS

An RSS and Atom reader for Android. Feeds, articles, and read state stay on
the device.

![Main screen](docs/screenshots/main.png)

## Features

- RSS 2.0 and Atom, including `media:group` enclosures and OPML import
- Per-feed and combined article lists
- Offline article storage with read/unread tracking
- Dark and light themes
- No account, no sync, no telemetry

## Install

Download the APK from the [releases page](../../releases) and sideload it.
Android 7.0 (API 24) or newer.

The release APK is signed; verify it against the certificate fingerprint
listed in the release notes.

## Build

Requires JDK 21 and the Android SDK with platform 37.

```sh
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`.

## License

GPL-3.0. See [LICENSE](LICENSE).

The icon source is [daisy.svg](daisy.svg).
