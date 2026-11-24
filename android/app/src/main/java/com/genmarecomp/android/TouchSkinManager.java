package com.genmarecomp.android;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Handles community touch-controller skin & layout import/export across three formats:
 *   1. RetroArch Overlay packs (.zip containing .cfg + PNG/WebP sprites, or standalone .cfg)
 *   2. Winlator Input Control Profiles (.icp JSON)
 *   3. Genma / Open Community JSON & ZIP packs (layout.json / skin.json + optional sprites)
 *
 * Extracted skin bitmaps are stored inside app-private storage (files/touch_skins/active/)
 * so they persist reliably without extra storage permissions on Android 10-15.
 */
final class TouchSkinManager {
    private static final String SKIN_DIR_NAME = "touch_skins";
    private static final String ACTIVE_SUBDIR = "active";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final int MAX_ZIP_ENTRIES = 128;
    private static final long MAX_ENTRY_BYTES = 4L << 20;
    private static final int MAX_CFG_BYTES = 2 << 20;
    private static final int MAX_SPRITE_DIM = 512;

    static final class ControlSpec {
        float nx = Float.NaN;
        float ny = Float.NaN;
        float scale = Float.NaN;
        TouchOverlayView.ButtonShape shape = null;
        Boolean hidden = null;
        Integer color = null;
        String bitmapFile = null;
    }

    static final class ImportedLayout {
        String sourceFormat = "";
        String packName = "";
        TouchOverlayView.StylePreset preset = null;
        float opacity = Float.NaN;
        float globalScale = Float.NaN;
        final Map<String, ControlSpec> controls = new HashMap<>();
        int spriteCount = 0;
    }

    private TouchSkinManager() {}

    static File getActiveSkinDir(Context ctx) {
        return new File(new File(ctx.getFilesDir(), SKIN_DIR_NAME), ACTIVE_SUBDIR);
    }

    static String getActiveSkinName(Context ctx) {
        File manifest = new File(getActiveSkinDir(ctx), MANIFEST_FILE);
        if (!manifest.isFile()) return null;
        try {
            JSONObject obj = new JSONObject(readFileText(manifest));
            String name = obj.optString("name", "");
            return name.isEmpty() ? null : name;
        } catch (Exception e) {
            return null;
        }
    }

