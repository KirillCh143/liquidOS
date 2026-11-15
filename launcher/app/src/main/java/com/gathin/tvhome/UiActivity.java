package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;

/**
 * Local patch: the base of every launcher screen except the clock and Aerial screensavers. It gives the screen the
 * launcher's own language (Lang) and the font the user chose (UiFonts), so layouts and styles can name them as
 * theme attributes (?attr/uiFontMedium, and so on).
 */
abstract class UiActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Motion.apply(this);
        // A downloaded font is put on the views as they are inflated (FontFactory); Outfit needs nothing.
        String font = UiFonts.current(this);
        if (FontStore.downloadable(font)) {
            android.view.LayoutInflater inflater = getLayoutInflater();
            if (inflater.getFactory2() == null) inflater.setFactory2(new FontFactory(inflater, this, font));
        }
    }

    /** Animations off: a screen of the launcher opens and closes with no window animation either. */
    @Override
    public void startActivityForResult(android.content.Intent intent, int requestCode, Bundle options) {
        super.startActivityForResult(intent, requestCode, options);
        if (!Config.animationsOn(this)) overridePendingTransition(0, 0);
    }

    @Override
    public void finish() {
        super.finish();
        if (!Config.animationsOn(this)) overridePendingTransition(0, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Motion.apply(this);   // another app, or the system, may have put the animator scale back
    }

    @Override
    protected void onApplyThemeResource(Resources.Theme theme, int resid, boolean first) {
        super.onApplyThemeResource(theme, resid, first);
        theme.applyStyle(UiFonts.style(UiFonts.current(getBaseContext())), true);
    }
}
