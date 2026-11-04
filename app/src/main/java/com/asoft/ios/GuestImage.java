package com.asoft.ios;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The guest's boot files from a SWEETXNU payload (ios-sweet tools/mkpayload.py), as qemu-sweet.py
 * lays them out: ram.bin is the kernel's segments, the device tree and the boot arguments at the
 * payload's physical base, stub.bin the four instructions that jump in. Plain Java: no Android,
 * so it can be checked against qemu-sweet.py on the PC.
 */
final class GuestImage {
    static final int PAGE = 0x4000;
    static final int BOOT_LINE = 608;
    static final long FB_BASE = 0x9c000000L;
    static final int FB_W = 1080, FB_H = 2400, FB_STRIDE = FB_W * 4;
    static final long STUB_PA = 0x40200000L;
    /** Where the app loads ram.bin (Guest's -device loader): the payload's physical base must be this. */
    static final long KERNEL_PA = 0x100000000L;
    private static final int CMDLINE_AT = 0x48, SEGMENTS_AT = 0x2a8;

    private GuestImage() {}

    static final class Image {
        long physBase, bootArgs, entry;
        byte[] ram, stub;
    }

    private static ByteBuffer le(byte[] b) {
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    }

    static boolean isPayload(byte[] p) {
        return p.length > SEGMENTS_AT && new String(p, 0, 8, StandardCharsets.US_ASCII).equals("SWEETXNU")
                && le(p).getInt(8) == 1;
    }

    static String cmdline(byte[] p) {
        int n = 0;
        while (n < BOOT_LINE && p[CMDLINE_AT + n] != 0) {
            n++;
        }
        return new String(p, CMDLINE_AT, n, StandardCharsets.US_ASCII);
    }

    static void setCmdline(byte[] p, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        if (b.length >= BOOT_LINE) {
            throw new IllegalArgumentException("boot-args longer than " + (BOOT_LINE - 1));
        }
        java.util.Arrays.fill(p, CMDLINE_AT, CMDLINE_AT + BOOT_LINE, (byte) 0);
        System.arraycopy(b, 0, p, CMDLINE_AT, b.length);
    }

    /**
     * The boot-args with the phone's time zone, region and clock (run-windows.ps1 does the same
     * with the PC's): asoft_tz=Europe/Istanbul asoft_region=TR asoft_clock=24.
     */
    static String withLocale(String cmdline, String tz, String region, boolean clock24) {
        StringBuilder sb = new StringBuilder();
        for (String w : cmdline.trim().split("\\s+")) {
            if (!w.startsWith("asoft_tz=") && !w.startsWith("asoft_region=") && !w.startsWith("asoft_clock=")) {
                sb.append(sb.length() > 0 ? " " : "").append(w);
            }
        }
        if (tz != null && tz.matches("[A-Za-z0-9_+/-]+")) {
            sb.append(" asoft_tz=").append(tz);
        }
        if (region != null && region.matches("[A-Z]{2}|[0-9]{3}")) {
            sb.append(" asoft_region=").append(region);
        }
        return sb.append(" asoft_clock=").append(clock24 ? "24" : "12").toString();
    }

