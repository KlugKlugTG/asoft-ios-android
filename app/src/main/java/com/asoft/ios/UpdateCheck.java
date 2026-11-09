package com.asoft.ios;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Is a newer release out? One read-only GET of the public GitHub API, no sign-in, at the app's
 * start and then at most every 6 hours while its process lives. Nothing is downloaded: the home
 * screen only says so and links to the release page. A switch under More turns it off.
 */
final class UpdateCheck {
    static final String RELEASE_PAGE = "https://github.com/alpersamur3/asoft-ios-android/releases/latest";
    private static final String API = "https://api.github.com/repos/alpersamur3/asoft-ios-android/releases/latest";
    private static final long EVERY_MS = 6 * 3600_000L;
    private static final int MAX_BODY = 1 << 20;
    private static final Pattern VERSION = Pattern.compile("(\\d{1,8})\\.(\\d{1,8})(?:\\.(\\d{1,8}))?(?:\\.(\\d{1,8}))?");

    private static volatile String latest;
    private static long lastAsked;

    private UpdateCheck() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("update", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean("check", true);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("check", on).apply();
        lastAsked = 0;
    }

    /** This app's version; a debug build takes it from files/update-test-version, for testing the notice. */
    static String version(Context c) {
        if ((c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            File f = new File(c.getFilesDir(), "update-test-version");
            try {
                if (f.exists()) {
                    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
                }
            } catch (java.io.IOException e) {
                // the real version, then
            }
        }
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private static int[] parts(String s) {
        int[] p = new int[4];
        Matcher m = VERSION.matcher(s == null ? "" : s);
        if (m.find()) {
            for (int i = 0; i < p.length; i++) {
                String g = m.group(i + 1);
                p[i] = g == null ? 0 : Integer.parseInt(g);
            }
        }
        return p;
    }

    static boolean newer(String tag, String version) {
        int[] a = parts(tag);
        int[] b = parts(version);
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return a[i] > b[i];
            }
        }
        return false;
    }

    /** The tag the home screen should announce now, or null. */
    static String toShow(Context c) {
        String tag = latest;
        return enabled(c) && tag != null && newer(tag, version(c)) && !tag.equals(prefs(c).getString("dismissed", "")) ? tag : null;
    }

    static void dismiss(Context c, String tag) {
        prefs(c).edit().putString("dismissed", tag).apply();
    }

    /** Asks GitHub in the background when it is time to, then runs @done on the screen's thread. */
    static void check(Activity a, Runnable done) {
        long now = SystemClock.elapsedRealtime();
        if (!enabled(a) || (lastAsked != 0 && now - lastAsked < EVERY_MS)) {
            return;
        }
        lastAsked = now;
        Context c = a.getApplicationContext();
        String version = version(a);
        Thread t = new Thread(() -> {
            try {
                latest = fetch(version);
                AppLog.note(c, "update check: latest " + latest + ", this app " + version);
            } catch (Exception e) {
                AppLog.note(c, "update check failed: " + e);
                return;
            }
            a.runOnUiThread(() -> {
                if (!a.isDestroyed()) {
                    done.run();
                }
            });
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    private static String fetch(String version) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(API).openConnection();
        try {
            h.setConnectTimeout(15_000);
            h.setReadTimeout(15_000);
            h.setRequestProperty("Accept", "application/vnd.github+json");
            h.setRequestProperty("User-Agent", "ASoftware-Android/" + version);
            if (h.getResponseCode() != 200) {
                throw new java.io.IOException("HTTP " + h.getResponseCode());
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) {
                byte[] b = new byte[16384];
                for (int n; (n = in.read(b)) > 0; ) {
                    body.write(b, 0, n);
                    if (body.size() > MAX_BODY) {
                        throw new java.io.IOException("answer too long");
                    }
                }
            }
            String tag = new JSONObject(body.toString("UTF-8")).getString("tag_name");
            if (tag.isEmpty()) {
                throw new java.io.IOException("no tag_name");
            }
            return tag;
        } finally {
            h.disconnect();
        }
    }
}