    static Map<String, Bitmap> loadActiveBitmaps(Context ctx) {
        Map<String, Bitmap> out = new HashMap<>();
        File dir = getActiveSkinDir(ctx);
        File manifest = new File(dir, MANIFEST_FILE);
        if (!manifest.isFile()) return out;
        try {
            JSONObject obj = new JSONObject(readFileText(manifest));
            JSONObject bitmaps = obj.optJSONObject("bitmaps");
            if (bitmaps == null) return out;
            Iterator<String> keys = bitmaps.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                String rel = sanitizeFileName(bitmaps.optString(id, ""));
                if (rel.isEmpty()) continue;
                File img = new File(dir, rel);
                if (!img.isFile()) continue;
                Bitmap bmp = decodeSampledBitmap(img);
                if (bmp != null) {
                    out.put(id, bmp);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static void clearActiveSkin(Context ctx) {
        deleteRecursive(getActiveSkinDir(ctx));
    }

    static List<File> findLocalSkinFiles() {
        List<File> found = new ArrayList<>();
        File root = new File(Environment.getExternalStorageDirectory(), GenmaActivity.SHARED + "/skins");
        File[] files = root.listFiles();
        if (files == null) return found;
        for (File f : files) {
            if (!f.isFile()) continue;
            String lo = f.getName().toLowerCase(Locale.US);
            if (lo.endsWith(".zip") || lo.endsWith(".cfg") || lo.endsWith(".icp") || lo.endsWith(".json")) {
                found.add(f);
            }
        }
        return found;
    }

    static ImportedLayout importFromFile(Context ctx, File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            return importFromStream(ctx, file.getName(), in);
        }
    }

    static ImportedLayout importFromUri(Context ctx, Uri uri) throws IOException {
        String name = queryDisplayName(ctx, uri);
        ContentResolver cr = ctx.getContentResolver();
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open selected file");
            return importFromStream(ctx, name, in);
        }
    }

    static ImportedLayout importFromStream(Context ctx, String fileName, InputStream in) throws IOException {
        String safeName = (fileName == null || fileName.trim().isEmpty()) ? "pack.json" : fileName.trim();
        String lower = safeName.toLowerCase(Locale.US);

        File stageDir = new File(new File(ctx.getFilesDir(), SKIN_DIR_NAME), "staging");
        deleteRecursive(stageDir);
        stageDir.mkdirs();

        try {
            ImportedLayout layout;
            if (lower.endsWith(".zip")) {
                layout = parseZipPack(in, stageDir, safeName);
            } else {
                String text = new String(readBoundedBytes(in, MAX_CFG_BYTES), StandardCharsets.UTF_8);
                if (looksLikeJson(text)) {
                    layout = parseJsonOrIcp(text, safeName, null);
                } else {
                    layout = parseRetroArchCfg(text, safeName, null, stageDir);
                }
            }

            if (layout.controls.isEmpty()) {
                throw new IOException("No mappable touch controls found in '" + safeName + "'");
            }

            // Persist staged sprites & manifest into active directory atomically.
            File activeDir = getActiveSkinDir(ctx);
            deleteRecursive(activeDir);
            activeDir.mkdirs();
            JSONObject manifest = new JSONObject();
            manifest.put("name", layout.packName);
            manifest.put("format", layout.sourceFormat);
            JSONObject bitmapMap = new JSONObject();
            for (Map.Entry<String, ControlSpec> en : layout.controls.entrySet()) {
                String bf = en.getValue().bitmapFile;
                if (bf != null && !bf.isEmpty()) {
                    File src = new File(stageDir, bf);
                    if (src.isFile()) {
                        File dst = new File(activeDir, bf);
                        copyFile(src, dst);
                        bitmapMap.put(en.getKey(), bf);
                    }
                }
            }
            manifest.put("bitmaps", bitmapMap);
            writeFileText(new File(activeDir, MANIFEST_FILE), manifest.toString(2));
            return layout;
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception ex) {
            throw new IOException(ex.getMessage() != null ? ex.getMessage() : "Invalid skin pack", ex);
        } finally {
            deleteRecursive(stageDir);
        }
    }

    static void exportToUri(Context ctx, Uri uri, String jsonText) throws IOException {
        try (OutputStream out = ctx.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("Cannot write destination file");
            out.write(jsonText.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static ImportedLayout parseZipPack(InputStream in, File stageDir, String fileName) throws Exception {
        Map<String, String> extractedNames = new HashMap<>(); // lower rel path -> sanitized filename in stageDir
        List<String> cfgFiles = new ArrayList<>();
        List<String> jsonFiles = new ArrayList<>();

        int entries = 0;
        byte[] buf = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_ZIP_ENTRIES) {
                    throw new IOException("ZIP archive exceeds maximum entry limit (" + MAX_ZIP_ENTRIES + ")");
                }
                if (ze.isDirectory()) continue;
                String rawEntry = ze.getName().replace('\\', '/');
                if (rawEntry.startsWith("/") || rawEntry.contains("..")) {
                    throw new IOException("Unsafe path in ZIP: " + rawEntry);
                }
                String baseLower = rawEntry.toLowerCase(Locale.US);
                if (baseLower.startsWith("__macosx/")) continue;

                String safeRel = flattenSafeZipName(rawEntry);
                File outFile = new File(stageDir, safeRel);
                long total = 0;
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_ENTRY_BYTES) {
                            throw new IOException("ZIP entry too large: " + rawEntry);
                        }
                        fos.write(buf, 0, n);
                    }
                }
                String leaf = new File(rawEntry).getName().toLowerCase(Locale.US);
                extractedNames.put(baseLower, safeRel);
                extractedNames.putIfAbsent(leaf, safeRel);
                if (leaf.endsWith(".json") || leaf.endsWith(".icp")) {
                    jsonFiles.add(safeRel);
                } else if (leaf.endsWith(".cfg")) {
                    cfgFiles.add(safeRel);
                }
            }
        }

        if (!jsonFiles.isEmpty()) {
            // Prefer skin.json or layout.json if multiple exist
            String chosen = jsonFiles.get(0);
            for (String c : jsonFiles) {
                if (c.endsWith("layout.json") || c.endsWith("skin.json")) {
                    chosen = c;
                    break;
                }
            }
            String text = readFileText(new File(stageDir, chosen));
            return parseJsonOrIcp(text, fileName, extractedNames);
        }
        if (!cfgFiles.isEmpty()) {
            String chosen = cfgFiles.get(0);
            String text = readFileText(new File(stageDir, chosen));
            return parseRetroArchCfg(text, fileName, extractedNames, stageDir);
        }
        throw new IOException("ZIP pack contains neither .json/.icp nor RetroArch .cfg");
    }

    private static ImportedLayout parseJsonOrIcp(
            String text, String fileName, Map<String, String> zipFiles) throws Exception {
        JSONObject root = new JSONObject(text);
        if (root.has("elements")) {
            return parseWinlatorIcp(root, fileName);
        }
        ImportedLayout out = new ImportedLayout();
        out.sourceFormat = "Genma JSON";
        out.packName = root.optString("name", stripExt(fileName));

        if (root.has("preset")) {
            out.preset = TouchOverlayView.parseStylePreset(root.optString("preset", ""));
        }
        if (root.has("opacity")) {
            out.opacity = (float) root.optDouble("opacity", 1.0);
        }
        if (root.has("globalScale")) {
            out.globalScale = (float) root.optDouble("globalScale", 1.0);
        }

        JSONObject ctrls = root.optJSONObject("controls");
        if (ctrls != null) {
            Iterator<String> keys = ctrls.keys();
            while (keys.hasNext()) {
                String rawKey = keys.next();
                String id = mapControlToken(rawKey);
                if (id == null) continue;
                JSONObject c = ctrls.optJSONObject(rawKey);
                if (c == null) continue;
                ControlSpec spec = new ControlSpec();
                if (c.has("x")) spec.nx = (float) c.optDouble("x", Float.NaN);
                if (c.has("y")) spec.ny = (float) c.optDouble("y", Float.NaN);
                if (c.has("scale")) spec.scale = (float) c.optDouble("scale", Float.NaN);
                if (c.has("hidden")) spec.hidden = c.optBoolean("hidden");
                if (c.has("shape")) spec.shape = TouchOverlayView.parseButtonShape(c.optString("shape", ""));
                if (c.has("color")) spec.color = parseHexColorOrNull(c.optString("color", ""));
                if (c.has("image") && zipFiles != null) {
                    String imgRef = resolveZipRef(zipFiles, c.optString("image", ""));
                    if (imgRef != null) {
                        spec.bitmapFile = imgRef;
                        out.spriteCount++;
                    }
                }
                out.controls.put(id, spec);
            }
        }
        return out;
    }

    private static ImportedLayout parseWinlatorIcp(JSONObject root, String fileName) {
        ImportedLayout out = new ImportedLayout();
        out.sourceFormat = "Winlator .icp";
        out.packName = root.optString("name", stripExt(fileName));

        JSONArray els = root.optJSONArray("elements");
        if (els == null) return out;
        for (int i = 0; i < els.length(); i++) {
            JSONObject el = els.optJSONObject(i);
            if (el == null) continue;
            String type = el.optString("type", "BUTTON").toUpperCase(Locale.US);
            String targetId = null;
            if ("STICK".equals(type)) {
                targetId = "L";
            } else if ("D_PAD".equals(type) || "DPAD".equals(type)) {
                targetId = "DPAD";
            } else {
                String txt = el.optString("text", "");
                targetId = mapControlToken(txt);
                if (targetId == null) {
                    JSONArray bindings = el.optJSONArray("bindings");
                    if (bindings != null) {
                        for (int b = 0; b < bindings.length(); b++) {
                            String tok = mapWinlatorBinding(bindings.optString(b, ""));
                            if (tok != null) {
                                targetId = tok;
                                break;
                            }
                        }
                    }
                }
            }
            if (targetId == null) continue;

            ControlSpec spec = out.controls.containsKey(targetId) ? out.controls.get(targetId) : new ControlSpec();
            double x = el.optDouble("x", Double.NaN);
            double y = el.optDouble("y", Double.NaN);
            // Normalize pixel coordinates heuristically only if already 0..1; Winlator >= 8 stores 0..1
            if (!Double.isNaN(x) && x >= 0.0 && x <= 1.0) spec.nx = (float) x;
            if (!Double.isNaN(y) && y >= 0.0 && y <= 1.0) spec.ny = (float) y;
            double sc = el.optDouble("scale", Double.NaN);
            if (!Double.isNaN(sc) && sc > 0.1) spec.scale = (float) sc;

            String shape = el.optString("shape", "").toUpperCase(Locale.US);
            if ("CIRCLE".equals(shape)) spec.shape = TouchOverlayView.ButtonShape.CIRCLE;
            else if ("ROUND_RECT".equals(shape)) spec.shape = TouchOverlayView.ButtonShape.ROUNDED_SQUARE;
            else if ("RECTANGLE".equals(shape)) spec.shape = TouchOverlayView.ButtonShape.PILL;

            out.controls.put(targetId, spec);
        }
        return out;
    }

    private static ImportedLayout parseRetroArchCfg(
            String cfgText, String fileName, Map<String, String> zipFiles, File stageDir) {
        ImportedLayout out = new ImportedLayout();
        out.sourceFormat = "RetroArch .cfg";
        out.packName = stripExt(fileName);

        Map<String, String> kv = new HashMap<>();
        for (String rawLine : cfgText.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim().toLowerCase(Locale.US);
            String v = line.substring(eq + 1).trim();
            if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                v = v.substring(1, v.length() - 1);
            }
            kv.put(k, v);
        }

        boolean globalNorm = parseBool(kv.get("overlay0_normalized"), true);
        int descs = parseIntSafe(kv.get("overlay0_descs"), 0);
        if (descs <= 0) {
            for (int i = 0; i < 64; i++) {
                if (kv.containsKey("overlay0_desc" + i)) descs = i + 1;
            }
        }

        // Collect dpad extremes to average center cleanly if RetroArch defines separate up/down/left/right hitboxes.
        List<float[]> dpadPts = new ArrayList<>();
        String dpadSprite = null;

        for (int i = 0; i < descs; i++) {
            String descKey = "overlay0_desc" + i;
            String descVal = kv.get(descKey);
            if (descVal == null || descVal.isEmpty()) continue;

            boolean norm = kv.containsKey(descKey + "_normalized")
                    ? parseBool(kv.get(descKey + "_normalized"), globalNorm)
                    : globalNorm;

            String[] parts = descVal.split(",");
            if (parts.length < 6) continue;
            String btnRaw = parts[0].trim();
            float px = parseFloatSafe(parts[1].trim(), Float.NaN);
            float py = parseFloatSafe(parts[2].trim(), Float.NaN);
            String shapeTok = parts[3].trim().toLowerCase(Locale.US);
            float rx = parseFloatSafe(parts[4].trim(), Float.NaN);
            float ry = parseFloatSafe(parts[5].trim(), Float.NaN);

            if (!norm || Float.isNaN(px) || Float.isNaN(py)) continue;
            String spriteName = kv.get(descKey + "_overlay");
            String resolvedSprite = (zipFiles != null && spriteName != null) ? resolveZipRef(zipFiles, spriteName) : null;

            String primaryBtn = btnRaw.contains("|") ? btnRaw.substring(0, btnRaw.indexOf('|')).trim() : btnRaw;
            String loBtn = primaryBtn.toLowerCase(Locale.US);
            if ("up".equals(loBtn) || "down".equals(loBtn) || "left".equals(loBtn) || "right".equals(loBtn)
                    || "dpad".equals(loBtn) || "d-pad".equals(loBtn)) {
                dpadPts.add(new float[] {px, py, Float.isNaN(rx) ? 0.05f : rx, Float.isNaN(ry) ? 0.05f : ry});
                if (dpadSprite == null && resolvedSprite != null) dpadSprite = resolvedSprite;
                continue;
            }

            String targetId = mapControlToken(primaryBtn);
            if (targetId == null) continue;

            ControlSpec spec = new ControlSpec();
            spec.nx = px;
            spec.ny = py;
            if (!Float.isNaN(ry) && ry > 0.01f && ry <= 0.35f) {
                spec.scale = Math.max(0.6f, Math.min(1.8f, ry / 0.068f));
            }
            if ("rect".equals(shapeTok)) {
                spec.shape = (!Float.isNaN(rx) && !Float.isNaN(ry) && rx > ry * 1.35f)
                        ? TouchOverlayView.ButtonShape.PILL
                        : TouchOverlayView.ButtonShape.ROUNDED_SQUARE;
            } else {
                spec.shape = TouchOverlayView.ButtonShape.CIRCLE;
            }
            if (resolvedSprite != null && new File(stageDir, resolvedSprite).isFile()) {
                spec.bitmapFile = resolvedSprite;
                out.spriteCount++;
            }
            out.controls.put(targetId, spec);
        }

        if (!dpadPts.isEmpty() && !out.controls.containsKey("DPAD")) {
            float sx = 0f, sy = 0f;
            float minX = 1f, maxX = 0f, minY = 1f, maxY = 0f;
            for (float[] pt : dpadPts) {
                sx += pt[0];
                sy += pt[1];
                minX = Math.min(minX, pt[0] - pt[2]);
                maxX = Math.max(maxX, pt[0] + pt[2]);
                minY = Math.min(minY, pt[1] - pt[3]);
                maxY = Math.max(maxY, pt[1] + pt[3]);
            }
            ControlSpec dpad = new ControlSpec();
            dpad.nx = sx / dpadPts.size();
            dpad.ny = sy / dpadPts.size();
            float spanY = Math.max(0.08f, maxY - minY);
            dpad.scale = Math.max(0.6f, Math.min(1.6f, (spanY * 0.5f) / 0.105f));
            if (dpadSprite != null && new File(stageDir, dpadSprite).isFile()) {
                dpad.bitmapFile = dpadSprite;
                out.spriteCount++;
            }
            out.controls.put("DPAD", dpad);
        }
        return out;
    }

    private static String mapControlToken(String raw) {
        if (raw == null) return null;
        String k = raw.trim().toUpperCase(Locale.US);
        switch (k) {
            case "L":
            case "STICK":
            case "LEFT_STICK":
            case "ANALOG_LEFT":
            case "L_X_PLUS":
                return "L";
            case "DPAD":
            case "D_PAD":
            case "D-PAD":
                return "DPAD";
            case "LB":
            case "L1":
            case "WHITE":
                return "LB";
            case "LT":
            case "L2":
                return "LT";
            case "RT":
            case "R2":
                return "RT";
            case "RB":
            case "R1":
            case "BLACK":
                return "RB";
            case "BACK":
            case "SELECT":
            case "SHARE":
                return "BACK";
            case "PAD":
            case "TOGGLE":
                return "PAD";
            case "START":
            case "OPTIONS":
                return "START";
            case "MENU":
            case "CFG":
            case "MENU_TOGGLE":
            case "SETTINGS":
                return "MENU";
            case "A":
            case "SOUTH":
            case "CROSS":
                return "A";
            case "B":
            case "EAST":
            case "CIRCLE":
                return "B";
            case "X":
            case "WEST":
            case "SQUARE":
                return "X";
            case "Y":
            case "NORTH":
            case "TRIANGLE":
                return "Y";
            default:
                return null;
        }
    }

    private static String mapWinlatorBinding(String raw) {
        if (raw == null) return null;
        String k = raw.trim().toUpperCase(Locale.US);
        if ("NONE".equals(k) || k.isEmpty()) return null;
        if (k.startsWith("GAMEPAD_BUTTON_")) {
            return mapControlToken(k.substring("GAMEPAD_BUTTON_".length()));
        }
        if ("KEY_SPACE".equals(k) || "KEY_ENTER".equals(k) || "KEY_RETURN".equals(k) || "KEY_Z".equals(k)) return "A";
        if ("KEY_X".equals(k) || "KEY_SHIFT_L".equals(k)) return "X";
        if ("KEY_C".equals(k) || "KEY_E".equals(k)) return "Y";
        if ("KEY_BACKSPACE".equals(k) || "KEY_Q".equals(k)) return "B";
        if ("KEY_ESC".equals(k) || "KEY_P".equals(k)) return "START";
        if ("KEY_TAB".equals(k) || "KEY_M".equals(k)) return "BACK";
        if ("KEY_F".equals(k) || "KEY_L".equals(k)) return "LB";
        if ("KEY_R".equals(k)) return "RB";
        return mapControlToken(k);
    }

    private static String resolveZipRef(Map<String, String> zipFiles, String ref) {
        if (ref == null) return null;
        String norm = ref.trim().replace('\\', '/').toLowerCase(Locale.US);
        if (norm.startsWith("./")) norm = norm.substring(2);
        if (zipFiles.containsKey(norm)) return zipFiles.get(norm);
        String leaf = new File(norm).getName();
        return zipFiles.get(leaf);
    }

    private static String flattenSafeZipName(String raw) {
        String clean = raw.replace('/', '_').replace('\\', '_');
        return sanitizeFileName(clean);
    }

    private static String sanitizeFileName(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String res = sb.toString();
        if (res.equals(".") || res.equals("..") || res.startsWith("..")) return "";
        return res;
    }

    private static Bitmap decodeSampledBitmap(File f) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
        int sample = 1;
        while ((opts.outWidth / sample) > MAX_SPRITE_DIM || (opts.outHeight / sample) > MAX_SPRITE_DIM) {
            sample *= 2;
        }
        BitmapFactory.Options real = new BitmapFactory.Options();
        real.inSampleSize = sample;
        real.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(f.getPath(), real);
    }

    private static Integer parseHexColorOrNull(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return android.graphics.Color.parseColor(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean looksLikeJson(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) return c == '{' || c == '[';
        }
        return false;
    }

    private static boolean parseBool(String s, boolean def) {
        if (s == null) return def;
        String lo = s.trim().toLowerCase(Locale.US);
        if ("true".equals(lo) || "1".equals(lo) || "yes".equals(lo)) return true;
        if ("false".equals(lo) || "0".equals(lo) || "no".equals(lo)) return false;
        return def;
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static float parseFloatSafe(String s, float def) {
        if (s == null) return def;
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String queryDisplayName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return new File(c.getString(0)).getName();
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "pack.json" : new File(last).getName();
    }

    private static byte[] readBoundedBytes(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > maxBytes) throw new IOException("Config file too large");
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static String readFileText(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            return new String(readBoundedBytes(fis, MAX_CFG_BYTES), StandardCharsets.UTF_8);
        }
    }

    private static void writeFileText(File f, String text) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        byte[] buf = new byte[8192];
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] ch = f.listFiles();
        if (ch != null) {
            for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }
}