    static int indexOf(byte[] p, byte[] s, int from, int to) {
        outer:
        for (int i = Math.max(0, from); i <= Math.min(p.length, to) - s.length; i++) {
            for (int j = 0; j < s.length; j++) {
                if (p[i + j] != s[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    static boolean contains(byte[] p, String s) {
        return indexOf(p, s.getBytes(StandardCharsets.US_ASCII), 0, p.length) >= 0;
    }

    /**
     * iOS's own keyboard instead of a hardware one (patch-softkbd.py): the SweetHIDKeyboard
     * personality's provider class IOResources becomes IOResourceX, so it never matches. True
     * when the kernel now has it off (patched here or before), false when it has no such driver.
     */
    static boolean softKeyboard(byte[] p) {
        byte[] head = "'IOClass'         = SweetHIDKeyboard;".getBytes(StandardCharsets.US_ASCII);
        int at = indexOf(p, head, 0, p.length);
        if (at < 0) {
            return false;
        }
        int end = indexOf(p, new byte[] {'}'}, at, p.length);
        byte[] on = "'IOProviderClass' = IOResources;".getBytes(StandardCharsets.US_ASCII);
        int i = indexOf(p, on, at, end < 0 ? p.length : end);
        if (i >= 0) {
            p[i + on.length - 2] = 'X';     // IOResources; -> IOResourceX;
            return true;
        }
        return indexOf(p, "'IOProviderClass' = IOResourceX;".getBytes(StandardCharsets.US_ASCII), at,
                end < 0 ? p.length : end) >= 0;
    }

    /** qemu-sweet.py build_image() and stub(). */
    static Image build(byte[] p) {
        if (!isPayload(p)) {
            throw new IllegalArgumentException("not a SWEETXNU v1 payload");
        }
        ByteBuffer b = le(p);
        int nsegs = b.getInt(12);
        long virtBase = b.getLong(0x10), entryVa = b.getLong(0x18), adtOff = b.getLong(0x20),
                adtSize = b.getLong(0x28), kernelEnd = b.getLong(0x30), physBase = b.getLong(0x38),
                memSize = b.getLong(0x40);
        long delta = physBase - virtBase;
        long adtPa = kernelEnd + delta;
        long baPa = (adtPa + adtSize + PAGE - 1) & ~(long) (PAGE - 1);
        long top = baPa + PAGE;
        byte[] img = new byte[Math.toIntExact(top - physBase)];
        for (int i = 0; i < nsegs; i++) {
            int s = SEGMENTS_AT + 32 * i;
            long vmaddr = b.getLong(s), foff = b.getLong(s + 16), fsize = b.getLong(s + 24);
            System.arraycopy(p, Math.toIntExact(foff), img, Math.toIntExact(vmaddr + delta - physBase),
                    Math.toIntExact(fsize));
        }
        System.arraycopy(p, Math.toIntExact(adtOff), img, Math.toIntExact(adtPa - physBase), Math.toIntExact(adtSize));
        // struct.pack('<HH4x4Q6QI4xQI', ...) + cmdline + 4 x 0 + pack('<QQ', 0, mem_size): 736 bytes
        ByteBuffer ba = ByteBuffer.allocate(736).order(ByteOrder.LITTLE_ENDIAN);
        ba.putShort((short) 2).putShort((short) 2).putInt(0);
        ba.putLong(virtBase).putLong(physBase).putLong(memSize).putLong(top);
        ba.putLong(FB_BASE).putLong(1).putLong(FB_STRIDE).putLong(FB_W).putLong(FB_H).putLong(32);
        ba.putInt(0).putInt(0);
        ba.putLong(adtPa - delta).putInt(Math.toIntExact(adtSize));
        ba.put(p, CMDLINE_AT, BOOT_LINE).putInt(0);
        ba.putLong(0).putLong(memSize);
        System.arraycopy(ba.array(), 0, img, Math.toIntExact(baPa - physBase), 736);
        // ldr x0, =ba ; ldr x1, =entry ; br x1 ; nop ; .quad ba ; .quad entry
        ByteBuffer st = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        st.putInt(0x58000080).putInt(0x580000a1).putInt(0xd61f0020).putInt(0xd503201f);
        st.putLong(baPa).putLong(entryVa + delta);
        Image im = new Image();
        im.physBase = physBase;
        im.bootArgs = baPa;
        im.entry = entryVa + delta;
        im.ram = img;
        im.stub = st.array();
        return im;
    }

    /** ram.bin and stub.bin in @dir from @payloadFile with @cmdline; checks the addresses Guest loads them at. */
    static Image write(File payloadFile, String cmdline, File dir) throws IOException {
        byte[] p = Files.readAllBytes(payloadFile.toPath());
        if (cmdline != null) {
            setCmdline(p, cmdline);
        }
        Image im = build(p);
        if (im.physBase != KERNEL_PA) {
            throw new IOException(String.format("kernel at %#x, the app loads it at %#x", im.physBase, KERNEL_PA));
        }
        writeFile(new File(dir, "ram.bin"), im.ram);
        writeFile(new File(dir, "stub.bin"), im.stub);
        return im;
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(data);
        }
        if (!tmp.renameTo(f)) {
            throw new IOException("cannot write " + f);
        }
    }

    /** On the PC: java GuestImage.java PAYLOAD OUTDIR [cmdline], to compare with qemu-sweet.py's ram.bin. */
    public static void main(String[] a) throws IOException {
        byte[] p = Files.readAllBytes(new File(a[0]).toPath());
        if (a.length > 2) {
            setCmdline(p, a[2]);
        }
        Image im = build(p);
        new File(a[1]).mkdirs();
        Files.write(new File(a[1], "ram.bin").toPath(), im.ram);
        Files.write(new File(a[1], "stub.bin").toPath(), im.stub);
        System.out.printf("ram.bin %d B at %#x, boot args %#x, entry %#x%n", im.ram.length, im.physBase, im.bootArgs, im.entry);
    }
}
