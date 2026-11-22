package com.genmarecomp.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

// Translucent multi-touch virtual Xbox controller overlay rendered directly over SDL3's surface.
// Supports simultaneous left stick movement, D-pad, face buttons (A/B/X/Y), shoulders (LB/RB/LT/RT),
// and START/BACK so Genma Onimusha is playable out of the box without a physical controller.
final class TouchOverlayView extends View {
    interface Listener {
        void onTouchPadState(int buttons, byte[] analog, short lx, short ly);
    }

    private enum Kind { STICK, DPAD, BTN, TOGGLE }

    private static final class Control {
        final String label;
        final float nx, ny, nr;  // normalized center (x, y) and radius (fraction of height)
        final Kind kind;
        final int btnBit;        // bit in Xbox digital buttons (0..7), or -1
        final int analogIdx;     // index in Xbox analog[0..7] (A,B,X,Y,Black/RB,White/LB,LT,RT), or -1
        final int color;

        Control(String label, float nx, float ny, float nr, Kind kind, int btnBit, int analogIdx, int color) {
            this.label = label;
            this.nx = nx;
            this.ny = ny;
            this.nr = nr;
            this.kind = kind;
            this.btnBit = btnBit;
            this.analogIdx = analogIdx;
            this.color = color;
        }
    }

    private static final class ActivePointer {
        final Control control;
        float x, y;

        ActivePointer(Control control, float x, float y) {
            this.control = control;
            this.x = x;
            this.y = y;
        }
    }

    private final Listener listener;
    private final Control[] controls;
    private final SparseArray<ActivePointer> active = new SparseArray<>();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();

    private boolean controlsVisible = true;
    private int lastButtons = 0;
    private final byte[] analogBuf = new byte[8];
    private short stickX = 0;
    private short stickY = 0;

    TouchOverlayView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setFocusable(false);
        setClickable(false);

        controls = new Control[] {
                // Left side: Analog stick + D-Pad
                new Control("L", 0.13f, 0.72f, 0.145f, Kind.STICK, -1, -1, Color.WHITE),
                new Control("DPAD", 0.13f, 0.34f, 0.105f, Kind.DPAD, -1, -1, Color.WHITE),
                // Top shoulders
                new Control("LB", 0.08f, 0.11f, 0.062f, Kind.BTN, -1, 5, Color.LTGRAY),
                new Control("LT", 0.18f, 0.11f, 0.062f, Kind.BTN, -1, 6, Color.LTGRAY),
                new Control("RT", 0.82f, 0.11f, 0.062f, Kind.BTN, -1, 7, Color.LTGRAY),
                new Control("RB", 0.92f, 0.11f, 0.062f, Kind.BTN, -1, 4, Color.LTGRAY),
                // Center top: BACK, START, and visibility toggle
                new Control("BACK", 0.41f, 0.09f, 0.052f, Kind.BTN, 5, -1, Color.LTGRAY),
                new Control("START", 0.59f, 0.09f, 0.052f, Kind.BTN, 4, -1, Color.parseColor("#FBBF24")),
                new Control("PAD", 0.50f, 0.09f, 0.044f, Kind.TOGGLE, -1, -1, Color.parseColor("#93C5FD")),
                // Right side: Xbox face buttons (A=Absorb/Confirm, B=Cancel, X=Attack, Y=Magic)
                new Control("Y", 0.85f, 0.52f, 0.068f, Kind.BTN, -1, 3, Color.parseColor("#FACC15")),
                new Control("X", 0.76f, 0.67f, 0.068f, Kind.BTN, -1, 2, Color.parseColor("#60A5FA")),
                new Control("B", 0.93f, 0.67f, 0.068f, Kind.BTN, -1, 1, Color.parseColor("#F87171")),
                new Control("A", 0.85f, 0.82f, 0.068f, Kind.BTN, -1, 0, Color.parseColor("#4ADE80")),
        };

        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(3.5f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
    }

