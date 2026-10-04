package com.nightabyss.hour30;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.Date;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Entry point: the generic formatter layer once per process, then the MIUI adapter for each package. */
public class Hook implements IXposedHookLoadPackage {

    private static boolean generic;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (lp.packageName.equals(Config.PACKAGE)) return;
        if (!generic) {
            generic = true;
            hookJavaText();
            hookAndroidFormat();
            hookIcu();
        }
        Miui.hook(lp);
    }

    /** java.text.SimpleDateFormat: AOSP status bar clock, DateFormat.getTimeFormat users. */
    private static void hookJavaText() {
        hook(java.text.SimpleDateFormat.class, "format",
                new Object[]{Date.class, StringBuffer.class, java.text.FieldPosition.class},
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (!Shift.enter()) return;
                        java.text.SimpleDateFormat f = (java.text.SimpleDateFormat) p.thisObject;
                        String pattern = f.toPattern();
                        if (Shift.is12HourPattern(pattern)) return;
                        Calendar c = (Calendar) f.getCalendar().clone();
                        c.setTime((Date) p.args[0]);
                        int hour = c.get(Calendar.HOUR_OF_DAY);
                        if (!Shift.applies(hour)) return;
                        Shift.fired("java.text.SimpleDateFormat.format");
                        c.add(Calendar.DATE, -1);
                        p.args[0] = c.getTime();
                        String shifted = Shift.rewriteHours(pattern, hour);
                        if (!shifted.equals(pattern)) {
                            f.applyPattern(shifted);
                            p.setObjectExtra("pattern", pattern);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        Shift.exit();
                        Object pattern = p.getObjectExtra("pattern");
                        if (pattern != null) ((java.text.SimpleDateFormat) p.thisObject).applyPattern((String) pattern);
                    }
                });
        deoptimize(java.text.DateFormat.class, "format", Date.class);
    }

    /** android.text.format.DateFormat.format: TextClock (lock screen, widgets, AOSP shade date). */
    private static void hookAndroidFormat() {
        hook(android.text.format.DateFormat.class, "format",
                new Object[]{CharSequence.class, Calendar.class},
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (!Shift.enter()) return;
                        CharSequence pattern = (CharSequence) p.args[0];
                        Calendar in = (Calendar) p.args[1];
                        if (pattern == null || in == null || Shift.is12HourPattern(pattern)) return;
                        int hour = in.get(Calendar.HOUR_OF_DAY);
                        if (!Shift.applies(hour)) return;
                        Shift.fired("android.text.format.DateFormat.format");
                        Calendar c = (Calendar) in.clone();
                        c.add(Calendar.DATE, -1);
                        p.args[1] = c;
                        p.args[0] = Shift.rewriteHours(pattern, hour);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        Shift.exit();
                    }
                });
        deoptimize(android.text.format.DateFormat.class, "format", CharSequence.class, long.class);
        deoptimize(android.text.format.DateFormat.class, "format", CharSequence.class, Date.class);
    }

    /** android.icu.text.SimpleDateFormat: ICU-based formatting (DateFormat.getInstanceForSkeleton etc.). */
    private static void hookIcu() {
        hook(android.icu.text.SimpleDateFormat.class, "format",
                new Object[]{android.icu.util.Calendar.class, StringBuffer.class, java.text.FieldPosition.class},
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (!Shift.enter()) return;
                        android.icu.text.SimpleDateFormat f = (android.icu.text.SimpleDateFormat) p.thisObject;
                        android.icu.util.Calendar in = (android.icu.util.Calendar) p.args[0];
                        String pattern = f.toPattern();
                        if (in == null || Shift.is12HourPattern(pattern)) return;
                        int hour = in.get(android.icu.util.Calendar.HOUR_OF_DAY);
                        if (!Shift.applies(hour)) return;
                        Shift.fired("android.icu.text.SimpleDateFormat.format");
                        android.icu.util.Calendar c = (android.icu.util.Calendar) in.clone();
                        c.add(android.icu.util.Calendar.DATE, -1);
                        p.args[0] = c;
                        String shifted = Shift.rewriteHours(pattern, hour);
                        if (!shifted.equals(pattern)) {
                            f.applyPattern(shifted);
                            p.setObjectExtra("pattern", pattern);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        Shift.exit();
                        Object pattern = p.getObjectExtra("pattern");
                        if (pattern != null) ((android.icu.text.SimpleDateFormat) p.thisObject).applyPattern((String) pattern);
                    }
                });
        deoptimize(android.icu.text.DateFormat.class, "format", Date.class);
    }

    /** Hooks one method; a missing class or method is logged and skipped. */
    static void hook(Class<?> cls, String method, Object[] params, XC_MethodHook callback) {
        String point = cls.getName() + "." + method;
        try {
            Object[] args = new Object[params.length + 1];
            System.arraycopy(params, 0, args, 0, params.length);
            args[params.length] = callback;
            XposedHelpers.findAndHookMethod(cls, method, args);
            Shift.attached(point, true, null);
        } catch (Throwable t) {
            Shift.attached(point, false, t);
        }
    }

    /**
     * Keeps a short caller of a hooked method from having it inlined in precompiled code, which
     * would bypass the hook. Uses LSPosed's XposedBridge.deoptimizeMethod when present.
     */
    static void deoptimize(Class<?> cls, String method, Class<?>... params) {
        try {
            deoptimize(cls.getDeclaredMethod(method, params));
        } catch (Throwable ignored) {
        }
    }

    static void deoptimize(Member m) {
        try {
            Method d = XposedBridge.class.getDeclaredMethod("deoptimizeMethod", Member.class);
            d.invoke(null, m);
        } catch (Throwable ignored) {
        }
    }
}
