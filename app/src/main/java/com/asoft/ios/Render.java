package com.asoft.ios;

import android.view.Surface;

/** render.c: the guest framebuffer, from QEMU's RAM memfd, onto a Surface with GLES. */
final class Render {
    static {
        System.loadLibrary("render");
    }

    private Render() {}

    /** A memfd of @size for the guest's RAM, inherited by the next spawn(); its fd number, or -1. */
    static native int createGuestRam(long size);

    /** Starts @argv with @envp (K=V), output to @log, keeping the guest RAM fd; the pid, or -1. */
    static native int spawn(String[] argv, String[] envp, String log);

    static native boolean alive(int pid);

    static native void terminate(int pid);

    /** Maps the framebuffer of QEMU process @pid; null when done, else what went wrong. */
    static native String openFramebuffer(int pid, long offset, int width, int height, int stride);

    /** Binds GLES to @s; call on the render thread, like every call below. */
    static native boolean attach(Surface s);

    static native void detach();

    /** One display frame; false when nothing changed (nothing drawn). */
    static native boolean frame(int width, int height);

    static native long uploads();

    /** Unmaps the framebuffer and blackens the surface (render thread). */
    static native void clear();
}
