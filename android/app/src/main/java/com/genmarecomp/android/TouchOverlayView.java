package com.genmarecomp.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

// Translucent multi-touch virtual Xbox controller overlay rendered directly over SDL3's surface.
// Supports simultaneous left stick movement, D-pad, face buttons (A/B/X/Y), shoulders (LB/RB/LT/RT),
// START/BACK, visibility toggle (PAD), settings trigger (CFG), and interactive drag/resize customization.
final class TouchOverlayView extends View {
    interface Listener {
        void onTouchPadState(int buttons, byte[] analog, short lx, short ly);
        void onOpenMenu();
        void onSelectionChanged(String label, float scale, boolean hidden, boolean canHide);
    }

    private enum Kind { STICK, DPAD, BTN, TOGGLE, MENU }

    private static final String PREFS = "genma_touch_layout";
    private static final float MIN_POS = 0.04f;
    private static final float MAX_POS = 0.96f;
    private static final float MIN_BTN_SCALE = 0.60f;
    private static final float MAX_BTN_SCALE = 1.80f;
    private static final float MIN_GLOBAL_SCALE = 0.70f;
    private static final float MAX_GLOBAL_SCALE = 1.50f;
    private static final float MIN_OPACITY = 0.20f;
    private static final float MAX_OPACITY = 1.00f;

    private static final class Control {
        final String id;
        final String label;
        final float defaultNx, defaultNy, defaultNr;
        float nx, ny;
        float scale = 1.0f;
        boolean hidden = false;
        final Kind kind;
        final int btnBit;        // bit in Xbox digital buttons (0..7), or -1
        final int analogIdx;     // index in Xbox analog[0..7] (A,B,X,Y,Black/RB,White/LB,LT,RT), or -1
        final int color;

        Control(String id, String label, float nx, float ny, float nr, Kind kind, int btnBit, int analogIdx, int color) {
            this.id = id;
            this.label = label;
            this.defaultNx = nx;
            this.defaultNy = ny;
            this.defaultNr = nr;
            this.nx = nx;
            this.ny = ny;
            this.kind = kind;
            this.btnBit = btnBit;
            this.analogIdx = analogIdx;
            this.color = color;
        }

