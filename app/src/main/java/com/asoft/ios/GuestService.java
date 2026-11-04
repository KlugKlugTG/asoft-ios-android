package com.asoft.ios;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/** Keeps iOS's QEMU in the foreground while it runs, whether or not its screen is open. */
public class GuestService extends Service {
    private static final String CHANNEL = "guest";
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    /** The ongoing notification; every start command (re)posts it: startForegroundService wants that. */
    private void foreground(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "iOS", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, ScreenActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent save = PendingIntent.getService(this, 1,
                new Intent(this, GuestService.class).setAction("save"), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("ASoftware iOS")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true);
        if (!Guest.saving()) {
            n.addAction(new Notification.Action.Builder(null, "Save and close", save).build());
        }
        startForeground(2, n.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if ("save".equals(action) || "stop".equals(action)) {
            // "stop" is the notification's old action: it saves too
            foreground("Saving iOS");
            saveThenStop();
            return START_NOT_STICKY;
        }
        if ("off".equals(action)) {     // power off without saving (adb tests)
            foreground("Turning iOS off");
            Guest.stop();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        // quick boot by default; "cold": a boot; "load": the saved state named in "tag", instead of the running iOS
        boolean load = "load".equals(action);
        String loadTag = load ? intent.getStringExtra("tag")
                : intent != null && intent.getBooleanExtra("cold", false) ? null : Guest.QUICK;
        foreground("iOS 16 is running");
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "asoft:guest");
            wakeLock.acquire();
        }
        // fork from the app's large process: not on the main thread
        new Thread(() -> {
            try {
                if (load && Guest.running()) {
                    Guest.state = "switching";
                    Guest.stopAndWait();
                }
                Guest.start(this, loadTag);
                if (Guest.resumed && !resumedOk()) {
                    // a state this QEMU cannot load (another build, other options): boot instead;
                    // the quick-boot state goes, a save the user named stays
                    note("the saved state " + loadTag + " did not load (" + Guest.lastWords(this) + "); booting");
                    Guest.stop();
                    if (Guest.QUICK.equals(loadTag)) {
                        Guest.dropQuick(this);
                    }
                    Guest.start(this, null);
                }
            } catch (IOException e) {
                note("start failed: " + e);
                stopSelf();
            }
        }, "guest-start").start();
        return START_NOT_STICKY;
    }

    /** After a start from the saved state: true once iOS runs, false if QEMU gave up. */
    private boolean resumedOk() {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 15 * 60_000) {
            if (!Guest.running()) {
                return false;
            }
            Qmp q = Guest.qmp();
            if (q != null) {
                String r = q.call("{\"execute\":\"query-status\"}", 10_000);
                if (r != null && r.contains("\"running\"")) {
                    note("resumed in " + (System.currentTimeMillis() - t0) + " ms");
                    Guest.state = "running";
                    return true;
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return true;
            }
        }
        return true;
    }

    private void saveThenStop() {
        new Thread(() -> {
            note("save: " + Guest.saveAndQuit(this));
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }, "guest-save").start();
    }

    /** Swiped away from Recents: keep iOS as it is for the next start, like closing the window on Windows. */
    @Override
    public void onTaskRemoved(Intent root) {
        if (Guest.running() && !Guest.saving()) {
            saveThenStop();
        }
    }

    @Override
    public void onDestroy() {
        Guest.stop();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    void note(String s) {
        try (PrintWriter w = new PrintWriter(new FileWriter(new File(Guest.runDir(this), "app.txt"), true))) {
            w.println(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date()) + " " + s);
        } catch (IOException ignored) {
            // nowhere to say it
        }
    }

    static void start(Context c) {
        c.startForegroundService(new Intent(c, GuestService.class));
    }

    /** Boots iOS even when a saved state exists (the state stays until the next save). */
    static void startCold(Context c) {
        c.startForegroundService(new Intent(c, GuestService.class).putExtra("cold", true));
    }

    /** Replaces the running iOS (what happened since its last save is lost) with the save @tag. */
    static void load(Context c, String tag) {
        c.startForegroundService(new Intent(c, GuestService.class).setAction("load").putExtra("tag", tag));
    }

    static void save(Context c) {
        c.startForegroundService(new Intent(c, GuestService.class).setAction("save"));
    }
}
