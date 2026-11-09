package com.asoft.ios;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Brings iOS in from a bundle the PC made (pc/Send-ToPhone.ps1): a tar of asoft-bundle.json,
 * payload.bin (the kernel with the device tree, from the PC install) and disk.qcow2 (iOS's root
 * disk, one compressed qcow2). It is read as a stream, so it can come from a picked file or the
 * folder adb pushes to, and is unpacked into files/ios16.part, checked, then renamed to
 * files/ios16. The iPhone's own data (user disk, saves) belongs to the old disk and goes.
 */
final class Installer {
    static final String[] PARTS = {"asoft-bundle.json", "payload.bin", "disk.qcow2"};
    /** Room kept free after the disk: one save (~3 GB) and what iOS writes. */
    static final long HEADROOM = 4_000_000_000L;

    interface Progress {
        void at(String what, long done, long total);
    }

    private Installer() {}

    static File dir(Context c) {
        return new File(c.getFilesDir(), "ios16");
    }

    /** iOS was brought in: the disk and the kernel are there. */
    static boolean installed(Context c) {
        File d = dir(c);
        return new File(d, "disk.qcow2").isFile() && new File(d, "payload.bin").isFile();
    }

    /** Where Send-ToPhone.ps1 pushes over adb: the app's own folder on the shared storage. */
    static File importDir(Context c) {
        File d = new File(c.getExternalFilesDir(null), "import");
        d.mkdirs();
        return d;
    }

    static File[] waiting(Context c) {
        File[] f = importDir(c).listFiles((d, n) -> n.endsWith(".asoft"));
        return f == null ? new File[0] : f;
    }

    /**
     * The phone's boot-args: the bundle's, with this phone's time zone, region and clock, and
     * @cpus (QEMU's -smp, CpuPlan). Done at every boot (not for a loaded save: its RAM is already
     * set up), so a changed setting counts.
     */
    static void prepareBoot(Context c, File dir, int cpus) throws IOException {
        File payload = new File(dir, "payload.bin");
        if (!payload.isFile() || !dir.canWrite()) {
            return;                     // a test folder with a ready ram.bin
        }
        String base = GuestImage.cmdline(Files.readAllBytes(payload.toPath()));
        String region = java.util.Locale.getDefault().getCountry();
        GuestImage.write(payload, GuestImage.withCpus(GuestImage.withLocale(base, java.util.TimeZone.getDefault().getID(),
                region, android.text.format.DateFormat.is24HourFormat(c)), cpus), dir);
    }

    /** Something an import would delete: an installed iOS 16, or its iPhone's own disk and saves. */
    static boolean hasData(Context c) {
        return installed(c) || Guest.userDisk(c, Guest.IOS16).exists();
    }

    /**
     * Unpacks @in (@total bytes, or -1) and installs it. The iPhone's disk and saves sit on the
     * old root disk, so they go first (the user agreed): the room they free is often what the
     * new disk needs. Not on the UI thread.
     */
    static void install(Context c, InputStream in, long total, Progress p) throws IOException {
        installAny(c, in, total, p);
    }

