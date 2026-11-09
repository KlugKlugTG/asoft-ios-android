package com.asoft.ios;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Can this phone's GPU run iOS 26 and watchOS? Asked before their download (they draw through reims-vgpu
 * and metal2vulkan on Vulkan; there is no GPU-less mode on the phone). The app's own Turnip is probed in
 * GpuCheckService (gpucheck.c: the device, its extensions and features, a compute dispatch, a render pass,
 * a buffer over host memory and sparse binds from host memory); a pass is remembered for this build of
 * the app and its Turnip. When Turnip fails, or the phone has no Adreno (Turnip drives Adreno GPUs only),
 * the phone's own Vulkan driver is probed too, for the log: what it lacks is what support for it
 * (Mali, say) would need. iOS 16 does not use the GPU and is not gated.
 */
final class GpuCheck {
    private GpuCheck() {}

    static native int probe(String loader, String log);

    static File dir(Context c) {
        return new File(c.getFilesDir(), "gpucheck");
    }

    /** An Adreno GPU (Qualcomm's KGSL device): the kind Turnip drives. */
    static boolean adreno() {
        return new File("/dev/kgsl-3d0").exists();
    }

    /** What a pass is good for: this app build and its Turnip (a new build checks again). */
    private static String key(Context c) {
        long turnip = new File(c.getApplicationInfo().nativeLibraryDir, "lib_libvulkan_freedreno.so.so").length();
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode() + "/" + turnip;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "?/" + turnip;
        }
    }

    /**
     * Debug builds: a check without Turnip, to see the unsupported-GPU screen and its log on a phone that passes
     * (adb: run-as PACKAGE touch files/gpucheck-no-turnip, then Check again; rm it to go back).
     */
    static boolean turnipOff(Context c) {
        return (c.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && new File(c.getFilesDir(), "gpucheck-no-turnip").exists();
    }

    /** The check passed for this build: iOS 26 and watchOS may be downloaded. */
    static boolean passed(Context c) {
        return !turnipOff(c) && key(c).equals(c.getSharedPreferences("setup", Context.MODE_PRIVATE).getString("gpucheck-pass", ""));
    }

    /** The last check's outcome, for the screen. */
    static final class Result {
        boolean pass;
        boolean ran;
        String summary = "";            // one line for people
        final List<String> missing = new ArrayList<>();
        String report = "";             // the whole log, to copy or share
        /** When it did not pass: true = will not run on this phone with its driver; false = not supported yet. */
        boolean never;
        /** Why, in plain words (what decided @never, then what else is missing). */
        final List<String> why = new ArrayList<>();
        /** A note that is not a reason by itself (a CPU without what the fast engine needs), or "". */
        String slow = "";
        long ram;                       // the phone's RAM (MemoryInfo.totalMem)
    }

    /*
     * Below these (MemoryInfo.totalMem; an "8 GB" phone reports ~7.3-7.6 GB) Set up warns that Android may close
     * iOS and points to a smaller guest memory in their menu on the home screen (Guest.ramMiB); it does not refuse. The guest's RAM
     * (iOS 26 4 GiB by default on a 12 GB phone, the watch 3 GiB) plus QEMU, Turnip and reims (~1.5 GB on the
     * S25) plus Android itself; estimates, not measured on such phones (2026-11-09).
     */
    static final long RAM_IOS26 = 7_000_000_000L, RAM_WATCH = 5_000_000_000L;

    /** Set up's warning for this phone's RAM, or "". */
    static String ramWarning(Context c) {
        long r = ram(c);
        if (r >= RAM_IOS26) {
            return "";
        }
        return String.format(Locale.ROOT, "This phone has %.1f GB of RAM: Android may close iOS 26%s while it runs. "
                + "A smaller memory for them (their menu on the home screen; iOS 26: 3 GB, watchOS: 2 GB) leaves Android more.",
                r / 1e9, r < RAM_WATCH ? " and watchOS" : "");
    }

    static long ram(Context c) {
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        c.getSystemService(android.app.ActivityManager.class).getMemoryInfo(mi);
        return mi.totalMem;
    }

    /** What our side could add for a driver that lacks it (m2v's dma-buf import, copies instead of sparse binds). */
    private static String coverable(String lack) {
        if (lack.contains("external_memory_host")) {
            return "importing the app's memory into the GPU (VK_EXT_external_memory_host; a dma-buf route could replace it)";
        }
        if (lack.startsWith("sparse") || lack.contains("sparse")) {
            return "sparse memory binding (copies could replace it)";
        }
        return null;
    }

    /** A lack that rules this phone out with its current driver, in plain words, or null. */
    private static String fatal(String lack) {
        if (lack.startsWith("Vulkan 1.2")) {
            return "Vulkan 1.2";     // judge() says which one the driver has
        }
        if (lack.equals("descriptorIndexing") || lack.equals("runtimeDescriptorArray") || lack.equals("descriptorBindingPartiallyBound")) {
            return "descriptor indexing, which the Metal translation's argument buffers need";   // one line for the three
        }
        return null;
    }

    /**
     * Sorts a failed check: will not run here (the phone's driver fails the basic tests, has no Vulkan 1.2 or no
     * descriptor indexing, or the phone has too little RAM), or not supported yet (it lacks only what our side could
     * add: host-memory import, sparse binding; or Turnip, ours, fails on an Adreno).
     */
    private static void judge(Context c, Result r, List<String> sysLines, boolean adrenoGpu) {
        r.ram = ram(c);
        if (!Guest.nativeMissing().isEmpty()) {
            r.slow = "The CPU lacks what the fast engine needs (" + Guest.nativeMissing() + "): it would run very slowly.";
        }
        if (r.pass) {
            return;
        }
        List<String> never = new ArrayList<>(), later = new ArrayList<>(), other = new ArrayList<>();
        boolean compute = false, render = false, ended = false, sawDevice = false;
        String api = "";
        for (String l : sysLines) {
            if (l.startsWith("compute: ")) {
                compute = l.startsWith("compute: 0 of ");
            } else if (l.startsWith("render: ")) {
                render = l.startsWith("render: 0 of ");
            } else if (l.startsWith("device api: ")) {
                api = l.substring(12);
            } else if (l.startsWith("device: vkCreateDevice")) {
                sawDevice = true;
            } else if (l.startsWith("result: ")) {
                ended = true;
            } else if (l.startsWith("lacks: ")) {
                String lack = l.substring(7);
                String f = fatal(lack), k = coverable(lack);
                if (f != null) {
                    if (!never.contains(f)) {
                        never.add(f);
                    }
                } else if (k != null) {
                    later.add(k);
                } else {
                    other.add(lack);
                }
            }
        }
        if (adrenoGpu) {
            // Turnip is the app's own driver: what it lacks or fails on an Adreno is ours to fix
            r.never = false;
            r.why.add("the app's Turnip driver does not run iOS 26 on this Adreno yet");
            r.why.addAll(r.missing);
            return;
        }
        if (!ended || !sawDevice) {
            never.add(0, "a working Vulkan driver (the phone's driver " + (ended ? "made no device" : "stopped the test") + ")");
        } else if (!compute || !render) {
            never.add(0, "a driver that passes the basic tests (" + (!compute ? "compute" : "") + (!compute && !render ? " and " : "")
                    + (!render ? "drawing" : "") + " gave wrong results or did not run)");
        }
        int v12 = never.indexOf("Vulkan 1.2");
        boolean old = !api.isEmpty() && !api.matches("Vulkan 1\\.([2-9]|\\d\\d).*");
        if (v12 >= 0 || old) {
            String s = "Vulkan 1.2 (the driver has " + (api.isEmpty() ? "an older one" : api) + ")";
            if (v12 >= 0) {
                never.set(v12, s);
            } else {
                never.add(s);
            }
        }
        // anything else a required feature lacks is not ours to add either
        r.never = !never.isEmpty() || !other.isEmpty();
        r.why.addAll(never);
        r.why.addAll(other);
        for (String k : later) {
            if (!r.why.contains(k)) {
                r.why.add(k);
            }
        }
    }

    static volatile boolean running;

    /** Runs the check (blocking, off the main thread): Turnip, then the system driver when it is needed. */
    static Result run(Context c) {
        running = true;
        try {
            File d = dir(c);
            Installer.deleteTree(d);
            d.mkdirs();
            boolean pass = false;
            String turnipEnd;
            if (turnipOff(c)) {
                turnipEnd = "skipped (Turnip turned off for a test)";
                write(new File(d, "turnip.log"), "error: Turnip is turned off for a test (debug flag files/gpucheck-no-turnip)\nresult: fail\n");
            } else if (!adreno()) {
                turnipEnd = "skipped (no Adreno GPU: Turnip drives Adreno GPUs only)";
                write(new File(d, "turnip.log"), "error: no /dev/kgsl-3d0: this phone has no Adreno GPU, which the app's Turnip driver needs\nresult: fail\n");
            } else {
                turnipEnd = once(c, "turnip");
                pass = "pass".equals(turnipEnd);
            }
            if (!pass) {
                once(c, "system");
            }
            Result r = last(c);
            c.getSharedPreferences("setup", Context.MODE_PRIVATE).edit().putString("gpucheck-pass", r.pass ? key(c) : "").apply();
            AppLog.note(c, "gpu check: " + (r.pass ? "pass" : (r.never ? "will not run here: " : "not supported yet: ") + String.join("; ", r.why))
                    + " (turnip " + turnipEnd + ")");
            return r;
        } finally {
            running = false;
        }
    }

    private static void write(File f, String s) {
        try {
            Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // the report says the log is missing
        }
    }

    /** One probe in the :gpucheck process: "pass", "fail", or how it ended without a result. */
    private static String once(Context c, String which) {
        File d = dir(c), log = new File(d, which + ".log"), pid = new File(d, which + ".pid");
        c.startService(new Intent(c, GpuCheckService.class).setAction(which));
        long t0 = System.currentTimeMillis();
        boolean seenPid = false;
        while (System.currentTimeMillis() - t0 < 90_000) {
            String res = resultOf(log);
            if (res != null) {
                return res;
            }
            if (pid.isFile()) {
                seenPid = true;
                try {
                    String p = new String(Files.readAllBytes(pid.toPath()), StandardCharsets.US_ASCII).trim();
                    if (!p.isEmpty() && !new File("/proc/" + p).exists()) {
                        // gone without a result: the driver crashed the probe (its last line says where)
                        appendLine(log, "error: the probe's process ended without a result (the driver crashed it)");
                        appendLine(log, "result: fail");
                        return "crashed";
                    }
                } catch (IOException ignored) {
                    // being written
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "stopped";
            }
        }
        appendLine(log, "error: no result in 90 s" + (seenPid ? " (the driver hangs)" : " (the probe did not start)"));
        appendLine(log, "result: fail");
        return "timeout";
    }

    private static void appendLine(File f, String s) {
        try {
            Files.write(f.toPath(), (s + "\n").getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // nothing to tell
        }
    }

    private static String resultOf(File log) {
        try {
            for (String l : Files.readAllLines(log.toPath())) {
                if (l.startsWith("result: ")) {
                    return l.substring(8).trim();
                }
            }
        } catch (IOException ignored) {
            // not there yet
        }
        return null;
    }

    private static String prop(String name) {
        try {
            Process p = new ProcessBuilder("getprop", name).redirectErrorStream(true).start();
            String v = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            return v;
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }

    /** The last check's logs as a report, and what it found. */
    static Result last(Context c) {
        Result r = new Result();
        File d = dir(c);
        StringBuilder rep = new StringBuilder();
        rep.append("ASoftware iOS GPU check\n");
        String version = "?";
        try {
            version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            // the report goes without it
        }
        rep.append("app: ").append(c.getPackageName()).append(' ').append(version).append('\n');
        rep.append("phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" (").append(Build.DEVICE)
                .append(", ").append(Build.PRODUCT).append(")\n");
        rep.append("android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append("), build ")
                .append(Build.DISPLAY).append('\n');
        String soc = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL : "";
        rep.append("soc: ").append(soc).append(" / board ").append(Build.BOARD).append(" / hardware ").append(Build.HARDWARE)
                .append(" / ro.soc.model ").append(prop("ro.soc.model")).append('\n');
        rep.append("gpu device: ").append(adreno() ? "/dev/kgsl-3d0 (Adreno)" : new File("/dev/mali0").exists() ? "/dev/mali0 (Mali)" : "neither kgsl nor mali")
                .append(", ro.hardware.vulkan ").append(prop("ro.hardware.vulkan")).append(", ro.hardware.egl ").append(prop("ro.hardware.egl")).append('\n');
        rep.append("cpu: ").append(Build.SUPPORTED_ABIS[0]).append(", ").append(Runtime.getRuntime().availableProcessors())
                .append(" cores; native engine needs: ").append(Guest.nativeMissing().isEmpty() ? "all there" : Guest.nativeMissing()).append('\n');
        rep.append("date: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new java.util.Date())).append('\n');
        String gpu = "", driver = "";
        List<String> sysLines = new ArrayList<>();
        for (String which : new String[] {"turnip", "system"}) {
            File log = new File(d, which + ".log");
            if (!log.isFile()) {
                continue;
            }
            r.ran = true;
            rep.append("\n== ").append("turnip".equals(which) ? "the app's Turnip driver (what iOS 26 uses)" : "the phone's own Vulkan driver (for the log)").append('\n');
            try {
                for (String l : Files.readAllLines(log.toPath())) {
                    rep.append(l).append('\n');
                    if (!"turnip".equals(which)) {
                        sysLines.add(l);
                        if (l.startsWith("gpu: ") && gpu.isEmpty()) {
                            gpu = l.substring(5);
                        }
                        continue;
                    }
                    if (l.startsWith("result: ")) {
                        r.pass = l.endsWith("pass");
                    } else if (l.startsWith("lacks: ")) {
                        r.missing.add(l.substring(7));
                    } else if (l.startsWith("gpu: ")) {
                        gpu = l.substring(5);
                    } else if (l.startsWith("driver: ")) {
                        driver = l.substring(8);
                    } else if (l.startsWith("error: ") && r.missing.isEmpty()) {
                        r.missing.add(l.substring(7));
                    }
                }
            } catch (IOException e) {
                rep.append("(cannot read: ").append(e.getMessage()).append(")\n");
            }
        }
        if (r.ran) {
            judge(c, r, sysLines, adreno() && !turnipOff(c));
            rep.append("\n== verdict\n");
            rep.append(String.format(Locale.ROOT, "ram: %.2f GB (a warning below %.1f for iOS 26, %.1f for watchOS; guest memory iOS 26 %d MiB, watchOS %d MiB)%n",
                    r.ram / 1e9, RAM_IOS26 / 1e9, RAM_WATCH / 1e9, Guest.ramMiB(c, Guest.IOS26), Guest.ramMiB(c, Guest.WATCH)));
            rep.append("result: ").append(r.pass ? "supported" : r.never ? "will not run on this phone (with its current driver)" : "not supported yet")
                    .append('\n');
            for (String w : r.why) {
                rep.append("needs: ").append(w).append('\n');
            }
            if (!r.slow.isEmpty()) {
                rep.append("note: ").append(r.slow).append('\n');
            }
        }
        r.report = rep.toString();
        String name = gpu.isEmpty() ? "" : " (" + gpu + ")";
        r.summary = r.pass ? "Your GPU" + name + " can run iOS 26 and watchOS." + (driver.isEmpty() ? "" : " Driver: " + driver)
                : r.never ? "iOS 26 and watchOS will not run on this phone" + name + " with its current graphics driver."
                : "This phone's GPU" + name + " is not supported yet.";
        return r;
    }
}
