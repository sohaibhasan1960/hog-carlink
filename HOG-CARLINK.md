# HOG CarLink

HOG CarLink is a modified version of [Headswap](https://github.com/YegorkaPomidorka/headswap) 2.6.0
by Egor (YegorkaPomidorka), licensed under GPL-3.0 (see `LICENSE`; `pc-headunit/proto/*.proto` are AGPL-3.0).
Modified by Sohaib Alomari, 2026-10-30, for one car: VW ID.6 CROZZ (CarLife head unit "VW BT 5075", channel 20082128).

Changes against upstream tag `v2.6.0` (see `git log v2.6.0..`):

- package `com.hog.carlink`, name "HOG CarLink", own signing key; self-update from upstream disabled
- auto-start: CompanionDeviceManager presence service wakes the app when the car connects over Bluetooth (next to the ACL receiver)
- default video 800x480 (the head unit's native size)
- VW BT 5075 treated like VW BT 1420 over Wi-Fi: heartbeat on PAUSE, fast resume
- Arabic text for the head unit server warnings
- 2.6.0-hog.2: re-request the car Wi-Fi network (up to 10 times, 5 s apart) after onUnavailable; an approved request on Android 16 gives up after 3 scans
- 2.6.0-hog.3: restart the car network request when the car appears over Bluetooth or Wait is pressed again; "HOG CarLink" on the start screen

Build: `cd android && ./gradlew assembleRelease`. Needs JDK 17+, Android SDK platform 37,
`~/.android-keys/carlink-release.properties`, and the AA head unit certs in
`android/app/src/main/assets/aa/{hur,jvc}/` (not in git; taken from the official Headswap 2.6.0 APK).
