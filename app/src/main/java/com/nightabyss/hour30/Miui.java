package com.nightabyss.hour30;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.widget.TextView;

import java.lang.reflect.Member;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * MIUI adapter. MIUI clocks do not use the standard formatters; they go through miuix's own
 * calendar (bundled into each app), which has two kinds of consumer:
 * <ul>
 *   <li>{@code Calendar.format}: status bar, notification shade, control center, lock screen clocks,
 *       lunar date, AOD dates. Hooked like the generic formatters (shift date, rewrite hour fields).</li>
 *   <li>Direct field reads ({@code Calendar.get}) by code that builds the digits itself: AOD clock
 *       styles and MAML (theme) clocks such as the launcher clock widget. Inside those methods
 *       (a "scope") the hour reads hour+24 and date and lunar fields read the previous day.</li>
 * </ul>
 */
final class Miui {
    private static final String CALENDAR = "miuix.pickerwidget.date.Calendar";
    private static final String SYMBOLS = "miuix.pickerwidget.date.CalendarFormatSymbols";
    private static final String MAML_TIME = "com.miui.maml.data.DateTimeVariableUpdater";
    /** Status bar clock; the shade header and control center clocks extend it. */
    private static final String STATUS_CLOCK = "com.android.systemui.statusbar.views.MiuiClock";

    /** miuix Calendar field numbers: 18 is hour of day; 0-15 are date, weekday and lunar fields. */
    private static final int HOUR = 18;
    private static final int LAST_DATE_FIELD = 15;

    /** AOD clock styles whose updateTime builds the time digits from Calendar.get. */
    private static final String[][] AOD_SCOPES = {
            {"com.miui.aod.widget.HorizontalClock", "updateTime"},
            {"com.miui.aod.widget.VerticalClock", "updateTime"},
            {"com.miui.aod.widget.DualClock", "updateTime"},
            {"com.miui.aod.widget.SunClock", "updateTime"},
            {"com.miui.aod.widget.ClockPanel", "updateTime"},
            {"com.miui.aod.widget.ClockPanel", "setClockTime"},
            {"com.miui.aod.widget.DualClockPanelContainerView", "setClockTime"},
            {"com.miui.aod.widget.SmartCoverClockView", "updateTime"},
            {"com.miui.aod.widget.SmartCoverClockBView", "updateTime"},
            {"com.miui.aod.widget.ImageSelectorClock", "updateTime"},
            {"com.miui.aod.widget.SuperWallpaperClock", "updateTime"},
    };

    /** Hooked (class, method) pairs; a process may report the same class loader more than once. */
    private static final Set<String> hooked = new HashSet<>();
    private static final ThreadLocal<int[]> scope = ThreadLocal.withInitial(() -> new int[1]);
    private static final ThreadLocal<boolean[]> inner = ThreadLocal.withInitial(() -> new boolean[1]);

    private Miui() {}

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        ClassLoader cl = lp.classLoader;
        boolean known = lp.packageName.equals("com.android.systemui")
                || lp.packageName.equals("com.miui.aod") || lp.packageName.equals("com.miui.home");

        Class<?> calendar = XposedHelpers.findClassIfExists(CALENDAR, cl);
        if (calendar != null && first(calendar, "format")) {
            hookFormat(calendar);
            hookGet(calendar);
        } else if (calendar == null && known) {
            Shift.attached(CALENDAR, false, new ClassNotFoundException(CALENDAR));
        }

        if (lp.packageName.equals("com.android.systemui")) {
            Class<?> clock = XposedHelpers.findClassIfExists(STATUS_CLOCK, cl);
            if (clock == null) {
                Shift.attached(STATUS_CLOCK, false, new ClassNotFoundException(STATUS_CLOCK));
            } else if (first(clock, "setText")) {
                guardClockText(clock);
            }
        }

        Class<?> maml = XposedHelpers.findClassIfExists(MAML_TIME, cl);
        if (maml != null && first(maml, "updateTime")) {
            scope(maml, "updateTime");
        }

