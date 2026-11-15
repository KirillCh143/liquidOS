package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

/**
 * Local patch: a glass panel's wallpaper layer. glassView (a child of slot) shows the part of the wallpaper that lies
 * behind slot on the screen, blurred (and, with Liquid glass, refracted at the edges). Used by the tile menu on Home and
 * in the apps grid, and by the Settings grid's tiles.
 */
final class GlassLayer {
    private GlassLayer() {}

    /** @param root the view the wallpaper covers (the screen); @param bitmap the picture behind it */
    static void apply(Context ctx, ImageView glassView, View slot, View root, Bitmap bitmap, float radius, boolean liquid) {
        apply(ctx, glassView, slot, root, bitmap, radius, liquid, 1f);
    }

    /** @param edgeDp how far the Liquid glass refraction reaches in from the edge (the dock uses 2, a small tile 1) */
    static void apply(Context ctx, ImageView glassView, View slot, View root, Bitmap bitmap, float radius, boolean liquid, float edgeDp) {
        if (glassView == null || bitmap == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        float density = ctx.getResources().getDisplayMetrics().density;
        float width = slot.getWidth(), height = slot.getHeight();
        // Local patch: a plain CENTER_CROP here would fit the whole wallpaper into this one small tile — a
        // tight, unrecognisable zoom into a random patch of it, not the slice actually behind this tile. A
        // MATRIX scaleType replicates the main wallpaper ImageView's own CENTER_CROP math (same scale, same
        // centred crop) and then shifts by this tile's own screen position, so what shows through is the exact
        // same picture content that would be directly behind it.
        int[] tileLoc = new int[2];
        slot.getLocationOnScreen(tileLoc);
        int[] rootLoc = new int[2];
        root.getLocationOnScreen(rootLoc);
        float tileX = tileLoc[0] - rootLoc[0], tileY = tileLoc[1] - rootLoc[1];
        float screenW = root.getWidth(), screenH = root.getHeight();
        float bmpW = bitmap.getWidth(), bmpH = bitmap.getHeight();
        float scale = Math.max(screenW / bmpW, screenH / bmpH);
        float cropX = (bmpW * scale - screenW) / 2f, cropY = (bmpH * scale - screenH) / 2f;
        Matrix matrix = new Matrix();
        // A wallpaper decoded with inScaled (WallpaperPlayer.decode, e.g. a 2560x1440 picture on a 1080p screen) carries
        // the target density, so its drawable's intrinsic size is not its pixel size; MATRIX works in intrinsic
        // units, so scale from there, or the picture shrinks into a corner and no blur shows.
        android.graphics.drawable.BitmapDrawable drawable = new android.graphics.drawable.BitmapDrawable(ctx.getResources(), bitmap);
        float intrinsicW = Math.max(1, drawable.getIntrinsicWidth()), intrinsicH = Math.max(1, drawable.getIntrinsicHeight());
        matrix.setScale(scale * bmpW / intrinsicW, scale * bmpH / intrinsicH);
        matrix.postTranslate(-cropX - tileX, -cropY - tileY);
        glassView.setScaleType(ImageView.ScaleType.MATRIX);
        glassView.setImageMatrix(matrix);
        glassView.setImageDrawable(drawable);
        RenderEffect effect = effect(ctx, width, height, radius, liquid, edgeDp);
        glassView.setRenderEffect(effect);
        glassView.setClipToOutline(true);
        glassView.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
    }

    /** The blur (the dock's "Blur intensity"), a little vibrancy, and with Liquid glass the edge refraction. */
    private static RenderEffect effect(Context ctx, float width, float height, float radius, boolean liquid, float edgeDp) {
        float density = ctx.getResources().getDisplayMetrics().density;
        ColorMatrix vibrancyMatrix = new ColorMatrix();
        vibrancyMatrix.setSaturation(1.1f);
        ColorMatrix whiteLift = new ColorMatrix(new float[]{
                1, 0, 0, 0, 16,
                0, 1, 0, 0, 16,
                0, 0, 1, 0, 16,
                0, 0, 0, 1, 0});
        vibrancyMatrix.postConcat(whiteLift);
        RenderEffect vibrancyEffect = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(vibrancyMatrix));
        float sigma = Config.dockBlur(ctx) * ctx.getResources().getDisplayMetrics().density;   // Launcher settings, 0 to 20 (dp)
        RenderEffect effect = sigma > 0f
                ? RenderEffect.createBlurEffect(sigma, sigma, vibrancyEffect, Shader.TileMode.CLAMP)
                : vibrancyEffect;
        if (liquid && edgeDp > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                RuntimeShader shader = new RuntimeShader(LiquidGlassShader.SOURCE);
                shader.setFloatUniform("rectMin", 0f, 0f);
                shader.setFloatUniform("rectMax", width, height);
                shader.setFloatUniform("cornerRadius", radius);
                shader.setFloatUniform("refractionHeight", edgeDp * ctx.getResources().getDisplayMetrics().density);
                shader.setFloatUniform("refractionAmount", Config.menuShiftDp(ctx) * ctx.getResources().getDisplayMetrics().density);
                effect = RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"), effect);
            } catch (Exception | LinkageError e) {
                android.util.Log.w("GlassLayer", "the liquid-glass shader is not available: " + e);   // blur only
            }
        }
        return effect;
    }

    /** What the screen shows behind a region: a copy of the activity's window pixels (tiles, the dock, the wallpaper),
     *  taken before the menu is shown. done gets null when the copy is not possible; use applyBehind otherwise. */
    interface Done { void run(Bitmap behind); }

    static void captureBehind(android.app.Activity activity, int x, int y, int w, int h, Done done) {
        if (w <= 0 || h <= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { done.run(null); return; }
        try {
            final Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            android.view.PixelCopy.request(activity.getWindow(), new android.graphics.Rect(x, y, x + w, y + h), bitmap,
                    result -> done.run(result == android.view.PixelCopy.SUCCESS ? bitmap : null),
                    new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (RuntimeException e) {   // an empty or off-screen region, no surface yet
            android.util.Log.w("GlassLayer", "cannot copy what is behind the menu: " + e);
            done.run(null);
        }
    }

    /** glassView shows the captured pixels as they are, blurred (and refracted with Liquid glass). */
    static void applyBehind(Context ctx, ImageView glassView, Bitmap behind, float radius, boolean liquid, float edgeDp) {
        if (glassView == null || behind == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        glassView.setScaleType(ImageView.ScaleType.FIT_XY);
        glassView.setImageBitmap(behind);
        glassView.setRenderEffect(effect(ctx, behind.getWidth(), behind.getHeight(), radius, liquid, edgeDp));
        glassView.setClipToOutline(true);
        glassView.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
    }
}
