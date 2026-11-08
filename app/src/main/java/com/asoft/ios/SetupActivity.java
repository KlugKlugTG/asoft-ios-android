package com.asoft.ios;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Set up: bring iOS 16 in from the PC (pc/Send-ToPhone.cmd makes ASoftware-iOS16.asoft and,
 * with the phone on USB, pushes it to this app's import folder), or from a file picked here.
 */
public class SetupActivity extends Activity {
    private static final int PICK = 1;
    private LinearLayout box;
    private TextView status;
    private ProgressBar bar;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DevNight.apply(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setFitsSystemWindows(true);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(56, 72, 56, 56);
        scroll.addView(box);
        setContentView(scroll);
        show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!busy) {
            show();                     // a file may have arrived over adb meanwhile
        }
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(0, 0, 0, 28);
        box.addView(t);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        box.addView(b);
        return b;
    }

    private void show() {
        box.removeAllViews();
        TextView title = text("Set up iOS 16", 26, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        if (Installer.installed(this)) {
            text("iOS 16 is set up on this phone. Importing again replaces it: this iPhone's settings, apps and saves go.",
                    15, 0xffbbbbbb);
        }
        text("Bring it from your PC", 18, Color.WHITE);
        text("1. Install ASoftware iOS (iOS 16) on a Windows PC.\n"
                + "2. On the PC, run Send-ToPhone.cmd from the ASoftware iOS folder. It packs that iOS into ASoftware-iOS16.asoft (about 6.5 GB).\n"
                + "3. With this phone on USB and USB debugging on, it sends the file here by itself. "
                + "Otherwise copy the file to the phone (for example to Download) and choose it below.",
                15, 0xffbbbbbb);
        for (File f : Installer.waiting(this)) {
            button(String.format("Import %s (%.1f GB)", f.getName(), f.length() / 1e9), v -> {
                try {
                    run(new FileInputStream(f), f.length(), f);
                } catch (IOException e) {
                    status.setText(e.getMessage());
                }
            });
        }
        button("Choose file…", v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(i, PICK);
        });
        text(String.format("%.1f GB free on this phone; iOS needs about 6.5 GB, and 4 GB more for a save.",
                getFilesDir().getUsableSpace() / 1e9), 13, 0xff888888).setPadding(0, 28, 0, 28);
        text("Install on this phone from Apple", 18, 0xff777777);
        text("Not yet: building the disk here takes the phone about two hours and is still being worked on.",
                15, 0xff777777);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setVisibility(View.GONE);
        box.addView(bar);
        status = text("", 15, Color.WHITE);
        status.setGravity(Gravity.START);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri u = data.getData();
        long size = -1;
        try (Cursor c = getContentResolver().query(u, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                size = c.getLong(0);
            }
        }
        try {
            run(getContentResolver().openInputStream(u), size, null);
        } catch (IOException e) {
            status.setText(e.getMessage());
        }
    }

    /** Asks first when it replaces something, then imports in the background; @done is the pushed file to delete after. */
    private void run(InputStream in, long total, File done) {
        if (busy) {
            return;
        }
        if (Guest.running()) {
            status.setText("Close iOS first (side bar > Saves > Save and close).");
            return;
        }
        if (Installer.hasData(this)) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Replace iOS on this phone?")
                    .setMessage("This iPhone's settings, apps and saves are deleted first, then the new iOS is copied in.")
                    .setPositiveButton("Replace", (d, w) -> importNow(in, total, done))
                    .setNegativeButton("Cancel", (d, w) -> {
                        try {
                            in.close();
                        } catch (IOException ignored) {
                            // nothing read
                        }
                    })
                    .show();
            return;
        }
        importNow(in, total, done);
    }

    private void importNow(InputStream in, long total, File done) {
        busy = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        for (int i = 0; i < box.getChildCount(); i++) {
            if (box.getChildAt(i) instanceof Button) {
                box.getChildAt(i).setEnabled(false);
            }
        }
        bar.setVisibility(View.VISIBLE);
        status.setText("Starting… keep this screen open.");
        long t0 = System.currentTimeMillis();
        new Thread(() -> {
            String result;
            try (InputStream s = in) {
                Installer.install(this, new java.io.BufferedInputStream(s, 1 << 20), total, (what, at, all) -> runOnUiThread(() -> {
                    bar.setIndeterminate(all <= 0);
                    if (all > 0) {
                        bar.setProgress((int) (at * 1000 / all));
                    }
                    status.setText(all > 0 ? String.format("%s: %.1f of %.1f GB", what, at / 1e9, all / 1e9) : what + "…");
                }));
                if (done != null) {
                    done.delete();
                }
                String notes = Installer.kernelNotes(this);
                result = String.format("iOS 16 is set up (%d s).%s", (System.currentTimeMillis() - t0) / 1000,
                        notes.isEmpty() ? "" : "\n" + notes);
            } catch (IOException | RuntimeException e) {
                result = "Not set up: " + e.getMessage();
            }
            String r = result;
            runOnUiThread(() -> finishRun(r));
        }, "setup-import").start();
    }

    private void finishRun(String result) {
        busy = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        show();
        status.setText(result);
        if (Installer.installed(this) && result.startsWith("iOS 16 is set up")) {
            button("Start iOS 16 (the first start takes a few minutes)", v -> {
                startActivity(new Intent(this, ScreenActivity.class));
                finish();
            });
        }
    }
}
