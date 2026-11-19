# Airtel Wifi Manager

An Android companion for managing devices connected to an Airtel Xstream Fiber connection through the Nokia G-2425G-A GPON router.

Airtel Wifi Manager reads the router's own device inventory, including reported device names, IP addresses, MAC addresses, connection types and active status.

## Features

- Connect to the router's local web interface (default: `192.168.1.1`).
- Authenticate using the router's RSA/AES login flow.
- Display router-reported device names, IP addresses, MAC addresses and connection/interface information.
- Separate **Active devices** and **All saved devices** sections.
- Retain previously discovered devices and mark devices inactive when absent from later scans.
- Search by device name, IP address, MAC address or interface.
- Reuse the router session while scanning.
- Save router credentials locally and reconnect automatically on app launch.
- Block and unblock a device's internet access through the router's Parental Control interface.
- Read the blocked state back from the router and show it on device cards.

## Device access controls

Block and Unblock use the Nokia router's Parental Control functionality. Blocking restricts a device's internet access; it does not necessarily disconnect the device from Wi-Fi. The app asks for confirmation and refreshes the router's state after an action.

**Important:** Do not block the Android phone running Airtel Wifi Manager. It may lose access to the router and be unable to issue an unblock command until connectivity is restored.

## Password storage

The app prefers AES-GCM encryption using Android Keystore. If Android Keystore is unavailable, it falls back to app-private storage. That fallback is protected by Android's application sandbox but is not separately encrypted.

Router credentials remain on the device and are not uploaded to a cloud service.

## Compatibility

Designed for Airtel Xstream Fiber routers using the Nokia G-2425G-A interface. Firmware variations may affect compatibility, particularly the Parental Control interface.

The router protocol implementation is based on the public project [surajnai567/airtel-router-api](https://github.com/surajnai567/airtel-router-api). See [NOTICE.md](NOTICE.md) for license attribution.

## Usage

1. Connect your Android phone or tablet to your Airtel Xstream Fiber Wi-Fi.
2. Open **Airtel Wifi Manager**.
3. Enter the router IP address, administrator username and password.
4. Use **Scan** to refresh the active-device list.
5. Use **Block** or **Unblock** on a device card to change internet access through Parental Control.
6. Use **All saved devices** to view previously discovered devices, including inactive ones.
7. Use **Logout** to end the current in-app router session. Saved credentials are retained for the next launch.
8. Use **Forget saved credentials** to remove locally stored router credentials.

The router administrator password may differ from the Wi-Fi password.

## Build

Requirements:

- JDK 17
- Android SDK Platform 35
- Android Gradle Plugin 8.7.3
- Gradle 8.9

Build a debug APK with:

```bash
./gradlew assembleDebug
```

Output:

`app/build/outputs/apk/debug/app-debug.apk`

Signed release APKs are built by the GitHub Actions workflow when a `v*` tag is pushed. The workflow uses the repository's Android signing secrets.

## Privacy

- No analytics or advertising.
- No telemetry.
- No third-party cloud API.
- Router communication takes place over the local network.
- Android app backup is disabled.

## License and attribution

See [NOTICE.md](NOTICE.md) for the MIT attribution covering the upstream router protocol reference. The app's own source remains in this repository.
