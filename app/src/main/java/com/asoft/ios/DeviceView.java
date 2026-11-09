package com.asoft.ios;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * One of the home screen's devices, drawn the way the app's logo draws its iPhone: a metal frame, a black
 * front, and on its screen what the system last showed (ScreenActivity keeps a small picture, Guest.thumbFile).
 * iOS 26 is an iPhone with the island, iOS 16 the iPhone with a Home button its 750x1334 screen belongs to, and
 * watchOS a watch with its crown and band. Without a picture yet the screen is a home screen in the logo's
 * colours (the watch: its rings); a system that is not installed is an empty, faint device with a plus.
 */
final class DeviceView extends View {
    static final int ISLAND = 0, HOME = 1, WATCH = 2;
    private static final int[] METAL = {0xff8a909d, 0xff3a3f4b, 0xff2a2e38, 0xff737986};
    private static final float[] METAL_AT = {0f, 0.12f, 0.88f, 1f};
    private static final int FRONT = 0xff0a0c11;

    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF(), s = new RectF();
    private final Rect src = new Rect();
    private final Path clip = new Path();
    private int kind = ISLAND;
    private Bitmap shot;
    private boolean present = true, awake;
    private int[] tint = {0xff5a9bff, 0xff4a5df0, 0xff5b36cf};

