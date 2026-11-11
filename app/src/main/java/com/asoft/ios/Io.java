package com.asoft.ios;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Stream helpers that exist on every Android the app installs on (minSdk 29). */
final class Io {
    private Io() {
    }

    /**
     * All of a stream. InputStream.readAllBytes() is Android 13's (API 33): on Android 10 to 12 it is a
     * NoSuchMethodError, which closed the app at its first screen (0.1.3 and before).
     */
    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        for (int n; (n = in.read(b)) > 0; ) {
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }
}
