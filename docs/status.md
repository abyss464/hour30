# Status

## Target build

MIUI **V14.0.29.0.TMCCNXM** (Xiaomi 13 China, `fuxi`, Android 13). This is the exact build requested.
Source: the official recovery ROM `miui_FUXI_V14.0.29.0.TMCCNXM_a72354d5c7_13.0.zip` (MD5
`a72354d5c79981e3efae774db12c4918`, matches the published value). From it, `payload.bin` was extracted
to get `system`, `system_ext` and `product` (erofs), and jadx 1.5.6 decompiled:
`MiuiSystemUI.apk`, `MIUIAod.apk`, `MiuiHomeT.apk`, `MIUISystemUIPlugin.apk`, `miui-framework.jar`,
`miuix.jar`, plus `android.text.format.DateFormat` from `framework.jar`.

## Verification state

Nothing was run on a device or emulator. The physical phone was not touched, and the emulator check
(rootAVD + LSPosed on API 33) was skipped to keep the implementation minimal. Each hook point below is
**compiled only**: the module builds, and every hook target was compared line by line with the
decompiled code of this ROM. The first real run will be on the phone. The diagnostic log (see README)
shows which hook points attached and which fired.

## The rule

For a moment `t` with hour `h` < boundary, in 24-hour mode:

- date fields come from `t` minus one calendar day (same wall-clock time);
- the hour reads `h + 24`.

Formatter hooks get this by formatting the previous day and rewriting the pattern's 24-hour fields
(`H`, `k`) into the literal digits of `h + 24`. Code that reads calendar fields directly gets it from a
field-read hook, active only inside the clock methods listed below (a "scope"). A pattern with `h`,
`K`, `a`, `b` or `B` is a 12-hour pattern and is left alone. When the system is in 12-hour mode, the
module does nothing.

## Hook points

### Generic layer (all scoped processes)

| Hook point | Covers | State |
|---|---|---|
| `java.text.SimpleDateFormat.format(Date, StringBuffer, FieldPosition)` | AOSP status bar `Clock`, `DateFormat.getTimeFormat()` users, any `SimpleDateFormat` | compiled only |
| `android.text.format.DateFormat.format(CharSequence, Calendar)` (the `long`/`Date` overloads delegate here; read in the ROM's framework.jar) | `TextClock`, AOSP keyguard `AnimatableClockView`, QS alarm tile | compiled only |
| `android.icu.text.SimpleDateFormat.format(Calendar, StringBuffer, FieldPosition)` | ICU formatters (`getInstanceForSkeleton`, etc.) | compiled only |
| Deoptimized callers: `java.text.DateFormat.format(Date)`, `android.text.format.DateFormat.format(CharSequence, long/Date)`, `android.icu.text.DateFormat.format(Date)` | keeps boot-image inlining from bypassing the hooks (LSPosed `XposedBridge.deoptimizeMethod`) | compiled only |

### MIUI adapter

MIUI's clocks use miuix's own calendar, `miuix.pickerwidget.date.Calendar`, and each app bundles its
own copy. The hooks are resolved in each app's class loader.

| Hook point | Covers | State |
|---|---|---|
| `miuix.pickerwidget.date.Calendar.format(Context, StringBuilder, CharSequence, CalendarFormatSymbols)`: all `format` overloads and `miuix.pickerwidget.date.DateUtils.formatDateTime` end here | **Status bar** `statusbar.views.MiuiClock` (time, date-time, date, weekday modes). **Notification shade / control center** (`MiuiQSHeaderView`, `MiuiNotificationHeaderView`, `ControlCenterClockView`, `ControlCenterDateView`, all built on `MiuiClock`). **Lock screen**, all built-in `com.miui.clock` styles: `MiuiLeftTopClock`, `MiuiLeftTopLargeClock`, `MiuiCenterHorizontalClock`, `MiuiVerticalClock` (`HH\nmm`), `MiuiDualClock` (both cities), the date line and **lunar date** (`YY年 N月e`) of `MiuiBaseClock`. **AOD** date and lunar lines. MAML `formatDate()` expressions | compiled only |
| `miuix.pickerwidget.date.Calendar.get(int)`, active only inside a scope | field 18 (hour) → `h+24`; fields 0-15 (era, year, month, day, weekday, day of year, lunar year/month/day, stems and branches, solar term) → previous day | compiled only |
| Scope: `com.miui.maml.data.DateTimeVariableUpdater.updateTime` (SystemUI, AOD and Home each bundle MAML) | MAML variables `hour24`, `year`, `month`, `date`, `day_of_week`, lunar variables → **launcher clock widgets** (`gadget.AwesomeClock`), theme lock screens, AOD MAML clocks, including digit-image themes driven by `#hour24` | compiled only |
| Scope (AOD): `HorizontalClock`, `VerticalClock`, `DualClock`, `SunClock`, `ClockPanel`, `SmartCoverClockView`, `SmartCoverClockBView`, `ImageSelectorClock`, `SuperWallpaperClock` `.updateTime`; `ClockPanel.setClockTime`, `DualClockPanelContainerView.setClockTime` | **AOD clock styles**: these build their digits with `String.format("%d:%02d", get(18), …)`. `OneLineClock`, `ScopeClock` and `PaintVerticalClock` inherit from them and are covered | compiled only |
| Every scope method is deoptimized | keeps AOT inlining of the small `Calendar.get` from bypassing the hook | compiled only |

## Not adapted, and why

- **Analog styles** (keyguard `ImageClock`, AOD `ClockPanelView` hands): a 12-hour dial has no 24-29.
  The hands stay correct (`25 % 12 = 1`).
- **MAML themes that pick one image per hour value** (e.g. `hour_#hour24.png` with 24 images): there is
  no image for 24-29, so the element shows blank. This depends on the theme and cannot be fixed in
  general. Themes that draw digits with `digit()` / `ImageNumber` work.
- **AOD time-of-day gradient** (`SunSelector.getDrawableIndex`, read inside the `SmartCoverClockBView`
  and `HorizontalClock` scopes): between 00:45 and the boundary it picks the 21:40-00:45 colour instead
  of the 00:45-02:20 one. Cosmetic only; the index stays in range.
- **`java.time.DateTimeFormatter`**: not hooked. None of the target clocks use it; left out to keep the
  module minimal.
- **12-hour opt-in**: not implemented. In 12-hour mode nothing changes.

## Side effects inside scoped processes

Every time these processes format through the hooked APIs is shown on the 30-hour clock, not only the
big clocks. This includes the times in the AOD schedule settings screen and `dumpsys` timestamps of System UI
(both read in the decompiled code). Notification timestamps are probably affected too, but this was
not traced. Stored values and other processes are not affected.