    DeviceView(Context c, int kind, int[] tint) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        this.kind = kind;
        if (tint != null) {
            this.tint = tint;
        }
    }

    /** @shot: the system's last picture or null; @present: it is installed; @awake: it runs now. */
    void show(Bitmap shot, boolean present, boolean awake) {
        this.shot = shot;
        this.present = present;
        this.awake = awake;
        invalidate();
    }

    private float dp(float v) {
        return v * d;
    }

    private void metal(float left, float right) {
        p.setShader(new LinearGradient(left, 0, right, 0, METAL, METAL_AT, Shader.TileMode.CLAMP));
        p.setAlpha(present ? 255 : 80);
    }

    private void flat(int color) {
        p.setShader(null);
        p.setColor(color);
        if (!present) {
            p.setAlpha((color >>> 24) * 80 / 255);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        if (kind == WATCH) {
            watch(c, w, h);
        } else {
            phone(c, w, h);
        }
    }

    private void phone(Canvas c, float w, float h) {
        float side = dp(2);                              // room for the buttons on the edges
        float bw = w - 2 * side, bh = bw / 0.488f;
        if (bh > h) {
            bh = h;
            bw = bh * 0.488f;
        }
        float x = (w - bw) / 2, y = (h - bh) / 2;
        float rad = bw * (kind == HOME ? 0.15f : 0.19f);
        // the buttons: volume on the left, the side button on the right
        flat(0xff4b505c);
        float t = dp(1.5f);
        c.drawRoundRect(x - t * 0.6f, y + bh * 0.21f, x + t, y + bh * 0.275f, t, t, p);
        c.drawRoundRect(x - t * 0.6f, y + bh * 0.30f, x + t, y + bh * 0.365f, t, t, p);
        c.drawRoundRect(x + bw - t, y + bh * 0.25f, x + bw + t * 0.6f, y + bh * 0.35f, t, t, p);
        // the frame and the front
        metal(x, x + bw);
        r.set(x, y, x + bw, y + bh);
        c.drawRoundRect(r, rad, rad, p);
        float f = Math.max(dp(1.2f), bw * 0.02f);
        flat(FRONT);
        r.inset(f, f);
        c.drawRoundRect(r, rad - f, rad - f, p);
        float sr;
        if (kind == HOME) {
            // a 750x1334 screen between the earpiece and the Home button
            float sw = bw * 0.885f, sh = sw * 1334f / 750f;
            s.set(x + (bw - sw) / 2, y + (bh - sh) / 2, x + (bw + sw) / 2, y + (bh + sh) / 2);
            sr = dp(1);
            flat(0xff1b1e26);
            float ew = bw * 0.16f, eh = Math.max(dp(1.2f), bw * 0.018f), ey = y + (s.top - y) * 0.55f;
            c.drawRoundRect(x + (bw - ew) / 2, ey - eh / 2, x + (bw + ew) / 2, ey + eh / 2, eh, eh, p);
            float hy = s.bottom + (y + bh - s.bottom) * 0.5f, hr = bw * 0.07f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(dp(0.9f), bw * 0.012f));
            flat(0xff2f3440);
            c.drawCircle(x + bw / 2, hy, hr, p);
            p.setStyle(Paint.Style.FILL);
        } else {
            float b = Math.max(dp(2f), bw * 0.034f);
            s.set(r);
            s.inset(b, b);
            sr = rad - f - b;
        }
        screen(c, sr);
        if (kind == ISLAND && present) {
            p.setShader(null);
            p.setColor(0xff04050a);
            float iw = s.width() * 0.30f, ih = s.width() * 0.088f, top = s.top + s.width() * 0.05f, cx = s.centerX();
            c.drawRoundRect(cx - iw / 2, top, cx + iw / 2, top + ih, ih / 2, ih / 2, p);
        }
    }

    private void watch(Canvas c, float w, float h) {
        float cw = w * 0.80f, ch = cw / 0.83f;
        if (ch > h * 0.66f) {
            ch = h * 0.66f;
            cw = ch * 0.83f;
        }
        float x = (w - cw) / 2 - dp(2), y = (h - ch) / 2;
        // the band, off both ends
        float bw = cw * 0.76f, bx = x + (cw - bw) / 2;
        p.setShader(new LinearGradient(bx, 0, bx + bw, 0, new int[] {0xff16171c, 0xff2a2c33, 0xff16171c}, null, Shader.TileMode.CLAMP));
        p.setAlpha(present ? 255 : 80);
        c.drawRoundRect(bx, -dp(8), bx + bw, y + ch * 0.2f, dp(5), dp(5), p);
        c.drawRoundRect(bx, y + ch * 0.8f, bx + bw, h + dp(8), dp(5), dp(5), p);
        // the crown and the side button
        metal(x + cw - dp(2), x + cw + dp(5));
        c.drawRoundRect(x + cw - dp(2), y + ch * 0.20f, x + cw + dp(4.2f), y + ch * 0.40f, dp(1.8f), dp(1.8f), p);
        c.drawRoundRect(x + cw - dp(2), y + ch * 0.52f, x + cw + dp(2f), y + ch * 0.72f, dp(1.4f), dp(1.4f), p);
        // the case and its front
        float rad = cw * 0.27f;
        metal(x, x + cw);
        r.set(x, y, x + cw, y + ch);
        c.drawRoundRect(r, rad, rad, p);
        float f = Math.max(dp(1.2f), cw * 0.022f);
        flat(FRONT);
        r.inset(f, f);
        c.drawRoundRect(r, rad - f, rad - f, p);
        float b = Math.max(dp(2.2f), cw * 0.05f);
        s.set(r);
        s.inset(b, b);
        screen(c, rad - f - b);
    }

    /** The screen @s: the last picture, else the placeholder; dimmed while the system does not run. */
    private void screen(Canvas c, float radius) {
        clip.reset();
        clip.addRoundRect(s, radius, radius, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        p.setShader(null);
        p.setAlpha(255);
        if (!present) {
            p.setColor(0xff0c0d11);
            c.drawRect(s, p);
            p.setColor(0xff5a5f6b);
            p.setStrokeWidth(dp(1.6f));
            p.setStrokeCap(Paint.Cap.ROUND);
            float k = s.width() * 0.13f;
            c.drawLine(s.centerX() - k, s.centerY(), s.centerX() + k, s.centerY(), p);
            c.drawLine(s.centerX(), s.centerY() - k, s.centerX(), s.centerY() + k, p);
        } else if (shot != null && !shot.isRecycled()) {
            // centre crop
            float want = s.width() / s.height(), have = (float) shot.getWidth() / shot.getHeight();
            int sw = shot.getWidth(), sh = shot.getHeight();
            if (have > want) {
                sw = Math.round(sh * want);
            } else {
                sh = Math.round(sw / want);
            }
            src.set((shot.getWidth() - sw) / 2, (shot.getHeight() - sh) / 2, (shot.getWidth() + sw) / 2, (shot.getHeight() + sh) / 2);
            c.drawBitmap(shot, src, s, p);
        } else if (kind == WATCH) {
            rings(c);
        } else {
            homeScreen(c);
        }
        if (present && !awake) {
            p.setShader(null);
            p.setColor(0x3d000000);
            c.drawRect(s, p);
        }
        c.restore();
    }

    /** A home screen in the logo's colours: rows of icons and a dock. */
    private void homeScreen(Canvas c) {
        p.setShader(new LinearGradient(s.left, s.top, s.left + s.width() * 0.35f, s.bottom, tint, new float[] {0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(s, p);
        p.setShader(null);
        float sw = s.width(), icon = sw * 0.155f, gap = (sw - 4 * icon) / 5, ir = icon * 0.27f;
        float top = s.top + sw * (kind == HOME ? 0.13f : 0.27f);
        int rows = kind == HOME ? 4 : 5;
        p.setColor(0x40ffffff);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 4; col++) {
                if (row == rows - 1 && col > 1) {
                    break;      // the last row is not full
                }
                float ix = s.left + gap + col * (icon + gap), iy = top + row * (icon + gap * 1.25f);
                c.drawRoundRect(ix, iy, ix + icon, iy + icon, ir, ir, p);
            }
        }
        // the dock
        float dh = icon + gap * 0.9f, dy = s.bottom - dh - gap * 0.7f;
        p.setColor(0x2effffff);
        c.drawRoundRect(s.left + gap * 0.45f, dy, s.right - gap * 0.45f, dy + dh, icon * 0.42f, icon * 0.42f, p);
        p.setColor(0x52ffffff);
        for (int col = 0; col < 4; col++) {
            float ix = s.left + gap + col * (icon + gap), iy = dy + (dh - icon) / 2;
            c.drawRoundRect(ix, iy, ix + icon, iy + icon, ir, ir, p);
        }
    }

    /** The watch without a picture: its three rings on black. */
    private void rings(Canvas c) {
        p.setColor(0xff000000);
        c.drawRect(s, p);
        int[] colors = {0xffff375f, 0xff9bf542, 0xff40d4e6};
        float[] sweep = {285, 220, 150};
        float cx = s.centerX(), cy = s.centerY(), stroke = s.width() * 0.085f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 3; i++) {
            float rr = s.width() * 0.33f - i * stroke * 1.22f;
            r.set(cx - rr, cy - rr, cx + rr, cy + rr);
            p.setColor(colors[i]);
            p.setAlpha(60);
            c.drawArc(r, 0, 360, false, p);
            p.setAlpha(255);
            c.drawArc(r, -90, sweep[i], false, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.BUTT);
    }
}
