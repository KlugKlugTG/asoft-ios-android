package com.asoft.ios;

import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.provider.Settings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Android's power saving caps the CPU, and iOS runs on the CPU: on the A71 (2026-11-05) it held the
 * two Cortex-A76 at 1.56 of 2.21 GHz. The app warns while it is on, and says when the fastest
 * core is held well below its top clock for another reason (heat, a "Light" performance mode).
 */
final class PowerCheck {
    private PowerCheck() {}

    static boolean saverOn(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        return pm != null && pm.isPowerSaveMode();
    }

    /**
     * {allowed, hardware} top clock of the fastest core in kHz, or null when the phone does not say.
     * Read from cpufreq/policyN, by name (an app may stat there, listing is not sure): cpuN/cpufreq
     * is a symlink labelled plain sysfs, which an app may not follow (A71, Android 13).
     */
    static long[] fastestCore() {
        long bestHw = -1, allowed = -1;
        for (int i = 0; i < 32; i++) {
            File d = new File("/sys/devices/system/cpu/cpufreq/policy" + i);
            long hw = readLong(new File(d, "cpuinfo_max_freq"));
            long now = readLong(new File(d, "scaling_max_freq"));
            if (hw > bestHw && now > 0) {
                bestHw = hw;
                allowed = now;
            }
        }
        return bestHw > 0 ? new long[] {allowed, bestHw} : null;
    }

    /** "1.56 of 2.21 GHz" when the fastest core is held below 90% of its top, else null. */
    static String capped() {
        long[] f = fastestCore();
        if (f == null || f[0] * 10 >= f[1] * 9) {
            return null;
        }
        return String.format(java.util.Locale.ROOT, "%.2f of %.2f GHz", f[0] / 1e6, f[1] / 1e6);
    }

    /** For the app's log: what the clock check saw. */
    static String describe() {
        long[] f = fastestCore();
        return f == null ? "cpufreq unreadable" : String.format(java.util.Locale.ROOT, "fastest core %.2f of %.2f GHz", f[0] / 1e6, f[1] / 1e6);
    }

    /** The warning for the home screen, or null when nothing holds the CPU back. */
    static String warning(Context c) {
        String cap = capped();
        if (saverOn(c)) {
            return "Power saving is on. It slows the CPU" + (cap != null ? " (now " + cap + ")" : "")
                    + ", and iOS runs on the CPU: a boot can take several times longer and apps open slowly. "
                    + "Turn it off while you use iOS. Tap here for the setting.";
        }
        if (cap != null) {
            return "The CPU is held at " + cap + " right now (heat, or a battery or performance setting such as "
                    + "\"Light\"): iOS runs slower until it is free. Tap here for the battery settings.";
        }
        return null;
    }

    /** Opens Android's power saving page, else the battery page, else Settings. */
    static void openSettings(Context c) {
        for (String a : new String[] {Settings.ACTION_BATTERY_SAVER_SETTINGS,
                Intent.ACTION_POWER_USAGE_SUMMARY, Settings.ACTION_SETTINGS}) {
            try {
                c.startActivity(new Intent(a));
                return;
            } catch (android.content.ActivityNotFoundException ignored) {
                // the next one
            }
        }
    }

    private static long readLong(File f) {
        try {
            return Long.parseLong(new String(Files.readAllBytes(f.toPath())).trim());
        } catch (IOException | NumberFormatException | SecurityException e) {
            return -1;
        }
    }
}
