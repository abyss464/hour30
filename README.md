# hour30

An LSPosed module that turns the clock on MIUI 14 (Xiaomi 13 / fuxi, Android 13) into a 30-hour clock:
the day ends at a boundary hour (default 06:00) instead of midnight. A moment before the boundary is
shown as the previous day with the hour plus 24, so Oct 5 01:23 reads **Oct 4 Sun 25:23**, lunar date
included. At the boundary itself (06:00) the clock reads normally.

- Only 24-hour display changes. When the system is set to 12-hour time, the module does nothing.
- Only displayed text changes. Stored times, alarms, logs written by other apps and network time stay
  the same, because only the date-formatting calls are hooked.

## Requirements

- Root and [LSPosed](https://github.com/LSPosed/LSPosed) (Zygisk or Riru), API 93 or newer.
- Built and checked against MIUI V14.0.29.0.TMCCNXM. Other MIUI 14 builds will probably work. If a
  hook does not find its target, it logs the miss and skips it.

## Install

1. Install `dist/hour30.apk`.
2. In LSPosed Manager → Modules → hour30, enable the module. The recommended scope is preselected:
   - System UI (`com.android.systemui`): status bar, notification shade, control center, lock screen
   - Always-on display (`com.miui.aod`)
   - System launcher (`com.miui.home`): clock widgets
3. Reboot. Restarting the scoped apps is enough if you know how to restart System UI.

You can add other apps to the scope. In those apps the standard Android formatters and MIUI's calendar
are adapted as well. Do not add the Clock app: its alarm editor accepts hours 0-23 only.

## Settings

Open the **hour30** app:

- **Enabled**: master switch.
- **Day boundary hour** (1-12, default 6): times before this hour count as the previous day.

Changes take effect at the next clock tick, with no restart needed.

## Diagnostics

Each hook point writes one line when it attaches (or is skipped, with the reason) and one line the first
time it actually changes a displayed time. Lines are written to:

- the LSPosed module log: LSPosed Manager → Logs → Modules, then use the save/share button to export;
- logcat, tag `hour30`: `adb logcat -s hour30`.

When something on the phone still shows 00-05, export the LSPosed log and send it back. It shows which
hook points attached and which fired.

## Build

```sh
./gradlew assembleRelease   # -> app/build/outputs/apk/release/app-release.apk (debug-signed)
```

Hook coverage and verification state: [docs/status.md](docs/status.md).
