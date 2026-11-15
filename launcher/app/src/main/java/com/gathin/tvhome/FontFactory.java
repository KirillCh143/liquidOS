package com.gathin.tvhome;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

/**
 * Local patch: a downloaded font (FontStore) cannot be named in a layout (android:fontFamily takes only bundled
 * fonts), so every text view a screen inflates gets it here, in the weight its layout asked for (the Outfit file
 * it was given: extra light, light, regular, medium; bold when textStyle is bold).
 */
final class FontFactory implements LayoutInflater.Factory2 {
    private static final String[] PREFIXES = {"android.widget.", "android.view.", "android.webkit."};
    private final LayoutInflater inflater;
    private final Context context;
    private final String code;

    FontFactory(LayoutInflater inflater, Context context, String code) {
        this.inflater = inflater;
        this.context = context;
        this.code = code;
    }

    @Override
    public View onCreateView(View parent, String name, Context ctx, AttributeSet attrs) {
        View view = null;
        try {
            if (name.indexOf('.') >= 0) {
                view = inflater.createView(name, null, attrs);
            } else {
                for (String prefix : PREFIXES) {
                    try {
                        view = inflater.createView(name, prefix, attrs);
                        break;
                    } catch (ClassNotFoundException next) {
                        // try the next package
                    }
                }
            }
        } catch (Exception e) {
            return null;   // the inflater makes the view itself, in the stock font
        }
        if (view instanceof TextView) restyle((TextView) view, ctx, attrs);
        return view;
    }

    @Override
    public View onCreateView(String name, Context ctx, AttributeSet attrs) {
        return onCreateView(null, name, ctx, attrs);
    }

    private void restyle(TextView text, Context ctx, AttributeSet attrs) {
        // textStyle comes before fontFamily: the attribute ids must be in ascending order.
        TypedArray a = ctx.obtainStyledAttributes(attrs, new int[]{android.R.attr.textStyle, android.R.attr.fontFamily});
        apply(text, context, code, a);
    }

    /** For a text view built in code from a style (new TextView(new ContextThemeWrapper(ctx, style), null, 0)). */
    static void applyStyle(Context ctx, TextView text, int styleRes) {
        String font = UiFonts.current(ctx);
        if (!FontStore.downloadable(font)) return;
        apply(text, ctx, font, ctx.obtainStyledAttributes(styleRes, new int[]{android.R.attr.textStyle, android.R.attr.fontFamily}));
    }

    /** Puts the font on the view for the weight this array names (textStyle, fontFamily); recycles the array. */
    private static void apply(TextView text, Context context, String code, TypedArray a) {
        try {
            int style = a.getInt(0, 0);
            TypedValue value = new TypedValue();
            if (!a.getValue(1, value) || value.type != TypedValue.TYPE_STRING) return;
            int weight = weightOf(context, String.valueOf(value.string));
            if (weight == 0) return;
            if ((style & Typeface.BOLD) != 0) weight = 700;
            Typeface face = FontStore.typeface(context, code, weight);
            if (face != null) text.setTypeface(face);
        } finally {
            a.recycle();
        }
    }

    private static final java.util.Map<String, Integer> WEIGHTS = new java.util.HashMap<>();

    /** The weight behind the path of a font a layout names: the font_* strings tell which path is which, whatever the
     *  release build shortened the paths to (res/bt.xml); 0 for a path that is none of them. */
    static synchronized int weightOf(Context context, String path) {
        if (WEIGHTS.isEmpty()) {
            put(context, R.string.font_extralight, 200);
            put(context, R.string.font_light, 300);
            put(context, R.string.font_regular, 400);
            put(context, R.string.font_body, 400);
            put(context, R.string.font_medium, 500);
        }
        Integer weight = WEIGHTS.get(path);
        return weight == null ? 0 : weight;
    }

    private static void put(Context context, int fontString, int weight) {
        TypedValue value = new TypedValue();
        context.getResources().getValue(fontString, value, true);
        if (value.string != null) WEIGHTS.put(value.string.toString(), weight);
    }
}
