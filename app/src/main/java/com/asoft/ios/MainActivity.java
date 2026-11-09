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
    /** iOS 26's card, beside iOS 16's: shown when its files are on the phone (or, debug builds, why not). */
    private LinearLayout card26;
    private TextView state26, info26;
    private Button primary26;
    /** watchOS's card (WatchProfile): shown when its files are on the phone. */
    private LinearLayout cardWatch;
    private TextView stateWatch, infoWatch;
    private Button primaryWatch;
    private LinearLayout more, running;
    private TextView freeRam, powerWarn, scale26Row, ram26Row, ramWatchRow, watchBootRow, watchEraseRow;
    private LinearLayout updateBar;
    private TextView updateText, updateRow;
    /**
     * The clock check again every 10 s while this screen shows: at onResume the phone has just
     * lifted its CPU cap for the app's launch (A71: 2.21 GHz then, 1.56 a few seconds later).
     */
    private final Runnable powerRecheck = new Runnable() {
        @Override
        public void run() {
            showPowerWarning();
            powerWarn.postDelayed(this, 10_000);
        }
    };
    /** Power saving switched while this screen shows: the warning follows it. */
    private final android.content.BroadcastReceiver powerChanged = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, Intent i) {
            showPowerWarning();
        }
    };
    private final Runnable engineRefresh = new Runnable() {
        @Override
        public void run() {
            File f = new File(getExternalFilesDir(null), EngineService.LOG);
            try {
                if (f.exists()) {
                    String[] lines = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n");
                    int from = Math.max(0, lines.length - 30);
                    engineLog.setText(String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length)));
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

    /** A rounded group, as iOS's Settings draws one; @top: space above it. */
    private LinearLayout group(LinearLayout to, int top) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff1c1c1e);
        bg.setCornerRadius(36);
        g.setBackground(bg);
        g.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = top;
        to.addView(g, lp);
        return g;
    }

    /** A row in a group: a thin line above it unless it is the first. */
    private TextView row(LinearLayout group, String label, int color, View.OnClickListener l) {
        if (group.getChildCount() > 0) {
            View line = new View(this);
            line.setBackgroundColor(0xff38383a);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 2);
            lp.leftMargin = 48;
            group.addView(line, lp);
        }
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(16);
        t.setTextColor(color);
        t.setPadding(48, 40, 48, 40);
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        t.setBackgroundResource(tv.resourceId);
        t.setOnClickListener(l);
        group.addView(t);
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
        DevNight.apply(this);
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
        text(box, (Guest.available26(this) ? "iOS 16 and iOS 26" : "iOS 16")
                + (WatchProfile.available(this) ? ", and watchOS," : "") + " on this phone", 16, 0xff9a9a9a)
                .setPadding(0, 0, 0, 48);

        addUpdateBar();

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

        card26 = new LinearLayout(this);
        card26.setOrientation(LinearLayout.VERTICAL);
        card26.setPadding(48, 40, 48, 40);
        GradientDrawable bg26 = new GradientDrawable();
        bg26.setColor(0xff1c1c1e);
        bg26.setCornerRadius(36);
        card26.setBackground(bg26);
        state26 = text(card26, "", 18, Color.WHITE);
        info26 = text(card26, "", 14, 0xff9a9a9a);
        info26.setPadding(0, 12, 0, 24);
        primary26 = button(card26, "", v -> primary26Action());
        primary26.setTextSize(18);
        LinearLayout.LayoutParams c26 = new LinearLayout.LayoutParams(-1, -2);
        c26.topMargin = 24;
        box.addView(card26, c26);

        cardWatch = new LinearLayout(this);
        cardWatch.setOrientation(LinearLayout.VERTICAL);
        cardWatch.setPadding(48, 40, 48, 40);
        GradientDrawable bgW = new GradientDrawable();
        bgW.setColor(0xff1c1c1e);
        bgW.setCornerRadius(36);
        cardWatch.setBackground(bgW);
        stateWatch = text(cardWatch, "", 18, Color.WHITE);
        infoWatch = text(cardWatch, "", 14, 0xff9a9a9a);
        infoWatch.setPadding(0, 12, 0, 24);
        primaryWatch = button(cardWatch, "", v -> {
            if (WatchProfile.available(this)) {
                open(Guest.WATCH);
            }
        });
        primaryWatch.setTextSize(18);
        LinearLayout.LayoutParams cW = new LinearLayout.LayoutParams(-1, -2);
        cW.topMargin = 24;
        box.addView(cardWatch, cW);

        // power saving slows iOS several times over: say so above everything else
        powerWarn = new TextView(this);
        powerWarn.setTextSize(15);
        powerWarn.setTextColor(0xffff9f0a);
        powerWarn.setPadding(48, 36, 48, 36);
        GradientDrawable warnBg = new GradientDrawable();
        warnBg.setColor(0xff2c2210);
        warnBg.setCornerRadius(36);
        powerWarn.setBackground(warnBg);
        powerWarn.setOnClickListener(v -> PowerCheck.openSettings(this));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
        wlp.topMargin = 24;
        box.addView(powerWarn, wlp);
        powerWarn.setVisibility(View.GONE);

        Button moreButton = button(box, "More", null);
        moreButton.setBackgroundColor(Color.TRANSPARENT);
        moreButton.setTextColor(0xff0a84ff);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
        mlp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        mlp.topMargin = 16;
        moreButton.setLayoutParams(mlp);
        more = new LinearLayout(this);
        more.setOrientation(LinearLayout.VERTICAL);
        more.setVisibility(View.GONE);
        box.addView(more);
        moreButton.setOnClickListener(v -> {
            boolean open = more.getVisibility() != View.VISIBLE;
            more.setVisibility(open ? View.VISIBLE : View.GONE);
            moreButton.setText(open ? "Less" : "More");
        });

        running = group(more, 16);
        row(running, "Turn off (no save)", 0xffff453a, v -> new AlertDialog.Builder(this)
                .setTitle("Turn off " + (Guest.os(this) == Guest.WATCH ? "watchOS" : "iOS") + "?")
                .setMessage((Guest.os(this) == Guest.WATCH ? "watchOS shuts down as an Apple Watch does"
                        : "iOS shuts down as an iPhone does") + ": settings and apps stay, open apps close. "
                        + "The next start boots (a few minutes). Named saves stay."
                        + (Guest.os(this) == Guest.IOS26 ? "\n\niOS 26 is slow for about 15 minutes after a boot (its own "
                        + "start-up work). Save and close keeps it as it is, past that." : ""))
                .setPositiveButton("Turn off", (d, w) -> {
                    if (Guest.running()) {
                        GuestService.powerOff(this);
                        state.setText("Turning iOS off…");
                        state.postDelayed(this::refreshUntilOff, 2000);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show());
        LinearLayout ios = group(more, 16);
        freeRam = row(ios, "", Color.WHITE, v -> toggleFreeRam());
        updateRow = row(ios, "", Color.WHITE, v -> {
            UpdateCheck.setEnabled(this, !UpdateCheck.enabled(this));
            showUpdate();
            UpdateCheck.check(this, this::showUpdate);
        });
        row(ios, "Set up: iOS 16, iOS 26 and watchOS (from Apple, or from the PC)", Color.WHITE, v -> startActivity(new Intent(this, SetupActivity.class)));
        row(ios, "Boot iOS 16, not from the saved state", Color.WHITE, v -> whenPowerOk(() -> {
            if (!Guest.running()) {
                Guest.select(this, Guest.IOS16);
                GuestService.startCold(this);
            }
            startActivity(new Intent(this, ScreenActivity.class));
        }));
        row(ios, "Boot iOS 26, not from the saved state", Color.WHITE, v -> whenPowerOk(() -> {
            if (!Guest.running()) {
                Guest.select(this, Guest.IOS26);
                GuestService.startCold(this);
            }
            startActivity(new Intent(this, ScreenActivity.class));
        }));
        scale26Row = row(ios, "", Color.WHITE, v -> chooseScale26());
        ram26Row = row(ios, "", Color.WHITE, v -> chooseRam(Guest.IOS26));
        ramWatchRow = row(ios, "", Color.WHITE, v -> chooseRam(Guest.WATCH));
        watchBootRow = row(ios, "Boot watchOS, not from the saved state", Color.WHITE, v -> whenPowerOk(() -> {
            if (!Guest.running()) {
                Guest.select(this, Guest.WATCH);
                GuestService.startCold(this);
            }
            startActivity(new Intent(this, ScreenActivity.class));
        }));
        watchEraseRow = row(ios, "Erase watchOS's watch", 0xffff453a, v -> new AlertDialog.Builder(this)
                .setTitle("Erase watchOS's watch?")
                .setMessage("The watch's settings, faces and saves go; it starts as it came. watchOS itself stays.")
                .setPositiveButton("Erase", (d, w) -> {
                    if (Guest.running()) {
                        Toast.makeText(this, "Close it first", Toast.LENGTH_SHORT).show();
                    } else {
                        Guest.select(this, Guest.WATCH);
                        Guest.erase(this, Guest.WATCH);
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show());
        row(ios, "Erase iOS 26's iPhone", 0xffff453a, v -> new AlertDialog.Builder(this)
                .setTitle("Erase iOS 26's iPhone?")
                .setMessage("iOS 26's settings, apps and saves go; it starts as new. iOS 26 itself stays.")
                .setPositiveButton("Erase", (d, w) -> {
                    if (Guest.running()) {
                        Toast.makeText(this, "Close iOS first", Toast.LENGTH_SHORT).show();
                    } else {
                        Guest.select(this, Guest.IOS26);
                        Guest.erase(this, Guest.IOS26);
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show());
        row(ios, "Erase iPhone", 0xffff453a, v -> new AlertDialog.Builder(this)
                .setTitle("Erase iPhone?")
                .setMessage("Settings, installed apps and saves go; iOS starts as new. The iOS you set up stays.")
                .setPositiveButton("Erase", (d, w) -> {
                    if (Guest.running()) {
                        Toast.makeText(this, "Close iOS first (side bar > Saves > Save and close)", Toast.LENGTH_SHORT).show();
                    } else {
                        Guest.select(this, Guest.IOS16);
                        Guest.erase(this, Guest.IOS16);
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show());
        LinearLayout engine = group(more, 40);
        row(engine, "Engine test", 0xff0a84ff, v -> {
            EngineService.start(this, 2, 2, 120);
            engineLog.setVisibility(View.VISIBLE);
        });
        TextView about = text(more, "Runs iOS-style code on this phone's CPU the way iOS 16 does here, and reports "
                + "its speed: useful when trying another phone.", 13, 0xff8a8a8a);
        about.setPadding(48, 16, 48, 16);
        engineLog = text(more, "", 11, 0xffcfcfcf);
        engineLog.setTypeface(Typeface.MONOSPACE);
        engineLog.setTextIsSelectable(true);
        engineLog.setPadding(36, 28, 36, 28);
        GradientDrawable logBg = new GradientDrawable();
        logBg.setColor(0xff111113);
        logBg.setCornerRadius(24);
        engineLog.setBackground(logBg);
        engineLog.setVisibility(new File(getExternalFilesDir(null), EngineService.LOG).exists() ? View.VISIBLE : View.GONE);
        engineLog.post(engineRefresh);

        if (saved == null && !Guest.available(this) && !Guest.available26(this)) {
            startActivity(new Intent(this, SetupActivity.class));   // first start: set up first
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        android.content.IntentFilter f = new android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(powerChanged, f, RECEIVER_NOT_EXPORTED);   // a system broadcast still comes
        } else {
            registerReceiver(powerChanged, f);
        }
        refresh();
        showUpdate();
        UpdateCheck.check(this, this::showUpdate);
        powerWarn.postDelayed(powerRecheck, 3_000);
    }

    /** The notice that a newer release is out, hidden until the check says so. */
    private void addUpdateBar() {
        updateBar = new LinearLayout(this);
        updateBar.setOrientation(LinearLayout.VERTICAL);
        updateBar.setPadding(48, 36, 48, 20);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff10243a);
        bg.setCornerRadius(36);
        updateBar.setBackground(bg);
        updateText = text(updateBar, "", 15, 0xff64b5ff);
        updateText.setPadding(0, 0, 0, 12);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        button(buttons, "Release page", v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(UpdateCheck.RELEASE_PAGE)));
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(this, UpdateCheck.RELEASE_PAGE, Toast.LENGTH_LONG).show();
            }
        }).setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        button(buttons, "Dismiss", v -> {
            String tag = UpdateCheck.toShow(this);
            if (tag != null) {
                UpdateCheck.dismiss(this, tag);
            }
            showUpdate();
        }).setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        updateBar.addView(buttons);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = 24;
        box.addView(updateBar, lp);
        updateBar.setVisibility(View.GONE);
    }

    private void showUpdate() {
        String tag = UpdateCheck.toShow(this);
        updateBar.setVisibility(tag == null ? View.GONE : View.VISIBLE);
        if (tag != null) {
            updateText.setText(tag + " is out. This app is " + UpdateCheck.version(this) + ".");
        }
        updateRow.setText("Check for updates: " + (UpdateCheck.enabled(this) ? "On" : "Off"));
    }

    @Override
    protected void onPause() {
        unregisterReceiver(powerChanged);
        powerWarn.removeCallbacks(powerRecheck);
        super.onPause();
    }

    private void showPowerWarning() {
        String w = PowerCheck.warning(this);
        powerWarn.setText(w == null ? "" : w);
        powerWarn.setVisibility(w == null ? View.GONE : View.VISIBLE);
    }

    /**
     * Runs @go, first asking when power saving is on and iOS is not running yet (a boot or a
     * load then takes several times longer).
     */
    private void whenPowerOk(Runnable go) {
        if (Guest.running() || !PowerCheck.saverOn(this)) {
            go.run();
            return;
        }
        String cap = PowerCheck.capped();
        new AlertDialog.Builder(this)
                .setTitle("Power saving is on")
                .setMessage("It slows the CPU" + (cap != null ? " (now " + cap + ")" : "") + ", and iOS runs on "
                        + "the CPU: a boot can take several times longer and apps open slowly. Turn power saving "
                        + "off while you use iOS.")
                .setPositiveButton("Open the setting", (d, w) -> PowerCheck.openSettings(this))
                .setNeutralButton("Start anyway", (d, w) -> go.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void primaryAction() {
        if (!Guest.available(this) || SetupService.busy()) {
            startActivity(new Intent(this, SetupActivity.class));
        } else {
            open(Guest.IOS16);
        }
    }

    private void primary26Action() {
        if (Guest.available26(this)) {
            open(Guest.IOS26);
        } else {
            startActivity(new Intent(this, SetupActivity.class));     // iOS 26 from Apple (Setup26Screen)
        }
    }

    /** Opens iOS @os: its screen starts it (quick boot when it was saved). One QEMU at a time. */
    private void open(int os) {
        if (Guest.running() && Guest.os(this) != os) {
            String other = Guest.osName(Guest.os(this));
            new AlertDialog.Builder(this)
                    .setTitle(other + " is running")
                    .setMessage("One at a time: close " + other + " first (\uD83D\uDCBE > Save and close, or Turn off).")
                    .setPositiveButton("Open " + other, (d, w) -> startActivity(new Intent(this, ScreenActivity.class)))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        whenPowerOk(() -> {
            Guest.select(this, os);
            startActivity(new Intent(this, ScreenActivity.class));
        });
    }

    /**
     * iOS 26's screen, 3x or 2x (Guest.scale26). 2x: the same layout with 2.25 times fewer pixels, so
     * this phone's CPU, which draws much of iOS, is done sooner after a boot. Each scale is its own
     * iPhone (Guest.diskName), so switching loses nothing.
     */
    private void chooseScale26() {
        if (Guest.running() && Guest.os(this) == Guest.IOS26) {
            Toast.makeText(this, "Close iOS 26 first (side bar > Saves > Save and close)", Toast.LENGTH_SHORT).show();
            return;
        }
        int now = Guest.scale26(this);
        int other = now == 2 ? 3 : 2;
        new AlertDialog.Builder(this)
                .setTitle("iOS 26's screen")
                .setMessage("Now " + (now == 2 ? "2x (faster)" : "3x (sharp, as on an iPhone)") + ".\n\n"
                        + "2x draws 2.25 times fewer pixels: after a boot the home screen and apps come up "
                        + "sooner on this phone; text is a little softer. Its first boot takes longer while "
                        + "iOS makes its caches for 2x.\n\n"
                        + "Each setting is its own iPhone, with its own settings, apps and saves; switching "
                        + "back finds the other one as you left it.")
                .setPositiveButton(other == 2 ? "Use 2x" : "Use 3x", (d, w) -> {
                    Guest.setScale26(this, other);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String ramRowText(int os) {
        return (os == Guest.WATCH ? "watchOS's memory: " : "iOS 26's memory: ") + Guest.ramMiB(this, os) / 1024 + " GB"
                + (Guest.ramMiB(this, os) == Guest.ramDefault(this, os) ? " (default for this phone)" : "");
    }

    /**
     * The guest's RAM (Guest.ramMiB): less leaves Android more, so it closes iOS less often on a phone with
     * less RAM; more keeps more apps alive inside iOS. A save loads with the size it was made with, so a new
     * size takes effect at the next full start.
     */
    private void chooseRam(int os) {
        int[] ch = os == Guest.WATCH ? Guest.RAM_CHOICES_WATCH : Guest.RAM_CHOICES_26;
        int now = Guest.ramMiB(this, os), def = Guest.ramDefault(this, os);
        String[] names = new String[ch.length];
        int pick = 0;
        for (int i = 0; i < ch.length; i++) {
            names[i] = ch[i] / 1024 + " GB" + (ch[i] == def ? " (default for this phone)" : "");
            if (ch[i] == now) {
                pick = i;
            }
        }
        int[] chosen = {pick};
        new AlertDialog.Builder(this)
                // (a message would hide the list: the explanation is in the title and the toast)
                .setTitle(String.format(java.util.Locale.ROOT, "%s's memory (this phone: %.1f GB; less leaves Android more)",
                        os == Guest.WATCH ? "watchOS" : "iOS 26", GpuCheck.ram(this) / 1e9))
                .setSingleChoiceItems(names, pick, (d, w) -> chosen[0] = w)
                .setPositiveButton("Use", (d, w) -> {
                    boolean changed = ch[chosen[0]] != now;
                    Guest.setRamMiB(this, os, ch[chosen[0]]);
                    refresh();
                    if (changed) {
                        Toast.makeText(this, "From the next full start (Boot … not from the saved state): a save opens with "
                                + "the memory it was made with", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** iOS 26's card: hidden in a release build until its files are on the phone. */
    private void refresh26() {
        boolean debug = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        String missing = Guest.missing26(this);
        // (shown without its files too: its button leads to Set up, which installs it from Apple)
        card26.setVisibility(View.VISIBLE);
        boolean twoX = Guest.scale26(this) == 2;
        if (scale26Row != null) {
            scale26Row.setText("iOS 26's screen: " + (twoX ? "2x (faster)" : "3x (sharp)"));
            scale26Row.setVisibility(missing == null ? View.VISIBLE : View.GONE);
        }
        if (ram26Row != null) {
            ram26Row.setText(ramRowText(Guest.IOS26));
            ram26Row.setVisibility(missing == null ? View.VISIBLE : View.GONE);
        }
        boolean up = Guest.running() && Guest.os(this) == Guest.IOS26;
        if (missing != null) {
            boolean busy = SetupService.busy() && "a26".equals(SetupService.state.target);
            state26.setText(busy ? "iOS 26 / watchOS are being set up" : "iOS 26 is not on this phone");
            info26.setText(busy ? SetupService.state.text
                    : "Set up downloads it from Apple (about 10 GB) and builds it on this phone, after a check of its GPU."
                    + (debug ? " (No " + new File(Guest.dir26(this), missing) + ".)" : ""));
            primary26.setText(busy ? "Open Set up" : "Set up iOS 26 or watchOS");
            primary26.setVisibility(View.VISIBLE);
            return;
        }
        primary26.setVisibility(View.VISIBLE);
        state26.setText(up ? "iOS 26 is running" : "iOS 26 is ready");
        primary26.setText(up ? "Open iOS 26" : "Start iOS 26");
        info26.setText("On the phone's GPU.");
        new Thread(() -> {
            List<Guest.Snap> list = up || Guest.userDisk(this, Guest.IOS26).exists()
                    ? Guest.snapshots(this, Guest.IOS26) : java.util.Collections.emptyList();
            Guest.Snap quick = null;
            for (Guest.Snap s : list) {
                if (Guest.isQuick(s.tag)) {
                    quick = s;
                }
            }
            String info;
            if (up) {
                info = "On the phone's GPU. " + list.size() + (list.size() == 1 ? " save" : " saves");
            } else if (quick != null) {
                // the last close, or an automatic save (AutoSave: settled after a boot, or before a crash)
                info = "Goes on from its last save (" + quick.date.substring(5, 16) + ").";
            } else {
                // measured 2026-11-05 (APK-PLAN §6.20): every vCPU busy for ~13 min after a boot, idle after ~15
                info = "On the phone's GPU. Starts with a boot: about 3 minutes to the lock screen on our phone, then "
                        + "about 15 minutes of iOS's own start-up work, slow until it ends. Save and close keeps iOS past it.";
            }
            String err = Guest.startError;
            String text = (!up && err != null && Guest.selected(this) == Guest.IOS26 ? "Did not start: " + err + "\n\n" : "")
                    + info + (twoX ? " Screen 2x." : "");
            runOnUiThread(() -> info26.setText(text));
        }, "home-refresh26").start();
    }

    /** watchOS's card: only when its files are on the phone (WatchProfile.dir), in every build. */
    private void refreshWatch() {
        boolean have = WatchProfile.available(this);
        cardWatch.setVisibility(have ? View.VISIBLE : View.GONE);
        watchBootRow.setVisibility(have ? View.VISIBLE : View.GONE);
        watchEraseRow.setVisibility(have ? View.VISIBLE : View.GONE);
        ramWatchRow.setText(ramRowText(Guest.WATCH));
        ramWatchRow.setVisibility(have ? View.VISIBLE : View.GONE);
        if (!have) {
            return;
        }
        boolean up = Guest.running() && Guest.os(this) == Guest.WATCH;
        stateWatch.setText(up ? "watchOS is running" : "watchOS 27.2 is ready");
        primaryWatch.setText(up ? "Open watchOS" : "Start watchOS");
        infoWatch.setText("Apple Watch Series 9 on the phone's GPU.");
        new Thread(() -> {
            List<Guest.Snap> list = up || Guest.userDisk(this, Guest.WATCH).exists()
                    ? Guest.snapshots(this, Guest.WATCH) : java.util.Collections.emptyList();
            Guest.Snap quick = null;
            for (Guest.Snap s : list) {
                if (Guest.isQuick(s.tag)) {
                    quick = s;
                }
            }
            String info;
            if (up) {
                info = "Apple Watch Series 9 on the phone's GPU. " + list.size() + (list.size() == 1 ? " save" : " saves");
            } else if (quick != null) {
                info = "Goes on from its last save (" + quick.date.substring(5, 16) + ").";
            } else {
                // measured 2026-11-07 (WATCHOS.md §2k): the face 5-7 minutes after a boot on our phone
                info = "Apple Watch Series 9 on the phone's GPU. Starts with a boot: about 5 to 7 minutes to the "
                        + "watch face on our phone. The crown and the side button are under its screen.";
            }
            String err = Guest.startError;
            String text = (!up && err != null && Guest.selected(this) == Guest.WATCH ? "Did not start: " + err + "\n\n" : "")
                    + info;
            runOnUiThread(() -> infoWatch.setText(text));
        }, "home-refresh-watch").start();
    }

    private void showFreeRam() {
        if (!MemoryCleaner.supported()) {
            freeRam.setText("Free memory while iOS runs: not on Android 14 and later (Android lets an app close only its own processes)");
            freeRam.setTextColor(0xff8a8a8a);
        } else {
            freeRam.setText("Free memory while iOS runs: " + (MemoryCleaner.enabled(this) ? "On" : "Off"));
        }
    }

    private void toggleFreeRam() {
        if (!MemoryCleaner.supported()) {
            return;
        }
        if (MemoryCleaner.enabled(this)) {
            MemoryCleaner.setEnabled(this, false);
            showFreeRam();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Free memory while iOS runs?")
                .setMessage("While iOS runs, and only then, this app ends the background processes of your other apps "
                        + "every 90 s, as Android does itself when memory runs short. iOS needs 4 to 5 GB, and a save "
                        + "3 GB more; without the room Android may close this app, iOS with it.\n\n"
                        + "Your other apps are not force-stopped: their alarms and notifications stay. But an app "
                        + "you left in the background starts again from its first screen when you open it, and "
                        + "music or a call keeps running only if it shows a notification.")
                .setPositiveButton("Turn on", (d, w) -> {
                    MemoryCleaner.setEnabled(this, true);
                    showFreeRam();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshUntilOff() {
        if (Guest.running() || Guest.saving()) {
            state.setText("Turning iOS off…");
            state.postDelayed(this::refreshUntilOff, 2000);
        } else {
            refresh();
        }
    }

    /** What iOS is doing, and the saves (those come from qemu-img or QEMU: off the UI thread). */
    private void refresh() {
        running.setVisibility(Guest.running() ? View.VISIBLE : View.GONE);
        refresh26();
        refreshWatch();
        boolean up = Guest.running() && Guest.os(this) == Guest.IOS16;
        showFreeRam();
        showPowerWarning();
        // the setup from Apple runs (its last step starts iOS once, in the background): the card follows it
        if (SetupService.busy()) {
            state.setText("Installing iOS 16");
            saveInfo.setText(SetupService.state.text);
            primary.setText("Show the install");
            return;
        }
        if (!Guest.available(this)) {
            state.setText("iOS 16 is not set up");
            saveInfo.setText("Set up installs it on this phone from Apple's servers (about 1.5 hours, no PC), or brings it from "
                    + "your PC (Send-ToPhone.cmd).");
            primary.setText("Set up");
            return;
        }
        state.setText(up ? "iOS is running" : "iOS 16 is ready");
        primary.setText(up ? "Open iOS" : "Start iOS 16");
        saveInfo.setText(String.format(java.util.Locale.ROOT, "%.1f GB free on this phone", getFilesDir().getUsableSpace() / 1e9));
        new Thread(() -> {
            List<Guest.Snap> list = up || Guest.userDisk(this, Guest.IOS16).exists()
                    ? Guest.snapshots(this, Guest.IOS16) : java.util.Collections.emptyList();
            Guest.Snap quick = null;
            for (Guest.Snap s : list) {
                if (Guest.isQuick(s.tag)) {
                    quick = s;
                }
            }
            long free = getFilesDir().getUsableSpace();
            String info;
            if (up) {
                info = list.size() + (list.size() == 1 ? " save" : " saves");
            } else if (quick != null) {
                info = "Goes on from where you closed it (" + quick.date.substring(5, 16) + "), in about 10 s.";
            } else if (Guest.userDisk(this, Guest.IOS16).exists()) {
                info = "Starts with a boot: a few minutes (2 to 4 on our phone).";
            } else {
                info = "First start: a few minutes (2 to 4 on our phone).";
            }
            if (!up && list.size() > (quick != null ? 1 : 0)) {
                info += " " + (list.size() - (quick != null ? 1 : 0)) + " named save(s) under the side bar's Saves.";
            }
            if (!Guest.nativeMissing().isEmpty()) {
                info += " This phone's CPU runs iOS in emulation only, much slower (it lacks ARMv8.2's "
                        + Guest.nativeMissing() + ").";
            }
            String err = Guest.startError;
            String text = (!up && err != null && Guest.selected(this) == Guest.IOS16 ? "Did not start: " + err + "\n\n" : "")
                    + info + String.format(java.util.Locale.ROOT, "\n%.1f GB free on this phone.", free / 1e9);
            runOnUiThread(() -> saveInfo.setText(text));
        }, "home-refresh").start();
    }
}
