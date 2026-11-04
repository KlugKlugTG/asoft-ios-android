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
    /** ⏏ was pressed: leave once the saved guest has quit. */
    private boolean closing;
    /** "Screen off" over the guest while iOS has its display off; a tap wakes it. */
    private TextView cover;
    private java.io.RandomAccessFile leds;
    private int ledsPid = -1;
    private long ledsRead;
    private boolean displayOff, displayWasOff, coverArmed;
    private long savingSince, savingShown = -1;
    /** The phone's own screen went off and that locked iOS: wake it when the user is back. */
    private boolean lockedByPhone;
    private final android.content.BroadcastReceiver phoneScreenOff = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, android.content.Intent i) {
            // the phone's power key never reaches an app; its effect does: iOS locks with the phone
            readLeds();
            if (Guest.running() && !Guest.saving() && !closing && !displayOff) {
                key("f2");
                lockedByPhone = true;
            }
        }
    };

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
        cover = new TextView(this);
        cover.setText(screenOffText("Screen off", "tap to wake"));
        cover.setTextColor(Color.WHITE);
        cover.setTextSize(18);
        cover.setGravity(Gravity.CENTER);
        cover.setBackgroundColor(Color.BLACK);
        cover.setVisibility(View.GONE);
        cover.setOnClickListener(v -> {
            if (!Guest.saving() && !closing) {
                key("f2");                  // the side button wakes iOS
            }
        });
        root.addView(cover, new FrameLayout.LayoutParams(-1, -1));
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
        registerReceiver(phoneScreenOff, new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF));
        // Android's back gesture (a swipe in from the left edge) is iOS's back gesture here, not "leave the app"
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.window.OnBackInvokedCallback cb = this::guestBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            backCallback = cb;
        }
    }

    /** Opened again (launcher, notification) while this screen exists: start iOS if it is not running. */
    @Override
    protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        if (!Guest.running() && !Guest.saving() && !closing) {
            restart.setVisibility(View.GONE);
            status.setText("starting");
            GuestService.start(this);
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
        bar.addView(button("💾", this::openSaves));
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

    /** Two lines for the black cover: a title and a smaller, dimmer hint. */
    private static CharSequence screenOffText(String title, String hint) {
        android.text.SpannableString t = new android.text.SpannableString(title + '\n' + hint);
        int at = title.length() + 1;
        t.setSpan(new android.text.style.RelativeSizeSpan(0.7f), at, t.length(), 0);
        t.setSpan(new android.text.style.ForegroundColorSpan(0xff9a9a9a), at, t.length(), 0);
        return t;
    }

    // -- saves: the side bar's 💾 lists them; a save is iOS as it was then (RAM and disk), about 3 GB

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show();
    }

    private static String gb(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1e9);
    }

    /** The list comes from QEMU or qemu-img: fetched off the UI thread, then shown. */
    private void openSaves() {
        if (Guest.saving() || closing) {
            return;
        }
        new Thread(() -> {
            java.util.List<Guest.Snap> list = Guest.snapshots(this);
            long free = getFilesDir().getUsableSpace();
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    showSaves(list, free);
                }
            });
        }, "saves-list").start();
    }

    private void showSaves(java.util.List<Guest.Snap> list, long free) {
        boolean up = Guest.running();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 16, 48, 0);
        TextView info = new TextView(this);
        info.setText(gb(free) + " free on the phone; a save takes about 3 GB.\n"
                + (list.isEmpty() ? "No saves yet." : "Tap a save to load or delete it."));
        info.setPadding(0, 0, 0, 16);
        box.addView(info);
        android.app.AlertDialog[] dialog = new android.app.AlertDialog[1];
        for (Guest.Snap snap : list) {
            Button row = new Button(this);
            row.setAllCaps(false);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setText(snap.label() + "\n" + snap.date + " · " + snap.size);
            row.setOnClickListener(v -> {
                dialog[0].dismiss();
                saveActions(snap);
            });
            box.addView(row);
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(box);
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle("Saves")
                .setView(scroll)
                .setNegativeButton("Close", null);
        if (up) {
            b.setPositiveButton("Save now", (d, w) -> askSaveName(list, free));
            b.setNeutralButton("Save and close", (d, w) -> saveAndClose());
        }
        dialog[0] = b.show();
    }

    private void askSaveName(java.util.List<Guest.Snap> list, long free) {
        if (free < 4_000_000_000L) {
            toast("Not enough space: " + gb(free) + " free, a save needs about 3 GB");
            return;
        }
        android.widget.EditText name = new android.widget.EditText(this);
        name.setSingleLine(true);
        name.setText(new java.text.SimpleDateFormat("'Save' d MMM HH.mm", java.util.Locale.ENGLISH)
                .format(new java.util.Date()));
        name.selectAll();
        FrameLayout pad = new FrameLayout(this);
        pad.setPadding(48, 16, 48, 0);
        pad.addView(name);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Name this save")
                .setMessage("iOS stops for up to a minute while it is saved, then goes on.")
                .setView(pad)
                .setPositiveButton("Save", (d, w) -> {
                    String tag = Guest.tagFor(name.getText().toString());
                    if (tag == null) {
                        toast("Use letters, digits, spaces, - or _");
                        return;
                    }
                    boolean exists = false;
                    for (Guest.Snap s : list) {
                        exists |= s.tag.equals(tag);
                    }
                    if (exists) {
                        new android.app.AlertDialog.Builder(this)
                                .setMessage("Replace the save \"" + tag.replace('_', ' ') + "\"?")
                                .setPositiveButton("Replace", (d2, w2) -> saveNamed(tag))
                                .setNegativeButton("Cancel", null)
                                .show();
                    } else {
                        saveNamed(tag);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveNamed(String tag) {
        new Thread(() -> {
            String why = Guest.saveNamed(this, tag);
            runOnUiThread(() -> toast(why == null ? "Saved \"" + tag.replace('_', ' ') + "\"" : "Not saved: " + why));
        }, "save-named").start();
    }

    private void saveActions(Guest.Snap snap) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(snap.label())
                .setMessage(snap.date + " · " + snap.size + "\n\n"
                        + (Guest.running() ? "Loading it replaces the iOS running now: what happened since its last save is lost."
                                : "Loading it starts iOS from that moment."))
                .setPositiveButton("Load", (d, w) -> {
                    status.setText("Loading \"" + snap.label() + "\"…");
                    restart.setVisibility(View.GONE);
                    GuestService.load(this, snap.tag);
                })
                .setNeutralButton("Delete", (d, w) -> new android.app.AlertDialog.Builder(this)
                        .setMessage("Delete the save \"" + snap.label() + "\"?")
                        .setPositiveButton("Delete", (d2, w2) -> new Thread(() -> {
                            String why = Guest.deleteSnapshot(this, snap.tag);
                            runOnUiThread(() -> toast(why == null ? "Deleted" : "Not deleted: " + why));
                        }, "save-delete").start())
                        .setNegativeButton("Cancel", null)
                        .show())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Quick boot: keep iOS as it is, close; the next start goes on from here. */
    private void saveAndClose() {
        if (!Guest.running()) {
            finishAndRemoveTask();
            return;
        }
        closing = true;
        GuestService.save(this);
    }

    /** The guest's display power from QEMU's LED byte (Guest.ledsFile); at most every 100 ms. */
    private void readLeds() {
        int pid = Guest.pid(this);
        if (pid != ledsPid) {
            ledsPid = pid;
            displayOff = displayWasOff = false;
            coverArmed = Guest.resumed;     // a loaded state is past the boot's own display off
            try {
                if (leds != null) {
                    leds.close();
                }
                leds = new java.io.RandomAccessFile(Guest.ledsFile(this), "r");
            } catch (java.io.IOException e) {
                leds = null;
            }
        }
        if (leds == null) {
            return;
        }
        try {
            leds.seek(0);
            int b = leds.read();
            boolean off = b >= 0 && (b & Guest.SCROLL_LOCK_LED) != 0;
            if (off) {
                displayWasOff = true;
            } else if (displayWasOff) {
                coverArmed = true;          // iOS turns the display off once under the Apple logo
            }
            displayOff = off;
        } catch (java.io.IOException ignored) {
            // keep the last state
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lockedByPhone) {
            lockedByPhone = false;
            readLeds();
            if (displayOff && Guest.running()) {
                key("f2");                  // back from the phone's lock screen: wake iOS too
            }
        }
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
        unregisterReceiver(phoneScreenOff);
        render.post(Render::detach);
        renderThread.quitSafely();
        super.onDestroy();
    }

    // -- the guest: open its framebuffer and QMP once it is up

    @Override
    public void doFrame(long frameTimeNanos) {
        Choreographer.getInstance().postFrameCallback(this);
        long now = System.currentTimeMillis();
        if (closing && !Guest.running()) {
            finishAndRemoveTask();
            return;
        }
        // saving takes ~25 s with iOS paused: the whole screen says so, or it looks hung
        if (Guest.saving() || closing && Guest.running()) {
            long s = savingSince == 0 ? 0 : (now - savingSince) / 1000;
            if (savingSince == 0 || s != savingShown) {
                if (savingSince == 0) {
                    savingSince = now;
                }
                savingShown = s;
                cover.setText(screenOffText("Saving iOS", s + " s"));
                cover.setVisibility(View.VISIBLE);
                status.setText("");
            }
            return;
        }
        if (savingSince != 0) {
            savingSince = 0;
            cover.setText(screenOffText("Screen off", "tap to wake"));
        }
        if (now - ledsRead >= 100) {
            ledsRead = now;
            readLeds();
            boolean show = displayOff && coverArmed && Guest.running();
            if (show != (cover.getVisibility() == View.VISIBLE)) {
                cover.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        }
        if ("resuming".equals(Guest.state) || "switching".equals(Guest.state)) {
            status.setText("Loading the saved iOS…");
        } else if (!closing && status.getText().length() > 0 && fbPid == Guest.pid(this)
                && ("running".equals(Guest.state) || "booting".equals(Guest.state))) {
            status.setText("");
        }
        if (!Guest.running() && !"switching".equals(Guest.state)) {
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
