package com.nightabyss.hour30;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * The one rule every hook applies: a moment before the boundary hour belongs to the previous day,
 * and its hour reads hour+24. Hooks shift the date they format by one day and rewrite 24-hour
 * fields (H, k) of the pattern into the literal hour+24; 12-hour patterns are left alone.
 */
final class Shift {
    static final String TAG = "hour30";

    private static XSharedPreferences prefs;
    private static long prefsCheckedAt;
    private static boolean enabled = true;
    private static int boundary = Config.DEFAULT_BOUNDARY;

    private static boolean is24Hour = true;
    private static long is24CheckedAt;

    /** Nesting depth of hooked format calls on this thread; only the outermost call shifts. */
    private static final ThreadLocal<int[]> depth = ThreadLocal.withInitial(() -> new int[1]);

    private Shift() {}

    /** Whether a moment at this hour of day is shown as the previous day's hour+24 right now. */
    static boolean applies(int hourOfDay) {
        refresh();
        return enabled && hourOfDay < boundary && is24Hour();
    }

    static boolean enter() {
        return depth.get()[0]++ == 0;
    }

    static void exit() {
        depth.get()[0]--;
    }

    /** True if the pattern has a 12-hour field or an AM/PM marker outside quotes. */
    static boolean is12HourPattern(CharSequence pattern) {
        boolean quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\'') quoted = !quoted;
            else if (!quoted && (c == 'h' || c == 'K' || c == 'a' || c == 'b' || c == 'B')) return true;
        }
        return false;
    }

    /**
     * Replaces each run of H or k outside quotes with the digits of hour+24 (digits are literal in
     * every date pattern syntax). Returns the input unchanged if it has no such field.
     */
    static String rewriteHours(CharSequence pattern, int hourOfDay) {
        String hour = Integer.toString(hourOfDay + 24);
        StringBuilder out = null;
        boolean quoted = false;
        int n = pattern.length();
        for (int i = 0; i < n; i++) {
            char c = pattern.charAt(i);
            if (c == '\'') quoted = !quoted;
            if (!quoted && (c == 'H' || c == 'k')) {
                if (out == null) out = new StringBuilder(n).append(pattern, 0, i);
                while (i + 1 < n && pattern.charAt(i + 1) == c) i++;
                out.append(hour);
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? pattern.toString() : out.toString();
    }

    private static void refresh() {
        long now = SystemClock.uptimeMillis();
        if (now - prefsCheckedAt < 1000) return;
        prefsCheckedAt = now;
        try {
            if (prefs == null) {
                prefs = new XSharedPreferences(Config.PACKAGE, Config.PREFS);
            } else if (prefs.hasFileChanged()) {
                prefs.reload();
            } else {
                return;
            }
            enabled = prefs.getBoolean(Config.KEY_ENABLED, true);
            boundary = prefs.getInt(Config.KEY_BOUNDARY, Config.DEFAULT_BOUNDARY);
        } catch (Throwable t) {
            Log.w(TAG, "config unreadable, using defaults", t);
        }
    }

    private static boolean is24Hour() {
        long now = SystemClock.uptimeMillis();
        if (now - is24CheckedAt < 5000) return is24Hour;
        is24CheckedAt = now;
        Application app = AndroidAppHelper.currentApplication();
        if (app != null) {
            try {
                is24Hour = DateFormat.is24HourFormat(app);
            } catch (Throwable ignored) {
            }
        }
        return is24Hour;
    }

    // Diagnostics: every hook point logs once whether it attached and once when it first fires.
    // Lines go to logcat (tag hour30) and to the LSPosed module log, which LSPosed can export.

    private static final Set<String> fired = new HashSet<>();

    static void attached(String point, boolean ok, Throwable error) {
        String line = (ok ? "attached " : "skipped ") + point + " in " + process()
                + (error == null ? "" : ": " + error);
        log(line);
    }

    static void fired(String point) {
        synchronized (fired) {
            if (!fired.add(point)) return;
        }
        log("fired " + point + " in " + process());
    }

    private static String process() {
        return Application.getProcessName();
    }

    private static void log(String line) {
        Log.i(TAG, line);
        try {
            XposedBridge.log(TAG + ": " + line);
        } catch (Throwable ignored) {
        }
    }
}
