package com.asoft.ios;

import android.content.Context;
import android.os.PerformanceHintManager;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Experiment (debug builds, ios26.txt adpf=1; APK-PLAN §7c): an ADPF hint session (PerformanceHintManager) over
 * iOS 26's QEMU and its stub processes, reporting every frame period as twice the 16.6 ms target, so the power HAL
 * raises the clocks it can for those threads. During use the Adreno sat at 160-222 MHz in 81% of the samples while
 * 28% busy (APK-PLAN §6.20): whether a hint moves the GPU (WorkDuration's GPU time) or only the CPUs is what this
 * measures. The framework takes threads of other processes of the app's own uid (HintManagerService.checkTidValid);
 * QEMU and its stubs are the app's children.
 */
final class HintBoost {
    private HintBoost() {}

    static final long TARGET_NS = 16_666_666L;

    static void start(Context c, int pid) {
        if (!"1".equals(Guest.overrides26().get("adpf"))) {
            return;
        }
        Thread t = new Thread(() -> run(c.getApplicationContext(), pid), "adpf");
        t.setDaemon(true);
        t.start();
    }

    /** The threads of @pid and of every process below it (the stub factory and its stubs). */
    private static int[] threads(int pid) {
        List<Integer> procs = new ArrayList<>();
        procs.add(pid);
        File[] all = new File("/proc").listFiles();
        for (boolean grew = true; grew && all != null; ) {
            grew = false;
            for (File p : all) {
                int n;
                try {
                    n = Integer.parseInt(p.getName());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (procs.contains(n)) {
                    continue;
                }
                try {
                    for (String l : Files.readAllLines(new File(p, "status").toPath())) {
                        if (l.startsWith("PPid:") && procs.contains(Integer.parseInt(l.substring(5).trim()))) {
                            procs.add(n);
                            grew = true;
                        }
                    }
                } catch (Exception ignored) {
                    // gone, or not ours
                }
            }
        }
        List<Integer> tids = new ArrayList<>();
        for (int p : procs) {
            String[] ts = new File("/proc/" + p + "/task").list();
            if (ts != null) {
                for (String s : ts) {
                    tids.add(Integer.parseInt(s));
                }
            }
        }
        int[] r = new int[tids.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = tids.get(i);
        }
        return r;
    }

    private static void run(Context c, int pid) {
        try {
            Thread.sleep(30_000);       // the stubs exist once EL0 runs natively (ASOFT_NATIVE_EL0_DELAY_MS)
        } catch (InterruptedException e) {
            return;
        }
        PerformanceHintManager phm = c.getSystemService(PerformanceHintManager.class);
        if (phm == null) {
            AppLog.note(c, "adpf: no PerformanceHintManager");
            return;
        }
        int[] tids = threads(pid);
        PerformanceHintManager.Session s;
        try {
            s = phm.createHintSession(tids, TARGET_NS);
        } catch (RuntimeException e) {
            AppLog.note(c, "adpf: createHintSession(" + tids.length + " threads) refused: " + e);
            return;
        }
        if (s == null) {
            AppLog.note(c, "adpf: no hint session for " + tids.length + " threads (unsupported)");
            return;
        }
        AppLog.note(c, "adpf: hint session over " + tids.length + " threads of QEMU " + pid + " and its stubs, "
                + "preferred rate " + phm.getPreferredUpdateRateNanos() / 1000 + " us");
        int rounds = 0;
        try {
            while (Guest.running() && Guest.pid(c) == pid) {
                long now = System.nanoTime();
                if (android.os.Build.VERSION.SDK_INT >= 35) {
                    android.os.WorkDuration w = new android.os.WorkDuration();
                    w.setWorkPeriodStartTimestampNanos(now - 2 * TARGET_NS);
                    w.setActualTotalDurationNanos(2 * TARGET_NS);
                    w.setActualCpuDurationNanos(2 * TARGET_NS);
                    w.setActualGpuDurationNanos(2 * TARGET_NS);
                    s.reportActualWorkDuration(w);
                } else {
                    s.reportActualWorkDuration(2 * TARGET_NS);
                }
                if (++rounds % 600 == 0) {      // threads come and go (stubs, Turnip): every ~10 s
                    s.setThreads(threads(pid));
                }
                Thread.sleep(16);
            }
        } catch (Exception e) {
            AppLog.note(c, "adpf: stopped: " + e);
        } finally {
            s.close();
        }
    }
}
