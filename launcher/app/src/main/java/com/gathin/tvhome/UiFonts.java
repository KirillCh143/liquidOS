package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;

/**
 * Local patch: the launcher's font is its own setting (Launcher settings, Personalization > Font). Outfit is bundled in the
 * app (res/font); the others are downloaded from GitHub (FontStore) and offered once they are on the TV. The clock and
 * Aerial screensavers keep Outfit whatever is chosen. See {@link UiActivity}, which applies the choice as a theme overlay.
 */
final class UiFonts {
    static final String KEY = "launcher_font";
    static final String OUTFIT = "outfit";

    /** Code and the font's name. */
    static final String[][] LIST = {
            {OUTFIT, "Outfit"},
            {"roboto", "Roboto"},
            {"nunito", "Nunito"},
            {"rubik", "Rubik"},
            {"roboto_slab", "Roboto Slab"},
            {"ubuntu", "Ubuntu"},
    };

    private UiFonts() {}

    static String current(Context context) {
        SharedPreferences p = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        String saved = p.getString(KEY, OUTFIT);
        for (String[] f : LIST) if (f[0].equals(saved) && (OUTFIT.equals(saved) || FontStore.installed(context, saved))) return saved;
        return OUTFIT;
    }

    static void set(Context context, String code) {
        context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit().putString(KEY, code).apply();
    }

    static String nameOf(String code) {
        for (String[] f : LIST) if (f[0].equals(code)) return f[1];
        return code;
    }

    /** Outfit, and the downloaded fonts that are on the TV: the ones the Font setting offers. */
    static String[][] available(Context context) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (String[] f : LIST) if (OUTFIT.equals(f[0]) || FontStore.installed(context, f[0])) out.add(f);
        return out.toArray(new String[0][]);
    }

    /** The overlay style that defines the five uiFont* attributes: always Outfit's. A downloaded font is put on top of it
     *  by FontFactory and UiFont.themed, since a layout can only name a bundled font. */
    static int style(String code) {
        return R.style.UiFont_Outfit;
    }

    /** The font's medium weight, to show its name in its own letters. */
    static Typeface sample(Context context, String code) {
        Typeface face = FontStore.typeface(context, code, 500);
        return face != null ? face : UiFont.get(context, R.string.font_medium);
    }
}
