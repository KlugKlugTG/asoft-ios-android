package com.asoft.ios;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/** Where QEMU and its libraries are, and how to start it. */
final class Emu {
    private Emu() {}

    /**
     * An APK can only carry lib*.so, so a library with the soname libz.so.1 is packed as
     * lib_libz.so.1.so. The loader looks for the soname: give it a directory of links.
     */
    static File linkLibraries(Context c) throws IOException {
        File links = new File(c.getFilesDir(), "lib");
        if (!links.isDirectory() && !links.mkdirs()) {
            throw new IOException("cannot create " + links);
        }
        File[] packed = new File(c.getApplicationInfo().nativeLibraryDir).listFiles();
        if (packed == null) {
            throw new IOException("no native library directory");
        }
        for (File f : packed) {
            String n = f.getName();
            if (!n.startsWith("lib_") || !n.endsWith(".so")) {
                continue;
            }
            File link = new File(links, n.substring(4, n.length() - 3));
            link.delete();
            try {
                Os.symlink(f.getPath(), link.getPath());
            } catch (ErrnoException e) {
                throw new IOException("symlink " + link, e);
            }
        }
        return links;
    }

    static String qemuPath(Context c) {
        return c.getApplicationInfo().nativeLibraryDir + "/libqemu.so";
    }

    /** qemu-img, packed as libqemuimg.so (pull-libs.sh). */
    static String imgPath(Context c) {
        return c.getApplicationInfo().nativeLibraryDir + "/libqemuimg.so";
    }

    /**
     * Runs qemu-img with @args and waits for it: [exit code, output]. A short tool, so the JVM's
     * ProcessBuilder is fine here (it needs no inherited descriptor).
     */
    static String[] qemuImg(Context c, String... args) throws IOException {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(imgPath(c));
        cmd.addAll(java.util.Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        baseEnv(c, linkLibraries(c), pb.environment());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        try {
            return new String[] { String.valueOf(p.waitFor()), out };
        } catch (InterruptedException e) {
            p.destroy();
            throw new IOException("qemu-img interrupted", e);
        }
    }

    static File copyAsset(Context c, String name) throws IOException {
        File out = new File(c.getFilesDir(), name);
        try (InputStream in = c.getAssets().open(name); OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
            }
        }
        return out;
    }

    /** The environment every QEMU child gets; the caller adds the engine's switches. */
    static void baseEnv(Context c, File links, Map<String, String> env) {
        env.put("LD_LIBRARY_PATH", links.getPath());
        env.put("HOME", c.getFilesDir().getPath());
        env.put("TMPDIR", c.getCacheDir().getPath());
    }
}
