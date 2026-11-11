package com.asoft.ios;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Is a newer release out? One read-only GET of the public GitHub API, no sign-in, at the app's
 * start and then at most every 6 hours while its process lives. Nothing is downloaded: the home
 * screen only says so and links to the release page. A switch in the home screen's Settings turns it off.
 *
 * A release's notes may carry a short message for the app in an HTML comment (GitHub's page does
 * not show it), anywhere in the body:
 * <pre>
 * &lt;!-- app-notice
 * level: critical
 * text: Adreno 740 and 750 GPUs can now run iOS 26 and watchOS.
 * --&gt;
 * </pre>
 * level is critical (red), important (amber) or normal; text may span several text: lines.
 */
final class UpdateCheck {
    static final String RELEASE_PAGE = "https://github.com/alpersamur3/asoft-ios-android/releases/latest";
    // the list, not /releases/latest: the notice's color is the highest level among every release
    // this app has not got yet, so someone two releases behind still sees a critical one in between
    private static final String API = "https://api.github.com/repos/alpersamur3/asoft-ios-android/releases?per_page=10";
    private static final long EVERY_MS = 6 * 3600_000L;
    // a dismissed critical notice comes back after this long: it is about something that hurts
    private static final long CRITICAL_AGAIN_MS = 24 * 3600_000L;
    private static final int MAX_BODY = 1 << 20;
    private static final int MAX_TEXT = 300;
    private static final Pattern VERSION = Pattern.compile("(\\d{1,8})\\.(\\d{1,8})(?:\\.(\\d{1,8}))?(?:\\.(\\d{1,8}))?");
    private static final Pattern BLOCK = Pattern.compile("<!--\\s*app-notice\\b");

    static final int NORMAL = 0, IMPORTANT = 1, CRITICAL = 2;

    /** What the home screen announces: the newest release's tag, its text (or null) and the level. */
    static final class Notice {
        final String tag;
        final String text;
        final int level;

        Notice(String tag, String text, int level) {
            this.tag = tag;
            this.text = text;
            this.level = level;
        }
    }

    private static volatile Notice latest;
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

    private static boolean debug(Context c) {
        return (c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /** A debug build's test file in files/, or null when there is none (or this is a release build). */
    private static String testFile(Context c, String name) {
        if (!debug(c)) {
            return null;
        }
        File f = new File(c.getFilesDir(), name);
        try {
            if (f.exists()) {
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            }
        } catch (java.io.IOException e) {
            // as if it were not there
        }
        return null;
    }

    /** This app's version; a debug build takes it from files/update-test-version, for testing the notice. */
    static String version(Context c) {
        String test = testFile(c, "update-test-version");
        if (test != null) {
            return test.trim();
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

    /** What the home screen should announce now, or null. */
    static Notice toShow(Context c) {
        Notice n = latest;
        if (!enabled(c) || n == null || !newer(n.tag, version(c))) {
            return null;
        }
        SharedPreferences p = prefs(c);
        if (!n.tag.equals(p.getString("dismissed", ""))) {
            return n;
        }
        if (n.level < CRITICAL) {
            return null;
        }
        // wall time, since it has to outlive the process; a clock set back counts as time gone by
        long at = p.getLong("dismissedAt", 0);
        long now = System.currentTimeMillis();
        return now - at >= CRITICAL_AGAIN_MS || now < at ? n : null;
    }

    static void dismiss(Context c, String tag) {
        prefs(c).edit().putString("dismissed", tag).putLong("dismissedAt", System.currentTimeMillis()).apply();
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
        // read here, not on the thread: a debug build's stand-in for the newest release's notes
        String testBody = testFile(a, "update-test-notice");
        Thread t = new Thread(() -> {
            try {
                Notice n = fetch(version, testBody);
                latest = n;
                AppLog.note(c, n == null ? "update check: nothing newer than " + version
                        : "update check: " + n.tag + " (level " + n.level + (n.text != null ? ", with a text" : "")
                        + "), this app " + version);
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

    /** The newest published release above @version with its text, at the highest level among all of them; null if none. */
    private static Notice fetch(String version, String testBody) throws Exception {
        JSONArray releases = new JSONArray(get(version));
        String tag = null;
        String body = null;
        // the highest level among the newer releases other than the newest; the newest's own is
        // added at the end, so that the debug test body really stands in for it
        int older = NORMAL;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject r = releases.optJSONObject(i);
            if (r == null || r.optBoolean("draft") || r.optBoolean("prerelease")) {
                continue;
            }
            String t = r.optString("tag_name", "");
            if (t.isEmpty() || !newer(t, version)) {
                continue;
            }
            // "body" is JSON null for a release with no notes, and optString would make that "null"
            String b = r.isNull("body") ? "" : r.optString("body", "");
            // the list comes newest first, but go by the version number in case one was edited later
            if (tag == null || newer(t, tag)) {
                if (tag != null) {
                    older = Math.max(older, level(block(body)));
                }
                tag = t;
                body = b;
            } else {
                older = Math.max(older, level(block(b)));
            }
        }
        if (tag == null) {
            return null;
        }
        if (testBody != null) {
            body = testBody;
        }
        String[] newest = block(body);
        return new Notice(tag, text(newest), Math.max(older, level(newest)));
    }

    private static String get(String version) throws Exception {
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
            return body.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }

    /**
     * The lines inside the first app-notice comment of a release body, or an empty array. Notes
     * written on Windows come with CRLF, so every line end is made \n first. A comment never closed
     * runs to the end, as it does on GitHub's page too.
     */
    static String[] block(String body) {
        if (body == null) {
            return new String[0];
        }
        String s = body.replace("\r\n", "\n").replace('\r', '\n');
        Matcher m = BLOCK.matcher(s);
        if (!m.find()) {
            return new String[0];
        }
        int end = s.indexOf("-->", m.end());
        return s.substring(m.end(), end < 0 ? s.length() : end).split("\n");
    }

    /** The value of a "key: value" line (key in any case), or null when the line is something else. */
    private static String field(String line, String key) {
        String l = line.trim();
        int colon = l.indexOf(':');
        if (colon < 0 || !l.substring(0, colon).trim().equalsIgnoreCase(key)) {
            return null;
        }
        return l.substring(colon + 1).trim();
    }

    /** The block's level; a missing or unknown one (a typo, a level added later) is normal. */
    static int level(String[] block) {
        for (String line : block) {
            String v = field(line, "level");
            if (v != null) {
                switch (v.toLowerCase(Locale.ROOT)) {
                    case "critical":
                        return CRITICAL;
                    case "important":
                        return IMPORTANT;
                    default:
                        return NORMAL;
                }
            }
        }
        return NORMAL;
    }

    /** The block's text: its text: lines one under the other, as plain text, cut at ~300 characters; null if none. */
    static String text(String[] block) {
        StringBuilder sb = new StringBuilder();
        for (String line : block) {
            String v = field(line, "text");
            if (v == null || v.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            // it goes into a TextView as it is: drop control characters (tabs too) so nothing odd shows
            for (int i = 0; i < v.length(); i++) {
                char ch = v.charAt(i);
                sb.append(Character.isISOControl(ch) ? ' ' : ch);
            }
        }
        String s = sb.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.length() > MAX_TEXT) {
            int cut = MAX_TEXT - 1;
            // never split a surrogate pair (an emoji) in two
            if (Character.isHighSurrogate(s.charAt(cut - 1))) {
                cut--;
            }
            s = s.substring(0, cut).trim() + "…";
        }
        return s;
    }
}
