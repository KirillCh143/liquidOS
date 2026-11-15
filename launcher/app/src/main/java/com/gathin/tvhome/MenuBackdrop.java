package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.PixelCopy;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Local patch: while the tile menu is open everything is blurred except the tile it belongs to (with its shadow and glow)
 * and the menu itself. The window's pixels are copied once; a blurred copy covers the screen, and the tile's own area of
 * the same copy is laid back over it sharp, its edge softened so no rectangle shows. The menu's glass uses the same copy.
 * Needs Android 12 for the blur; otherwise nothing is covered.
 */
final class MenuBackdrop {
    interface Ready { void run(Bitmap behindMenu, MenuBackdrop backdrop); }

    private final FrameLayout parent;
    private final Bitmap screen, patch;
    private final ImageView blurred, sharp;
    private final int patchX, patchY;
    private boolean shown;

    private MenuBackdrop(Context context, FrameLayout parent, Bitmap screen, RectF tile, float tileRadius) {
        this.parent = parent;
        this.screen = screen;
        float density = context.getResources().getDisplayMetrics().density;
        // The sharp patch: the tile's rectangle (a few dp over, for the anti-aliased edge), in screen coordinates.
        int pad = Math.round(4 * density);
        Rect src = new Rect(Math.max(0, Math.round(tile.left) - pad), Math.max(0, Math.round(tile.top) - pad),
                Math.min(screen.getWidth(), Math.round(tile.right) + pad), Math.min(screen.getHeight(), Math.round(tile.bottom) + pad));
        patchX = src.left;
        patchY = src.top;
        patch = Bitmap.createBitmap(Math.max(1, src.width()), Math.max(1, src.height()), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(patch);
        canvas.drawBitmap(screen, src, new Rect(0, 0, patch.getWidth(), patch.getHeight()), null);
        // Only the tile itself stays sharp, cut along its own rounded outline (anti-aliased): no ring of sharp wallpaper, shadow
        // or glow around it. The outline is drawn into a mask bitmap and laid over the patch with DST_IN, which clears
        // everything outside the tile.
        Bitmap maskBitmap = Bitmap.createBitmap(patch.getWidth(), patch.getHeight(), Bitmap.Config.ARGB_8888);
        Paint shape = new Paint(Paint.ANTI_ALIAS_FLAG);
        shape.setColor(Color.BLACK);
        new Canvas(maskBitmap).drawRoundRect(new RectF(tile.left - patchX, tile.top - patchY, tile.right - patchX, tile.bottom - patchY),
                tileRadius, tileRadius, shape);
        Paint apply = new Paint();
        apply.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        canvas.drawBitmap(maskBitmap, 0, 0, apply);
        maskBitmap.recycle();

        blurred = new ImageView(context);
        blurred.setImageBitmap(screen);
        blurred.setScaleType(ImageView.ScaleType.FIT_XY);
        // The same blur as the dock and the menu's glass: Launcher settings, "Blur intensity".
        float sigma = Config.dockBlur(context) * density;
        if (sigma > 0f) blurred.setRenderEffect(RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.CLAMP));
        sharp = new ImageView(context);
        sharp.setImageBitmap(patch);
        sharp.setScaleType(ImageView.ScaleType.FIT_XY);
        for (View v : new View[]{blurred, sharp}) {
            v.setFocusable(false);
            v.setClickable(false);
            v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            v.setAlpha(0f);
        }
    }

    /** Copies the window and prepares the covers. ready gets the pixels behind the menu's rectangle (for its glass) and the
     *  backdrop, or nulls when the copy or the blur is not possible. */
    static void prepare(Activity activity, FrameLayout parent, View card, int x, int y, int w, int h, float tileRadius, Ready ready) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) { ready.run(null, null); return; }
        View decor = activity.getWindow().getDecorView();
        int sw = decor.getWidth(), sh = decor.getHeight();
        if (sw <= 0 || sh <= 0) { ready.run(null, null); return; }
        try {
            final Bitmap screen = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            PixelCopy.request(activity.getWindow(), screen, result -> {
                if (result != PixelCopy.SUCCESS || activity.isFinishing() || activity.isDestroyed()) {
                    ready.run(null, null);
                    return;
                }
                int[] at = new int[2];
                card.getLocationInWindow(at);
                RectF tile = new RectF(at[0], at[1], at[0] + card.getWidth() * card.getScaleX(), at[1] + card.getHeight() * card.getScaleY());
                int cx = Math.max(0, Math.min(x, sw - 1)), cy = Math.max(0, Math.min(y, sh - 1));
                Bitmap behind = Bitmap.createBitmap(screen, cx, cy, Math.max(1, Math.min(w, sw - cx)), Math.max(1, Math.min(h, sh - cy)));
                // The tile's own corner radius, grown with its focus zoom (the callers' tileRadius is the unscaled one).
                float radius = tileRadius * card.getScaleX();
                ready.run(behind, new MenuBackdrop(activity, parent, screen, tile, radius));
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (RuntimeException e) {
            android.util.Log.w("MenuBackdrop", "cannot copy the window: " + e);
            ready.run(null, null);
        }
    }

    /** Puts the covers over the screen, fading in. */
    void show(boolean animate) {
        if (shown) return;
        shown = true;
        int[] origin = new int[2];
        parent.getLocationInWindow(origin);
        FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(screen.getWidth(), screen.getHeight());
        full.leftMargin = -origin[0];
        full.topMargin = -origin[1];
        parent.addView(blurred, full);
        FrameLayout.LayoutParams part = new FrameLayout.LayoutParams(patch.getWidth(), patch.getHeight());
        part.leftMargin = patchX - origin[0];
        part.topMargin = patchY - origin[1];
        parent.addView(sharp, part);
        for (View v : new View[]{blurred, sharp}) {
            if (animate) v.animate().alpha(1f).setDuration(200).start(); else v.setAlpha(1f);
        }
    }

    /** Takes the covers off, fading out. */
    void dismiss() {
        if (!shown) return;
        shown = false;
        for (final View v : new View[]{blurred, sharp}) {
            v.animate().alpha(0f).setDuration(120).withEndAction(() -> parent.removeView(v)).start();
        }
    }
}
