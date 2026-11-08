package com.asoft.ios;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.system.Os;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * One GPU probe (gpucheck.c) in this app's :gpucheck process, which ends with it: the Vulkan loader and
 * driver it loads, and a driver crash, stay out of the app's own process. Action "turnip": the app's
 * Khronos loader with Turnip's ICD (what iOS 26's QEMU uses); "system": the phone's own libvulkan.so.
 * GpuCheck starts it and reads the log it writes.
 */
public class GpuCheckService extends Service {
    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String which = intent != null && "system".equals(intent.getAction()) ? "system" : "turnip";
        new Thread(() -> probe(which), "gpucheck").start();
        return START_NOT_STICKY;
    }

    private void probe(String which) {
        File dir = GpuCheck.dir(this);
        dir.mkdirs();
        File log = new File(dir, which + ".log");
        int rc = 9;
        try (PrintWriter w = new PrintWriter(new FileWriter(new File(dir, which + ".pid")))) {
            w.println(android.os.Process.myPid());
        } catch (IOException ignored) {
            // GpuCheck then waits for the log's result line alone
        }
        try {
            String loader;
            if ("turnip".equals(which)) {
                File links = Emu.linkLibraries(this);
                File icd = Guest.writeVulkanIcd(this, links);
                // before the loader is loaded: it reads them when the instance is made
                Os.setenv("VK_DRIVER_FILES", icd.getPath(), true);
                Os.setenv("VK_ICD_FILENAMES", icd.getPath(), true);
                loader = new File(links, "libvulkan.so").getPath();
                if (!new File(loader).exists()) {
                    throw new IOException("this build has no Vulkan loader (" + loader + ")");
                }
            } else {
                loader = "libvulkan.so";
            }
            System.loadLibrary("gpucheck");
            rc = GpuCheck.probe(loader, log.getPath());
        } catch (IOException | android.system.ErrnoException | UnsatisfiedLinkError e) {
            try (PrintWriter w = new PrintWriter(new FileWriter(log, true))) {
                w.println("error: " + e);
                w.println("result: fail");
            } catch (IOException ignored) {
                // nothing to tell
            }
        }
        try (PrintWriter w = new PrintWriter(new FileWriter(new File(dir, which + ".exit")))) {
            w.println(rc);
        } catch (IOException ignored) {
            // the log's result line says it
        }
        stopSelf();
        // a loader and a driver loaded once per process: the next probe gets a new one
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
