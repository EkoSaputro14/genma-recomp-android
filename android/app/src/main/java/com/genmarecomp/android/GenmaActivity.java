package com.genmarecomp.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public class GenmaActivity extends Activity implements InputManager.InputDeviceListener {
    private static final String SHARED_FOLDER_NAME = "GenmaOnimusha";
    private static final int REQ_STORAGE_PERM = 1000;
    private static final int REQ_PICK_GAME_DIR = 1001;
    private static final int REQ_PICK_TEXTURE_DIR = 1002;

    static {
        System.loadLibrary("genma_android");
    }

    private native String nativeRunSelfCheck();
    private native void nativeSetGamePaths(String dataDir, String texDir);
    private native boolean nativeLoadGameLibrary(String soPath);
    private native void nativeTouchEvent(int action, int pointerId, float normX, float normY);
    private native void nativeSetGamepadConnected(boolean connected);
    private native boolean nativeToggleImGuiMenu();

    private TextView diagOutput;
    private TextView storageStatus;
    private InputManager inputManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        inputManager = (InputManager) getSystemService(INPUT_SERVICE);
        if (inputManager != null) {
            inputManager.registerInputDeviceListener(this, null);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B0F17"));
        root.setPadding(44, 32, 44, 32);

        TextView title = new TextView(this);
        title.setText("Genma Onimusha — Android ARM64 (v0.7.0-android.1)");
        title.setTextColor(Color.parseColor("#F3F4F6"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("API 29+ · Vulkan 1.0+ Fallback · Folder: /storage/emulated/0/" + SHARED_FOLDER_NAME);
        subtitle.setTextColor(Color.parseColor("#9CA3AF"));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitle.setPadding(0, 6, 0, 18);
        root.addView(subtitle);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.START);

        Button btnStorage = createActionButton("Izinkan Akses Storage", "#DC2626");
        btnStorage.setOnClickListener(v -> requestSharedStorageAccess());
        actions.addView(btnStorage);

        Button btnPickGame = createActionButton("Pilih Folder SAF", "#1F2937");
        btnPickGame.setOnClickListener(v -> openDirectoryPicker(REQ_PICK_GAME_DIR));
        actions.addView(btnPickGame);

        Button btnImGui = createActionButton("Menu Pengaturan (ImGui)", "#1F2937");
        btnImGui.setOnClickListener(v -> {
            boolean open = nativeToggleImGuiMenu();
            Toast.makeText(this, open ? "Menu ImGui Sentuh Aktif" : "Menu ImGui Ditutup", Toast.LENGTH_SHORT).show();
        });
        actions.addView(btnImGui);

        root.addView(actions);

        storageStatus = new TextView(this);
        storageStatus.setTextColor(Color.parseColor("#D1D5DB"));
        storageStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        storageStatus.setPadding(0, 16, 0, 12);
        root.addView(storageStatus);

        ScrollView scroll = new ScrollView(this);
        diagOutput = new TextView(this);
        diagOutput.setTextColor(Color.parseColor("#E5E7EB"));
        diagOutput.setBackgroundColor(Color.parseColor("#111827"));
        diagOutput.setTypeface(Typeface.MONOSPACE);
        diagOutput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        diagOutput.setPadding(24, 20, 24, 20);
        scroll.addView(diagOutput);
        root.addView(scroll);

        setContentView(root);
        updateGamepadConnectionState();
        scanGameFolderAndRefresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        scanGameFolderAndRefresh();
    }

    private boolean hasSharedStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestSharedStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(
                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQ_STORAGE_PERM
            );
        }
    }

    private void scanGameFolderAndRefresh() {
        File privateAssets = new File(getExternalFilesDir(null), "assets");
        privateAssets.mkdirs();

        File targetDir = privateAssets;
        String foundSummary = "Salin file .iso atau folder ekstrak default.xbe ke /storage/emulated/0/" + SHARED_FOLDER_NAME;

        if (hasSharedStorageAccess()) {
            File sharedDir = new File(Environment.getExternalStorageDirectory(), SHARED_FOLDER_NAME);
            if (!sharedDir.exists()) {
                sharedDir.mkdirs();
            }
            new File(sharedDir, "textures").mkdirs();
            targetDir = sharedDir;

            File xbe = new File(sharedDir, "default.xbe");
            File foundIso = findIsoInDir(sharedDir);
            if (xbe.exists()) {
                foundSummary = "Ditemukan default.xbe di " + sharedDir.getAbsolutePath();
            } else if (foundIso != null) {
                foundSummary = "Ditemukan image ISO: " + foundIso.getName() + " di " + sharedDir.getAbsolutePath();
            } else {
                foundSummary = "Folder siap: " + sharedDir.getAbsolutePath() + " (belum ada .iso / default.xbe)";
            }
        } else {
            foundSummary = "Izin All Files Access belum aktif. Ketuk 'Izinkan Akses Storage' atau salin data ke " + privateAssets.getAbsolutePath();
        }

        File texDir = new File(targetDir, "textures");
        nativeSetGamePaths(targetDir.getAbsolutePath(), texDir.getAbsolutePath());
        storageStatus.setText(foundSummary);
        diagOutput.setText(nativeRunSelfCheck());
    }

    private File findIsoInDir(File dir) {
        File[] list = dir.listFiles();
        if (list == null) return null;
        for (File f : list) {
            if (f.isFile()) {
                String lower = f.getName().toLowerCase();
                if (lower.endsWith(".iso") || lower.endsWith(".xiso")) {
                    return f;
                }
            }
        }
        return null;
    }

    private Button createActionButton(String label, String bgHex) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundColor(Color.parseColor(bgHex));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, 0, 14, 0);
        btn.setLayoutParams(lp);
        btn.setPadding(24, 14, 24, 14);
        return btn;
    }

    private void openDirectoryPicker(int reqCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, reqCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri treeUri = data.getData();
        final int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(treeUri, flags);
        } catch (SecurityException ignored) {
        }
        if (requestCode == REQ_PICK_GAME_DIR) {
            storageStatus.setText("Folder Game SAF: " + treeUri.toString());
        } else if (requestCode == REQ_PICK_TEXTURE_DIR) {
            storageStatus.setText("Folder HD Textures SAF: " + treeUri.toString());
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        View decor = getWindow().getDecorView();
        float width = Math.max(1f, decor.getWidth());
        float height = Math.max(1f, decor.getHeight());
        int action = event.getActionMasked();
        int idx = event.getActionIndex();
        int pid = event.getPointerId(idx);

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            nativeTouchEvent(0, pid, event.getX(idx) / width, event.getY(idx) / height);
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                nativeTouchEvent(1, event.getPointerId(i), event.getX(i) / width, event.getY(i) / height);
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            nativeTouchEvent(2, pid, event.getX(idx) / width, event.getY(idx) / height);
        }
        return super.onTouchEvent(event);
    }

    private void updateGamepadConnectionState() {
        boolean found = false;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice dev = InputDevice.getDevice(id);
            if (dev != null) {
                int sources = dev.getSources();
                if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                    found = true;
                    break;
                }
            }
        }
        nativeSetGamepadConnected(found);
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        updateGamepadConnectionState();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        updateGamepadConnectionState();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        updateGamepadConnectionState();
    }

    @Override
    protected void onDestroy() {
        if (inputManager != null) {
            inputManager.unregisterInputDeviceListener(this);
        }
        super.onDestroy();
    }
}
