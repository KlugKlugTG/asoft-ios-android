package com.asoft.ios;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * The app's own adb connection to this phone, over Android 11's Wireless debugging (as Shizuku does it): once
 * the user has paired the app (WadbPairService: the code from Settings' pairing dialog), the app connects to
 * adbd's port on this very phone with its own key and runs commands in adb's shell (uid 2000). What that is
 * for: "am kill-all" while iOS runs (MemoryCleaner). Android 14 and later let an app end only its own
 * background processes, and without the room iOS's 4 to 8 GB make Android end this app, iOS with it.
 *
 * adbd picks a new port every time Wireless debugging is switched on, and says so only over mDNS on the Wi-Fi
 * (AdbMdns): the last port is tried first, then the announcement. After a restart of the phone Wireless
 * debugging is off until the user turns it on again; the pairing itself stays (Settings lists the key under
 * "Paired devices" until it is removed there).
 */
final class WirelessAdb {
    private static final String PREFS = "asoft", PAIRED = "wadb-paired", PORT = "wadb-port", REFUSED = "wadb-refused";
    private static final Object LOCK = new Object();
    private static AdbConnection conn;
    /** Why the last try to connect failed, in the screen's words; null after one that worked. */
    static volatile String lastError;

    private WirelessAdb() {}

    /** Wireless debugging came with Android 11. */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= 30;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File dir(Context c) {
        return new File(c.getNoBackupFilesDir(), "adb");
    }

    static boolean paired(Context c) {
        return supported() && prefs(c).getBoolean(PAIRED, false);
    }

    /** The phone no longer takes the key (the pairing was removed in Settings): pairing again is the way on. */
    static boolean refused(Context c) {
        return prefs(c).getBoolean(REFUSED, false);
    }

    /** The name Settings lists the key under ("Paired devices"). */
    static String name(Context c) {
        return c.getPackageName().endsWith(".dev") ? "ASoftware-iOS-dev" : "ASoftware-iOS";
    }

    /**
     * Wireless debugging's switch, when Android lets the app read it: 1 on, 0 off, -1 unknown. (Wi-Fi off turns
     * it off too.)
     */
    static int switchedOn(Context c) {
        try {
            return android.provider.Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) != 0 ? 1 : 0;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * Pairs the app's key with the pairing dialog at @port of this phone, with its six digits. Throws
     * AdbPairing.WrongCode for a code that did not match (the dialog is gone then: the next try needs a new one).
     */
    static void pair(Context c, int port, String code) throws IOException {
        AdbKey key;
        try {
            key = AdbKey.of(dir(c));
        } catch (GeneralSecurityException e) {
            throw new IOException("no adb key: " + e.getMessage(), e);
        }
        String id = AdbPairing.pair(key, name(c), "127.0.0.1", port, code);
        prefs(c).edit().putBoolean(PAIRED, true).putBoolean(REFUSED, false).commit();
        AppLog.note(c, "wireless adb: paired with this phone (" + id + ") as " + name(c));
        synchronized (LOCK) {
            close();
        }
    }

    /** Forgets the pairing on the app's side: the key is deleted. Settings keeps its entry until removed there. */
    static void unpair(Context c) {
        synchronized (LOCK) {
            close();
            AdbKey.delete(dir(c));
            prefs(c).edit().remove(PAIRED).remove(PORT).remove(REFUSED).commit();
            lastError = null;
        }
        AppLog.note(c, "wireless adb: unpaired (the app's key is deleted)");
    }

    private static void close() {
        if (conn != null) {
            conn.close();
            conn = null;
        }
    }

    static boolean connected() {
        synchronized (LOCK) {
            return conn != null && !conn.closed();
        }
    }

    /** The open connection, or a new one: the last port first, then the one adbd announces now. */
    private static AdbConnection connection(Context c) throws IOException {
        if (conn != null && !conn.closed()) {
            return conn;
        }
        conn = null;
        AdbKey key;
        try {
            key = AdbKey.of(dir(c));
        } catch (GeneralSecurityException e) {
            throw new IOException("no adb key: " + e.getMessage(), e);
        }
        int last = prefs(c).getInt(PORT, -1);
        if (last > 0) {
            try {
                conn = AdbConnection.open(key, "127.0.0.1", last, 3_000);
                return conn;
            } catch (AdbConnection.NotPaired e) {
                throw e;
            } catch (IOException e) {
                // Wireless debugging was switched off and on since: another port now
            }
        }
        int port = AdbMdns.find(c, AdbMdns.CONNECT, 6_000);
        if (port <= 0) {
            throw new IOException(switchedOn(c) == 0 ? "Wireless debugging is off (Settings > Developer options)"
                    : "Wireless debugging's port was not found: is it on, and the phone on Wi-Fi?");
        }
        conn = AdbConnection.open(key, "127.0.0.1", port, 5_000);
        prefs(c).edit().putInt(PORT, port).apply();
        return conn;
    }

    /**
     * Runs @command in adb's shell on this phone and returns what it printed. Not on the UI thread: a first
     * call looks for adbd's port (up to 6 s). Throws when the app is not paired, the phone refuses the key, or
     * Wireless debugging is off.
     */
    static String shell(Context c, String command, int timeoutMs) throws IOException {
        if (!paired(c)) {
            throw new IOException("not paired");
        }
        synchronized (LOCK) {
            IOException failed = null;
            for (int i = 0; i < 2; i++) {
                try {
                    String out = connection(c).shell(command, timeoutMs);
                    lastError = null;
                    if (refused(c)) {
                        prefs(c).edit().putBoolean(REFUSED, false).apply();
                    }
                    return out;
                } catch (AdbConnection.NotPaired e) {
                    close();
                    prefs(c).edit().putBoolean(REFUSED, true).apply();
                    lastError = "This phone no longer accepts the app's key: pair again";
                    throw e;
                } catch (IOException e) {
                    // a connection from before Wireless debugging was switched off and on: once more, a new one
                    close();
                    failed = e;
                }
            }
            lastError = failed.getMessage();
            throw failed;
        }
    }

    /** One line for the screen: the pairing and the connection as they are now. */
    static String status(Context c) {
        if (!supported()) {
            return "Needs Android 11 or later";
        }
        if (!paired(c)) {
            return "Not paired";
        }
        if (refused(c)) {
            return "Paired, but the phone no longer accepts the key: pair again";
        }
        if (connected()) {
            return "Paired and connected";
        }
        if (switchedOn(c) == 0) {
            return "Paired; Wireless debugging is off now";
        }
        String e = lastError;
        return "Paired" + (e == null ? "" : "; not connected: " + e);
    }
}
