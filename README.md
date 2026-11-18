# Network Scanner Airtel XStream

An independent Android companion for Airtel Xstream Fiber users with the Nokia G-2425G-A GPON router.

It reads connected-device information directly from the router, including actual device names rather than relying on MAC vendor identification.

## Features

- Router connection through the local web interface (default: 192.168.1.1).
- RSA/AES router authentication.
- Actual router-reported device names.
- IP addresses and MAC addresses.
- Connection/interface information.
- Separate Active Devices and All Saved Devices sections.
- Previously discovered devices retained and marked inactive when absent from later scans.
- Search by device name, IP, MAC or interface.
- Router session reuse during refresh.
- Automatic login using locally saved credentials.
- Block and unblock internet access for individual devices using router Parental Control.
- Blocked status is read back from the router and shown on device cards.

## Password storage

The app prefers AES-GCM encryption using Android Keystore.

If Android Keystore is unavailable, it falls back to app-private storage. This fallback is protected by Android's application sandbox but is not separately encrypted.

Credentials remain on the device. They are not uploaded to a cloud service.

## Device access controls

Block and Unblock use the Nokia router's Parental Control interface. Blocking restricts internet access; it does not necessarily disconnect a device from Wi-Fi. The app asks for confirmation and checks the router's policy state after each change. Do not block the Android phone running the app, because it may lose access to the router.

## Compatibility

Designed for Airtel Xstream Fiber Nokia G-2425G-A routers.

The implementation is based on the public project:
https://github.com/surajnai567/airtel-router-api

Firmware variations may affect compatibility.

## Usage

1. Connect your Android device to Airtel Xstream Fiber Wi-Fi.
2. Open Network Scanner Airtel XStream.
3. Enter your router IP, administrator username and password.
4. The app saves credentials and attempts automatic login on subsequent launches.
5. Use Scan to refresh active devices.
6. Use Block or Unblock on a device card to change its internet access through Parental Control.
7. Use All Saved Devices to see previously discovered devices and their status.
8. Use Logout to return to the login screen.
9. Use Forget Saved Credentials to remove locally stored credentials.

The router administrator password may differ from your Wi-Fi password.

## Build

Requirements:
- JDK 17
- Android SDK Platform 35
- Android Gradle Plugin 8.7.3
- Gradle 8.9

Build using:

./gradlew assembleDebug

APK output:

app/build/outputs/apk/debug/app-debug.apk

## Privacy

- No analytics or advertising.
- No telemetry.
- No remote API.
- Local router communication only.
- Android backup disabled.

## Reference

https://github.com/surajnai567/airtel-router-api

This app independently implements the router device-list and Parental Control functions in Java. See NOTICE.md for MIT license attribution.