    /**
     * What a bundle holds: "16" (iOS 16: PARTS), "26" (iOS 26: Guest.PHONE_PARTS26, the layout the phone's own
     * setup leaves) or "watchos" (WatchProfile's files with iOS 26's sptm, txm and ramdisk), by its manifest,
     * the archive's first file (Send-ToPhone.ps1 -OS). Returns it.
     */
    static String installAny(Context c, InputStream in, long total, Progress p) throws IOException {
        File part = new File(c.getFilesDir(), "import.part");
        deleteTree(part);
        if (!part.mkdirs()) {
            throw new IOException("cannot create " + part);
        }
        String[] os = new String[1];
        try {
            // once the manifest is in (the first file): that OS's old disk, user disk and saves go, so the
            // room they free is there for the new disk
            untar(in, part, total, p, m -> {
                os[0] = bundleOs(m);
                if ("16".equals(os[0])) {
                    Guest.erase(c, Guest.IOS16);    // iOS 16's own iPhone only: never iOS 26's or the watch's
                    deleteTree(dir(c));
                } else if ("26".equals(os[0])) {
                    for (String n : new String[] {"ios26-user.qcow2", "ios26-2x-user.qcow2"}) {
                        retireUserDisk(c, n);
                    }
                    deleteTree(new File(c.getFilesDir(), "ios26"));
                } else {
                    retireUserDisk(c, "watch-user.qcow2");
                    deleteTree(new File(c.getFilesDir(), "watchos"));
                }
            });
            if (os[0] == null) {
                throw new IOException("not an ASoftware iOS bundle: no asoft-bundle.json first");
            }
            p.at("Checking", 0, -1);
            File d;
            if ("16".equals(os[0])) {
                check(c, part);
                Guest.select(c, Guest.IOS16);
                d = dir(c);
            } else {
                String[] need = "26".equals(os[0]) ? Guest.PHONE_PARTS26 : WATCH_PARTS;
                for (String n : need) {
                    if (!new File(part, n).isFile()) {
                        throw new IOException("not a whole " + ("26".equals(os[0]) ? "iOS 26" : "watchOS") + " bundle: no " + n);
                    }
                }
                File disk = new File(part, "26".equals(os[0]) ? Guest.AOD26_BASE : WatchProfile.BASE);
                byte[] head = new byte[4];
                try (InputStream s = Files.newInputStream(disk.toPath())) {
                    if (s.read(head) != 4 || head[0] != 'Q' || head[1] != 'F' || head[2] != 'I' || head[3] != (byte) 0xfb) {
                        throw new IOException(disk.getName() + " is not a qcow2 image");
                    }
                }
                // the phone's setup marks its own installs with install.json too (Guest.phoneMade26)
                new File(part, "asoft-bundle.json").renameTo(new File(part, "install.json"));
                Guest.select(c, "26".equals(os[0]) ? Guest.IOS26 : Guest.WATCH);
                d = new File(c.getFilesDir(), "26".equals(os[0]) ? "ios26" : "watchos");
                new File(c.getFilesDir(), Guest.DTREE26 + "-2x").delete();
            }
            if (!part.renameTo(d)) {
                throw new IOException("cannot move " + part + " to " + d);
            }
            return os[0];
        } catch (IOException | RuntimeException e) {
            deleteTree(part);
            throw e;
        }
    }

    /** The watch's files in a watchOS bundle (WatchProfile.PARTS and the iOS 26 files it shares). */
    static final String[] WATCH_PARTS = {WatchProfile.KERNEL, WatchProfile.DTREE, WatchProfile.TC, WatchProfile.BASE, "sptm", "txm", "rd-shell.hfs"};

    /** The names a bundle may hold: iOS 16's, iOS 26's and the watch's; anything else in it is skipped. */
    private static final java.util.Set<String> NAMES = new java.util.HashSet<>();

    static {
        NAMES.addAll(java.util.Arrays.asList(PARTS));
        NAMES.addAll(java.util.Arrays.asList(Guest.PHONE_PARTS26));
        NAMES.addAll(java.util.Arrays.asList(WATCH_PARTS));
    }

    /** A user disk rests on the disk an import replaces: kept as .old (as the PC keeps it), it cannot boot on the new one. */
    private static void retireUserDisk(Context c, String name) {
        File f = new File(c.getFilesDir(), name);
        if (f.exists()) {
            File old = new File(f.getPath() + ".old");
            old.delete();
            f.renameTo(old);
            AppLog.note(c, "import: " + name + " rested on the old disk: kept as " + old.getName());
        }
    }

