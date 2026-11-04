package com.asoft.ios;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The iOS 16 machine, as qemu-sweet.py builds it, and its one QEMU process. The guest's files
 * (ram.bin and stub.bin from qemu-sweet.py, the root disk) are in a directory the app can read.
 */
final class Guest {
    static final long RAM_BASE = 0x40000000L;
    static final long FB_BASE = 0x9c000000L;        // qemu-sweet.py FB_BASE: the guest's ramfb framebuffer
    static final int FB_W = 1080, FB_H = 2400, FB_STRIDE = FB_W * 4;
    static final long STUB_PA = 0x40200000L;
    static final long KERNEL_PA = GuestImage.KERNEL_PA;     // ram.bin's load address for this guest

    static final long RAM_SIZE = 7L << 30;
    /** The quick-boot state's name inside the user disk (qcow2 internal snapshot). */
    static final String QUICK = "asoft-quick";
    private static int pid = -1;
    private static volatile Qmp qmp;
    static volatile String state = "stopped";
    /** True when this QEMU was started from the saved state. */
    static volatile boolean resumed;
    private static final java.util.concurrent.atomic.AtomicBoolean saving = new java.util.concurrent.atomic.AtomicBoolean();

    static File runDir(Context c) {
        File d = new File(c.getFilesDir(), "run");
        d.mkdirs();
        return d;
    }

    static String qmpPath(Context c) {
        return new File(runDir(c), "qmp.sock").getPath();
    }

    /** The adb test folder (debug builds): ram.bin, stub.bin and disk.qcow2 made on the PC. */
    static final File TEST_DIR = new File("/data/local/tmp/asoft/ios16-app");

    /** Where payload.bin (or ram.bin and stub.bin) and disk.qcow2 are: what Set up brought in, else the test folder. */
    static File guestDir(Context c) {
        if (Installer.installed(c)) {
            return Installer.dir(c);
        }
        return TEST_DIR;
    }

    /** Something to start: iOS was set up, or a debug build has the test folder. */
    static boolean available(Context c) {
        return Installer.installed(c)
                || (c.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && new File(TEST_DIR, "ram.bin").canRead();
    }

    /**
     * What iOS writes (settings, apps, the quick-boot state) goes to this overlay of the
     * read-only root disk; deleting it erases the iPhone.
     */
    static File userDisk(Context c) {
        return new File(c.getFilesDir(), "ios16-user.qcow2");
    }

    private static void ensureUserDisk(Context c, File g) throws IOException {
        File u = userDisk(c);
        if (u.exists()) {
            return;
        }
        String[] r = Emu.qemuImg(c, "create", "-q", "-f", "qcow2", "-F", "qcow2",
                "-b", new File(g, "disk.qcow2").getPath(), u.getPath());
        if (!"0".equals(r[0])) {
            u.delete();
            throw new IOException("qemu-img create: " + r[1].trim());
        }
    }

    /** One saved state (qcow2 internal snapshot) in the user disk. */
    static final class Snap {
        final String tag, size, date;

        Snap(String tag, String size, String date) {
            this.tag = tag;
            this.size = size;
            this.date = date;
        }

        /** What the user calls it: the quick-boot state is "Last close", "_" is a space. */
        String label() {
            return QUICK.equals(tag) ? "Last close" : tag.replace('_', ' ');
        }
    }

    /** A row of "info snapshots" or "qemu-img snapshot -l": ID, TAG, VM size (number and unit), date. */
    private static final java.util.regex.Pattern SNAP_ROW = java.util.regex.Pattern.compile(
            "^\\s*\\S+\\s+(\\S+)\\s+([\\d.]+\\s*\\S+)\\s+(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d)");

    /** The saved states, newest first; through QMP while iOS runs (the disk is open then). Not on the UI thread. */
    static List<Snap> snapshots(Context c) {
        String text = "";
        Qmp q = qmp;
        if (running() && q != null) {
            String r = q.hmp("info snapshots", 15000);
            try {
                text = r == null ? "" : new org.json.JSONObject(r).optString("return", "");
            } catch (org.json.JSONException ignored) {
                // no list
            }
        } else if (userDisk(c).exists()) {
            try {
                text = Emu.qemuImg(c, "snapshot", "-l", userDisk(c).getPath())[1];
            } catch (IOException ignored) {
                // no list
            }
        }
        List<Snap> out = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            java.util.regex.Matcher m = SNAP_ROW.matcher(line);
            if (m.find()) {
                out.add(new Snap(m.group(1), m.group(2), m.group(3)));
            }
        }
        out.sort((a, b) -> b.date.compareTo(a.date));
        return out;
    }

