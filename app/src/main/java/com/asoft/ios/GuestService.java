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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            Guest.stop();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "iOS", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, ScreenActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, GuestService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("ASoftware iOS")
                .setContentText("iOS 16 is running")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .build();
        startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "asoft:guest");
            wakeLock.acquire();
        }
        // fork from the app's large process: not on the main thread
        new Thread(() -> {
            try {
                Guest.start(this);
            } catch (IOException e) {
                note("start failed: " + e);
                stopSelf();
            }
        }, "guest-start").start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Guest.stop();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    private void note(String s) {
        try (PrintWriter w = new PrintWriter(new FileWriter(new File(Guest.runDir(this), "app.txt"), true))) {
            w.println(s);
        } catch (IOException ignored) {
            // nowhere to say it
        }
    }

    static void start(Context c) {
        c.startForegroundService(new Intent(c, GuestService.class));
    }
}
