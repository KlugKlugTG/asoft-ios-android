package com.asoft.ios;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * iOS 26's automatic quick saves, so that a start after a crash goes on from a recent, settled iOS:
 * <ul>
 * <li><b>Settled.</b> After a boot iOS works through its own start-up for ~8-15 minutes with every vCPU busy
 * (APK-PLAN §6.20). Once QEMU's EL sampler shows it mostly idle (two reports in a row with the vCPUs halted at
 * least 60% of the time, at least 5 minutes after the start) and nobody is looking (iOS's display is off, or no
 * touch for 2 minutes), the state becomes the quick save. A start from a save skips this: it came back settled.</li>
 * <li><b>Mappings.</b> reims' views of iOS's graphics memory take a kernel mapping per 16 KiB page, and Android
 * ends the process at 65,530 (§6.19); a few apps opened in a row took it from ~19,000 to 47,000. At 50,000 the
 * state is saved at once (iOS pauses for it, ~5-8 s): the crash that may follow loses little. Once more only
 * after the count fell below 40,000.</li>
 * </ul>
 * The mapping count is logged as it passes each 10,000, for the reports of crashes. Debug builds take
 * {@code autosave-maps=N} and {@code autosave-settle-s=S} from ios26.txt (tests).
 */
final class AutoSave {
    private AutoSave() {}

    static final int MAPS_SAVE = 50_000, MAPS_REARM = 40_000, SETTLE_MIN_S = 300;
    private static final Pattern EL = Pattern.compile("el-sample t=(\\d+)s all: halted +([\\d.]+)%");
    /** The QEMU being watched; a new start replaces the watcher. */
    private static volatile int watching = -1;

    static void start(Context c, int pid, boolean resumed) {
        Context app = c.getApplicationContext();
        watching = pid;
        Thread t = new Thread(() -> run(app, pid, resumed), "auto-save");
        t.setDaemon(true);
        t.start();
    }

    private static int intOr(String s, int d) {
        try {
            return s == null ? d : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return d;
        }
    }

    private static void run(Context c, int pid, boolean resumed) {
        Map<String, String> ov = Guest.overrides26();
        int mapsSave = intOr(ov.get("autosave-maps"), MAPS_SAVE);
        int mapsRearm = (int) ((long) mapsSave * MAPS_REARM / MAPS_SAVE);
        int settleMinS = intOr(ov.get("autosave-settle-s"), SETTLE_MIN_S);
        boolean settledDone = resumed;
        boolean mapsArmed = true;
        long lastSave = 0;
        int bucket = 1;
        File maps = new File("/proc/" + pid + "/maps");
        File stderr = new File(Guest.runDir(c), "qemu-stderr.txt");
        AppLog.note(c, "auto save: watching iOS 26 (pid " + pid + "; mappings now " + mapCount(maps) + ", a save at "
                + mapsSave + (settledDone ? "" : ", and one once iOS has settled") + ")");
        while (watching == pid) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                return;
            }
            if (!Guest.running() || Guest.pid(c) != pid) {
                return;
            }
            if (Guest.saving() || Guest.turningOff() || "switching".equals(Guest.state)) {
                continue;
            }
            int m = mapCount(maps);
            if (m >= (bucket + 1) * 10_000) {
                bucket = m / 10_000;
                AppLog.note(c, "auto save: iOS 26's process holds " + m + " kernel mappings (Android ends it at 65,530)");
            }
            if (m >= 0 && m < mapsRearm) {
                mapsArmed = true;
            }
            long now = SystemClock.elapsedRealtime();
            String why = null;
            if (mapsArmed && m >= mapsSave && now - lastSave > 60_000) {
                why = m + " kernel mappings";
                mapsArmed = false;
                toast(c, "iOS's graphics memory is close to Android's limit: saving iOS now, in case it stops");
            } else if (!settledDone && settled(stderr, settleMinS) && unwatched(c)) {
                why = "iOS has settled after its boot";
                settledDone = true;
            }
            if (why == null) {
                continue;
            }
            String r = Guest.saveNamed(c, Guest.QUICK);
            lastSave = SystemClock.elapsedRealtime();
            AppLog.note(c, "auto save (" + why + "): " + (r == null ? "saved in " + (lastSave - now) + " ms" : r));
        }
    }

    /** The lines of /proc/PID/maps (one per kernel mapping), -1 when it cannot be read. */
    static int mapCount(File maps) {
        byte[] b = new byte[1 << 16];
        int lines = 0;
        try (FileInputStream in = new FileInputStream(maps)) {
            for (int n; (n = in.read(b)) > 0; ) {
                for (int i = 0; i < n; i++) {
                    if (b[i] == '\n') {
                        lines++;
                    }
                }
            }
            return lines;
        } catch (IOException e) {
            return -1;
        }
    }

    /** QEMU's last two EL sampler reports (every 30 s) both have the vCPUs at least 60% halted, @minS after the start. */
    static boolean settled(File stderr, int minS) {
        String tail;
        try (RandomAccessFile f = new RandomAccessFile(stderr, "r")) {
            long len = f.length(), from = Math.max(0, len - 65536);
            byte[] b = new byte[(int) (len - from)];
            f.seek(from);
            f.readFully(b);
            tail = new String(b, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            return false;
        }
        Matcher m = EL.matcher(tail);
        double last = -1, before = -1;
        int t = 0;
        while (m.find()) {
            before = last;
            last = Double.parseDouble(m.group(2));
            t = Integer.parseInt(m.group(1));
        }
        return t >= minS && last >= 60 && before >= 60;
    }

    /** iOS has its display off, or nobody touched it for 2 minutes. */
    private static boolean unwatched(Context c) {
        return (Guest.currentLeds(c) & Guest.SCROLL_LOCK_LED) != 0
                || SystemClock.uptimeMillis() - Guest.lastInputMs > 120_000;
    }

    private static void toast(Context c, String s) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(
                () -> android.widget.Toast.makeText(c, s, android.widget.Toast.LENGTH_LONG).show());
    }
}