    /** "16", "26" or "watchos" from a bundle's manifest; throws for one this app does not know. */
    private static String bundleOs(File manifest) throws IOException {
        String text = new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);
        try {
            org.json.JSONObject m = new org.json.JSONObject(text);
            String os = m.optString("ios");
            if (m.optInt("format") != 1 || !("16".equals(os) || "26".equals(os) || "watchos".equals(os))) {
                throw new IOException("a bundle this app does not know: " + text.trim());
            }
            return os;
        } catch (org.json.JSONException e) {
            throw new IOException("bad asoft-bundle.json", e);
        }
    }

    interface ManifestHook {
        void got(File manifest) throws IOException;
    }

    /**
     * Installs iOS 16 from the three files of a bundle that this phone made itself (PhoneSetup): they
     * move from @from (the same file system) into files/ios16.part, pass the checks an import does,
     * then the folder becomes files/ios16. As install(), iOS 16's own iPhone (user disk, saves) goes first.
     */
    static void finish(Context c, File from) throws IOException {
        Guest.erase(c, Guest.IOS16);
        Guest.select(c, Guest.IOS16);
        deleteTree(dir(c));
        File part = new File(c.getFilesDir(), "ios16.part");
        deleteTree(part);
        if (!part.mkdirs()) {
            throw new IOException("cannot create " + part);
        }
        try {
            for (String n : PARTS) {
                if (!new File(from, n).renameTo(new File(part, n))) {
                    throw new IOException("cannot move " + n + " into " + part);
                }
            }
            check(c, part);
            File d = dir(c);
            if (!part.renameTo(d)) {
                throw new IOException("cannot move " + part + " to " + d);
            }
        } catch (IOException | RuntimeException e) {
            deleteTree(part);
            throw e;
        }
    }

    /** What an install checks in a bundle's files and makes this phone's: its manifest, a qcow2, a SWEETXNU payload. */
    private static void check(Context c, File part) throws IOException {
        for (String n : PARTS) {
            if (!new File(part, n).isFile()) {
                throw new IOException("not an ASoftware iOS bundle: no " + n);
            }
        }
        String manifest = new String(Files.readAllBytes(new File(part, PARTS[0]).toPath()), StandardCharsets.UTF_8);
        try {
            org.json.JSONObject m = new org.json.JSONObject(manifest);
            if (m.optInt("format") != 1 || !"16".equals(m.optString("ios"))) {
                throw new IOException("a bundle this app does not know: " + manifest.trim());
            }
        } catch (org.json.JSONException e) {
            throw new IOException("bad asoft-bundle.json", e);
        }
        byte[] head = new byte[4];
        try (InputStream d = Files.newInputStream(new File(part, "disk.qcow2").toPath())) {
            if (d.read(head) != 4 || head[0] != 'Q' || head[1] != 'F' || head[2] != 'I' || head[3] != (byte) 0xfb) {
                throw new IOException("disk.qcow2 is not a qcow2 image");
            }
        }
        File payload = new File(part, "payload.bin");
        byte[] k = Files.readAllBytes(payload.toPath());
        if (!GuestImage.isPayload(k)) {
            throw new IOException("payload.bin is not a SWEETXNU payload");
        }
        // iOS's own keyboard (the kernel's hardware keyboard off: see patch-softkbd.py)
        if (GuestImage.softKeyboard(k)) {
            Files.write(payload.toPath(), k);
        }
        prepareBoot(c, part, CpuPlan.forThisPhone(c).smp);  // fails here, not at the first start, if the kernel does not fit
    }

    /** A kernel without xnu 0103 keeps the time a save was made in until it boots again. */
    static String kernelNotes(Context c) {
        try {
            byte[] k = Files.readAllBytes(new File(dir(c), "payload.bin").toPath());
            StringBuilder sb = new StringBuilder();
            if (!GuestImage.contains(k, "calendar %ld s off")) {
                sb.append("This kernel does not set iOS's clock after a save loads (it needs xnu 0103). ");
            }
            if (!GuestImage.contains(k, "display off ignored")) {
                sb.append("This kernel does not report when iOS turns its screen off. ");
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return "";
        }
    }

    // -- tar (POSIX ustar; pax size and path records; GNU base-256 sizes), only the bundle's three names

    private static void readFully(InputStream in, byte[] b, int len) throws IOException {
        for (int n = 0; n < len; ) {
            int r = in.read(b, n, len - n);
            if (r < 0) {
                throw new IOException("the file ends early: incomplete copy?");
            }
            n += r;
        }
    }

    private static void skip(InputStream in, long n) throws IOException {
        byte[] b = new byte[1 << 16];
        while (n > 0) {
            int r = in.read(b, 0, (int) Math.min(b.length, n));
            if (r < 0) {
                throw new IOException("the file ends early");
            }
            n -= r;
        }
    }

    private static String field(byte[] h, int at, int len) {
        int n = 0;
        while (n < len && h[at + n] != 0) {
            n++;
        }
        return new String(h, at, n, StandardCharsets.UTF_8).trim();
    }

    private static long size(byte[] h) {
        if ((h[124] & 0x80) != 0) {         // GNU base-256
            long v = 0;
            for (int i = 125; i < 136; i++) {
                v = (v << 8) | (h[i] & 0xff);
            }
            return v;
        }
        String s = field(h, 124, 12);
        return s.isEmpty() ? 0 : Long.parseLong(s, 8);
    }

    private static void untar(InputStream in, File dir, long total, Progress p, ManifestHook hook) throws IOException {
        byte[] h = new byte[512];
        long done = 0;
        long paxSize = -1;
        String paxPath = null;
        byte[] buf = new byte[1 << 20];
        while (true) {
            readFully(in, h, 512);
            done += 512;
            boolean zero = true;
            for (byte x : h) {
                zero &= x == 0;
            }
            if (zero) {
                return;                     // end of archive
            }
            String name = field(h, 0, 100);
            String prefix = field(h, 345, 155);
            if (!prefix.isEmpty() && "ustar".equals(field(h, 257, 6))) {
                name = prefix + "/" + name;
            }
            long size = size(h);
            char type = (char) h[156];
            long padded = (size + 511) & ~511L;
            if (type == 'x' || type == 'g') {
                byte[] pax = new byte[(int) size];
                readFully(in, pax, (int) size);
                skip(in, padded - size);
                done += padded;
                if (type == 'x') {
                    for (String rec : new String(pax, StandardCharsets.UTF_8).split("\n")) {
                        int sp = rec.indexOf(' '), eq = rec.indexOf('=');
                        if (sp < 0 || eq < sp) {
                            continue;
                        }
                        String key = rec.substring(sp + 1, eq), val = rec.substring(eq + 1);
                        if (key.equals("size")) {
                            paxSize = Long.parseLong(val);
                        } else if (key.equals("path")) {
                            paxPath = val;
                        }
                    }
                }
                continue;
            }
            if (paxSize >= 0) {
                size = paxSize;
                padded = (size + 511) & ~511L;
            }
            if (paxPath != null) {
                name = paxPath;
            }
            paxSize = -1;
            paxPath = null;
            if (name.startsWith("./")) {
                name = name.substring(2);
            }
            boolean wanted = (type == '0' || type == 0) && NAMES.contains(name);
            if (!wanted) {
                skip(in, padded);
                done += padded;
                continue;
            }
            if (name.endsWith(".qcow2") && dir.getUsableSpace() < size + HEADROOM) {
                throw new IOException(String.format("not enough space: the disk needs %.1f GB and %.1f GB more stay free, %.1f GB are free",
                        size / 1e9, HEADROOM / 1e9, dir.getUsableSpace() / 1e9));
            }
            try (OutputStream o = new FileOutputStream(new File(dir, name))) {
                long left = size;
                long lastReport = 0;
                while (left > 0) {
                    int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (r < 0) {
                        throw new IOException("the file ends early: incomplete copy?");
                    }
                    o.write(buf, 0, r);
                    left -= r;
                    done += r;
                    if (done - lastReport > (64 << 20)) {
                        lastReport = done;
                        p.at("Copying iOS", done, total);
                    }
                }
            }
            skip(in, padded - size);
            done += padded - size;
            p.at("Copying iOS", done, total);
            if (name.equals(PARTS[0])) {
                hook.got(new File(dir, name));
            }
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }
}
