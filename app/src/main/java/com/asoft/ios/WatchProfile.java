package com.asoft.ios;

import android.content.Context;

import java.io.File;
import java.util.List;

/**
 * watchOS 27.2 (24S118, Watch7,1: Apple Watch Series 9, 41 mm) on -M vresearch, iOS 26's machine with the
 * watch's own pieces (notes/WATCHOS.md §2k): the patched cloudOS 26.4 kernel (bootkc-w), the watch's device
 * tree with 3 GiB of RAM, its trust cache and its root disk; SPTM, TXM and the ramdisk are iOS 26's (the same
 * files). QEMU's watch options: ppi=326 (the 41 mm screen class: the logo and the layout sit right), the
 * usb-consumer's second button as Consumer Help 0x95 (the side button) and usb-crown (the crown's turns).
 * Measured on the S25 Ultra under Termux: the face in 5-7 min, crown, touch and side button; 2 GiB of RAM
 * slowed a cold Settings to 27 s, 3 GiB ran as fast as 4 GiB with ~0.7 GB less memory.
 */
final class WatchProfile {
    private WatchProfile() {
    }

    /** The folder adb pushed the watch's files to (the Termux runs); files/watchos when brought into the app. */
    static final File TEST_DIR = new File("/data/local/tmp/asoft/watchos");
    /**
     * DTREE: the watch's tree with 3 GiB of RAM and the CPUs' timebase-frequency at 19.2 MHz, the phone's counter
     * (dtree-w-3g with 4 u64s changed): the watch runs on the host counter, as iOS 26 does, so its clock is right
     * after a quick boot (QEMU carries the counter across a save plus the time since). On QEMU's own 24 MHz counter
     * a save loaded 37 min later showed the time it was saved at (2026-11-07, release DC7897AE).
     */
    static final String KERNEL = "bootkc-w", DTREE = "dtree-w-3g-19m2", TC = "rootw-q.tc", BASE = "rootw-phone.qcow2";
    /** What a watch save's .clock file says: saved on the host counter with the 19.2 MHz tree; others never load. */
    static final String CLOCK = "watch-host-counter-19m2";
    /** The watch's own files, then iOS 26's that the watch shares (looked for in the watch's folder first). */
    static final String[] PARTS = {KERNEL, DTREE, TC, BASE};
    static final String[] SHARED = {"sptm", "txm", "rd-shell.hfs"};

    /** The watch's screen: 352x430 pixels at @2x (176x215 points). */
    static final int FB_W = 352, FB_H = 430;

    /**
     * iOS 26's CPU (x-host-counter with the 19.2 MHz tree, x-keep-kernel-tlb=off) and its 24 stubs: the combination
     * measured on the watch on 2026-11-07 (face in ~390 s against 356 s on QEMU's counter: no speed-up, one run
     * each, WATCHOS.md §2m), used for the clock after a quick boot.
     */
    static final String CPU = Guest.CPU26;
    static final String STUBS = "24";
    /** Free room to start: a first save holds ~3 GB of RAM, plus room to write. */
    static final long ROOM = 5L << 30;

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "watchos");
        return new File(d, BASE).isFile() ? d : TEST_DIR;
    }

    /** @name in the watch's folder, else (SHARED) iOS 26's. */
    static File part(Context c, String name) {
        File f = new File(dir(c), name);
        return f.canRead() ? f : new File(Guest.dir26(c), name);
    }

    /** The first watch file this app cannot read, null when all are there. */
    static String missing(Context c) {
        for (String p : PARTS) {
            if (!new File(dir(c), p).canRead()) {
                return p;
            }
        }
        for (String p : SHARED) {
            if (!part(c, p).canRead()) {
                return p;
            }
        }
        return null;
    }

    static boolean available(Context c) {
        return missing(c) == null;
    }

    /** The machine: iOS 26's flags plus the watch's screen and its pitch. */
    static String machine() {
        return "vresearch,fb=" + FB_W + "x" + FB_H + ",ppi=326,unauth-root=on,pvg=reims,aks-stub=on,amfi-lc-off=on,"
                + "aks-crypt=on,acm-stub=on,hfs-dp-stub=on";
    }

    /** The watch's USB devices after the keyboard and the touchscreen: its two buttons and the crown. */
    static void addButtons(List<String> cmd) {
        cmd.add("-device");
        cmd.add("usb-consumer,bus=xhci.0,port=3,id=ucons,power-usage=0x95");
        cmd.add("-device");
        cmd.add("usb-crown,bus=xhci.0,port=4,id=ucrown");
    }

    /** The network (slirp): the watch's apps and its clock use it; measured working in the Termux runs. */
    static void addNetwork(List<String> cmd) {
        cmd.add("-nic");
        cmd.add("user,model=virtio-net-pci");
    }
}
