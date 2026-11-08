package com.asoft.ios;

import android.app.Activity;
import android.view.WindowManager;

import java.io.File;

/**
 * Debug builds only, for tests that run while nobody is at the phone: the app's screens keep the
 * display on, show over the lock screen (the phone stays locked; this app's window comes up over
 * it when adb starts it) and, while the file "dim" is in the app's external folder, dim their own
 * window. No system setting is changed: all of it ends when the window goes.
 */
final class DevNight {
    private DevNight() {}

    static void apply(Activity a) {
        if ((a.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            return;
        }
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        a.setShowWhenLocked(true);
        a.setTurnScreenOn(true);
        if (new File(a.getExternalFilesDir(null), "dim").exists()) {
            WindowManager.LayoutParams lp = a.getWindow().getAttributes();
            lp.screenBrightness = 0.02f;
            a.getWindow().setAttributes(lp);
        }
    }
}
