package com.gathin.tvhome;

import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** Reduced motion (config.xml reduced_motion): the one spring and the one time limit every animation uses then. */
final class Motion {

    /** The longest any reduced-motion animation runs, in milliseconds. */
    static final int REDUCED_MS = 90;
    /** Critically damped (no bounce) and stiff enough that any move, even across the screen, settles in 5 frames. */
    static final float REDUCED_STIFFNESS = 15000f;
    /** The spring stops once it is within this many pixels of its target. */
    static final float REDUCED_MIN_CHANGE = 2f;

    private static float systemScale = -1f;

    private Motion() {
    }

    /**
     * Animations off (Launcher settings > Effect settings) means every animation of the launcher, the apps grid, the
     * menus and the settings included: they run with a zero duration scale, so each ends on its first frame. Back on,
     * the system's own animator scale (developer options) is put back. The setter is a hidden method; where it is
     * not there, the animations the code gates itself (the dock, the tiles) are still off.
     */
    static void apply(android.content.Context context) {
        try {
            if (systemScale < 0f) {
                systemScale = android.provider.Settings.Global.getFloat(context.getContentResolver(),
                        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            }
            java.lang.reflect.Method set = android.animation.ValueAnimator.class.getMethod("setDurationScale", float.class);
            set.invoke(null, Config.animationsOn(context) ? systemScale : 0f);
        } catch (Throwable e) {
            android.util.Log.w("Motion", "cannot set the animator duration scale: " + e);
        }
    }

    static void reduce(SpringAnimation spring) {
        spring.setSpring(new SpringForce().setStiffness(REDUCED_STIFFNESS).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        spring.setMinimumVisibleChange(REDUCED_MIN_CHANGE);
    }
}
