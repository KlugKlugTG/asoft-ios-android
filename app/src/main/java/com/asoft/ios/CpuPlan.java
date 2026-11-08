package com.asoft.ios;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * How many vCPUs iOS gets and which of the phone's CPUs run them: four, on any core, on every
 * phone. The kernel's ranking of its cores (cpu_capacity, 1024 = the fastest; "fast" = 60% of the
 * best or more) goes to the app's log only. Measured on the A71 (2026-11-05, power saving on): two
 * Cortex-A76 (1024) and six A55 (492); four vCPUs anywhere reached the lock screen in ~21 min, two
 * vCPUs pinned to the A76 (with the native EL0 stubs) had not left the boot logo after 30 min.
 * XNU runs under TCG there 75-79% of the vCPUs' time, so vCPUs in parallel count for more than
 * fast cores. A saved state keeps the count it was saved with (Guest.savedCpus).
 * cpu.txt in the app's external folder overrides it for tests: "smp=2 pin=6,7" ("pin=" none).
 */
final class CpuPlan {
    final int smp;
    /** The host CPUs for the vCPU threads and the stubs ("6,7"), empty for any. */
    final String pin;
    final String why;

    private CpuPlan(int smp, String pin, String why) {
        this.smp = smp;
        this.pin = pin;
        this.why = why;
    }

    static File overrideFile(Context c) {
        return new File(c.getExternalFilesDir(null), "cpu.txt");
    }

    static CpuPlan forThisPhone(Context c) {
        CpuPlan auto = auto();
        try {
            String t = new String(Files.readAllBytes(overrideFile(c).toPath())).trim();
            int smp = auto.smp;
            String pin = auto.pin;
            for (String w : t.split("\\s+")) {
                if (w.matches("smp=[1-8]")) {
                    smp = Integer.parseInt(w.substring(4));
                } else if (w.matches("pin=[0-9,]*")) {
                    pin = w.substring(4);
                }
            }
            return new CpuPlan(smp, pin, "cpu.txt \"" + t + "\" (automatic: " + auto.smp + " vCPUs, pin "
                    + (auto.pin.isEmpty() ? "none" : auto.pin) + ")");
        } catch (IOException e) {
            return auto;        // no test file
        }
    }

    private static CpuPlan auto() {
        List<Integer> cap = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            File d = new File("/sys/devices/system/cpu/cpu" + i);
            if (!d.isDirectory()) {
                break;
            }
            cap.add(readInt(new File(d, "cpu_capacity")));
        }
        int best = 0;
        for (int v : cap) {
            best = Math.max(best, v);
        }
        if (cap.isEmpty() || best <= 0) {
            return new CpuPlan(4, "", "no cpu_capacity: 4 vCPUs, any core");
        }
        StringBuilder fast = new StringBuilder();
        int n = 0;
        for (int i = 0; i < cap.size(); i++) {
            if (cap.get(i) * 10 >= best * 6) {
                fast.append(n++ > 0 ? "," : "").append(i);
            }
        }
        return new CpuPlan(4, "", n + " of " + cap.size() + " cores fast (capacity " + cap + "), fast: " + fast);
    }

    private static int readInt(File f) {
        try {
            return Integer.parseInt(new String(Files.readAllBytes(f.toPath())).trim());
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }
}
