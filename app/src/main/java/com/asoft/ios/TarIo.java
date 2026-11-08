package com.asoft.ios;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The helper VM's job-run.sh disks: "in" is a tar the host makes (the job's script and files), "out" a
 * tar the job writes from its first byte ("ASOFT: out BYTES" says how long). POSIX ustar, regular files.
 */
final class TarIo {
    private TarIo() {}

    private static void octal(byte[] h, int at, int len, long v) {
        String s = Long.toOctalString(v);
        int pad = len - 1 - s.length();
        if (pad < 0) {
            throw new IllegalArgumentException("too big for a tar header: " + v);
        }
        for (int i = 0; i < pad; i++) {
            h[at + i] = '0';
        }
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, at + pad, b.length);
        h[at + len - 1] = 0;
    }

    private static byte[] header(String name, long size, int mode) {
        byte[] h = new byte[512];
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        if (n.length > 100) {
            int cut = name.lastIndexOf('/', 154);
            byte[] prefix = name.substring(0, cut).getBytes(StandardCharsets.UTF_8);
            n = name.substring(cut + 1).getBytes(StandardCharsets.UTF_8);
            if (prefix.length > 155 || n.length > 100) {
                throw new IllegalArgumentException("name too long for ustar: " + name);
            }
            System.arraycopy(prefix, 0, h, 345, prefix.length);
        }
        System.arraycopy(n, 0, h, 0, n.length);
        octal(h, 100, 8, mode);
        octal(h, 108, 8, 0);
        octal(h, 116, 8, 0);
        octal(h, 124, 12, size);
        octal(h, 136, 12, System.currentTimeMillis() / 1000);
        h[156] = '0';
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 6);
        h[263] = '0';
        h[264] = '0';
        for (int i = 148; i < 156; i++) {
            h[i] = ' ';
        }
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        octal(h, 148, 7, sum);
        h[155] = ' ';
        return h;
    }

    /** A tar of @files (name in the tar -> file here, or a String for a small text) at @out; its length. */
    static long write(File out, Map<String, Object> files) throws IOException {
        long n = 0;
        byte[] buf = new byte[1 << 20];
        try (OutputStream o = new FileOutputStream(out)) {
            for (Map.Entry<String, Object> e : files.entrySet()) {
                Object v = e.getValue();
                byte[] text = v instanceof String ? ((String) v).getBytes(StandardCharsets.UTF_8) : null;
                File f = text == null ? (File) v : null;
                long size = text != null ? text.length : f.length();
                o.write(header(e.getKey(), size, e.getKey().endsWith(".sh") ? 0755 : 0644));
                n += 512;
                if (text != null) {
                    o.write(text);
                } else {
                    try (InputStream in = new FileInputStream(f)) {
                        int r;
                        long left = size;
                        while (left > 0 && (r = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                            o.write(buf, 0, r);
                            left -= r;
                        }
                        if (left != 0) {
                            throw new IOException(f + " changed while it was packed");
                        }
                    }
                }
                n += size;
                int pad = (int) ((512 - size % 512) % 512);
                o.write(new byte[pad]);
                n += pad;
            }
            o.write(new byte[1024]);            // two zero records end the archive
            n += 1024;
            int pad = (int) ((10240 - n % 10240) % 10240);
            o.write(new byte[pad]);             // a whole number of 10 KB blocks, as tar(1) writes
            n += pad;
            o.flush();
        }
        return n;
    }

    /** Unpacks the regular files of the tar in the first @len bytes of @in into @dir; returns their names. */
    static java.util.List<String> read(File in, long len, File dir) throws IOException {
        java.util.List<String> names = new java.util.ArrayList<>();
        byte[] h = new byte[512];
        byte[] buf = new byte[1 << 20];
        try (InputStream s = new java.io.BufferedInputStream(new FileInputStream(in), 1 << 20)) {
            long at = 0;
            while (at + 512 <= len) {
                readFully(s, h);
                at += 512;
                boolean zero = true;
                for (byte b : h) {
                    zero &= b == 0;
                }
                if (zero) {
                    break;
                }
                String name = field(h, 0, 100), prefix = field(h, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
                long size = Long.parseLong(field(h, 124, 12).trim().isEmpty() ? "0" : field(h, 124, 12).trim(), 8);
                char type = (char) h[156];
                long padded = (size + 511) & ~511L;
                if (name.contains("..") || name.startsWith("/")) {
                    throw new IOException("unsafe name in the tar: " + name);
                }
                if (type == '0' || type == 0) {
                    File f = new File(dir, name);
                    f.getParentFile().mkdirs();
                    try (OutputStream o = new FileOutputStream(f)) {
                        long left = size;
                        while (left > 0) {
                            int r = s.read(buf, 0, (int) Math.min(buf.length, left));
                            if (r < 0) {
                                throw new IOException("the tar ends early");
                            }
                            o.write(buf, 0, r);
                            left -= r;
                        }
                    }
                    skip(s, padded - size);
                    names.add(name);
                } else {
                    skip(s, padded);
                }
                at += padded;
            }
        }
        return names;
    }

    private static String field(byte[] h, int at, int len) {
        int n = 0;
        while (n < len && h[at + n] != 0) {
            n++;
        }
        return new String(h, at, n, StandardCharsets.UTF_8);
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int n = 0;
        while (n < b.length) {
            int r = in.read(b, n, b.length - n);
            if (r < 0) {
                throw new IOException("the tar ends early");
            }
            n += r;
        }
    }

    private static void skip(InputStream in, long n) throws IOException {
        while (n > 0) {
            long r = in.skip(n);
            if (r <= 0) {
                if (in.read() < 0) {
                    throw new IOException("the tar ends early");
                }
                r = 1;
            }
            n -= r;
        }
    }

    /** Copies @in to @out. */
    static void copy(InputStream in, File out) throws IOException {
        try (OutputStream o = new FileOutputStream(out)) {
            byte[] b = new byte[1 << 20];
            int r;
            while ((r = in.read(b)) > 0) {
                o.write(b, 0, r);
            }
        }
    }
}
