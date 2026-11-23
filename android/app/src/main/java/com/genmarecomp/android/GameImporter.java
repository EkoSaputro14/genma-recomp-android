package com.genmarecomp.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.function.Consumer;

// Atomic Xbox disc image (.iso/.xiso) importer via Android SAF, following RanggaCasper/Black-Android.
// Verifies the XDVDFS magic header ("MICROSOFT*XBOX*MEDIA"), copies into a temporary .part file with a
// 4 MiB direct buffer + fsync, then atomically renames to .iso so native app::FindGame() never opens a partial copy.
final class GameImporter {
    static final int REQUEST = 0x8A11;
    private static final String TAG = "GenmaImport";
    private static final byte[] MAGIC = "MICROSOFT*XBOX*MEDIA".getBytes();
    private static final long[] PARTITIONS = {0, 0x18300000L, 0xFD90000L, 0x2080000L};
    private static final int BUFFER = 4 << 20;

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;

    GameImporter(Activity activity) {
        this.activity = activity;
    }

    private boolean isId() {
        return "id".equals(activity.getSharedPreferences(GenmaActivity.PREFS_GRAPHICS, Context.MODE_PRIVATE)
                .getString(GenmaActivity.KEY_UI_LANG, "en"));
    }

    private String tr(String en, String id) {
        return isId() ? id : en;
    }

    boolean pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            activity.startActivityForResult(i, REQUEST);
            return true;
        } catch (ActivityNotFoundException e) {
            message(tr("Import game", "Impor game"),
                    tr("This device has no file picker to select the disc image.",
                       "Perangkat ini tidak memiliki pemilih berkas untuk memilih ISO."), null);
            return false;
        }
    }

    void copy(Uri uri, File dir, Consumer<Boolean> done) {
        String name = displayName(uri);
        long size = size(uri);
        String lower = name != null ? name.toLowerCase() : "";
        if (name == null || (!lower.endsWith(".iso") && !lower.endsWith(".xiso"))) {
            message(tr("Import game", "Impor game"),
                    tr("Choose the game's Xbox disc image (.iso or .xiso).",
                       "Pilih berkas citra cakram Xbox game (.iso atau .xiso)."), () -> done.accept(false));
            return;
        }
        dir.mkdirs();
        if (size > 0 && dir.getUsableSpace() < size + (64L << 20)) {
            message(tr("Not enough space", "Ruang penyimpanan tidak cukup"),
                    String.format(tr("The disc image needs %.1f GB free, only %.1f GB available.",
                                     "Berkas ISO membutuhkan %.1f GB kosong, hanya tersedia %.1f GB."),
                            size / 1e9, dir.getUsableSpace() / 1e9),
                    () -> done.accept(false));
            return;
        }

        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(size <= 0);
        TextView text = new TextView(activity);
        text.setText(tr("Starting…", "Memulai…"));
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(bar);
        box.addView(text);
        cancelled = false;
        AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle(tr("Importing ", "Mengimpor ") + name)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(tr("Cancel", "Batal"), (d, w) -> cancelled = true)
                .show();
        if (progress.getWindow() != null) {
            progress.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        File part = new File(dir, name + ".part");
        File iso = new File(dir, name);
        new Thread(() -> {
            String error = null;
            try (ParcelFileDescriptor pfd = activity.getContentResolver().openFileDescriptor(uri, "r");
                 FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
                 FileChannel src = in.getChannel()) {
                if (isXboxImage(src) == Boolean.FALSE) {
                    error = name + tr(" is not a valid original Xbox disc image.", " bukan berkas ISO Xbox asli yang valid.");
                } else {
                    try (FileOutputStream out = new FileOutputStream(part); FileChannel dst = out.getChannel()) {
                        ByteBuffer buf = ByteBuffer.allocateDirect(BUFFER);
                        long copied = 0, shown = 0;
                        while (!cancelled && src.read(buf) > 0) {
                            buf.flip();
                            while (buf.hasRemaining()) copied += dst.write(buf);
                            buf.clear();
                            if (copied - shown >= (16L << 20)) {
                                shown = copied;
                                long c = copied;
                                main.post(() -> show(bar, text, c, size));
                            }
                        }
                        out.getFD().sync();
                        if (!cancelled && size > 0 && copied != size) {
                            error = tr("The copy stopped early.", "Penyalinan berhenti sebelum selesai.");
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "import failed", e);
                error = tr("The copy failed: ", "Gagal menyalin: ") + e.getMessage();
            }
            boolean ok = error == null && !cancelled && part.renameTo(iso);
            if (!ok) {
                part.delete();
                if (error == null && !cancelled) {
                    error = tr("The image could not be finalized.", "Berkas ISO gagal diselesaikan.");
                }
            }
            String why = error;
            main.post(() -> {
                progress.dismiss();
                if (ok) {
                    Log.i(TAG, "imported " + iso);
                    message(tr("Game imported", "Game berhasil diimpor"),
                            name + tr(" is ready. Genma Onimusha will start in a moment.",
                                      " siap. Genma Onimusha akan segera dimulai."), () -> done.accept(true));
                } else if (why != null) {
                    message(tr("Import failed", "Impor gagal"), why, () -> done.accept(false));
                } else {
                    done.accept(false);
                }
            });
        }, "GenmaImport").start();
    }

    private static void show(ProgressBar bar, TextView text, long copied, long size) {
        if (size > 0) {
            bar.setProgress((int) (copied * 1000 / size));
            text.setText(String.format("%.2f of %.2f GB", copied / 1e9, size / 1e9));
        } else {
            text.setText(String.format("%.2f GB", copied / 1e9));
        }
    }

    private static Boolean isXboxImage(FileChannel ch) {
        ByteBuffer b = ByteBuffer.allocate(MAGIC.length);
        try {
            for (long part : PARTITIONS) {
                b.clear();
                if (ch.read(b, part + 32 * 2048) == MAGIC.length && Arrays.equals(b.array(), MAGIC)) return true;
            }
            return false;
        } catch (Exception e) {
            Log.i(TAG, "image not checked, non-seekable source: " + e);
            return null;
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = activity.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return new File(c.getString(0)).getName();
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? null : new File(last).getName();
    }

    private long size(Uri uri) {
        try (Cursor c = activity.getContentResolver().query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {
        }
        return -1;
    }

    private void message(String title, String text, Runnable after) {
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton("OK", null)
                .setOnDismissListener(d -> {
                    if (after != null) after.run();
                })
                .show();
    }
}
