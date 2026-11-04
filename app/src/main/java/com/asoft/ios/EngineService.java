package com.asoft.ios;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Keeps a QEMU child alive in the foreground. This build has one job: el0bench, the native EL0
 * engine's test guest, from a normal app process (everything before ran under run-as).
 * Extras: stubs (int, 0 = in-process mirror), smp (int), seconds (int).
 */
public class EngineService extends Service {
    static final String LOG = "el0bench.log";
    private static final String CHANNEL = "engine";

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Emulator", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("ASoftware iOS")
                .setContentText("Engine test")
                .setOngoing(true)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        int stubs = intent == null ? 2 : intent.getIntExtra("stubs", 2);
        int smp = intent == null ? 2 : intent.getIntExtra("smp", 2);
        int seconds = intent == null ? 120 : intent.getIntExtra("seconds", 120);
        new Thread(() -> {
            runBench(stubs, smp, seconds);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }, "engine").start();
        return START_NOT_STICKY;
    }

    private void runBench(int stubs, int smp, int seconds) {
        File logFile = new File(getExternalFilesDir(null), LOG);
        PowerManager.WakeLock wl = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "asoft:engine");
        wl.acquire(TimeUnit.SECONDS.toMillis(seconds + 30));
        try (PrintWriter log = new PrintWriter(new FileWriter(logFile), true)) {
            log.println("uid " + android.os.Process.myUid() + " sdk " + android.os.Build.VERSION.SDK_INT
                    + " target " + getApplicationInfo().targetSdkVersion + " qemu " + Emu.qemuPath(this));
            File links = Emu.linkLibraries(this);
            File elf = Emu.copyAsset(this, "el0bench.elf");
            List<String> cmd = new ArrayList<>(List.of(Emu.qemuPath(this),
                    "-M", "virt,memory-backend=m0",
                    "-object", "memory-backend-memfd,id=m0,size=512M,share=on",
                    "-cpu", "max,pauth-impdef=on", "-smp", String.valueOf(smp), "-m", "512",
                    "-accel", "tcg,thread=multi",
                    "-kernel", elf.getPath(),
                    "-nographic", "-monitor", "none", "-serial", "stdio", "-nic", "none", "-no-reboot"));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            Map<String, String> env = pb.environment();
            Emu.baseEnv(this, links, env);
            env.put("ASOFT_NATIVE_EL0", "1");
            env.put("ASOFT_NATIVE_EL0_STATS", "1");
            if (stubs > 0) {
                env.put("ASOFT_NATIVE_EL0_STUBS", String.valueOf(stubs));
            }
            Process p = pb.start();
            Thread killer = new Thread(() -> {
                try {
                    if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
                        log.println("timed out after " + seconds + " s");
                        p.destroyForcibly();
                    }
                } catch (InterruptedException ignored) {
                    p.destroyForcibly();
                }
            });
            killer.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log.println(line);
                }
            }
            log.println("exit " + p.waitFor());
            log.println("FINISHED");
        } catch (IOException | InterruptedException e) {
            try (PrintWriter log = new PrintWriter(new FileWriter(logFile, true), true)) {
                log.println("failed: " + e);
                log.println("FINISHED");
            } catch (IOException ignored) {
                // nowhere left to say it
            }
        } finally {
            if (wl.isHeld()) {
                wl.release();
            }
        }
    }

    static void start(Context c, int stubs, int smp, int seconds) {
        c.startForegroundService(new Intent(c, EngineService.class)
                .putExtra("stubs", stubs).putExtra("smp", smp).putExtra("seconds", seconds));
    }
}
