# vpnbox

A portable, volunteer-run VPN client for Android. No accounts, no keys, no
analytics.

Built as a Material 3 app by
[sajadinmaker](https://github.com/sajadinmaker). vpnbox connects through the
volunteer relays of [VPN Gate](https://vpngate.net), the academic experiment
run by the University of Tsukuba.

## What it does

vpnbox fetches the live VPN Gate relay list, lets you pick the fastest server
or a server from a specific country, and connects through a real OpenVPN
engine. If the tunnel drops, the app restores it automatically — it re-enters
protection, retries recovery with backoff, and keeps the session intent until
it succeeds or you disconnect.

## Features

- Live server list refresh from VPN Gate.
- Fastest-server and per-country picking.
- Automatic reconnect with backoff after a tunnel drop.
- A real OpenVPN 3 client core embedded in the app (see [NOTICE](NOTICE)).
- Material 3 UI with Material You dynamic colour.
- System, light, and dark theme modes.
- About and Settings screens.
- No tracking, no analytics.

## Install

Download
[`vpnbox-1.0.0-beta7-android.apk`](https://github.com/sajadinmaker/vpnbox/releases)
(~34 MB — the OpenVPN 3 core ships inside for four ABIs) from the GitHub
Releases page. Android releases begin at the tag `v1.0.0-beta5-android`.
The app requires Android 8.0 or newer.

Your browser may warn about unknown sources, and Play Protect may flag the
sideloaded APK. That is expected for apps installed outside the Play Store;
verify the file against `SHA256SUMS` (attached to the same release) before
installing:

```bash
sha256sum -c SHA256SUMS
```

## Build from source

Prerequisites: JDK 17 and the Android SDK. The repository uses the Android
Gradle Plugin; all Gradle commands run from the `android/` directory.

```bash
cd android
./gradlew assembleDebug
```

Unit tests:

```bash
./gradlew testDebugUnitTest
```

A release build signs with `android/keystore.properties`, which is gitignored.
It must contain exactly four keys:

```properties
storeFile=/absolute/path/to/keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

The store file must contain a key whose name matches `keyAlias`.

## Security notes

A VPN Gate relay is a public network run by volunteers. Do not carry traffic
you would not carry over an open Wi-Fi network. Each relay operator sets its
own logging policy.

The app talks to plain HTTP endpoints by design of the experiment: the VPN
Gate mirror list, and ip-api.com for exit-IP geolocation in the server list.
This is documented deliberately — it is how VPN Gate distributes its data.
The app performs no analytics and makes no other network calls. Caches and
settings stay on-device in app-private storage.

## Transparency

- Source:
  [github.com/sajadinmaker/vpnbox](https://github.com/sajadinmaker/vpnbox)
- Changes: [CHANGELOG.md](CHANGELOG.md)
- License: vpnbox itself is [MIT](LICENSE); the bundled OpenVPN 3 core and
  its dependencies keep their own licenses — full list with sources in
  [NOTICE](NOTICE)

Server list and relay profiles: VPN Gate Academic Experiment, University of
Tsukuba.