    static boolean hasSnapshot(Context c, String tag) {
        for (Snap s : snapshots(c)) {
            if (s.tag.equals(tag)) {
                return true;
            }
        }
        return false;
    }

    /** A user's name for a save as a snapshot tag: letters, digits, - and _ (a space becomes _). */
    static String tagFor(String name) {
        String t = name.trim().replace(' ', '_').replaceAll("[^A-Za-z0-9_-]", "");
        if (t.length() > 40) {
            t = t.substring(0, 40);
        }
        return t.isEmpty() || t.equals(QUICK) ? null : t;
    }

    /** Drops a saved state; with iOS running through QMP, else with qemu-img. Not on the UI thread. */
    static String deleteSnapshot(Context c, String tag) {
        savedLedsFile(c, tag).delete();
        Qmp q = qmp;
        if (running() && q != null) {
            String r = q.hmp("delvm " + tag, 60000);
            return r != null && r.replace(" ", "").equals("{\"return\":\"\"}") ? null : "delvm: " + r;
        }
        try {
            String[] r = Emu.qemuImg(c, "snapshot", "-d", tag, userDisk(c).getPath());
            return "0".equals(r[0]) ? null : r[1].trim();
        } catch (IOException e) {
            return e.toString();
        }
    }

    /** Drops the quick-boot state: the next start boots iOS. */
    static void dropQuick(Context c) {
        deleteSnapshot(c, QUICK);
    }

    static File ledsFile(Context c) {
        return new File(runDir(c), "leds");
    }

    /** Erase iPhone: the user disk and what belongs to its saved states. */
    static void erase(Context c) {
        userDisk(c).delete();
        File[] f = c.getFilesDir().listFiles((d, n) -> n.startsWith("ios16-user.") && n.endsWith(".leds"));
        if (f != null) {
            for (File x : f) {
                x.delete();
            }
        }
    }

    /** The LED byte when @tag was saved (the display's power then), next to the user disk. */
    private static File savedLedsFile(Context c, String tag) {
        return new File(c.getFilesDir(), "ios16-user." + tag + ".leds");
    }