    public void setControlsVisible(boolean visible) {
        if (controlsVisible == visible) return;
        controlsVisible = visible;
        if (!visible) {
            active.clear();
            emitState();
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        for (Control c : controls) {
            if (!controlsVisible && c.kind != Kind.TOGGLE) continue;
            float cx = c.nx * w;
            float cy = c.ny * h;
            float r = c.nr * h;
            boolean pressed = isControlActive(c);

            int baseAlpha = (c.kind == Kind.TOGGLE && !controlsVisible) ? 70 : (pressed ? 150 : 72);
            int strokeAlpha = (c.kind == Kind.TOGGLE && !controlsVisible) ? 120 : (pressed ? 235 : 155);

            fillPaint.setColor(Color.argb(baseAlpha, Color.red(c.color), Color.green(c.color), Color.blue(c.color)));
            strokePaint.setColor(Color.argb(strokeAlpha, Color.red(c.color), Color.green(c.color), Color.blue(c.color)));

            if (c.kind == Kind.STICK) {
                canvas.drawCircle(cx, cy, r, fillPaint);
                canvas.drawCircle(cx, cy, r, strokePaint);
                float kx = cx + (stickX / 32767f) * (r * 0.55f);
                float ky = cy - (stickY / 32767f) * (r * 0.55f);
                fillPaint.setColor(Color.argb(pressed ? 210 : 120, 255, 255, 255));
                canvas.drawCircle(kx, ky, r * 0.42f, fillPaint);
            } else if (c.kind == Kind.DPAD) {
                float arm = r * 0.36f;
                tmpRect.set(cx - arm, cy - r, cx + arm, cy + r);
                canvas.drawRoundRect(tmpRect, 12f, 12f, fillPaint);
                canvas.drawRoundRect(tmpRect, 12f, 12f, strokePaint);
                tmpRect.set(cx - r, cy - arm, cx + r, cy + arm);
                canvas.drawRoundRect(tmpRect, 12f, 12f, fillPaint);
                canvas.drawRoundRect(tmpRect, 12f, 12f, strokePaint);
            } else {
                canvas.drawCircle(cx, cy, r, fillPaint);
                canvas.drawCircle(cx, cy, r, strokePaint);
                textPaint.setColor(Color.argb(pressed ? 255 : 215, 255, 255, 255));
                textPaint.setTextSize(r * (c.label.length() > 2 ? 0.48f : 0.78f));
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float ty = cy - (fm.ascent + fm.descent) * 0.5f;
                canvas.drawText(c.label, cx, ty, textPaint);
            }
        }
    }

    private boolean isControlActive(Control c) {
        for (int i = 0; i < active.size(); i++) {
            if (active.valueAt(i).control == c) return true;
        }
        return false;
    }

    private Control hitTest(float x, float y) {
        float w = getWidth();
        float h = getHeight();
        Control best = null;
        float bestDist = Float.MAX_VALUE;
        for (Control c : controls) {
            if (!controlsVisible && c.kind != Kind.TOGGLE) continue;
            float cx = c.nx * w;
            float cy = c.ny * h;
            float r = c.nr * h * 1.25f;
            float d = (float) Math.hypot(x - cx, y - cy);
            if (d <= r && d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int idx = event.getActionIndex();
        int pid = event.getPointerId(idx);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = event.getX(idx);
                float y = event.getY(idx);
                Control hit = hitTest(x, y);
                if (hit == null) return false;
                if (hit.kind == Kind.TOGGLE) {
                    setControlsVisible(!controlsVisible);
                    return true;
                }
                active.put(pid, new ActivePointer(hit, x, y));
                emitState();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                boolean changed = false;
                for (int i = 0; i < event.getPointerCount(); i++) {
                    ActivePointer ap = active.get(event.getPointerId(i));
                    if (ap != null) {
                        ap.x = event.getX(i);
                        ap.y = event.getY(i);
                        changed = true;
                    }
                }
                if (changed) {
                    emitState();
                    invalidate();
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                if (active.get(pid) != null) {
                    active.remove(pid);
                    emitState();
                    invalidate();
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (active.size() > 0) {
                    active.clear();
                    emitState();
                    invalidate();
                }
                return true;
            }
            default:
                return false;
        }
    }

    private void emitState() {
        float w = Math.max(1f, getWidth());
        float h = Math.max(1f, getHeight());
        int buttons = 0;
        for (int i = 0; i < 8; i++) analogBuf[i] = 0;
        short lx = 0, ly = 0;

        for (int i = 0; i < active.size(); i++) {
            ActivePointer ap = active.valueAt(i);
            Control c = ap.control;
            float cx = c.nx * w;
            float cy = c.ny * h;
            float r = Math.max(1f, c.nr * h);

            if (c.kind == Kind.STICK) {
                float dx = (ap.x - cx) / r;
                float dy = (ap.y - cy) / r;
                float mag = (float) Math.hypot(dx, dy);
                if (mag > 1f) {
                    dx /= mag;
                    dy /= mag;
                }
                if (mag >= 0.14f) {
                    lx = (short) Math.max(-32767, Math.min(32767, Math.round(dx * 32767f)));
                    ly = (short) Math.max(-32767, Math.min(32767, Math.round(-dy * 32767f)));
                }
            } else if (c.kind == Kind.DPAD) {
                float dx = ap.x - cx;
                float dy = ap.y - cy;
                if (Math.abs(dx) > r * 0.22f || Math.abs(dy) > r * 0.22f) {
                    if (Math.abs(dx) > Math.abs(dy)) {
                        buttons |= (dx < 0) ? (1 << 2) : (1 << 3);
                    } else {
                        buttons |= (dy < 0) ? (1 << 0) : (1 << 1);
                    }
                }
            } else if (c.kind == Kind.BTN) {
                if (c.btnBit >= 0) buttons |= (1 << c.btnBit);
                if (c.analogIdx >= 0 && c.analogIdx < 8) analogBuf[c.analogIdx] = (byte) 0xFF;
            }
        }

        lastButtons = buttons;
        stickX = lx;
        stickY = ly;
        if (listener != null) {
            listener.onTouchPadState(lastButtons, analogBuf, stickX, stickY);
        }
    }
}