        if (lp.packageName.equals("com.miui.aod")) {
            for (String[] s : AOD_SCOPES) {
                Class<?> c = XposedHelpers.findClassIfExists(s[0], cl);
                if (c == null) {
                    Shift.attached(s[0] + "." + s[1], false, new ClassNotFoundException(s[0]));
                } else if (first(c, s[1])) {
                    scope(c, s[1]);
                }
            }
        }
    }

    private static final List<Class<?>> clockViews = new CopyOnWriteArrayList<>();
    private static final Pattern TIME = Pattern.compile("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)");

    /**
     * Last line of defence for the status bar clock: whatever produced its text (stock MiuiClock
     * ticks once a minute and has no seconds mode on this build; a seconds clock added by another
     * module may format the time itself and refresh every second), a leading H:mm that still shows
     * the current hour before the boundary is rewritten to hour+24. Text the formatter hooks already
     * shifted reads 24-29 and is left alone. Hooks the private TextView.setText that every public
     * overload ends in, so inlining of the small public overloads cannot bypass it.
     */
    private static void guardClockText(Class<?> clock) {
        boolean firstClock = clockViews.isEmpty();
        clockViews.add(clock);
        if (!firstClock) return;
        String point = STATUS_CLOCK + " text (TextView.setText)";
        Hook.hook(TextView.class, "setText",
                new Object[]{CharSequence.class, TextView.BufferType.class, boolean.class, int.class},
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        CharSequence text = (CharSequence) p.args[0];
                        if (text == null || text.length() == 0 || !isClockView(p.thisObject)) return;
                        CharSequence shifted = shiftClockText(text);
                        if (shifted != text) {
                            Shift.fired(point);
                            p.args[0] = shifted;
                        }
                    }
                });
    }

    private static boolean isClockView(Object view) {
        for (Class<?> c : clockViews) if (c.isInstance(view)) return true;
        return false;
    }

    /** Rewrites the first H:mm equal to the current (or 5 s earlier) hour and minute, if before the boundary. */
    private static CharSequence shiftClockText(CharSequence text) {
        Matcher m = TIME.matcher(text);
        if (!m.find()) return text;
        int hour = Integer.parseInt(m.group(1));
        int minute = Integer.parseInt(m.group(2));
        long now = System.currentTimeMillis();
        for (long t : new long[]{now, now - 5000}) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(t);
            int h = c.get(Calendar.HOUR_OF_DAY);
            if (h != hour || c.get(Calendar.MINUTE) != minute || !Shift.applies(h)) continue;
            SpannableStringBuilder out = new SpannableStringBuilder(text);
            out.replace(m.start(1), m.end(1), Integer.toString(h + 24));
            return out;
        }
        return text;
    }

    private static boolean first(Class<?> cls, String method) {
        return hooked.add(cls.getName() + "#" + method + "@" + System.identityHashCode(cls));
    }

    /** Calendar.format(Context, StringBuilder, CharSequence, CalendarFormatSymbols): every format overload ends here. */
    private static void hookFormat(Class<?> calendar) {
        String point = CALENDAR + ".format";
        Hook.hook(calendar, "format",
                new Object[]{Context.class, StringBuilder.class, CharSequence.class, SYMBOLS},
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (!Shift.enter()) return;
                        CharSequence pattern = (CharSequence) p.args[2];
                        if (pattern == null || Shift.is12HourPattern(pattern)) return;
                        long t = (long) XposedHelpers.callMethod(p.thisObject, "getTimeInMillis");
                        TimeZone tz = (TimeZone) XposedHelpers.callMethod(p.thisObject, "getTimeZone");
                        int hour = hourOf(t, tz);
                        if (!Shift.applies(hour)) return;
                        Shift.fired(point);
                        XposedHelpers.callMethod(p.thisObject, "setTimeInMillis", previousDay(t, tz));
                        p.setObjectExtra("time", t);
                        p.args[2] = Shift.rewriteHours(pattern, hour);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        Shift.exit();
                        Object t = p.getObjectExtra("time");
                        if (t != null) XposedHelpers.callMethod(p.thisObject, "setTimeInMillis", t);
                    }
                });
    }

    /** Calendar.get(int), only inside a scope: the hour reads hour+24, date fields the previous day. */
    private static void hookGet(Class<?> calendar) {
        String point = CALENDAR + ".get";
        Hook.hook(calendar, "get", new Object[]{int.class}, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (scope.get()[0] == 0 || inner.get()[0]) return;
                int field = (int) p.args[0];
                if (field != HOUR && (field < 0 || field > LAST_DATE_FIELD)) return;
                long t = (long) XposedHelpers.callMethod(p.thisObject, "getTimeInMillis");
                TimeZone tz = (TimeZone) XposedHelpers.callMethod(p.thisObject, "getTimeZone");
                int hour = hourOf(t, tz);
                if (!Shift.applies(hour)) return;
                Shift.fired(point);
                if (field == HOUR) {
                    p.setResult(hour + 24);
                    return;
                }
                boolean[] in = inner.get();
                in[0] = true;
                try {
                    Object previous = XposedHelpers.newInstance(p.thisObject.getClass(), tz);
                    XposedHelpers.callMethod(previous, "setTimeInMillis", previousDay(t, tz));
                    p.setResult(XposedHelpers.callMethod(previous, "get", field));
                } finally {
                    in[0] = false;
                }
            }
        });
    }

    /**
     * Marks every method of this name as a scope, and deoptimizes it so that Calendar.get calls
     * inlined into its precompiled code still reach the hook.
     */
    private static void scope(Class<?> cls, String method) {
        String point = cls.getName() + "." + method + " (scope)";
        try {
            Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(cls, method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    scope.get()[0]++;
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    scope.get()[0]--;
                }
            });
            if (unhooks.isEmpty()) throw new NoSuchMethodException(method);
            for (XC_MethodHook.Unhook u : unhooks) {
                Member m = u.getHookedMethod();
                Hook.deoptimize(m);
            }
            Shift.attached(point, true, null);
        } catch (Throwable t) {
            Shift.attached(point, false, t);
        }
    }

    private static int hourOf(long t, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz != null ? tz : TimeZone.getDefault());
        c.setTimeInMillis(t);
        return c.get(Calendar.HOUR_OF_DAY);
    }

    /** The same wall-clock time one day earlier. */
    private static long previousDay(long t, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz != null ? tz : TimeZone.getDefault());
        c.setTimeInMillis(t);
        c.add(Calendar.DATE, -1);
        return c.getTimeInMillis();
    }
}
