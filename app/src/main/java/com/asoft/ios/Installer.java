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
     * The phone's boot-args: the bundle's, with this phone's time zone, region and clock. Done at
     * every boot (not for a loaded save: its RAM is already set up), so a changed setting counts.
     */
    static void prepareBoot(Context c, File dir) throws IOException {
        File payload = new File(dir, "payload.bin");
        if (!payload.isFile() || !dir.canWrite()) {
            return;                     // a test folder with a ready ram.bin
        }
        String base = GuestImage.cmdline(Files.readAllBytes(payload.toPath()));
        String region = java.util.Locale.getDefault().getCountry();
        GuestImage.write(payload, GuestImage.withLocale(base, java.util.TimeZone.getDefault().getID(), region,
                android.text.format.DateFormat.is24HourFormat(c)), dir);
    }

    /** Something an import would delete: an installed iOS, or this iPhone's own disk and saves. */
    static boolean hasData(Context c) {
        return installed(c) || Guest.userDisk(c).exists();
    }

    /**
     * Unpacks @in (@total bytes, or -1) and installs it. The iPhone's disk and saves sit on the
     * old root disk, so they go first (the user agreed): the room they free is often what the
     * new disk needs. Not on the UI thread.
     */
    static void install(Context c, InputStream in, long total, Progress p) throws IOException {
        Guest.erase(c);
        deleteTree(dir(c));
        File part = new File(c.getFilesDir(), "ios16.part");
        deleteTree(part);
        if (!part.mkdirs()) {
            throw new IOException("cannot create " + part);
        }
        try {
            untar(in, part, total, p);
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
            p.at("Checking", 0, -1);
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
            prepareBoot(c, part);       // fails here, not at the first start, if the kernel does not fit
            File d = dir(c);
            if (!part.renameTo(d)) {
                throw new IOException("cannot move " + part + " to " + d);
            }
        } catch (IOException | RuntimeException e) {
            deleteTree(part);
            throw e;
        }
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

    private static void untar(InputStream in, File dir, long total, Progress p) throws IOException {
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
            boolean wanted = (type == '0' || type == 0) && java.util.Arrays.asList(PARTS).contains(name);
            if (!wanted) {
                skip(in, padded);
                done += padded;
                continue;
            }
            if (name.equals("disk.qcow2") && dir.getUsableSpace() < size + HEADROOM) {
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
