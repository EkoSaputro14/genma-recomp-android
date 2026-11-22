package com.genmarecomp.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Bundle;
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

public class GenmaActivity extends Activity implements InputManager.InputDeviceListener {
    private static final int REQ_PICK_GAME_DIR = 1001;
    private static final int REQ_PICK_TEXTURE_DIR = 1002;

    static {
        System.loadLibrary("genma_android");
    }

    private native String nativeRunSelfCheck();
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
        root.setPadding(48, 36, 48, 36);

        TextView title = new TextView(this);
        title.setText("Genma Onimusha — Android ARM64 Runtime (v0.7.0-android.1)");
        title.setTextColor(Color.parseColor("#F3F4F6"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Static Recompilation Runtime · API 29+ · Vulkan 1.0+ Fallback · Asset-Free");
        subtitle.setTextColor(Color.parseColor("#9CA3AF"));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitle.setPadding(0, 6, 0, 20);
        root.addView(subtitle);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.START);

        Button btnPickGame = createActionButton("Pilih Folder Game / ISO", "#DC2626");
        btnPickGame.setOnClickListener(v -> openDirectoryPicker(REQ_PICK_GAME_DIR));
        actions.addView(btnPickGame);

        Button btnPickTex = createActionButton("Pilih HD Texture Pack", "#1F2937");
        btnPickTex.setOnClickListener(v -> openDirectoryPicker(REQ_PICK_TEXTURE_DIR));
        actions.addView(btnPickTex);

        Button btnImGui = createActionButton("Menu Pengaturan (ImGui)", "#1F2937");
        btnImGui.setOnClickListener(v -> {
            boolean open = nativeToggleImGuiMenu();
            Toast.makeText(this, open ? "Menu ImGui Sentuh Aktif" : "Menu ImGui Ditutup", Toast.LENGTH_SHORT).show();
        });
        actions.addView(btnImGui);

        root.addView(actions);

        storageStatus = new TextView(this);
        storageStatus.setText("Folder game: belum dipilih (gunakan build_android_pc.sh untuk menyertakan libgenma_game.so)");
        storageStatus.setTextColor(Color.parseColor("#D1D5DB"));
        storageStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        storageStatus.setPadding(0, 18, 0, 12);
        root.addView(storageStatus);

        ScrollView scroll = new ScrollView(this);
        diagOutput = new TextView(this);
        diagOutput.setTextColor(Color.parseColor("#E5E7EB"));
        diagOutput.setBackgroundColor(Color.parseColor("#111827"));
        diagOutput.setTypeface(Typeface.MONOSPACE);
        diagOutput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        diagOutput.setPadding(28, 24, 28, 24);
        scroll.addView(diagOutput);
        root.addView(scroll);

        setContentView(root);
        updateGamepadConnectionState();
        diagOutput.setText(nativeRunSelfCheck());
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
        lp.setMargins(0, 0, 16, 0);
        btn.setLayoutParams(lp);
        btn.setPadding(28, 16, 28, 16);
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
