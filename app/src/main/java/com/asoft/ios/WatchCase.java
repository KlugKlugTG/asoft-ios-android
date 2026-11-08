package com.asoft.ios;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

/**
 * An Apple Watch drawn around the guest's screen: a midnight aluminium case with the Digital Crown and the
 * side button on its right, a sport band running off the top and the bottom of the phone. Its children (the
 * guest's SurfaceView, the screen-off cover) are laid out at the screen, which keeps the watch's 352x430 shape
 * and fills most of the phone's width. The crown is real: drag it up or down to turn it (a tick of the
 * phone's haptics per notch, into QEMU's usb-crown through Guest.crown), tap it to press, hold it to hold
 * (Siri); the side button is pressed, double pressed or held as on the watch (Guest.button "power").
 */
final class WatchCase extends ViewGroup {
    /** usb-crown units per notch: the PC's mouse wheel turns it the same per detent (1 unit ~ 1.7 watch pixels). */
    static final int CROWN_PER_NOTCH = 30;
    private static final int NOTCH_DP = 18, HOLD_MS = 550;

    private final float d;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rc = new RectF();
    private final Path corners = new Path();
    // the geometry of the last layout, in pixels
    private final RectF caseRect = new RectF(), screenRect = new RectF(), crownRect = new RectF(), sideRect = new RectF();
    private float caseRadius, screenRadius;
    // the crown's turn: ridges move with the finger
    private float phase;
    // the touch on the crown or the side button
    private static final int NONE = 0, CROWN = 1, SIDE = 2;
    private int target = NONE;
    private float downY, lastY, acc;
    private boolean moved, holding;
    private final Runnable hold = () -> {
        if (target == CROWN && !moved) {
            holding = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            Guest.button("home", true);
        }
    };

    WatchCase(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setBackgroundColor(Color.BLACK);
        setContentDescription("Apple Watch: the Digital Crown and the side button are on its right");
    }

    private float dp(float v) {
        return v * d;
    }

