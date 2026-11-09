package com.asoft.ios;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;

import java.io.IOException;

/**
 * Pairing with this phone's Wireless debugging (WirelessAdb). Settings shows the six digits in a dialog that
 * closes, and its port with it, as soon as the user leaves that screen, so the code cannot be typed in the app:
 * it goes into this service's notification, which has a reply field (as Shizuku does it). The service looks for
 * the dialog's port (AdbMdns), asks for the code once it is there, pairs, and says how it went. It ends after
 * the pairing or ten minutes.
 */
public class WadbPairService extends Service {
    private static final String CHANNEL = "wadb", KEY_CODE = "code";
    private static final int ID = 4;
    private static final long GIVE_UP_MS = 10 * 60_000;

    /** What the pairing is doing, for the app's own screen; null when no pairing runs. */
    static volatile String state;

    private AdbMdns mdns;
    private volatile int port = -1;
    private volatile boolean pairing, done;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable giveUp = () -> finish("Pairing stopped: no code came in ten minutes. Start it again from the app.");

    static void start(Context c) {
        c.startForegroundService(new Intent(c, WadbPairService.class));
    }

    static boolean running() {
        return state != null;
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    private PendingIntent self(String action, int flags) {
        return PendingIntent.getService(this, action.hashCode(), new Intent(this, WadbPairService.class).setAction(action), flags);
    }

    /** The notification: @text, with the reply field when @ask. */
    private Notification notification(String text, boolean ask, boolean ongoing) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        // high: it has to come up over Settings' pairing dialog, where the user is
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Pairing with Wireless debugging", NotificationManager.IMPORTANCE_HIGH));
        Notification.Builder n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("ASoftware iOS: pairing")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(ongoing)
                .setOnlyAlertOnce(!ask)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        if (ask) {
            RemoteInput in = new RemoteInput.Builder(KEY_CODE).setLabel("Pairing code").build();
            // mutable: Android writes the typed code into it
            n.addAction(new Notification.Action.Builder(null, "Enter the code",
                    self("code", PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).addRemoteInput(in).build());
        }
        if (ongoing) {
            n.addAction(new Notification.Action.Builder(null, "Cancel", self("cancel", PendingIntent.FLAG_IMMUTABLE)).build());
        }
        return n.build();
    }

    private void show(String text, boolean ask) {
        state = text;
        startForeground(ID, notification(text, ask, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }

    private static final String SEARCHING = "Open Settings > Developer options > Wireless debugging and tap "
            + "\"Pair device with pairing code\". Stay on that screen: this notification then asks for its code.";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if ("cancel".equals(action)) {
            finish(null);
            return START_NOT_STICKY;
        }
        if ("code".equals(action)) {
            Bundle b = RemoteInput.getResultsFromIntent(intent);
            CharSequence typed = b == null ? null : b.getCharSequence(KEY_CODE);
            if (state == null) {
                show(SEARCHING, false);     // the service was gone (its notification is from another life): start over
                look();
            } else if (typed != null) {
                pair(typed.toString());
            }
            return START_NOT_STICKY;
        }
        done = false;
        show(SEARCHING, false);
        look();
        main.removeCallbacks(giveUp);
        main.postDelayed(giveUp, GIVE_UP_MS);
        return START_NOT_STICKY;
    }

    /** Watches for the pairing dialog's port; the notification asks for the code while there is one. */
    private void look() {
        if (mdns != null) {
            return;
        }
        mdns = new AdbMdns(this, AdbMdns.PAIRING, p -> main.post(() -> {
            if (done || pairing) {
                return;
            }
            port = p;
            if (p > 0) {
                show("The pairing dialog is open: type its six digits here (Enter the code).", true);
            } else {
                show(SEARCHING, false);
            }
        }));
        mdns.start();
    }

    /**
     * The reply: six digits, or the digits, a space and the port when the dialog was not found by itself (it
     * shows "IP address & Port").
     */
    private void pair(String typed) {
        String[] parts = typed.trim().split("[\\s:]+");
        String code = parts[0].replaceAll("\\D", "");
        int p = port;
        if (parts.length > 1) {
            try {
                p = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                // the found port then
            }
        }
        if (code.length() != 6) {
            show("The code has six digits. Type it again (Enter the code).", true);
            return;
        }
        if (p <= 0) {
            show("The pairing dialog was not found. Type the code, a space, and the port the dialog shows after "
                    + "the colon (as in 123456 37215).", true);
            return;
        }
        int usePort = p;
        pairing = true;
        show("Pairing…", false);
        new Thread(() -> {
            String said;
            boolean ok = false;
            try {
                WirelessAdb.pair(this, usePort, code);
                ok = true;
                MemoryCleaner.setEnabled(this, true);
                String out;
                try {
                    out = WirelessAdb.shell(this, "id -u", 15_000).trim();
                } catch (IOException e) {
                    out = null;
                    AppLog.note(this, "wireless adb: paired, but the first connection failed: " + e);
                }
                said = "2000".equals(out) ? "Paired and connected. The app now frees memory while iOS runs."
                        : "Paired. The connection did not open yet (" + WirelessAdb.status(this) + "); the app tries again when iOS runs.";
            } catch (AdbPairing.WrongCode e) {
                said = "That code did not match. Settings shows a new one now: tap \"Pair device with pairing code\" "
                        + "again and type the new code here.";
            } catch (IOException e) {
                AppLog.note(this, "wireless adb: pairing failed: " + e);
                said = "Pairing did not work (" + e.getMessage() + "). Open the pairing dialog again and type its code here.";
            }
            boolean paired = ok;
            String text = said;
            main.post(() -> {
                pairing = false;
                if (paired) {
                    finish(text);
                } else if (!done) {
                    port = -1;      // that dialog is gone; its successor is announced anew
                    show(text, false);
                }
            });
        }, "wadb-pair").start();
    }

    /** Ends the service; @last (or nothing) stays as an ordinary notification. */
    private void finish(String last) {
        done = true;
        state = null;
        main.removeCallbacks(giveUp);
        if (mdns != null) {
            mdns.stop();
            mdns = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (last != null) {
            getSystemService(NotificationManager.class).notify(ID + 1, notification(last, false, false));
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        state = null;
        main.removeCallbacks(giveUp);
        if (mdns != null) {
            mdns.stop();
            mdns = null;
        }
        super.onDestroy();
    }
}
