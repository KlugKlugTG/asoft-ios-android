package com.asoft.ios;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * iOS full screen: the guest's framebuffer drawn every display frame it changes, a finger is
 * the guest's touchscreen, a slim bar holds Home and the side button, the phone's volume keys
 * are the guest's.
 */
public class ScreenActivity extends Activity implements SurfaceHolder.Callback, Choreographer.FrameCallback {
    private SurfaceView view;
    private TextView status;
    private HandlerThread renderThread;
    private Handler render;
    private volatile boolean attached;
    private volatile int surfW, surfH;
    private int fbPid = -1;             // the QEMU whose framebuffer is mapped
    private long lastTry;
    private Button restart;
    private Object backCallback;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        getWindow().setDecorFitsSystemWindows(false);
        view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        root.addView(view);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(13);
        status.setPadding(24, 24, 24, 24);
        root.addView(status, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        // mid right edge: iOS keeps nothing there on the lock screen, home screen or dock
        root.addView(buttonBar(), new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL | Gravity.END));
        restart = new Button(this);
        restart.setText("Restart iOS");
        restart.setVisibility(View.GONE);
        restart.setOnClickListener(v -> {
            restart.setVisibility(View.GONE);
            status.setText("starting");
            GuestService.start(this);
        });
        root.addView(restart, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(root);
        view.setOnTouchListener(this::onTouch);
        renderThread = new HandlerThread("render");
        renderThread.start();
        render = new Handler(renderThread.getLooper());
        if (!Guest.running()) {
            GuestService.start(this);
        }
        // Android's back gesture (a swipe in from the left edge) is iOS's back gesture here, not "leave the app"
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.window.OnBackInvokedCallback cb = this::guestBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            backCallback = cb;
        }
    }

    private void guestBack() {
        Qmp q = Guest.qmp();
        if (q != null) {
            q.edgeSwipeBack();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        guestBack();
    }

    private void key(String qcode) {
        Qmp q = Guest.qmp();
        if (q != null) {
            q.key(qcode);
        }
    }

    private View buttonBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(8, 8, 8, 8);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x55000000);
        bg.setCornerRadius(24);
        bar.setBackground(bg);
        bar.addView(button("⌂", () -> key("f1")));
        bar.addView(button("⏻", () -> key("f2")));
        bar.setAlpha(0.35f);
        return bar;
    }

    private Button button(String label, Runnable r) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(16, 8, 16, 8);
        // never focused: Enter from a physical keyboard would click it (Home!)
        b.setFocusable(false);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onPause() {
        Choreographer.getInstance().removeFrameCallback(this);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) {
            immersive();
        }
    }

    private void immersive() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    protected void onDestroy() {
        // the guest and its QMP connection stay: they belong to GuestService, not to this screen
        if (backCallback != null && android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) backCallback);
        }
        render.post(Render::detach);
        renderThread.quitSafely();
        super.onDestroy();
    }

    // -- the guest: open its framebuffer and QMP once it is up

    @Override
    public void doFrame(long frameTimeNanos) {
        Choreographer.getInstance().postFrameCallback(this);
        long now = System.currentTimeMillis();
        if (!Guest.running()) {
            if (now - lastTry > 1000) {
                lastTry = now;
                String why = Guest.lastWords(this);
                status.setText("iOS stopped" + (why.isEmpty() ? "" : ": " + why));
                restart.setVisibility(View.VISIBLE);
                if (fbPid != -1) {
                    render.post(Render::clear);     // not the stopped guest's last frame
                }
                fbPid = -1;
            }
            return;
        }
        int pid = Guest.pid(this);
        if (fbPid != pid && now - lastTry > 1000) {
            lastTry = now;
            String err = Render.openFramebuffer(pid, Guest.FB_BASE - Guest.RAM_BASE,
                    Guest.FB_W, Guest.FB_H, Guest.FB_STRIDE);
            fbPid = err == null ? pid : -1;
            status.setText(err == null ? "" : "starting: " + err);
            restart.setVisibility(View.GONE);
        }
        if (fbPid == pid && attached) {
            int w = surfW, h = surfH;
            render.post(() -> Render.frame(w, h));
        }
    }

    // -- the surface

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        render.post(() -> attached = Render.attach(h.getSurface()));
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) {
        surfW = w;
        surfH = hgt;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        attached = false;
        // the surface goes when this returns: GLES must have let go of it by then
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        render.post(() -> {
            Render.detach();
            done.countDown();
        });
        try {
            done.await(1, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // -- input

    /** iOS 16's volume buttons are F3 and F4 (bsd/dev/sweet_vinput.c). */
    private static String volumeKey(int code) {
        return code == KeyEvent.KEYCODE_VOLUME_DOWN ? "f3" : code == KeyEvent.KEYCODE_VOLUME_UP ? "f4" : null;
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        String volume = volumeKey(code);
        if (volume != null && Guest.qmp() != null) {
            Guest.qmp().keyEvent(volume, false);
            return true;
        }
        return super.onKeyUp(code, e);
    }

    /** Where the guest screen is inside the view (aspect kept, centred). */
    private float[] guestPoint(float x, float y) {
        float vw = view.getWidth(), vh = view.getHeight();
        float g = (float) Guest.FB_W / Guest.FB_H;
        float w = vw, h = vh;
        if (vw / vh > g) {
            w = vh * g;
        } else {
            h = vw / g;
        }
        return new float[] { (x - (vw - w) / 2) / w, (y - (vh - h) / 2) / h };
    }

    private boolean onTouch(View v, MotionEvent e) {
        Qmp qmp = Guest.qmp();
        if (qmp == null) {
            return true;
        }
        float[] p = guestPoint(e.getX(), e.getY());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                qmp.touch(p[0], p[1], true);
                break;
            case MotionEvent.ACTION_MOVE:
                qmp.touch(p[0], p[1], null);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                qmp.touch(p[0], p[1], false);
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        String volume = volumeKey(code);
        if (volume != null && Guest.qmp() != null) {
            // down here, up in onKeyUp: holding is iOS's to see; repeats and the release must
            // not reach Android, or the phone's own volume moves too
            if (e.getRepeatCount() == 0) {
                Guest.qmp().keyEvent(volume, true);
            }
            return true;
        }
        return super.onKeyDown(code, e);
    }
}
