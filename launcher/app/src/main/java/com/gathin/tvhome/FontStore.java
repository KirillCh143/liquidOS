package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local patch: the fonts other than Outfit are not in the APK. They are copied from the fonts/ folder of the GitHub
 * repository (Launcher settings > Personalization > Advanced) into the app's private files, and drawn from there.
 * Roboto, Nunito, Rubik and Roboto Slab are variable fonts (one file, any weight); Ubuntu has a file per weight.
 */
final class FontStore {
    /** Font code (see UiFonts) to its files in the repository's fonts/ folder. */
    private static final Map<String, String[]> FILES = new HashMap<>();
    static {
        FILES.put("roboto", new String[]{"roboto.ttf"});
        FILES.put("nunito", new String[]{"nunito.ttf"});
        FILES.put("rubik", new String[]{"rubik.ttf"});
        FILES.put("roboto_slab", new String[]{"robotoslab.ttf"});
        FILES.put("ubuntu", new String[]{"ubuntu-light.ttf", "ubuntu-regular.ttf", "ubuntu-medium.ttf", "ubuntu-bold.ttf"});
    }

    private static final Map<String, Typeface> CACHE = new HashMap<>();

    private FontStore() {}

    /** A font that is downloaded, not bundled. */
    static boolean downloadable(String code) {
        return FILES.containsKey(code);
    }

    static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "fonts");
    }

    /** Every file of the font is on the TV. */
    static boolean installed(Context ctx, String code) {
        String[] files = FILES.get(code);
        if (files == null) return false;
        for (String name : files) if (!new File(dir(ctx), name).isFile()) return false;
        return true;
    }

    /** The font at a weight (200 extra light, 300 light, 400, 500 medium, 700 bold); null when it is not downloaded. */
    static synchronized Typeface typeface(Context ctx, String code, int weight) {
        if (!installed(ctx, code)) return null;
        String key = code + weight;
        Typeface cached = CACHE.get(key);
        if (cached != null) return cached;
        Typeface made = null;
        try {
            if ("ubuntu".equals(code)) {
                String name = weight <= 300 ? "ubuntu-light.ttf" : weight <= 400 ? "ubuntu-regular.ttf"
                        : weight < 600 ? "ubuntu-medium.ttf" : "ubuntu-bold.ttf";
                made = Typeface.createFromFile(new File(dir(ctx), name));
            } else {
                String axes = "'wght' " + weight + ("roboto".equals(code) ? ", 'wdth' 100" : "");
                made = new Typeface.Builder(new File(dir(ctx), FILES.get(code)[0]))
                        .setWeight(weight).setFontVariationSettings(axes).build();
            }
        } catch (RuntimeException e) {
            android.util.Log.w("FontStore", "cannot load " + code + ": " + e);
        }
        if (made == null) return null;
        CACHE.put(key, made);
        return made;
    }

    /** Deletes every downloaded font; how many files went. */
    static synchronized int deleteAll(Context ctx) {
        CACHE.clear();
        int removed = 0;
        File[] files = dir(ctx).listFiles();
        if (files != null) for (File f : files) if (f.delete()) removed++;
        return removed;
    }

    /** Copies the fonts that are not on the TV yet. Runs on its own thread; the callbacks come on the main thread
     *  and none after cancel is set. onDone gets how many fonts were added and how many were already there. */
    static void download(Context ctx, AtomicBoolean cancel, WallpaperDownloader.Listener listener) {
        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                File dir = dir(ctx);
                dir.mkdirs();
                String[] codes = {"roboto", "nunito", "rubik", "roboto_slab", "ubuntu"};
                int added = 0, skipped = 0;
                for (int i = 0; i < codes.length && !cancel.get(); i++) {
                    final int index = i + 1;
                    final String code = codes[i];
                    ui.post(() -> { if (!cancel.get()) listener.onProgress(index, codes.length, UiFonts.nameOf(code)); });
                    if (installed(ctx, code)) { skipped++; continue; }
                    for (String name : FILES.get(code)) {
                        File target = new File(dir, name);
                        if (target.isFile()) continue;
                        File part = new File(dir, name + ".part");
                        HttpURLConnection c = open("https://raw.githubusercontent.com/" + WallpaperDownloader.REPO + "/"
                                + WallpaperDownloader.BRANCH + "/fonts/" + name);
                        try (InputStream in = c.getInputStream(); OutputStream out = new java.io.FileOutputStream(part)) {
                            byte[] buf = new byte[16384];
                            for (int n; (n = in.read(buf)) > 0 && !cancel.get(); ) out.write(buf, 0, n);
                        } finally {
                            c.disconnect();
                        }
                        if (cancel.get()) { part.delete(); break; }
                        if (!looksLikeFont(part)) { part.delete(); throw new java.io.IOException("not a font: " + name); }
                        if (!part.renameTo(target)) throw new java.io.IOException("rename");
                    }
                    if (cancel.get()) break;
                    added++;
                }
                final int a = added, s = skipped;
                ui.post(() -> { if (!cancel.get()) listener.onDone(a, s); });
            } catch (Exception e) {
                android.util.Log.w("FontStore", "download failed: " + e, e);
                ui.post(() -> { if (!cancel.get()) listener.onError(); });
            }
        }, "font-download").start();
    }

    /** TrueType (0x00010000, "true") or OpenType ("OTTO") header, so an error page is not kept as a font. */
    private static boolean looksLikeFont(File f) {
        if (f.length() < 10 * 1024) return false;
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] h = new byte[4];
            if (in.read(h) != 4) return false;
            String tag = new String(h, "ISO-8859-1");
            return (h[0] == 0 && h[1] == 1 && h[2] == 0 && h[3] == 0) || "true".equals(tag) || "OTTO".equals(tag);
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static HttpURLConnection open(String url) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "liquidOS");
        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode() + " " + url);
        return c;
    }
}
