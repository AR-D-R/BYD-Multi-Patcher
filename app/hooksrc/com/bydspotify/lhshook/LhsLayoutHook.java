package com.bydspotify.lhshook;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

/**
 * Runtime large-screen LHS transform validated against Spotify 9.1.82.2161.
 *
 * Spotify's ConstraintLayout graph and its collapsed/expanded state machine stay
 * completely stock. Instead of translating individual surfaces after each stock
 * animation frame, this hook mirrors the MainLayout coordinate system itself and
 * immediately mirrors each direct child back around its own centre.
 *
 * Result:
 *   - each direct child's RECTANGLE is mirrored left <-> right;
 *   - each child's LOCAL CONTENT remains LTR and visually unmirrored;
 *   - Spotify's own motion/resize animation runs inside the mirrored coordinate
 *     system, so there is no post-animation right-side hop or delayed relocation;
 *   - full-width children remain full-width automatically.
 *
 * The transform is enforced from an OnPreDrawListener, i.e. before the first
 * visible frame and before every subsequent animation frame. This removes the
 * brief stock-RHS flash that the earlier 50 ms translation poll could expose.
 */
public final class LhsLayoutHook extends FrameLayout implements ViewTreeObserver.OnPreDrawListener {
    // Spotify 9.1.82.2161 resource ID. 9.1.78 used 0x7f0b04a6; the ID moved
    // to 0x7f0b0498 in 9.1.82 even though the layout role/name is unchanged.
    private static final int ID_DISPLAY_CUTOUT_START = 0x7f0b0498;

    private ViewGroup mirroredRoot;
    private boolean active;

    public LhsLayoutHook(Context context) {
        super(context);
    }

    public LhsLayoutHook(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public LhsLayoutHook(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public LhsLayoutHook(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getId() != ID_DISPLAY_CUTOUT_START) return;

        ViewParent parent = getParent();
        if (!(parent instanceof ViewGroup)) return;

        mirroredRoot = (ViewGroup) parent;
        active = true;
        getViewTreeObserver().addOnPreDrawListener(this);

        // Apply as early as possible. The pre-draw callback below is still the
        // authoritative pass because sizes/pivots may not be final at attach time.
        applyMirrorTransform(mirroredRoot);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (getId() == ID_DISPLAY_CUTOUT_START) {
            active = false;
            ViewTreeObserver vto = getViewTreeObserver();
            if (vto.isAlive()) {
                vto.removeOnPreDrawListener(this);
            }
            mirroredRoot = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onPreDraw() {
        if (active && mirroredRoot != null) {
            applyMirrorTransform(mirroredRoot);
        }
        return true;
    }

    private static void applyMirrorTransform(ViewGroup root) {
        final int rootWidth = root.getWidth();
        if (rootWidth <= 0) return;

        // Mirror the root about the exact screen centre. ConstraintLayout still
        // computes every child in the original stock RHS coordinate system.
        root.setPivotX(rootWidth * 0.5f);
        if (root.getScaleX() != -1.0f) {
            root.setScaleX(-1.0f);
        }

        // Counter-mirror every direct child around its own centre. Parent mirror
        // moves the child's rectangle to the opposite side; this child mirror
        // restores its internal artwork/text/buttons and touch coordinates to LTR.
        // Re-running before each draw also catches any dynamically-added child.
        final int count = root.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = root.getChildAt(i);
            child.setPivotX(child.getWidth() * 0.5f);
            if (child.getScaleX() != -1.0f) {
                child.setScaleX(-1.0f);
            }
        }
    }
}
