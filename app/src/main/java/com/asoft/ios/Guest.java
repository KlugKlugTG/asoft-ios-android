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
    static final long KERNEL_PA = 0x100000000L;     // ram.bin's load address for this guest

    static final long RAM_SIZE = 7L << 30;
    private static int pid = -1;
    private static volatile Qmp qmp;
    static String state = "stopped";

    static File runDir(Context c) {
        File d = new File(c.getFilesDir(), "run");
        d.mkdirs();
        return d;
    }

    static String qmpPath(Context c) {
        return new File(runDir(c), "qmp.sock").getPath();
    }

    /** Where ram.bin, stub.bin and disk.qcow2 are: the app's files, else the shared test folder. */
    static File guestDir(Context c) {
        File own = new File(c.getFilesDir(), "ios16");
        if (new File(own, "ram.bin").exists()) {
            return own;
        }
        return new File("/data/local/tmp/asoft/ios16-app");
    }

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
            for (int i = 0; i < 300 && Render.alive(forPid) && pid == forPid; i++) {
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

    static synchronized void start(Context c) throws IOException {
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
        new File(run, "qmp.sock").delete();
        int ramFd = Render.createGuestRam(RAM_SIZE);
        if (ramFd < 0) {
            throw new IOException("no memfd for the guest's RAM");
        }
        List<String> cmd = new ArrayList<>(List.of(Emu.qemuPath(c),
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
                "-drive", "file=" + new File(g, "disk.qcow2").getPath()
                        + ",if=none,id=root,cache=writeback,format=qcow2,snapshot=on",
                "-device", "virtio-blk-device,drive=root"));
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
        state = "booting";
        connectQmp(c, pid);
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
