package com.genmarecomp.android;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLControllerManager;

// SDL3 activity running libmain.so for Genma Onimusha (ARM64, Vulkan).
// Follows the RanggaCasper/Black-Android game loading pattern:
// - Looks for default.xbe or a disc image (.iso/.xiso) in /storage/emulated/0/GenmaOnimusha,
//   at the top of any mounted SD card/USB drive (GenmaOnimusha/), or in Android/data/<pkg>/files/assets.
// - Requests All Files Access (Android 11+) or legacy Storage permission (Android 10), and also offers
//   a built-in SAF GameImporter to copy an .iso atomically (.part -> .iso) without requiring manual file moves.
// - Native app::FindGame() polls every second while pumping SDL events, so the game boots automatically
//   the moment permissions are granted or the disc image finishes importing.
public class GenmaActivity extends SDLActivity {
    static final String SHARED = "GenmaOnimusha";
    private static final String PREFS_GRAPHICS = "genma_graphics";
    private static final String KEY_SCALE_PCT = "scale_pct";
    private static final String KEY_NEAREST = "nearest_present";

    private AlertDialog dialog;
    private boolean notNow;
    private final GameImporter importer = new GameImporter(this);
    private boolean importing;
    private TouchOverlayView touchOverlay;

    private View editToolbar;
    private TextView editSelLabel;
    private Button editHideBtn;
    private Button editDockBtn;
    private boolean toolbarTop = false;

    private static final boolean ALL_FILES_ACCESS = Build.VERSION.SDK_INT >= 30;
    private static final int STORAGE_REQUEST = 0x474D;
    private boolean storageAsked;