        boolean canHide() {
            return kind != Kind.TOGGLE && kind != Kind.MENU;
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
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpRect = new RectF();

    private boolean controlsVisible = true;
    private float globalScale = 1.0f;
    private float opacity = 1.0f;

    // Interactive layout editor state + snapshot for Cancel
    private boolean editMode = false;
    private Control selectedControl = null;
    private int dragPointerId = MotionEvent.INVALID_POINTER_ID;
    private float dragOffsetX = 0f;
    private float dragOffsetY = 0f;
    private float[] savedNx, savedNy, savedScale;
    private boolean[] savedHidden;
    private float savedGlobalScale = 1.0f;
    private float savedOpacity = 1.0f;

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
                new Control("L", "L", 0.13f, 0.72f, 0.145f, Kind.STICK, -1, -1, Color.WHITE),
                new Control("DPAD", "DPAD", 0.13f, 0.34f, 0.105f, Kind.DPAD, -1, -1, Color.WHITE),
                // Top shoulders
                new Control("LB", "LB", 0.08f, 0.11f, 0.062f, Kind.BTN, -1, 5, Color.LTGRAY),
                new Control("LT", "LT", 0.18f, 0.11f, 0.062f, Kind.BTN, -1, 6, Color.LTGRAY),
                new Control("RT", "RT", 0.82f, 0.11f, 0.062f, Kind.BTN, -1, 7, Color.LTGRAY),
                new Control("RB", "RB", 0.92f, 0.11f, 0.062f, Kind.BTN, -1, 4, Color.LTGRAY),
                // Center top: BACK, PAD toggle, START, CFG menu
                new Control("BACK", "BACK", 0.41f, 0.09f, 0.052f, Kind.BTN, 5, -1, Color.LTGRAY),
                new Control("PAD", "PAD", 0.50f, 0.09f, 0.044f, Kind.TOGGLE, -1, -1, Color.parseColor("#93C5FD")),
                new Control("START", "START", 0.59f, 0.09f, 0.052f, Kind.BTN, 4, -1, Color.parseColor("#FBBF24")),
                new Control("MENU", "CFG", 0.69f, 0.09f, 0.044f, Kind.MENU, -1, -1, Color.parseColor("#CBD5E1")),
                // Right side: Xbox face buttons (A=Absorb/Confirm, B=Cancel, X=Attack, Y=Magic)
                new Control("Y", "Y", 0.85f, 0.52f, 0.068f, Kind.BTN, -1, 3, Color.parseColor("#FACC15")),
                new Control("X", "X", 0.76f, 0.67f, 0.068f, Kind.BTN, -1, 2, Color.parseColor("#60A5FA")),
                new Control("B", "B", 0.93f, 0.67f, 0.068f, Kind.BTN, -1, 1, Color.parseColor("#F87171")),
                new Control("A", "A", 0.85f, 0.82f, 0.068f, Kind.BTN, -1, 0, Color.parseColor("#4ADE80")),
        };

        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(3.5f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(1.5f);
        guidePaint.setColor(Color.argb(65, 148, 163, 184));
        selPaint.setStyle(Paint.Style.STROKE);
        selPaint.setStrokeWidth(5.5f);
        selPaint.setColor(Color.parseColor("#FBBF24"));

        loadLayout();
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void loadLayout() {
        SharedPreferences sp = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        controlsVisible = sp.getBoolean("controls_visible", true);
        globalScale = clamp(sp.getFloat("global_scale", 1.0f), MIN_GLOBAL_SCALE, MAX_GLOBAL_SCALE);
        opacity = clamp(sp.getFloat("opacity", 1.0f), MIN_OPACITY, MAX_OPACITY);
        for (Control c : controls) {
            c.nx = clamp(sp.getFloat("nx_" + c.id, c.defaultNx), MIN_POS, MAX_POS);
            c.ny = clamp(sp.getFloat("ny_" + c.id, c.defaultNy), MIN_POS, MAX_POS);
            c.scale = clamp(sp.getFloat("sc_" + c.id, 1.0f), MIN_BTN_SCALE, MAX_BTN_SCALE);
            c.hidden = c.canHide() && sp.getBoolean("hd_" + c.id, false);
        }
    }

    void saveLayout() {
        SharedPreferences.Editor ed = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        ed.putBoolean("controls_visible", controlsVisible);
        ed.putFloat("global_scale", globalScale);
        ed.putFloat("opacity", opacity);
        for (Control c : controls) {
            ed.putFloat("nx_" + c.id, c.nx);
            ed.putFloat("ny_" + c.id, c.ny);
            ed.putFloat("sc_" + c.id, c.scale);
            ed.putBoolean("hd_" + c.id, c.hidden);
        }
        ed.apply();
    }

    void resetToDefaults(boolean persist) {
        globalScale = 1.0f;
        opacity = 1.0f;
        controlsVisible = true;
        for (Control c : controls) {
            c.nx = c.defaultNx;
            c.ny = c.defaultNy;
            c.scale = 1.0f;
            c.hidden = false;
        }
        if (persist) {
            saveLayout();
        }
        notifySelection();
        invalidate();
    }

    boolean isEditMode() {
        return editMode;
    }

    boolean isControlsVisible() {
        return controlsVisible;
    }

    float getGlobalScale() {
        return globalScale;
    }

    float getOverlayOpacity() {
        return opacity;
    }

    void setGlobalScale(float scale) {
        globalScale = clamp(scale, MIN_GLOBAL_SCALE, MAX_GLOBAL_SCALE);
        invalidate();
    }

    void setOverlayOpacity(float alpha) {
        opacity = clamp(alpha, MIN_OPACITY, MAX_OPACITY);
        invalidate();
    }

    public void setControlsVisible(boolean visible) {
        if (controlsVisible == visible) return;
        controlsVisible = visible;
        if (!visible) {
            clearActiveInputs();
        }
        SharedPreferences sp = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit().putBoolean("controls_visible", controlsVisible).apply();
        invalidate();
    }

    void clearActiveInputs() {
        if (active.size() > 0 || lastButtons != 0 || stickX != 0 || stickY != 0) {
            active.clear();
            emitState();
            invalidate();
        }
    }

    void enterEditMode() {
        if (editMode) return;
        clearActiveInputs();
        savedNx = new float[controls.length];
        savedNy = new float[controls.length];
        savedScale = new float[controls.length];
        savedHidden = new boolean[controls.length];
        for (int i = 0; i < controls.length; i++) {
            savedNx[i] = controls[i].nx;
            savedNy[i] = controls[i].ny;
            savedScale[i] = controls[i].scale;
            savedHidden[i] = controls[i].hidden;
        }
        savedGlobalScale = globalScale;
        savedOpacity = opacity;
        editMode = true;
        selectedControl = controls[0];
        dragPointerId = MotionEvent.INVALID_POINTER_ID;
        notifySelection();
        invalidate();
    }

    void exitEditMode(boolean save) {
        if (!editMode) return;
        if (save) {
            saveLayout();
        } else if (savedNx != null) {
            for (int i = 0; i < controls.length; i++) {
                controls[i].nx = savedNx[i];
                controls[i].ny = savedNy[i];
                controls[i].scale = savedScale[i];
                controls[i].hidden = savedHidden[i];
            }
            globalScale = savedGlobalScale;
            opacity = savedOpacity;
        }
        editMode = false;
        selectedControl = null;
        dragPointerId = MotionEvent.INVALID_POINTER_ID;
        savedNx = savedNy = savedScale = null;
        savedHidden = null;
        invalidate();
    }

    void adjustSelectedScale(float delta) {
        if (selectedControl == null) return;
        selectedControl.scale = clamp(selectedControl.scale + delta, MIN_BTN_SCALE, MAX_BTN_SCALE);
        notifySelection();
        invalidate();
    }

    void toggleSelectedHidden() {
        if (selectedControl == null || !selectedControl.canHide()) return;
        selectedControl.hidden = !selectedControl.hidden;
        notifySelection();
        invalidate();
    }

    private void notifySelection() {
        if (listener == null || selectedControl == null) return;
        listener.onSelectionChanged(
                selectedControl.label,
                selectedControl.scale,
                selectedControl.hidden,
                selectedControl.canHide());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (editMode) {
            canvas.drawColor(Color.argb(95, 0, 0, 0));
            canvas.drawLine(w * 0.5f, 0f, w * 0.5f, h, guidePaint);
            canvas.drawLine(0f, h * 0.5f, w, h * 0.5f, guidePaint);
            canvas.drawLine(w * 0.25f, 0f, w * 0.25f, h, guidePaint);
            canvas.drawLine(w * 0.75f, 0f, w * 0.75f, h, guidePaint);
            canvas.drawLine(0f, h * 0.25f, w, h * 0.25f, guidePaint);
            canvas.drawLine(0f, h * 0.75f, w, h * 0.75f, guidePaint);
        }

        for (Control c : controls) {
            boolean keepVisible = c.kind == Kind.TOGGLE || c.kind == Kind.MENU;
            if (!editMode) {
                if (c.hidden && !keepVisible) continue;
                if (!controlsVisible && !keepVisible) continue;
            }

            float cx = c.nx * w;
            float cy = c.ny * h;
            float r = c.defaultNr * c.scale * globalScale * h;
            boolean pressed = !editMode && isControlActive(c);
            boolean dimmed = (!controlsVisible && keepVisible) || (editMode && c.hidden);

            int baseRaw = dimmed ? 48 : (pressed ? 150 : 72);
            int strokeRaw = dimmed ? 105 : (pressed ? 235 : 155);
            int textRaw = dimmed ? 140 : (pressed ? 255 : 215);
            int baseAlpha = editMode ? (c.hidden ? 35 : 95) : Math.round(baseRaw * opacity);
            int strokeAlpha = editMode ? (c.hidden ? 95 : 200) : Math.round(strokeRaw * opacity);
            int textAlpha = editMode ? (c.hidden ? 130 : 245) : Math.round(textRaw * opacity);

            fillPaint.setColor(Color.argb(baseAlpha, Color.red(c.color), Color.green(c.color), Color.blue(c.color)));
            strokePaint.setColor(Color.argb(strokeAlpha, Color.red(c.color), Color.green(c.color), Color.blue(c.color)));

            if (c.kind == Kind.STICK) {
                canvas.drawCircle(cx, cy, r, fillPaint);
                canvas.drawCircle(cx, cy, r, strokePaint);
                float kx = cx + (editMode ? 0f : (stickX / 32767f) * (r * 0.55f));
                float ky = cy - (editMode ? 0f : (stickY / 32767f) * (r * 0.55f));
                int knobAlpha = editMode ? (c.hidden ? 70 : 140) : Math.round((pressed ? 210 : 120) * opacity);
                fillPaint.setColor(Color.argb(knobAlpha, 255, 255, 255));
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
                textPaint.setColor(Color.argb(textAlpha, 255, 255, 255));
                textPaint.setTextSize(r * (c.label.length() > 2 ? 0.48f : 0.78f));
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float ty = cy - (fm.ascent + fm.descent) * 0.5f;
                canvas.drawText(c.label, cx, ty, textPaint);
            }

            if (editMode) {
                if (c.hidden) {
                    textPaint.setColor(Color.argb(220, 248, 113, 113));
                    textPaint.setTextSize(Math.max(18f, r * 0.35f));
                    canvas.drawText("OFF", cx, cy + r + textPaint.getTextSize(), textPaint);
                }
                if (c == selectedControl) {
                    canvas.drawCircle(cx, cy, r + 8f, selPaint);
                }
            }
        }
    }

    private boolean isControlActive(Control c) {
        for (int i = 0; i < active.size(); i++) {
            if (active.valueAt(i).control == c) return true;
        }
        return false;
    }

    private Control hitTest(float x, float y, boolean includeHidden) {
        float w = getWidth();
        float h = getHeight();
        Control best = null;
        float bestDist = Float.MAX_VALUE;
        for (Control c : controls) {
            boolean keepVisible = c.kind == Kind.TOGGLE || c.kind == Kind.MENU;
            if (!includeHidden) {
                if (c.hidden && !keepVisible) continue;
                if (!controlsVisible && !keepVisible) continue;
            }
            float cx = c.nx * w;
            float cy = c.ny * h;
            float r = c.defaultNr * c.scale * globalScale * h * 1.25f;
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
        if (editMode) {
            return handleEditTouch(event);
        }

        int action = event.getActionMasked();
        int idx = event.getActionIndex();
        int pid = event.getPointerId(idx);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = event.getX(idx);
                float y = event.getY(idx);
                Control hit = hitTest(x, y, false);
                if (hit == null) return false;
                if (hit.kind == Kind.TOGGLE) {
                    setControlsVisible(!controlsVisible);
                    return true;
                }
                if (hit.kind == Kind.MENU) {
                    clearActiveInputs();
                    if (listener != null) listener.onOpenMenu();
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

    private boolean handleEditTouch(MotionEvent event) {
        float w = Math.max(1f, getWidth());
        float h = Math.max(1f, getHeight());
        int action = event.getActionMasked();
        int idx = event.getActionIndex();
        int pid = event.getPointerId(idx);

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                float x = event.getX(idx);
                float y = event.getY(idx);
                Control hit = hitTest(x, y, true);
                if (hit != null) {
                    selectedControl = hit;
                    dragPointerId = pid;
                    dragOffsetX = hit.nx * w - x;
                    dragOffsetY = hit.ny * h - y;
                    notifySelection();
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (selectedControl != null && dragPointerId != MotionEvent.INVALID_POINTER_ID) {
                    int pIdx = event.findPointerIndex(dragPointerId);
                    if (pIdx >= 0) {
                        selectedControl.nx = clamp((event.getX(pIdx) + dragOffsetX) / w, MIN_POS, MAX_POS);
                        selectedControl.ny = clamp((event.getY(pIdx) + dragOffsetY) / h, MIN_POS, MAX_POS);
                        invalidate();
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                if (pid == dragPointerId) {
                    dragPointerId = MotionEvent.INVALID_POINTER_ID;
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                dragPointerId = MotionEvent.INVALID_POINTER_ID;
                return true;
            }
            default:
                return true;
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
            float r = Math.max(1f, c.defaultNr * c.scale * globalScale * h);

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
