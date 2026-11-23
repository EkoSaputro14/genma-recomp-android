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
// Preserves v0.8.0-android.9/10 defaults (1x Native 480p, 4:3, Bilinear, 60 FPS, V-Sync On, English)
// while offering optional 16:9 / Full-Screen No-Stretch ("Tanpa Gepeng") + Vertical Framing,
// full Bahasa Indonesia UI & In-Game Subtitles/Dialogues/Items/Menus, Touch Layout Editor, and Upscaling.
public class GenmaActivity extends SDLActivity {
    static final String SHARED = "GenmaOnimusha";
    static final String PREFS_GRAPHICS = "genma_graphics";
    private static final String KEY_SCALE_PCT = "scale_pct";
    private static final String KEY_NEAREST = "nearest_present";
    private static final String KEY_FILL_NO_STRETCH = "fill_no_stretch";
    private static final String KEY_WIDE_PAN_PCT = "wide_pan_pct";
    static final String KEY_UI_LANG = "ui_lang";
    private static final String KEY_GAME_LANG_ID = "game_lang_id";

    private AlertDialog dialog;
    private boolean notNow;
    private final GameImporter importer = new GameImporter(this);
    private boolean importing;
    private TouchOverlayView touchOverlay;

    private View editToolbar;
    private TextView editSelLabel;
    private Button editMinusBtn;
    private Button editPlusBtn;
    private Button editHideBtn;
    private Button editResetBtn;
    private Button editCancelBtn;
    private Button editSaveBtn;
    private Button editDockBtn;
    private boolean toolbarTop = false;
    private boolean lastSelHidden = false;

    private static final boolean ALL_FILES_ACCESS = Build.VERSION.SDK_INT >= 30;
    private static final int STORAGE_REQUEST = 0x474D;
    private boolean storageAsked;

    private boolean isId() {
        SharedPreferences gp = getSharedPreferences(PREFS_GRAPHICS, Context.MODE_PRIVATE);
        return "id".equals(gp.getString(KEY_UI_LANG, "en"));
    }

