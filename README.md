# Router Devices — Airtel Nokia G-2425G-A

A small, read-only Android app for retrieving the connected-device list from the local web interface of the Nokia G-2425G-A GPON router commonly supplied with Airtel Xstream Fiber.

## What it does

- Connects to the router (default `http://192.168.1.1`).
- Authenticates using the router's RSA/AES login handshake.
- Reads `/parental_control.cgi` and parses the `deviceCfg` / `device_cfg` array.
- Displays the router-reported hostname, IP address, MAC address, active state and interface type.
- Filters the device list by name, IP, MAC or interface.
- Optionally remembers the router password encrypted with an AES key held by Android Keystore.

## What it deliberately does not do

This first version is read-only. It does not block/unblock devices, change Wi-Fi settings, edit parental controls, or send router credentials to a cloud service. Its only router requests are the login-page GET, login POST and read-only device-page GET.

## Build

Requirements: Android Studio with JDK 17 and Android SDK Platform 35. The project uses Android Gradle Plugin 8.7.3 and Gradle 8.9.

1. Open this folder in Android Studio.
2. Allow Gradle sync to complete.
3. Build > Build Bundle(s) / APK(s) > Build APK(s).

From a terminal, run `./gradlew assembleDebug` (Linux/macOS) or `gradlew.bat assembleDebug` (Windows). These bootstrap scripts download Gradle 8.9 into the user's Gradle cache; they do not need a preinstalled Gradle.

The debug APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

## Use

1. Connect the phone to your Airtel Xstream Fiber Wi-Fi.
2. Open Router Devices.
3. Enter your router web UI IP, admin username and admin password.
4. Tap **Connect and load devices**.
5. Optionally enable **Remember password securely on this phone**.

If authentication fails, confirm the same username/password work at `http://192.168.1.1` in a browser. The router's admin password is not necessarily the Wi-Fi password.

## Compatibility note

The protocol and page parser are based on the public [`surajnai567/airtel-router-api`](https://github.com/surajnai567/airtel-router-api) project. That project's README identifies the Nokia G-2425G-A as tested hardware and documents its encrypted login and `device_cfg` data source. Firmware customisations can change the HTML or login response, so this app must be tested against the specific Airtel firmware on the user's router. If the page layout differs, the app shows a parser/firmware error rather than inventing device data.

## Privacy and network security

- Credentials stay on the phone. If saved, the password is encrypted with Android Keystore (AES-GCM); the host and username are stored as ordinary app preferences.
- The app communicates with the router over the local network using the router's HTTP web interface. Use it only on your own trusted home network.
- Android backup is disabled so app preferences are not included in normal device backup.
- No analytics, advertising, remote API, or telemetry is included.

## Source reference

The router protocol was adapted from the public MIT-licensed project:

- https://github.com/surajnai567/airtel-router-api
- `core/client.py` — login, `GET /parental_control.cgi`, `device_cfg` parser
- `core/crypto.py` — AES-CBC and RSA PKCS#1 v1.5 request encryption

This Android project is an independent Java implementation of the read-only portion. It does not include that project's SQLite, REST server, MCP server, CLI, or device-blocking features.


## Version 1.1.0 changes

- Router admin credentials are saved after a successful login, encrypted with Android Keystore.
- The app automatically reconnects on launch until the user explicitly logs out.
- Explicit Logout disables automatic reconnect while keeping the encrypted credentials available for the next manual login.
- The Scan action displays only active devices from the latest router scan.
- All saved devices are retained locally, including devices that disappear from later scans, and shown with Active/Inactive status.
- Router session cookies are reused across scans instead of logging in again for every refresh.
