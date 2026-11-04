package com.asoft.ios;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * The home screen: iOS 16's state on this phone and the one thing to do next (set up, start,
 * open), with the rest under More. The engine test stays there for comparing phones.
 */
public class MainActivity extends Activity {
    private LinearLayout box;
    private TextView state, saveInfo, engineLog;
    private Button primary;
    private LinearLayout more;
    private final Runnable engineRefresh = new Runnable() {
        @Override
        public void run() {
            File f = new File(getExternalFilesDir(null), EngineService.LOG);
            try {
                if (f.exists()) {
                    engineLog.setText(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                engineLog.setText(e.toString());
            }
            engineLog.postDelayed(this, 1000);
        }
    };

    private TextView text(LinearLayout to, String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        to.addView(t);
        return t;
    }

    private Button button(LinearLayout to, String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        to.addView(b);
        return b;
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setFitsSystemWindows(true);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(56, 96, 56, 56);
        scroll.addView(box);
        setContentView(scroll);

        TextView title = text(box, "ASoftware iOS", 30, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        text(box, "iOS 16 on this phone", 16, 0xff9a9a9a).setPadding(0, 0, 0, 48);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(48, 40, 48, 40);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff1c1c1e);
        bg.setCornerRadius(36);
        card.setBackground(bg);
        state = text(card, "", 18, Color.WHITE);
        saveInfo = text(card, "", 14, 0xff9a9a9a);
        saveInfo.setPadding(0, 12, 0, 24);
        primary = button(card, "", v -> primaryAction());
        primary.setTextSize(18);
        box.addView(card);

        Button moreButton = button(box, "More", null);
        moreButton.setBackgroundColor(Color.TRANSPARENT);
        moreButton.setTextColor(0xff0a84ff);
        more = new LinearLayout(this);
        more.setOrientation(LinearLayout.VERTICAL);
        more.setVisibility(View.GONE);
        box.addView(more);
        moreButton.setOnClickListener(v -> more.setVisibility(more.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        button(more, "Set up iOS 16 (bring it from the PC)", v -> startActivity(new Intent(this, SetupActivity.class)));
        button(more, "Boot iOS 16 (not from the saved state)", v -> {
            if (!Guest.running()) {
                GuestService.startCold(this);
            }
            startActivity(new Intent(this, ScreenActivity.class));
        });
        button(more, "Erase iPhone", v -> new AlertDialog.Builder(this)
                .setTitle("Erase iPhone?")
                .setMessage("Settings, installed apps and saves go; iOS starts as new. The iOS you set up stays.")
                .setPositiveButton("Erase", (d, w) -> {
                    if (Guest.running()) {
                        Toast.makeText(this, "Close iOS first (💾 > Save and close)", Toast.LENGTH_SHORT).show();
                    } else {
                        Guest.erase(this);
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show());
        text(more, "Engine test: runs iOS-style code on this phone's CPU the way iOS 16 does here, "
                + "and reports its speed. Useful when trying another phone.", 13, 0xff8a8a8a).setPadding(0, 32, 0, 8);
        button(more, "Engine test", v -> {
            EngineService.start(this, 2, 2, 120);
            engineLog.setVisibility(View.VISIBLE);
        });
        engineLog = text(more, "", 11, 0xffcfcfcf);
        engineLog.setTypeface(Typeface.MONOSPACE);
        engineLog.setTextIsSelectable(true);
        engineLog.setVisibility(new File(getExternalFilesDir(null), EngineService.LOG).exists() ? View.VISIBLE : View.GONE);
        engineLog.post(engineRefresh);

        if (saved == null && !Guest.available(this)) {
            startActivity(new Intent(this, SetupActivity.class));   // first start: set up first
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void primaryAction() {
        if (!Guest.available(this)) {
            startActivity(new Intent(this, SetupActivity.class));
        } else {
            startActivity(new Intent(this, ScreenActivity.class));
        }
    }

    /** What iOS is doing, and the saves (those come from qemu-img or QEMU: off the UI thread). */
    private void refresh() {
        boolean up = Guest.running();
        if (!Guest.available(this)) {
            state.setText("iOS 16 is not set up");
            saveInfo.setText("Bring it from your PC: install ASoftware iOS (iOS 16) there, then run Send-ToPhone.cmd.");
            primary.setText("Set up");
            return;
        }
        state.setText(up ? "iOS is running" : "iOS 16 is ready");
        primary.setText(up ? "Open iOS" : "Start iOS 16");
        saveInfo.setText(String.format(java.util.Locale.ROOT, "%.1f GB free on this phone", getFilesDir().getUsableSpace() / 1e9));
        new Thread(() -> {
            List<Guest.Snap> list = up || Guest.userDisk(this).exists() ? Guest.snapshots(this) : java.util.Collections.emptyList();
            Guest.Snap quick = null;
            for (Guest.Snap s : list) {
                if (Guest.QUICK.equals(s.tag)) {
                    quick = s;
                }
            }
            long free = getFilesDir().getUsableSpace();
            String info;
            if (up) {
                info = list.size() + (list.size() == 1 ? " save" : " saves");
            } else if (quick != null) {
                info = "Goes on from where you closed it (" + quick.date.substring(5, 16) + "), in about 10 s.";
            } else if (Guest.userDisk(this).exists()) {
                info = "Starts with a boot: about 5 minutes.";
            } else {
                info = "First start: about 5 minutes.";
            }
            if (!up && list.size() > (quick != null ? 1 : 0)) {
                info += " " + (list.size() - (quick != null ? 1 : 0)) + " named save(s) under 💾.";
            }
            String text = info + String.format(java.util.Locale.ROOT, "\n%.1f GB free on this phone.", free / 1e9);
            runOnUiThread(() -> saveInfo.setText(text));
        }, "home-refresh").start();
    }
}
