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
- 2.6.0-hog.4: HOG logo (icon, notification, head unit start screen), HOG CarLink name in all UI and log/report files, Arabic translation, wireless onboarding (car Wi-Fi and car pick steps)
- 2.6.0-hog.5: self-update from github.com/sohaibhasan1960/hog-carlink releases (asset hog-carlink-<version>.apk)
- 2.6.0-hog.6: HOG colors; home screen waits for the car instead of a cable when the car and its Wi-Fi are set
- 2.6.0-hog.7: map video on the ID.6 - 30 fps by default (one-time switch from saved 15), Android Auto UI density 140 at 800x480
- 2.6.0-hog.8: VideoMode ID6 (AA 1280x720 with 32 px vertical margins -> 1280x688 area, squeezed to 800x480 so the ID.6 window shows it at 1.86:1) and ID6_HD (same area sent as 1280x688; CarLifeSession probes it and falls back to ID6 if the head unit sends no START in 8 s, repeats INIT or closes the socket, remembering the fallback in prefs); AA per-frame log lines sampled (20 first, then every 300th) so reports cover whole trips; head-unit banner texts localized (strings_banner.xml); one-time prefs migration R800 -> ID6_HD; note: CarSettings() and therefore every CarPreset without an explicit mode now default to ID6_HD
- 2.6.0-hog.9: phone calls (AudioManager mode IN_CALL/IN_COMMUNICATION, polled once a second via ThermalSource.inCall) throttle transcoding like ThermalLevel.HOT (15 fps, half bitrate) and are logged (`звонок:` lines, WL `call state=`); encoder asks KEY_LATENCY=1, and where FEATURE_IntraRefresh is supported uses a 4 s GOP with intra refresh instead of an IDR every second (tuning "nolat"/"nointra" restore the old behaviour)

Build: `cd android && ./gradlew assembleRelease`. Needs JDK 17+, Android SDK platform 37,
`~/.android-keys/carlink-release.properties`, and the AA head unit certs in
`android/app/src/main/assets/aa/{hur,jvc}/` (not in git; taken from the official Headswap 2.6.0 APK).
