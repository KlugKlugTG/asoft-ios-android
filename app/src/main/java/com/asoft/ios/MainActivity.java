package com.asoft.ios;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** The test build's whole interface: start the engine test, read its log. */
public class MainActivity extends Activity {
    private TextView text;
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            File f = new File(getExternalFilesDir(null), EngineService.LOG);
            try {
                if (f.exists()) {
                    text.setText(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                text.setText(e.toString());
            }
            text.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        Button ios = new Button(this);
        ios.setText("Start iOS 16");
        ios.setOnClickListener(v -> startActivity(new android.content.Intent(this, ScreenActivity.class)));
        root.addView(ios);
        Button cold = new Button(this);
        cold.setText("Boot iOS 16 (not from the saved state)");
        cold.setOnClickListener(v -> {
            if (!Guest.running()) {
                GuestService.startCold(this);
            }
            startActivity(new android.content.Intent(this, ScreenActivity.class));
        });
        root.addView(cold);
        Button erase = new Button(this);
        erase.setText("Erase iPhone");
        erase.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle("Erase iPhone?")
                .setMessage("Settings, installed apps and the saved state go; iOS starts as new.")
                .setPositiveButton("Erase", (d, w) -> {
                    if (Guest.running()) {
                        android.widget.Toast.makeText(this, "Close iOS first", android.widget.Toast.LENGTH_SHORT).show();
                    } else {
                        Guest.erase(this);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show());
        root.addView(erase);
        Button run = new Button(this);
        run.setText("Engine test");
        run.setOnClickListener(v -> EngineService.start(this, 2, 2, 120));
        root.addView(run);
        ScrollView scroll = new ScrollView(this);
        text = new TextView(this);
        text.setTextIsSelectable(true);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setTextSize(11);
        scroll.addView(text);
        root.addView(scroll);
        setContentView(root);
        text.post(refresh);
    }
}
