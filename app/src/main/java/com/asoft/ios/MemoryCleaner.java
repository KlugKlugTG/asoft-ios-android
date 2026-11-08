package com.asoft.ios;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.os.Build;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Free memory while iOS runs": every 90 s, while iOS runs and only then, the background processes
 * of the phone's other apps are ended the way Android ends them when memory runs short
 * (ActivityManager.killBackgroundProcesses): not a force stop, so their alarms, jobs and
 * notifications stay, but an app left in the background starts afresh when opened. iOS needs
 * 4 to 5 GB, and a save writes 3 GB more, which Android otherwise answers by ending this app.
 *
 * Android 14 and later let an app end only its own processes: there the option is off.
 */
final class MemoryCleaner {
    private static final String PREFS = "asoft", KEY = "free_ram";
    private static final long PERIOD_MS = 90_000;
    private static Thread loop;

    private MemoryCleaner() {}

    static boolean supported() {
        return Build.VERSION.SDK_INT < 34;
    }

    static boolean enabled(Context c) {
        return supported() && prefs(c).getBoolean(KEY, false);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY, on).apply();
        if (on && Guest.running()) {
            start(c);
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Runs the rounds in the background until iOS stops; a second call while it runs does nothing. */
    static synchronized void start(Context c) {
        if (!enabled(c) || loop != null && loop.isAlive()) {
            return;
        }
        Context app = c.getApplicationContext();
        loop = new Thread(() -> {
            // the first round once QEMU is up, then every PERIOD_MS while it runs
            for (int i = 0; i < 100 && !Guest.running(); i++) {
                sleep(200);
            }
            while (Guest.running() && enabled(app)) {
                round(app);
                sleep(PERIOD_MS);
            }
        }, "free-memory");
        loop.setDaemon(true);
        loop.start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** The apps a person starts (the launcher's list), but this one, the launcher and the keyboard. */
    private static Set<String> others(Context c) {
        Set<String> skip = new HashSet<>();
        skip.add(c.getPackageName());
        ResolveInfo home = c.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        if (home != null && home.activityInfo != null) {
            skip.add(home.activityInfo.packageName);
        }
        String ime = android.provider.Settings.Secure.getString(c.getContentResolver(),
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null && ime.contains("/")) {
            skip.add(ime.substring(0, ime.indexOf('/')));
        }
        Set<String> out = new HashSet<>();
        List<ResolveInfo> apps = c.getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        for (ResolveInfo r : apps) {
            String p = r.activityInfo.packageName;
            if (!skip.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    private static long availableMb(ActivityManager am) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem >> 20;
    }

    private static void round(Context c) {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        long before = availableMb(am);
        Set<String> apps = others(c);
        for (String p : apps) {
            try {
                am.killBackgroundProcesses(p);
            } catch (SecurityException e) {
                AppLog.note(c, "free memory: refused (" + e.getMessage() + ")");
                return;
            }
        }
        sleep(1500);
        AppLog.note(c, "free memory: " + apps.size() + " apps' background processes ended, available "
                + before + " -> " + availableMb(am) + " MB");
    }
}