    private static int savedLeds(Context c, String tag) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(savedLedsFile(c, tag))) {
            int b = in.read();
            return b < 0 ? 0 : b;
        } catch (IOException e) {
            return 0;
        }
    }

    private static void keepLeds(Context c, String tag) {
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(savedLedsFile(c, tag))) {
            o.write(currentLeds(c));
        } catch (IOException ignored) {
            // the display then reads on after a load
        }
    }

    private static int currentLeds(Context c) {
        try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(ledsFile(c), "r")) {
            int b = in.read();
            return b < 0 ? 0 : b;
        } catch (IOException e) {
            return 0;
        }
    }

    /** QEMU's Scroll Lock LED bit: ASoftware's XNU lights it while iOS has the display off (xnu 0102). */
    static final int SCROLL_LOCK_LED = 1;

    static synchronized boolean running() {
        return Render.alive(pid);
    }

    static synchronized int pid(Context c) {
        return pid;
    }

    /** The guest's one QMP connection, or null while there is none (input is dropped then). */
    static Qmp qmp() {
        return qmp;
    }

    /** Connects QMP in the background once QEMU listens; QEMU takes one client, this is it. */
    private static void connectQmp(Context c, int forPid) {
        Thread t = new Thread(() -> {
            File sock = new File(qmpPath(c));
            // a saved state loads before QEMU answers: minutes, on a cold phone
            for (int i = 0; i < 3000 && Render.alive(forPid) && pid == forPid; i++) {
                if (sock.exists()) {
                    Qmp q = new Qmp();
                    try {
                        q.connect(sock.getPath(), 5000);
                        qmp = q;
                        return;
                    } catch (IOException e) {
                        q.close();
                    }
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "qmp-connect");
        t.setDaemon(true);
        t.start();
    }

    /** The DNS servers of the active network, for slirp (see patch-slirp.py); public ones as a fallback. */
    private static void writeResolvConf(Context c) throws IOException {
        StringBuilder sb = new StringBuilder();
        android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
        android.net.LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
        if (lp != null) {
            for (java.net.InetAddress a : lp.getDnsServers()) {
                sb.append("nameserver ").append(a.getHostAddress()).append('\n');
            }
        }
        sb.append("nameserver 8.8.8.8\nnameserver 1.1.1.1\n");
        try (PrintWriter w = new PrintWriter(new FileWriter(new File(c.getFilesDir(), "resolv.conf")))) {
            w.print(sb);
        }
    }

    /** The end of QEMU's stderr: why it stopped, for the screen. */
    static String lastWords(Context c) {
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(new File(runDir(c), "qemu-stderr.txt").toPath());
            for (int i = lines.size() - 1; i >= 0 && i >= lines.size() - 40; i--) {
                String l = lines.get(i);
                if (l.contains("host signal") || l.contains("died") || l.contains("Error") || l.contains("error")) {
                    return l.length() > 200 ? l.substring(0, 200) : l;
                }
            }
        } catch (IOException ignored) {
            // no log yet
        }
        return "";
    }

    /** The saved state this QEMU started from, null after a boot. */
    static volatile String loadedTag;

    /** Starts iOS: from the saved state @loadTag when there is one (QUICK: quick boot), else a boot. */
    static synchronized void start(Context c, String loadTag) throws IOException {
        if (running()) {
            return;
        }
        if (qmp != null) {
            qmp.close();                // of a QEMU that is gone
            qmp = null;
        }
        File links = Emu.linkLibraries(c);
        writeResolvConf(c);
        File g = guestDir(c);
        File run = runDir(c);
        ensureUserDisk(c, g);
        resumed = loadTag != null && hasSnapshot(c, loadTag);
        loadedTag = resumed ? loadTag : null;
        if (!resumed) {
            Installer.prepareBoot(c, g);    // this phone's time zone, region and clock in the boot-args
        }
        new File(run, "qmp.sock").delete();
        // the guest keyboard's LEDs, one byte QEMU keeps current (ASOFT_LEDS_FILE): zero until iOS says
        // (written in place, never truncated: the screen reads it while this runs). QEMU's LED state
        // is not in a saved state and XNU only reports changes: a resumed guest starts from the LEDs
        // it had when it was saved, so a display saved off still reads off.
        try (java.io.RandomAccessFile o = new java.io.RandomAccessFile(ledsFile(c), "rw")) {
            o.write(resumed ? savedLeds(c, loadTag) : 0);
        }
        int ramFd = Render.createGuestRam(RAM_SIZE);
        if (ramFd < 0) {
            throw new IOException("no memfd for the guest's RAM");
        }
        List<String> cmd = new ArrayList<>(List.of(Emu.qemuPath(c),
                // no loadvm-catch-up: moving the clock under a loaded state trips XNU's spinlock timeouts
                // (a lock held at the save looks held for minutes); the kernel (xnu 0103) sets its
                // calendar from the PL031 within 5 s instead
                "-M", "virt,gic-version=3,memory-backend=m0",
                "-object", "memory-backend-memfd,id=m0,size=7G,share=on",
                "-cpu", "cortex-a76", "-smp", "4", "-m", "7G",
                "-display", "none", "-monitor", "none", "-parallel", "none",
                "-device", "ramfb", "-device", "virtio-keyboard-device", "-device", "virtio-tablet-device",
                "-qmp", "unix:" + qmpPath(c) + ",server,nowait",
                "-serial", "file:" + new File(run, "serial.txt").getPath(),
                "-d", "guest_errors,unimp", "-D", new File(run, "qemu.log").getPath(),
                "-no-reboot", "-nic", "none",
                "-device", "loader,file=" + new File(g, "ram.bin").getPath() + ",addr=0x" + Long.toHexString(KERNEL_PA) + ",force-raw=on",
                "-device", "loader,file=" + new File(g, "stub.bin").getPath() + ",addr=0x" + Long.toHexString(STUB_PA) + ",force-raw=on",
                "-device", "loader,addr=0x" + Long.toHexString(STUB_PA) + ",cpu-num=0",
                "-global", "virtio-mmio.force-legacy=false",
                "-name", "ASoftware iOS",
                "-netdev", "user,id=net0", "-device", "virtio-net-device,netdev=net0",
                "-drive", "file=" + userDisk(c).getPath() + ",if=none,id=root,cache=writeback,format=qcow2",
                "-device", "virtio-blk-device,drive=root"));
        if (resumed) {
            cmd.add("-loadvm");
            cmd.add(loadTag);
        }
        Map<String, String> env = new java.util.HashMap<>(System.getenv());
        Emu.baseEnv(c, links, env);
        env.put("ASOFT_GUEST_RAM_FD", String.valueOf(ramFd));    // our memfd, inherited: see render.c
        env.put("ASOFT_NATIVE_EL0", "1");
        env.put("ASOFT_NATIVE_EL0_TPIDRRO", "1");
        env.put("ASOFT_NATIVE_EL0_STUBS", "8");
        env.put("ASOFT_NATIVE_EL0_DELAY_MS", "20000");
        env.put("ASOFT_NATIVE_EL0_STATS", "1");
        // while this file exists EL0 stays under TCG: for comparing the two without a reboot
        env.put("ASOFT_NATIVE_EL0_OFF_FILE", new File(run, "native-off").getPath());
        env.put("ASOFT_EL_SAMPLER", "30");
        env.put("ASOFT_LEDS_FILE", ledsFile(c).getPath());
        try (PrintWriter w = new PrintWriter(new FileWriter(new File(run, "cmdline.txt")))) {
            w.println(String.join(" ", cmd));
        }
        List<String> envp = new ArrayList<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            envp.add(e.getKey() + "=" + e.getValue());
        }
        pid = Render.spawn(cmd.toArray(new String[0]), envp.toArray(new String[0]),
                new File(run, "qemu-stderr.txt").getPath());
        if (pid < 0) {
            throw new IOException("cannot start QEMU");
        }
        state = resumed ? "resuming" : "booting";
        connectQmp(c, pid);
    }

    /**
     * Quick boot's save: pause iOS, write its whole state into the user disk, quit QEMU.
     * Null when saved, else why not (QEMU is stopped either way). Not synchronized: it takes
     * tens of seconds, and the screen asks running() every frame.
     */
    static String saveAndQuit(Context c) {
        if (!saving.compareAndSet(false, true)) {
            return "already saving";
        }
        try {
            Qmp q = qmp;
            int p = pid;
            if (q == null || !Render.alive(p)) {
                return "iOS is not running";
            }
            state = "saving";
            long t0 = System.currentTimeMillis();
            String why = null;
            if (q.call("{\"execute\":\"stop\"}", 30000) == null) {
                why = "stop: no answer";
            } else {
                // HMP answers "" when it worked, the error text when not
                String r = q.hmp("savevm " + QUICK, 600000);
                if (r == null || !r.replace(" ", "").equals("{\"return\":\"\"}")) {
                    why = "savevm: " + r;
                }
            }
            long saved = System.currentTimeMillis() - t0;
            if (why == null) {
                keepLeds(c, QUICK);
            }
            q.call("{\"execute\":\"quit\"}", 10000);
            for (int i = 0; i < 100 && Render.alive(p); i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }
            stop();
            return why != null ? why : "saved in " + saved + " ms";
        } finally {
            saving.set(false);
        }
    }

    /**
     * A named save while iOS goes on: iOS pauses for it (~25 s) and continues. Null when saved,
     * else why not. Not on the UI thread.
     */
    static String saveNamed(Context c, String tag) {
        if (!saving.compareAndSet(false, true)) {
            return "already saving";
        }
        try {
            Qmp q = qmp;
            if (q == null || !running()) {
                return "iOS is not running";
            }
            String r = q.hmp("savevm " + tag, 600000);
            if (r == null || !r.replace(" ", "").equals("{\"return\":\"\"}")) {
                return "savevm: " + r;
            }
            keepLeds(c, tag);
            return null;
        } finally {
            saving.set(false);
        }
    }

    static boolean saving() {
        return saving.get();
    }

    /** stop(), and wait (at most 30 s) until QEMU has gone and let go of the user disk. */
    static void stopAndWait() {
        int p = pid;
        Render.terminate(p);
        for (int i = 0; i < 300 && Render.alive(p); i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }
        stop();
    }

    static synchronized void stop() {
        Render.terminate(pid);
        if (qmp != null) {
            qmp.close();
            qmp = null;
        }
        pid = -1;
        state = "stopped";
    }
}