    @Override
    protected String[] getLibraries() {
        return new String[] {"SDL3", "main"};
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences gp = getSharedPreferences(PREFS_GRAPHICS, Context.MODE_PRIVATE);
        int savedScalePct = gp.getInt(KEY_SCALE_PCT, 100);
        boolean savedNearest = gp.getBoolean(KEY_NEAREST, false);
        if (!mBrokenLibraries) {
            try {
                nativeSetRenderOptions(savedScalePct / 100.0f, savedNearest);
            } catch (UnsatisfiedLinkError ignored) {
            }
        }

        if (mLayout != null) {
            RelativeLayout overlayHost = new RelativeLayout(this);
            touchOverlay = new TouchOverlayView(this, new TouchOverlayView.Listener() {
                @Override
                public void onTouchPadState(int buttons, byte[] analog, short lx, short ly) {
                    if (!mBrokenLibraries) {
                        nativeTouchPad(buttons, analog, lx, ly);
                    }
                }

                @Override
                public void onOpenMenu() {
                    runOnUiThread(() -> openQuickMenu());
                }

                @Override
                public void onSelectionChanged(String label, float scale, boolean hidden, boolean canHide) {
                    runOnUiThread(() -> updateEditToolbar(label, scale, hidden, canHide));
                }
            });
            overlayHost.addView(touchOverlay, new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            editToolbar = buildEditToolbar();
            editToolbar.setVisibility(View.GONE);
            RelativeLayout.LayoutParams tbLp = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tbLp.addRule(RelativeLayout.CENTER_HORIZONTAL);
            tbLp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            int margin = dp(12);
            tbLp.setMargins(margin, margin, margin, margin);
            overlayHost.addView(editToolbar, tbLp);

            mLayout.addView(overlayHost, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        File own = getExternalFilesDir(null);
        if (own != null) {
            new File(own, "assets").mkdirs();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private View buildEditToolbar() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(10);
        int padV = dp(6);
        bar.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#E6111827"));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.parseColor("#475569"));
        bar.setBackground(bg);

        editDockBtn = makeToolbarButton("▲ Top", Color.parseColor("#334155"), Color.WHITE);
        editDockBtn.setOnClickListener(v -> toggleToolbarDock());
        bar.addView(editDockBtn);

        editSelLabel = new TextView(this);
        editSelLabel.setTextColor(Color.parseColor("#FBBF24"));
        editSelLabel.setTextSize(13f);
        editSelLabel.setPadding(dp(8), 0, dp(8), 0);
        editSelLabel.setText("L 100%");
        bar.addView(editSelLabel);

        Button minusBtn = makeToolbarButton("− Size", Color.parseColor("#1E293B"), Color.WHITE);
        minusBtn.setOnClickListener(v -> touchOverlay.adjustSelectedScale(-0.10f));
        bar.addView(minusBtn);

        Button plusBtn = makeToolbarButton("+ Size", Color.parseColor("#1E293B"), Color.WHITE);
        plusBtn.setOnClickListener(v -> touchOverlay.adjustSelectedScale(0.10f));
        bar.addView(plusBtn);

        editHideBtn = makeToolbarButton("Hide", Color.parseColor("#1E293B"), Color.WHITE);
        editHideBtn.setOnClickListener(v -> touchOverlay.toggleSelectedHidden());
        bar.addView(editHideBtn);

        Button resetBtn = makeToolbarButton("Reset", Color.parseColor("#7F1D1D"), Color.WHITE);
        resetBtn.setOnClickListener(v -> touchOverlay.resetToDefaults(false));
        bar.addView(resetBtn);

        Button cancelBtn = makeToolbarButton("Cancel", Color.parseColor("#334155"), Color.WHITE);
        cancelBtn.setOnClickListener(v -> finishTouchEdit(false));
        bar.addView(cancelBtn);

        Button saveBtn = makeToolbarButton("Save", Color.parseColor("#15803D"), Color.WHITE);
        saveBtn.setOnClickListener(v -> finishTouchEdit(true));
        bar.addView(saveBtn);

        scroll.addView(bar);
        return scroll;
    }

    private Button makeToolbarButton(String label, int bgColor, int fgColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12f);
        b.setTextColor(fgColor);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(dp(34));
        b.setPadding(dp(10), dp(4), dp(10), dp(4));

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(8));
        b.setBackground(gd);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        int gap = dp(4);
        lp.setMargins(gap, 0, gap, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void toggleToolbarDock() {
        if (editToolbar == null) return;
        toolbarTop = !toolbarTop;
        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) editToolbar.getLayoutParams();
        lp.removeRule(RelativeLayout.ALIGN_PARENT_TOP);
        lp.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        lp.addRule(toolbarTop ? RelativeLayout.ALIGN_PARENT_TOP : RelativeLayout.ALIGN_PARENT_BOTTOM);
        editToolbar.setLayoutParams(lp);
        if (editDockBtn != null) {
            editDockBtn.setText(toolbarTop ? "▼ Bot" : "▲ Top");
        }
    }

    private void updateEditToolbar(String label, float scale, boolean hidden, boolean canHide) {
        if (editSelLabel != null) {
            int pct = Math.round(scale * 100f);
            editSelLabel.setText(label + " " + pct + "%");
        }
        if (editHideBtn != null) {
            editHideBtn.setEnabled(canHide);
            editHideBtn.setAlpha(canHide ? 1.0f : 0.45f);
            editHideBtn.setText(hidden ? "Show" : "Hide");
        }
    }

    private void startTouchEdit() {
        if (touchOverlay == null || editToolbar == null) return;
        touchOverlay.enterEditMode();
        editToolbar.setVisibility(View.VISIBLE);
    }

    private void finishTouchEdit(boolean save) {
        if (touchOverlay == null || editToolbar == null) return;
        touchOverlay.exitEditMode(save);
        editToolbar.setVisibility(View.GONE);
        hideSystemBars();
        if (save) {
            Toast.makeText(this, "Touch layout saved", Toast.LENGTH_SHORT).show();
        }
    }

    private void openQuickMenu() {
        if (touchOverlay == null) return;
        final String[] items = new String[] {
                "Edit Touch Layout (Drag & Resize)",
                "Touch Controls Preferences (Opacity & Scale)",
                "Graphics & Upscale Settings"
        };
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Genma Onimusha Menu")
                .setItems(items, (dlg, which) -> {
                    if (which == 0) {
                        startTouchEdit();
                    } else if (which == 1) {
                        openTouchPrefsDialog();
                    } else if (which == 2) {
                        openGraphicsSettingsDialog();
                    }
                })
                .setNegativeButton("Resume", null)
                .create();
        d.setOnDismissListener(dlg -> hideSystemBars());
        d.show();
    }

    private void openTouchPrefsDialog() {
        if (touchOverlay == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        Context ctx = builder.getContext();

        ScrollView scroll = new ScrollView(ctx);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, dp(10), pad, dp(8));

        final boolean prevVisible = touchOverlay.isControlsVisible();
        final float prevOpacity = touchOverlay.getOverlayOpacity();
        final float prevScale = touchOverlay.getGlobalScale();
        final boolean[] saved = {false};

        CheckBox showCheck = new CheckBox(ctx);
        showCheck.setText("Show on-screen touch controls");
        showCheck.setChecked(prevVisible);
        showCheck.setOnCheckedChangeListener((b, checked) -> touchOverlay.setControlsVisible(checked));
        box.addView(showCheck);

        TextView opLabel = new TextView(ctx);
        opLabel.setPadding(0, dp(10), 0, dp(2));
        opLabel.setText(String.format(Locale.US, "Opacity: %d%%", Math.round(prevOpacity * 100f)));
        box.addView(opLabel);

        SeekBar opBar = new SeekBar(ctx);
        opBar.setMax(80); // 20%..100%
        opBar.setProgress(Math.max(0, Math.min(80, Math.round(prevOpacity * 100f) - 20)));
        opBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int pct = 20 + progress;
                opLabel.setText(String.format(Locale.US, "Opacity: %d%%", pct));
                touchOverlay.setOverlayOpacity(pct / 100f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        box.addView(opBar);

        TextView scLabel = new TextView(ctx);
        scLabel.setPadding(0, dp(10), 0, dp(2));
        scLabel.setText(String.format(Locale.US, "Global Button Scale: %d%%", Math.round(prevScale * 100f)));
        box.addView(scLabel);

        SeekBar scBar = new SeekBar(ctx);
        scBar.setMax(80); // 70%..150%
        scBar.setProgress(Math.max(0, Math.min(80, Math.round(prevScale * 100f) - 70)));
        scBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int pct = 70 + progress;
                scLabel.setText(String.format(Locale.US, "Global Button Scale: %d%%", pct));
                touchOverlay.setGlobalScale(pct / 100f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        box.addView(scBar);

        scroll.addView(box);

        AlertDialog d = builder
                .setTitle("Touch Controls Preferences")
                .setView(scroll)
                .setPositiveButton("Save", (dlg, w) -> {
                    saved[0] = true;
                    touchOverlay.saveLayout();
                })
                .setNeutralButton("Reset Defaults", (dlg, w) -> {
                    saved[0] = true;
                    touchOverlay.resetToDefaults(true);
                    Toast.makeText(this, "Touch controls reset to default", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnDismissListener(dlg -> {
            if (!saved[0]) {
                touchOverlay.setControlsVisible(prevVisible);
                touchOverlay.setOverlayOpacity(prevOpacity);
                touchOverlay.setGlobalScale(prevScale);
            }
            hideSystemBars();
        });
        d.show();
    }

    private static Spinner makeSpinner(Context ctx, List<String> labels, List<Integer> values, int currentVal) {
        Spinner sp = new Spinner(ctx);
        ArrayAdapter<String> ad = new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        int sel = 0;
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) == currentVal) {
                sel = i;
                break;
            }
        }
        sp.setSelection(sel);
        return sp;
    }

    private static TextView makeSectionLabel(Context ctx, String text, int topPad) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setPadding(0, topPad, 0, Math.max(2, topPad / 4));
        return tv;
    }

    private void openGraphicsSettingsDialog() {
        SharedPreferences gp = getSharedPreferences(PREFS_GRAPHICS, Context.MODE_PRIVATE);
        int[] cur = null;
        if (!mBrokenLibraries) {
            try {
                cur = nativeGetGraphicsSettings();
            } catch (UnsatisfiedLinkError ignored) {
            }
        }
        if (cur == null || cur.length < 11) {
            cur = new int[] {
                    gp.getInt(KEY_SCALE_PCT, 100),
                    1,
                    0,
                    1,
                    60,
                    333,
                    1,
                    1,
                    gp.getBoolean(KEY_NEAREST, false) ? 1 : 0,
                    1,
                    1
            };
        }

        final int curScalePct = cur[0];
        final int curAspect = cur[1];
        final int curAniso = cur[2];
        final boolean curVsync = cur[3] != 0;
        final int curFps = cur[4];
        final int curOverscan = cur[5];
        final boolean curNudge = cur[6] != 0;
        final boolean curPDepth = cur[7] != 0;
        final boolean curNearest = cur[8] != 0;
        final boolean curModern = cur[9] != 0;
        final int maxAniso = Math.max(1, cur[10]);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        Context ctx = builder.getContext();
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, dp(8), pad, dp(8));

        // 1. Internal Resolution (Upscale)
        List<String> scaleLabels = new ArrayList<>();
        List<Integer> scaleVals = new ArrayList<>();
        scaleLabels.add("0.75x (360p — Performance)"); scaleVals.add(75);
        scaleLabels.add("1x (480p Native — Default)"); scaleVals.add(100);
        scaleLabels.add("1.5x (720p HD)"); scaleVals.add(150);
        scaleLabels.add("2x (960p FHD)"); scaleVals.add(200);
        scaleLabels.add("2.5x (1200p)"); scaleVals.add(250);
        scaleLabels.add("3x (1440p QHD)"); scaleVals.add(300);
        if (!scaleVals.contains(curScalePct)) {
            scaleLabels.add(String.format(Locale.US, "%.2fx (Custom)", curScalePct / 100.0f));
            scaleVals.add(curScalePct);
        }
        box.addView(makeSectionLabel(ctx, "Internal Resolution (Upscale)", dp(4)));
        Spinner scaleSpinner = makeSpinner(ctx, scaleLabels, scaleVals, curScalePct);
        box.addView(scaleSpinner);

        // 2. Scaling Filter
        List<String> filterLabels = new ArrayList<>();
        List<Integer> filterVals = new ArrayList<>();
        filterLabels.add("Bilinear (Smooth — Default)"); filterVals.add(0);
        filterLabels.add("Nearest (Sharp Pixels)"); filterVals.add(1);
        box.addView(makeSectionLabel(ctx, "Display Scaling Filter", dp(10)));
        Spinner filterSpinner = makeSpinner(ctx, filterLabels, filterVals, curNearest ? 1 : 0);
        box.addView(filterSpinner);

        // 3. Aspect Ratio
        List<String> aspectLabels = new ArrayList<>();
        List<Integer> aspectVals = new ArrayList<>();
        aspectLabels.add("4:3 (Default)"); aspectVals.add(1);
        aspectLabels.add("16:9"); aspectVals.add(2);
        aspectLabels.add("Auto (Screen Ratio)"); aspectVals.add(0);
        aspectLabels.add("Stretch (Fill Screen)"); aspectVals.add(3);
        box.addView(makeSectionLabel(ctx, "Aspect Ratio", dp(10)));
        Spinner aspectSpinner = makeSpinner(ctx, aspectLabels, aspectVals, curAspect);
        box.addView(aspectSpinner);

        // 4. Anisotropic Filtering
        List<String> anisoLabels = new ArrayList<>();
        List<Integer> anisoVals = new ArrayList<>();
        anisoLabels.add("Off / Game Default (Default)"); anisoVals.add(0);
        for (int candidate : new int[] {2, 4, 8, 16}) {
            if (candidate <= maxAniso) {
                anisoLabels.add(candidate + "x");
                anisoVals.add(candidate);
            }
        }
        int clampedAniso = anisoVals.contains(curAniso) ? curAniso : 0;
        box.addView(makeSectionLabel(ctx, "Anisotropic Filtering (GPU Max: " + maxAniso + "x)", dp(10)));
        Spinner anisoSpinner = makeSpinner(ctx, anisoLabels, anisoVals, clampedAniso);
        box.addView(anisoSpinner);

        // 5. Overscan Crop
        List<String> overscanLabels = new ArrayList<>();
        List<Integer> overscanVals = new ArrayList<>();
        overscanLabels.add("3.3% Fill Screen (Default)"); overscanVals.add(333);
        overscanLabels.add("0% Original Console Frame"); overscanVals.add(0);
        overscanLabels.add("5.0% Zoom Crop"); overscanVals.add(500);
        int selectedOverscan = curOverscan;
        if (Math.abs(curOverscan - 333) <= 2) selectedOverscan = 333;
        else if (!overscanVals.contains(curOverscan)) {
            overscanLabels.add(String.format(Locale.US, "%.2f%% (Custom)", curOverscan / 100.0f));
            overscanVals.add(curOverscan);
        }
        box.addView(makeSectionLabel(ctx, "Overscan Crop", dp(10)));
        Spinner overscanSpinner = makeSpinner(ctx, overscanLabels, overscanVals, selectedOverscan);
        box.addView(overscanSpinner);

        // 6. FPS Cap
        List<String> fpsLabels = new ArrayList<>();
        List<Integer> fpsVals = new ArrayList<>();
        fpsLabels.add("60 FPS (Default)"); fpsVals.add(60);
        fpsLabels.add("30 FPS (Power Saver)"); fpsVals.add(30);
        fpsLabels.add("120 FPS"); fpsVals.add(120);
        fpsLabels.add("Unlimited"); fpsVals.add(0);
        if (!fpsVals.contains(curFps)) {
            fpsLabels.add(curFps + " FPS (Custom)");
            fpsVals.add(curFps);
        }
        box.addView(makeSectionLabel(ctx, "FPS Cap", dp(10)));
        Spinner fpsSpinner = makeSpinner(ctx, fpsLabels, fpsVals, curFps);
        box.addView(fpsSpinner);

        // 7. Recomp Fixes & Game Controls CheckBoxes
        CheckBox vsyncCheck = new CheckBox(ctx);
        vsyncCheck.setText("V-Sync (Default: On)");
        vsyncCheck.setChecked(curVsync);
        box.addView(vsyncCheck);

        CheckBox pdepthCheck = new CheckBox(ctx);
        pdepthCheck.setText("Precise Depth Fix (Default: On)");
        pdepthCheck.setChecked(curPDepth);
        box.addView(pdepthCheck);

        CheckBox nudgeCheck = new CheckBox(ctx);
        nudgeCheck.setText("Stable Point Sampling (Default: On)");
        nudgeCheck.setChecked(curNudge);
        box.addView(nudgeCheck);

        CheckBox modernCheck = new CheckBox(ctx);
        modernCheck.setText("Modern Analog Controls (Default: On)");
        modernCheck.setChecked(curModern);
        box.addView(modernCheck);

        scroll.addView(box);

        AlertDialog d = builder
                .setTitle("Graphics & Recomp Settings")
                .setView(scroll)
                .setPositiveButton("Apply & Save", (dlg, w) -> {
                    int scalePct = scaleVals.get(scaleSpinner.getSelectedItemPosition());
                    boolean nearest = filterVals.get(filterSpinner.getSelectedItemPosition()) != 0;
                    int aspect = aspectVals.get(aspectSpinner.getSelectedItemPosition());
                    int aniso = anisoVals.get(anisoSpinner.getSelectedItemPosition());
                    int overscanX100 = overscanVals.get(overscanSpinner.getSelectedItemPosition());
                    int fpsCap = fpsVals.get(fpsSpinner.getSelectedItemPosition());
                    boolean vsync = vsyncCheck.isChecked();
                    boolean pdepth = pdepthCheck.isChecked();
                    boolean nudge = nudgeCheck.isChecked();
                    boolean modern = modernCheck.isChecked();

                    gp.edit()
                            .putInt(KEY_SCALE_PCT, scalePct)
                            .putBoolean(KEY_NEAREST, nearest)
                            .apply();
                    if (!mBrokenLibraries) {
                        nativeApplyGraphicsSettings(
                                scalePct, aspect, aniso, vsync, fpsCap,
                                overscanX100, nudge, pdepth, nearest, modern);
                    }
                    Toast.makeText(this, "Graphics settings applied", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Reset Defaults", (dlg, w) -> {
                    gp.edit()
                            .putInt(KEY_SCALE_PCT, 100)
                            .putBoolean(KEY_NEAREST, false)
                            .apply();
                    if (!mBrokenLibraries) {
                        nativeApplyGraphicsSettings(100, 1, 0, true, 60, 333, true, true, false, true);
                    }
                    Toast.makeText(this, "Default graphics settings restored", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnDismissListener(dlg -> hideSystemBars());
        d.show();
    }

    // Visual virtual touch overlay bridge -> native/main.cpp
    private static native void nativeTouchPad(int buttons, byte[] analog, short lx, short ly);

    // Graphics & upscale settings bridge -> native/main.cpp
    private static native void nativeSetRenderOptions(float renderScale, boolean nearestPresent);
    private static native int[] nativeGetGraphicsSettings();
    private static native void nativeApplyGraphicsSettings(
            int scalePct, int aspect, int aniso, boolean vsync, int fpsCap,
            int overscanX100, boolean pointNudge, boolean preciseDepth,
            boolean nearestPresent, boolean modernControls);

    // Loose OEM controller bridge (e.g., MIUI virtualized DualShock 4) forwarded to native/main.cpp.
    private static native void nativeLooseKey(int keyCode, boolean down);
    private static native void nativeLooseAxes(float lx, float ly, float rx, float ry,
                                               float lt, float rt, float hx, float hy);
    private static native void nativeLooseRelease();

    private static boolean loose(InputEvent e, int sources) {
        return !mBrokenLibraries
                && (e.getSource() & sources) != 0
                && !SDLControllerManager.isDeviceSDLJoystick(e.getDeviceId());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (touchOverlay != null && touchOverlay.isEditMode() && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                finishTouchEdit(false);
            }
            return true;
        }
        boolean pad = KeyEvent.isGamepadButton(e.getKeyCode())
                || (e.getKeyCode() >= KeyEvent.KEYCODE_DPAD_UP && e.getKeyCode() <= KeyEvent.KEYCODE_DPAD_RIGHT);
        if (pad && loose(e, InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK)) {
            if (e.getRepeatCount() == 0) {
                nativeLooseKey(e.getKeyCode(), e.getAction() == KeyEvent.ACTION_DOWN);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (loose(e, InputDevice.SOURCE_JOYSTICK) && e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            nativeLooseAxes(
                    e.getAxisValue(MotionEvent.AXIS_X),
                    e.getAxisValue(MotionEvent.AXIS_Y),
                    e.getAxisValue(MotionEvent.AXIS_Z),
                    e.getAxisValue(MotionEvent.AXIS_RZ),
                    Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)),
                    Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)),
                    e.getAxisValue(MotionEvent.AXIS_HAT_X),
                    e.getAxisValue(MotionEvent.AXIS_HAT_Y));
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    @Override
    protected void onResume() {
        super.onResume();
        askForGame();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        } else if (!mBrokenLibraries) {
            nativeLooseRelease();
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT < 30) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            return;
        }
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        c.hide(WindowInsets.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private boolean hasStorageAccess() {
        if (ALL_FILES_ACCESS) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void askForGame() {
        if (importing || (dialog != null && dialog.isShowing())) return;
        File own = getExternalFilesDir(null);
        if (own != null && hasGame(new File(own, "assets"))) return;
        if (!hasStorageAccess()) {
            if (!notNow) askAllFilesAccess();
            return;
        }
        File shared = new File(Environment.getExternalStorageDirectory(), SHARED);
        shared.mkdirs();
        if (hasGame(shared) || hasGame(new File(shared, "assets"))) return;
        for (File card : cardFolders()) {
            if (hasGame(card) || hasGame(new File(card, "assets"))) return;
        }
        tellWhereTheGameGoes();
    }

    private List<File> cardFolders() {
        List<File> out = new ArrayList<>();
        if (!ALL_FILES_ACCESS) {
            File[] own = getExternalFilesDirs(null);
            for (int i = 1; i < own.length; i++) {
                File root = own[i];
                for (int up = 0; root != null && up < 4; up++) root = root.getParentFile();
                if (root != null) out.add(new File(root, SHARED));
            }
            return out;
        }
        StorageManager sm = getSystemService(StorageManager.class);
        if (sm == null) return out;
        for (StorageVolume v : sm.getStorageVolumes()) {
            File root = v.getDirectory();
            if (v.isPrimary() || root == null || !Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
            out.add(new File(root, SHARED));
        }
        return out;
    }

    // Called from native/game_files.cpp via JNI to locate GenmaOnimusha folders on SD cards / USB drives.
    public String[] cardFolderPaths() {
        List<File> cards = cardFolders();
        String[] out = new String[cards.size()];
        for (int i = 0; i < out.length; i++) out[i] = cards.get(i).getPath();
        return out;
    }

    private static boolean hasGame(File dir) {
        if (new File(dir, "default.xbe").exists()) return true;
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            String lower = f.getName().toLowerCase();
            if (lower.endsWith(".iso") || lower.endsWith(".xiso")) return true;
        }
        return false;
    }

    private void askAllFilesAccess() {
        dialog = new AlertDialog.Builder(this)
                .setTitle("Storage access")
                .setMessage("Genma Onimusha reads the game from the " + SHARED + " folder on your phone's storage:\n\n"
                        + "/storage/emulated/0/" + SHARED + "\n\n"
                        + (ALL_FILES_ACCESS
                                ? "On the next screen turn on \"Allow access to manage all files\" for Genma Onimusha, then come back."
                                : "Allow Genma Onimusha to access your storage on the next screen."))
                .setCancelable(false)
                .setPositiveButton("Allow access", (d, w) -> openAccessSettings())
                .setNegativeButton("Not now", (d, w) -> notNow = true)
                .setNeutralButton("Import game", (d, w) -> startImport())
                .show();
    }

    private void tellWhereTheGameGoes() {
        dialog = new AlertDialog.Builder(this)
                .setTitle("Game files")
                .setMessage("Copy Genma Onimusha (USA) into:\n\n"
                        + "/storage/emulated/0/" + SHARED + "\n\n"
                        + "or into a " + SHARED + " folder at the top of your SD card.\n\n"
                        + "Either its disc image (.iso) or default.xbe with its data folders. The game starts automatically once detected.\n\n"
                        + "Or tap Import game to pick the .iso from Downloads, cloud storage, or a USB drive.")
                .setPositiveButton("OK", null)
                .setNeutralButton("Import game", (d, w) -> startImport())
                .show();
    }

    private void startImport() {
        importing = importer.pick();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != GameImporter.REQUEST) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
        if (uri == null) {
            importing = false;
            return;
        }
        File dir = hasStorageAccess()
                ? new File(Environment.getExternalStorageDirectory(), SHARED)
                : new File(getExternalFilesDir(null), "assets");
        importer.copy(uri, dir, ok -> {
            importing = false;
            if (!ok) askForGame();
        });
    }

    private void openAccessSettings() {
        if (!ALL_FILES_ACCESS) {
            boolean ask = !storageAsked || shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE);
            if (ask) {
                storageAsked = true;
                requestPermissions(new String[] {
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, STORAGE_REQUEST);
            } else {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (ActivityNotFoundException e2) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        }
    }
}
