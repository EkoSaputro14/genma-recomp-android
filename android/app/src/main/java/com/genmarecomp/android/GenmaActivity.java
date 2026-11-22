package com.genmarecomp.android;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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

    private AlertDialog dialog;
    private boolean notNow;
    private final GameImporter importer = new GameImporter(this);
    private boolean importing;
    private TouchOverlayView touchOverlay;

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
        if (mLayout != null) {
            touchOverlay = new TouchOverlayView(this, (buttons, analog, lx, ly) -> {
                if (!mBrokenLibraries) {
                    nativeTouchPad(buttons, analog, lx, ly);
                }
            });
            mLayout.addView(touchOverlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        File own = getExternalFilesDir(null);
        if (own != null) {
            new File(own, "assets").mkdirs();
        }
    }

    // Visual virtual touch overlay bridge -> native/main.cpp
    private static native void nativeTouchPad(int buttons, byte[] analog, short lx, short ly);

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