    // -- layout: the case in the middle, the screen inside it, the crown's column on the right

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
        float crownRoom = dp(54), left = dp(18), bezel = dp(11), band = dp(72);
        float caseW = w - left - crownRoom;
        float screenW = caseW - 2 * bezel;
        float screenH = screenW * WatchProfile.FB_H / WatchProfile.FB_W;
        float caseH = screenH + 2 * bezel;
        float maxCaseH = h - 2 * band;
        if (caseH > maxCaseH) {         // a short screen: the case shrinks, the band stays
            caseH = maxCaseH;
            screenH = caseH - 2 * bezel;
            screenW = screenH * WatchProfile.FB_W / WatchProfile.FB_H;
            caseW = screenW + 2 * bezel;
        }
        float caseLeft = left + (w - left - crownRoom - caseW) / 2;
        float caseTop = (h - caseH) / 2;
        caseRect.set(caseLeft, caseTop, caseLeft + caseW, caseTop + caseH);
        screenRect.set(caseLeft + bezel, caseTop + bezel, caseLeft + bezel + screenW, caseTop + bezel + screenH);
        caseRadius = caseW * 0.27f;
        screenRadius = screenW * 0.21f;
        // the crown a third of the way down the case, the side button below it, both out of the case's right edge
        float crownY = caseTop + caseH * 0.34f, sideY = caseTop + caseH * 0.60f;
        // a 41 mm watch: the crown ~16% of the case's height and out ~2.5 mm, the side button ~27% long
        crownRect.set(caseRect.right - dp(9), crownY - dp(33), caseRect.right + dp(21), crownY + dp(33));
        sideRect.set(caseRect.right - dp(4), sideY - dp(50), caseRect.right + dp(10), sideY + dp(50));
        int cw = MeasureSpec.makeMeasureSpec(Math.round(screenRect.width()), MeasureSpec.EXACTLY);
        int ch = MeasureSpec.makeMeasureSpec(Math.round(screenRect.height()), MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(cw, ch);
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int sl = Math.round(screenRect.left), st = Math.round(screenRect.top);
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            v.layout(sl, st, sl + v.getMeasuredWidth(), st + v.getMeasuredHeight());
        }
    }

    // -- drawing: the band and the case go under the children; the screen's round corners over them

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        // the band: a sport band in storm blue, wider than half the case, under the case's ends
        float bandW = caseRect.width() * 0.56f, bx = caseRect.centerX() - bandW / 2;
        fill.setShader(new LinearGradient(bx, 0, bx + bandW, 0, new int[] {0xff2b4a6e, 0xff3d6694, 0xff2b4a6e}, null, Shader.TileMode.CLAMP));
        rc.set(bx, -dp(40), bx + bandW, caseRect.top + dp(36));
        cv.drawRoundRect(rc, dp(28), dp(28), fill);
        rc.set(bx, caseRect.bottom - dp(36), bx + bandW, h + dp(40));
        cv.drawRoundRect(rc, dp(28), dp(28), fill);
        fill.setShader(null);
        // the band's holes, down the lower strap, and the pin that sits in one of them
        fill.setColor(0xff1d3550);
        float holeY = caseRect.bottom + dp(58), gap = dp(21);
        for (int i = 0; i < 7 && holeY + gap < h; i++, holeY += gap) {
            cv.drawCircle(caseRect.centerX(), holeY, dp(4.2f), fill);
        }
        fill.setColor(0xffd0d2d6);
        cv.drawCircle(caseRect.centerX(), caseRect.bottom + dp(58) + 2 * gap, dp(3), fill);
        // the upper strap's end: a thin metal pin across it
        fill.setColor(0xff9a9ca2);
        rc.set(bx + dp(10), dp(24), bx + bandW - dp(10), dp(30));
        cv.drawRoundRect(rc, dp(3), dp(3), fill);

        // the crown and the side button first: the case covers their inner ends
        drawCrown(cv);
        drawSide(cv);

        // the case: midnight aluminium, a lighter rim at the top
        fill.setShader(new LinearGradient(0, caseRect.top, 0, caseRect.bottom, new int[] {0xff4b4b52, 0xff2a2a2f, 0xff1b1b1f}, null, Shader.TileMode.CLAMP));
        cv.drawRoundRect(caseRect, caseRadius, caseRadius, fill);
        fill.setShader(null);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(1.5f));
        line.setColor(0x66ffffff);
        rc.set(caseRect);
        rc.inset(dp(1), dp(1));
        cv.drawRoundRect(rc, caseRadius, caseRadius, line);
        // the glass: black to the screen's edge (the bezel), slightly larger than the screen
        fill.setColor(Color.BLACK);
        rc.set(screenRect);
        rc.inset(-dp(4), -dp(4));
        cv.drawRoundRect(rc, screenRadius + dp(4), screenRadius + dp(4), fill);
    }

    /** The Digital Crown seen from the front: a knurled cylinder on its side, its ridges turning with the finger. */
    private void drawCrown(Canvas cv) {
        RectF r = crownRect;
        fill.setShader(new LinearGradient(0, r.top, 0, r.bottom, new int[] {0xff303036, 0xff7c7c84, 0xff9a9aa2, 0xff6c6c74, 0xff2a2a30}, null, Shader.TileMode.CLAMP));
        cv.drawRoundRect(r, dp(7), dp(7), fill);
        fill.setShader(null);
        // the knurling: lines across the crown, moving with the turn, fainter towards the top and the bottom
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(1.6f));
        float gap = dp(4.6f), start = r.top + ((phase % gap) + gap) % gap;
        cv.save();
        cv.clipRect(r.left + dp(8), r.top, r.right - dp(3), r.bottom);
        for (float y = start; y < r.bottom; y += gap) {
            float edge = Math.min(y - r.top, r.bottom - y) / (r.height() / 2);
            line.setColor(Color.argb((int) (40 + 150 * Math.min(1, edge * 1.4f)), 0, 0, 0));
            cv.drawLine(r.left, y, r.right, y, line);
        }
        cv.restore();
        // the crown's rim, a touch of light on top
        line.setColor(0x55ffffff);
        line.setStrokeWidth(dp(1));
        cv.drawLine(r.left + dp(9), r.top + dp(1), r.right - dp(4), r.top + dp(1), line);
        // the crown's end cap: a lighter disc edge on the right
        line.setColor(0x88c8c8d0);
        line.setStrokeWidth(dp(2));
        cv.drawLine(r.right - dp(2), r.top + dp(6), r.right - dp(2), r.bottom - dp(6), line);
    }

    private void drawSide(Canvas cv) {
        RectF r = sideRect;
        fill.setShader(new LinearGradient(r.left, 0, r.right, 0, new int[] {0xff3a3a40, 0xff8a8a92, 0xff4a4a50}, null, Shader.TileMode.CLAMP));
        cv.drawRoundRect(r, dp(5), dp(5), fill);
        fill.setShader(null);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(1));
        line.setColor(0x55ffffff);
        cv.drawLine(r.right - dp(2), r.top + dp(5), r.right - dp(2), r.bottom - dp(5), line);
    }

    @Override
    protected void dispatchDraw(Canvas cv) {
        super.dispatchDraw(cv);
        // the screen's round corners: the case's black over the children's square corners
        corners.rewind();
        corners.addRect(screenRect, Path.Direction.CW);
        corners.addRoundRect(screenRect, screenRadius, screenRadius, Path.Direction.CCW);
        corners.setFillType(Path.FillType.EVEN_ODD);
        fill.setColor(Color.BLACK);
        cv.drawPath(corners, fill);
    }

    // -- the crown and the side button

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                target = NONE;
                if (x >= caseRect.right - dp(26)) {
                    if (Math.abs(y - sideRect.centerY()) < sideRect.height() / 2 + dp(14)) {
                        target = SIDE;
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        Guest.button("power", true);
                    } else if (Math.abs(y - crownRect.centerY()) < dp(90)) {
                        target = CROWN;
                        downY = lastY = y;
                        acc = 0;
                        moved = holding = false;
                        postDelayed(hold, HOLD_MS);
                    }
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (target != CROWN) {
                    return true;
                }
                if (!moved && !holding && Math.abs(y - downY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                    moved = true;
                    removeCallbacks(hold);
                }
                if (moved) {
                    float dy = lastY - y, notch = dp(NOTCH_DP);     // finger up: the list moves on down, as the PC's wheel down
                    acc += dy;
                    phase -= dy;
                    while (acc >= notch) {
                        acc -= notch;
                        turn(CROWN_PER_NOTCH);
                    }
                    while (acc <= -notch) {
                        acc += notch;
                        turn(-CROWN_PER_NOTCH);
                    }
                    invalidate();
                }
                lastY = y;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(hold);
                if (target == SIDE) {
                    Guest.button("power", false);
                } else if (target == CROWN) {
                    if (holding) {
                        Guest.button("home", false);
                    } else if (!moved && e.getActionMasked() == MotionEvent.ACTION_UP) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        Guest.button("home");
                    }
                }
                target = NONE;
                holding = false;
                return true;
            default:
                return true;
        }
    }

    /** One notch of the crown, from the finger or the phone's volume keys. */
    void turn(int units) {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        phase -= Math.signum(units) * dp(NOTCH_DP) / 2;
        invalidate();
        Guest.crown(units);
    }
}