    private String tr(String en, String id) {
        return isId() ? id : en;
    }

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
        boolean savedFillNoStretch = gp.getBoolean(KEY_FILL_NO_STRETCH, false);
        int savedWidePanPct = gp.getInt(KEY_WIDE_PAN_PCT, 70);
        boolean savedGameLangId = gp.getBoolean(KEY_GAME_LANG_ID, false);
        if (!mBrokenLibraries) {
            try {
                nativeSetRenderOptions(savedScalePct / 100.0f, savedNearest);
                nativeSetExtendedOptions(savedFillNoStretch, savedWidePanPct / 100.0f, savedGameLangId);
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

        editMinusBtn = makeToolbarButton("− Size", Color.parseColor("#1E293B"), Color.WHITE);
        editMinusBtn.setOnClickListener(v -> touchOverlay.adjustSelectedScale(-0.10f));
        bar.addView(editMinusBtn);

        editPlusBtn = makeToolbarButton("+ Size", Color.parseColor("#1E293B"), Color.WHITE);
        editPlusBtn.setOnClickListener(v -> touchOverlay.adjustSelectedScale(0.10f));
        bar.addView(editPlusBtn);

        editHideBtn = makeToolbarButton("Hide", Color.parseColor("#1E293B"), Color.WHITE);
        editHideBtn.setOnClickListener(v -> touchOverlay.toggleSelectedHidden());
        bar.addView(editHideBtn);

        editResetBtn = makeToolbarButton("Reset", Color.parseColor("#7F1D1D"), Color.WHITE);
        editResetBtn.setOnClickListener(v -> touchOverlay.resetToDefaults(false));
        bar.addView(editResetBtn);

        editCancelBtn = makeToolbarButton("Cancel", Color.parseColor("#334155"), Color.WHITE);
        editCancelBtn.setOnClickListener(v -> finishTouchEdit(false));
        bar.addView(editCancelBtn);

        editSaveBtn = makeToolbarButton("Save", Color.parseColor("#15803D"), Color.WHITE);
        editSaveBtn.setOnClickListener(v -> finishTouchEdit(true));
        bar.addView(editSaveBtn);

        scroll.addView(bar);
        return scroll;
    }

    private void refreshEditToolbarLocale() {
        if (editDockBtn != null) {
            editDockBtn.setText(toolbarTop ? tr("▼ Bot", "▼ Bawah") : tr("▲ Top", "▲ Atas"));
        }
        if (editMinusBtn != null) editMinusBtn.setText(tr("− Size", "− Ukuran"));
        if (editPlusBtn != null) editPlusBtn.setText(tr("+ Size", "+ Ukuran"));
        if (editHideBtn != null) {
            editHideBtn.setText(lastSelHidden ? tr("Show", "Tampil") : tr("Hide", "Sembunyi"));
        }
        if (editResetBtn != null) editResetBtn.setText("Reset");
        if (editCancelBtn != null) editCancelBtn.setText(tr("Cancel", "Batal"));
        if (editSaveBtn != null) editSaveBtn.setText(tr("Save", "Simpan"));
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
        refreshEditToolbarLocale();
    }

    private void updateEditToolbar(String label, float scale, boolean hidden, boolean canHide) {
        lastSelHidden = hidden;
        if (editSelLabel != null) {
            int pct = Math.round(scale * 100f);
            editSelLabel.setText(label + " " + pct + "%");
        }
        if (editHideBtn != null) {
            editHideBtn.setEnabled(canHide);
            editHideBtn.setAlpha(canHide ? 1.0f : 0.45f);
            editHideBtn.setText(hidden ? tr("Show", "Tampil") : tr("Hide", "Sembunyi"));
        }
    }

    private void startTouchEdit() {
        if (touchOverlay == null || editToolbar == null) return;
        refreshEditToolbarLocale();
        touchOverlay.enterEditMode();
        editToolbar.setVisibility(View.VISIBLE);
    }

    private void finishTouchEdit(boolean save) {
        if (touchOverlay == null || editToolbar == null) return;
        touchOverlay.exitEditMode(save);
        editToolbar.setVisibility(View.GONE);
        hideSystemBars();
        if (save) {
            Toast.makeText(this, tr("Touch layout saved", "Tata letak sentuh disimpan"), Toast.LENGTH_SHORT).show();
        }
    }

    private void openQuickMenu() {
        if (touchOverlay == null) return;
        final String[] items = new String[] {
                tr("Edit Touch Layout (Drag & Resize)", "Atur Tata Letak Sentuh (Geser & Ukuran)"),
                tr("Touch Controls Preferences (Opacity & Scale)", "Preferensi Kontrol Sentuh (Opasitas & Skala)"),
                tr("Graphics, 16:9 & Upscale Settings", "Pengaturan Grafis, 16:9 & Upscale"),
                tr("Language / Bahasa (App & Game Subtitles)", "Bahasa / Language (Menu & Subtitle Game)")
        };
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(tr("Genma Onimusha Menu", "Menu Genma Onimusha"))
                .setItems(items, (dlg, which) -> {
                    if (which == 0) {
                        startTouchEdit();
                    } else if (which == 1) {
                        openTouchPrefsDialog();
                    } else if (which == 2) {
                        openGraphicsSettingsDialog();
                    } else if (which == 3) {
                        openLanguageDialog();
                    }
                })
                .setNegativeButton(tr("Resume", "Lanjutkan"), null)
                .create();
        d.setOnDismissListener(dlg -> hideSystemBars());
        d.show();
    }

    private void openLanguageDialog() {
        SharedPreferences gp = getSharedPreferences(PREFS_GRAPHICS, Context.MODE_PRIVATE);
        final boolean curUiId = isId();
        boolean curGameId = gp.getBoolean(KEY_GAME_LANG_ID, false);
        if (!mBrokenLibraries) {
            try {
                int[] cur = nativeGetGraphicsSettings();
                if (cur != null && cur.length >= 14) {
                    curGameId = (cur[13] != 0);
                }
            } catch (UnsatisfiedLinkError ignored) {
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        Context ctx = builder.getContext();
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, dp(8), pad, dp(8));

        List<String> langLabels = new ArrayList<>();
        List<Integer> langVals = new ArrayList<>();
        langLabels.add("English (Default)"); langVals.add(0);
        langLabels.add("Bahasa Indonesia"); langVals.add(1);

        box.addView(makeSectionLabel(ctx, tr("App Menu Language", "Bahasa Menu Aplikasi"), dp(4)));
        Spinner uiSpinner = makeSpinner(ctx, langLabels, langVals, curUiId ? 1 : 0);
        box.addView(uiSpinner);

        List<String> gameLangLabels = new ArrayList<>();
        List<Integer> gameLangVals = new ArrayList<>();
        gameLangLabels.add("English (Original)"); gameLangVals.add(0);
        gameLangLabels.add(tr("Bahasa Indonesia (Subtitles, Dialogue, Items)",
                "Bahasa Indonesia (Subtitle, Dialog, Item, Menu)")); gameLangVals.add(1);

        box.addView(makeSectionLabel(ctx, tr("Game Subtitles & In-Game Text", "Bahasa Subtitle & Teks Game"), dp(12)));
        Spinner gameSpinner = makeSpinner(ctx, gameLangLabels, gameLangVals, curGameId ? 1 : 0);
        box.addView(gameSpinner);

        TextView note = new TextView(ctx);
        note.setTextSize(12f);
        note.setPadding(0, dp(12), 0, 0);
        note.setText(tr(
                "Note: Item names, descriptions, and choices switch immediately in real time. "
                        + "Cutscene subtitles and room dialogues switch when entering the next room/scene (or restart app for Title Screen).",
                "Catatan: Nama item, deskripsi, dan pilihan menu langsung berubah saat itu juga. "
                        + "Subtitle cutscene & dialog ruangan berlaku saat masuk ruangan/adegan berikutnya (atau muat ulang aplikasi untuk Layar Judul)."));
        box.addView(note);

        scroll.addView(box);

        AlertDialog d = builder
                .setTitle(tr("Language / Bahasa Settings", "Pengaturan Bahasa (UI & Game)"))
                .setView(scroll)
                .setPositiveButton(tr("Apply & Save", "Simpan & Terapkan"), (dlg, w) -> {
                    boolean uiId = langVals.get(uiSpinner.getSelectedItemPosition()) != 0;
                    boolean gameId = gameLangVals.get(gameSpinner.getSelectedItemPosition()) != 0;
                    applyLanguageSelection(gp, uiId, gameId);
                })
                .setNeutralButton(tr("Full Indonesia", "Indonesia Penuh"), (dlg, w) -> {
                    applyLanguageSelection(gp, true, true);
                })
                .setNegativeButton(tr("Cancel", "Batal"), null)
                .create();
        d.setOnDismissListener(dlg -> hideSystemBars());
        d.show();
    }

    private void applyLanguageSelection(SharedPreferences gp, boolean uiId, boolean gameId) {
        boolean fillNoStretch = gp.getBoolean(KEY_FILL_NO_STRETCH, false);
        int widePanPct = gp.getInt(KEY_WIDE_PAN_PCT, 70);
        gp.edit()
                .putString(KEY_UI_LANG, uiId ? "id" : "en")
                .putBoolean(KEY_GAME_LANG_ID, gameId)
                .apply();
        if (!mBrokenLibraries) {
            try {
                int[] cur = nativeGetGraphicsSettings();
                if (cur != null && cur.length >= 13) {
                    fillNoStretch = (cur[11] != 0);
                    widePanPct = cur[12];
                }
                nativeSetExtendedOptions(fillNoStretch, widePanPct / 100.0f, gameId);
            } catch (UnsatisfiedLinkError ignored) {
            }
        }
        Toast.makeText(
                this,
                tr("Language updated (UI: " + (uiId ? "ID" : "EN") + ", Game: " + (gameId ? "ID" : "EN") + ")",
                   "Bahasa diterapkan (Menu: " + (uiId ? "Indonesia" : "English") + ", Game: " + (gameId ? "Indonesia" : "English") + ")"),
                Toast.LENGTH_SHORT).show();
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
        showCheck.setText(tr("Show on-screen touch controls", "Tampilkan kontrol sentuh di layar"));
        showCheck.setChecked(prevVisible);
        showCheck.setOnCheckedChangeListener((b, checked) -> touchOverlay.setControlsVisible(checked));
        box.addView(showCheck);

        TextView opLabel = new TextView(ctx);
        opLabel.setPadding(0, dp(10), 0, dp(2));
        opLabel.setText(String.format(Locale.US, "%s: %d%%", tr("Opacity", "Transparansi"), Math.round(prevOpacity * 100f)));
        box.addView(opLabel);

        SeekBar opBar = new SeekBar(ctx);
        opBar.setMax(80); // 20%..100%
        opBar.setProgress(Math.max(0, Math.min(80, Math.round(prevOpacity * 100f) - 20)));
        opBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int pct = 20 + progress;
                opLabel.setText(String.format(Locale.US, "%s: %d%%", tr("Opacity", "Transparansi"), pct));
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
        scLabel.setText(String.format(Locale.US, "%s: %d%%", tr("Global Button Scale", "Skala Tombol Global"), Math.round(prevScale * 100f)));
        box.addView(scLabel);

        SeekBar scBar = new SeekBar(ctx);
        scBar.setMax(80); // 70%..150%
        scBar.setProgress(Math.max(0, Math.min(80, Math.round(prevScale * 100f) - 70)));
        scBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int pct = 70 + progress;
                scLabel.setText(String.format(Locale.US, "%s: %d%%", tr("Global Button Scale", "Skala Tombol Global"), pct));
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
                .setTitle(tr("Touch Controls Preferences", "Preferensi Kontrol Sentuh"))
                .setView(scroll)
                .setPositiveButton(tr("Save", "Simpan"), (dlg, w) -> {
                    saved[0] = true;
                    touchOverlay.saveLayout();
                })
                .setNeutralButton(tr("Reset Defaults", "Reset Bawaan"), (dlg, w) -> {
                    saved[0] = true;
                    touchOverlay.resetToDefaults(true);
                    Toast.makeText(this, tr("Touch controls reset to default", "Kontrol sentuh dikembalikan ke bawaan"), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(tr("Cancel", "Batal"), null)
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
        if (cur == null || cur.length < 14) {
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
                    1,
                    gp.getBoolean(KEY_FILL_NO_STRETCH, false) ? 1 : 0,
                    gp.getInt(KEY_WIDE_PAN_PCT, 70),
                    gp.getBoolean(KEY_GAME_LANG_ID, false) ? 1 : 0
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
        final boolean curFillNoStretch = cur[11] != 0;
        final int curWidePanPct = cur[12];
        final boolean curGameLangId = cur[13] != 0;

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
        scaleLabels.add(tr("0.75x (360p — Performance)", "0.75x (360p — Hemat Performa)")); scaleVals.add(75);
        scaleLabels.add(tr("1x (480p Native — Default)", "1x (480p Asli — Bawaan)")); scaleVals.add(100);
        scaleLabels.add("1.5x (720p HD)"); scaleVals.add(150);
        scaleLabels.add("2x (960p FHD)"); scaleVals.add(200);
        scaleLabels.add("2.5x (1200p)"); scaleVals.add(250);
        scaleLabels.add("3x (1440p QHD)"); scaleVals.add(300);
        if (!scaleVals.contains(curScalePct)) {
            scaleLabels.add(String.format(Locale.US, "%.2fx (Custom)", curScalePct / 100.0f));
            scaleVals.add(curScalePct);
        }
        box.addView(makeSectionLabel(ctx, tr("Internal Resolution (Upscale)", "Resolusi Internal (Upscale)"), dp(4)));
        Spinner scaleSpinner = makeSpinner(ctx, scaleLabels, scaleVals, curScalePct);
        box.addView(scaleSpinner);

        // 2. Scaling Filter
        List<String> filterLabels = new ArrayList<>();
        List<Integer> filterVals = new ArrayList<>();
        filterLabels.add(tr("Bilinear (Smooth — Default)", "Bilinear (Halus — Bawaan)")); filterVals.add(0);
        filterLabels.add(tr("Nearest (Sharp Pixels)", "Nearest (Piksel Tajam)")); filterVals.add(1);
        box.addView(makeSectionLabel(ctx, tr("Display Scaling Filter", "Filter Penskalaan Layar"), dp(10)));
        Spinner filterSpinner = makeSpinner(ctx, filterLabels, filterVals, curNearest ? 1 : 0);
        box.addView(filterSpinner);

        // 3. Aspect Ratio (including 16:9 / Full Screen Tanpa Gepeng!)
        List<String> aspectLabels = new ArrayList<>();
        List<Integer> aspectChoiceVals = new ArrayList<>();
        // Encoded as aspect*10 + (fillNoStretch ? 1 : 0)
        aspectLabels.add(tr("4:3 (Default)", "4:3 (Bawaan Asli)")); aspectChoiceVals.add(10);
        aspectLabels.add(tr("16:9 (No Stretch / Tanpa Gepeng)", "16:9 (Tanpa Gepeng)")); aspectChoiceVals.add(20);
        aspectLabels.add(tr("Auto (16:9 No Stretch)", "Otomatis (16:9 Tanpa Gepeng)")); aspectChoiceVals.add(0);
        aspectLabels.add(tr("Full Screen (No Stretch / Tanpa Gepeng)", "Layar Penuh (Tanpa Gepeng)")); aspectChoiceVals.add(31);
        aspectLabels.add(tr("Stretch (Fill Screen — Stretched)", "Rentangkan Penuh (Melar)")); aspectChoiceVals.add(30);
        int curAspectChoice = curAspect * 10 + ((curAspect == 3 && curFillNoStretch) ? 1 : 0);
        box.addView(makeSectionLabel(ctx, tr("Aspect Ratio", "Rasio Layar (Aspect Ratio)"), dp(10)));
        Spinner aspectSpinner = makeSpinner(ctx, aspectLabels, aspectChoiceVals, curAspectChoice);
        box.addView(aspectSpinner);

        // 3b. Vertical Framing for 16:9 / Full Screen No-Stretch
        List<String> panLabels = new ArrayList<>();
        List<Integer> panVals = new ArrayList<>();
        panLabels.add(tr("25% — Top (HUD-Safe)", "25% — Atas (Aman untuk HUD)")); panVals.add(25);
        panLabels.add(tr("50% — Center (Standard / 16:9 FMV)", "50% — Tengah (Standar / Video 16:9)")); panVals.add(50);
        panLabels.add(tr("70% — Subtitle-Safe (Default)", "70% — Aman Subtitle (Bawaan)")); panVals.add(70);
        panLabels.add(tr("90% — Bottom (Full Subtitle)", "90% — Bawah (Subtitle Penuh)")); panVals.add(90);
        if (!panVals.contains(curWidePanPct)) {
            panLabels.add(curWidePanPct + "%");
            panVals.add(curWidePanPct);
        }
        box.addView(makeSectionLabel(ctx,
                tr("16:9 Vertical Position (No-Stretch Crop)", "Posisi Vertikal 16:9 (Tanpa Gepeng)"), dp(10)));
        Spinner panSpinner = makeSpinner(ctx, panLabels, panVals, curWidePanPct);
        box.addView(panSpinner);

        // 4. Anisotropic Filtering
        List<String> anisoLabels = new ArrayList<>();
        List<Integer> anisoVals = new ArrayList<>();
        anisoLabels.add(tr("Off / Game Default (Default)", "Mati / Bawaan Game (Bawaan)")); anisoVals.add(0);
        for (int candidate : new int[] {2, 4, 8, 16}) {
            if (candidate <= maxAniso) {
                anisoLabels.add(candidate + "x");
                anisoVals.add(candidate);
            }
        }
        int clampedAniso = anisoVals.contains(curAniso) ? curAniso : 0;
        box.addView(makeSectionLabel(ctx,
                tr("Anisotropic Filtering (GPU Max: " + maxAniso + "x)", "Filter Anisotropik (Maks GPU: " + maxAniso + "x)"), dp(10)));
        Spinner anisoSpinner = makeSpinner(ctx, anisoLabels, anisoVals, clampedAniso);
        box.addView(anisoSpinner);

        // 5. Overscan Crop
        List<String> overscanLabels = new ArrayList<>();
        List<Integer> overscanVals = new ArrayList<>();
        overscanLabels.add(tr("3.3% Fill Screen (Default)", "3.3% Penuhi Bingkai (Bawaan)")); overscanVals.add(333);
        overscanLabels.add(tr("0% Original Console Frame", "0% Bingkai Konsol Asli")); overscanVals.add(0);
        overscanLabels.add(tr("5.0% Zoom Crop", "5.0% Potong Lebih Rapat")); overscanVals.add(500);
        int selectedOverscan = curOverscan;
        if (Math.abs(curOverscan - 333) <= 2) selectedOverscan = 333;
        else if (!overscanVals.contains(curOverscan)) {
            overscanLabels.add(String.format(Locale.US, "%.2f%% (Custom)", curOverscan / 100.0f));
            overscanVals.add(curOverscan);
        }
        box.addView(makeSectionLabel(ctx, tr("Overscan Crop", "Potong Tepi (Overscan)"), dp(10)));
        Spinner overscanSpinner = makeSpinner(ctx, overscanLabels, overscanVals, selectedOverscan);
        box.addView(overscanSpinner);

        // 6. FPS Cap
        List<String> fpsLabels = new ArrayList<>();
        List<Integer> fpsVals = new ArrayList<>();
        fpsLabels.add(tr("60 FPS (Default)", "60 FPS (Bawaan)")); fpsVals.add(60);
        fpsLabels.add(tr("30 FPS (Power Saver)", "30 FPS (Hemat Daya)")); fpsVals.add(30);
        fpsLabels.add("120 FPS"); fpsVals.add(120);
        fpsLabels.add(tr("Unlimited", "Tanpa Batas")); fpsVals.add(0);
        if (!fpsVals.contains(curFps)) {
            fpsLabels.add(curFps + " FPS (Custom)");
            fpsVals.add(curFps);
        }
        box.addView(makeSectionLabel(ctx, tr("FPS Cap", "Batas FPS"), dp(10)));
        Spinner fpsSpinner = makeSpinner(ctx, fpsLabels, fpsVals, curFps);
        box.addView(fpsSpinner);

        // 7. Recomp Fixes & Game Controls CheckBoxes
        CheckBox vsyncCheck = new CheckBox(ctx);
        vsyncCheck.setText(tr("V-Sync (Default: On)", "V-Sync (Bawaan: Aktif)"));
        vsyncCheck.setChecked(curVsync);
        box.addView(vsyncCheck);

        CheckBox pdepthCheck = new CheckBox(ctx);
        pdepthCheck.setText(tr("Precise Depth Fix (Default: On)", "Perbaikan Kedalaman Presisi (Bawaan: Aktif)"));
        pdepthCheck.setChecked(curPDepth);
        box.addView(pdepthCheck);

        CheckBox nudgeCheck = new CheckBox(ctx);
        nudgeCheck.setText(tr("Stable Point Sampling (Default: On)", "Sampling Titik Stabil (Bawaan: Aktif)"));
        nudgeCheck.setChecked(curNudge);
        box.addView(nudgeCheck);

        CheckBox modernCheck = new CheckBox(ctx);
        modernCheck.setText(tr("Modern Analog Controls (Default: On)", "Kontrol Analog Modern (Bawaan: Aktif)"));
        modernCheck.setChecked(curModern);
        box.addView(modernCheck);

        scroll.addView(box);

        AlertDialog d = builder
                .setTitle(tr("Graphics & Recomp Settings", "Pengaturan Grafis & Recomp"))
                .setView(scroll)
                .setPositiveButton(tr("Apply & Save", "Simpan & Terapkan"), (dlg, w) -> {
                    int scalePct = scaleVals.get(scaleSpinner.getSelectedItemPosition());
                    boolean nearest = filterVals.get(filterSpinner.getSelectedItemPosition()) != 0;
                    int aspectChoice = aspectChoiceVals.get(aspectSpinner.getSelectedItemPosition());
                    int aspect = aspectChoice / 10;
                    boolean fillNoStretch = (aspectChoice % 10) != 0;
                    int widePanPct = panVals.get(panSpinner.getSelectedItemPosition());
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
                            .putBoolean(KEY_FILL_NO_STRETCH, fillNoStretch)
                            .putInt(KEY_WIDE_PAN_PCT, widePanPct)
                            .apply();
                    if (!mBrokenLibraries) {
                        nativeApplyGraphicsSettings(
                                scalePct, aspect, aniso, vsync, fpsCap,
                                overscanX100, nudge, pdepth, nearest, modern,
                                fillNoStretch, widePanPct, curGameLangId);
                    }
                    Toast.makeText(this, tr("Graphics settings applied", "Pengaturan grafis diterapkan"), Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton(tr("Reset Defaults", "Reset Bawaan"), (dlg, w) -> {
                    gp.edit()
                            .putInt(KEY_SCALE_PCT, 100)
                            .putBoolean(KEY_NEAREST, false)
                            .putBoolean(KEY_FILL_NO_STRETCH, false)
                            .putInt(KEY_WIDE_PAN_PCT, 70)
                            .apply();
                    if (!mBrokenLibraries) {
                        nativeApplyGraphicsSettings(
                                100, 1, 0, true, 60, 333, true, true, false, true,
                                false, 70, curGameLangId);
                    }
                    Toast.makeText(this, tr("Default graphics settings restored", "Pengaturan grafis dikembalikan ke bawaan"), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(tr("Cancel", "Batal"), null)
                .create();
        d.setOnDismissListener(dlg -> hideSystemBars());
        d.show();
    }

    // Visual virtual touch overlay bridge -> native/main.cpp
    private static native void nativeTouchPad(int buttons, byte[] analog, short lx, short ly);

    // Graphics, 16:9 no-stretch & localization bridge -> native/main.cpp
    private static native void nativeSetRenderOptions(float renderScale, boolean nearestPresent);
    private static native void nativeSetExtendedOptions(boolean fillNoStretch, float widePan, boolean indonesianGame);
    private static native int[] nativeGetGraphicsSettings();
    private static native void nativeApplyGraphicsSettings(
            int scalePct, int aspect, int aniso, boolean vsync, int fpsCap,
            int overscanX100, boolean pointNudge, boolean preciseDepth,
            boolean nearestPresent, boolean modernControls,
            boolean fillNoStretch, int widePanPct, boolean indonesianGame);

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
                .setTitle(tr("Storage access", "Akses penyimpanan"))
                .setMessage(tr(
                        "Genma Onimusha reads the game from the " + SHARED + " folder on your phone's storage:\n\n"
                                + "/storage/emulated/0/" + SHARED + "\n\n"
                                + (ALL_FILES_ACCESS
                                        ? "On the next screen turn on \"Allow access to manage all files\" for Genma Onimusha, then come back."
                                        : "Allow Genma Onimusha to access your storage on the next screen."),
                        "Genma Onimusha membaca file game dari folder " + SHARED + " di penyimpanan HP Anda:\n\n"
                                + "/storage/emulated/0/" + SHARED + "\n\n"
                                + (ALL_FILES_ACCESS
                                        ? "Di layar berikutnya aktifkan \"Izinkan akses untuk mengelola semua file\" untuk Genma Onimusha, lalu kembali ke sini."
                                        : "Izinkan Genma Onimusha mengakses penyimpanan Anda di layar berikutnya.")))
                .setCancelable(false)
                .setPositiveButton(tr("Allow access", "Izinkan akses"), (d, w) -> openAccessSettings())
                .setNegativeButton(tr("Not now", "Nanti saja"), (d, w) -> notNow = true)
                .setNeutralButton(tr("Import game", "Impor game"), (d, w) -> startImport())
                .show();
    }

    private void tellWhereTheGameGoes() {
        dialog = new AlertDialog.Builder(this)
                .setTitle(tr("Game files", "Berkas game"))
                .setMessage(tr(
                        "Copy Genma Onimusha (USA) into:\n\n"
                                + "/storage/emulated/0/" + SHARED + "\n\n"
                                + "or into a " + SHARED + " folder at the top of your SD card.\n\n"
                                + "Either its disc image (.iso) or default.xbe with its data folders. The game starts automatically once detected.\n\n"
                                + "Or tap Import game to pick the .iso from Downloads, cloud storage, or a USB drive.",
                        "Salin Genma Onimusha (USA) ke dalam:\n\n"
                                + "/storage/emulated/0/" + SHARED + "\n\n"
                                + "atau ke folder " + SHARED + " di direktori utama kartu SD.\n\n"
                                + "Bisa berupa file .iso atau folder berisi default.xbe. Game otomatis dimulai saat terdeteksi.\n\n"
                                + "Atau ketuk Impor game untuk memilih file .iso dari Unduhan atau USB."))
                .setPositiveButton("OK", null)
                .setNeutralButton(tr("Import game", "Impor game"), (d, w) -> startImport())
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
