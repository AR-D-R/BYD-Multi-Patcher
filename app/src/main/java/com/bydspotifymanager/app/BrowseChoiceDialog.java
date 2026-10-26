package com.bydspotifymanager.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Choice dialog contributed in PR #7: built-in browser or a third-party file manager. */
final class BrowseChoiceDialog {
    static final String METHOD_BUILT_IN = "builtin";
    static final String METHOD_EXTERNAL = "external";

    private BrowseChoiceDialog() {}

    static Dialog show(Activity activity, int accent, String subtitle, String lastMethod,
                       Runnable onBuiltIn, Runnable onExternal) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 22), dp(activity, 20), dp(activity, 22), dp(activity, 20));
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(Color.rgb(27, 27, 27));
        rootBg.setCornerRadius(dp(activity, 16));
        rootBg.setStroke(dp(activity, 1), Color.rgb(48, 48, 48));
        root.setBackground(rootBg);

        TextView title = new TextView(activity);
        title.setText("BROWSE SOURCE FILE");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title, matchWrap());

        TextView hint = new TextView(activity);
        hint.setText(subtitle);
        hint.setTextColor(Color.rgb(185, 185, 185));
        hint.setTextSize(14);
        LinearLayout.LayoutParams hintLp = matchWrap();
        hintLp.topMargin = dp(activity, 5);
        root.addView(hint, hintLp);

        LinearLayout options = new LinearLayout(activity);
        options.setOrientation(LinearLayout.HORIZONTAL);
        // Horizontal LinearLayout baseline alignment can vertically offset TextViews
        // when one option wraps onto more lines than the other. Keep both cards
        // pinned to the same top edge instead.
        options.setBaselineAligned(false);
        options.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams optionsLp = matchWrap();
        optionsLp.topMargin = dp(activity, 18);
        root.addView(options, optionsLp);

        boolean externalLast = METHOD_EXTERNAL.equals(lastMethod);
        boolean builtInLast = METHOD_BUILT_IN.equals(lastMethod);

        TextView builtIn = option(activity,
                "BUILT-IN BROWSER",
                builtInLast ? "Last used · Recommended for DiLink" : "Recommended · Works without a compatible system picker",
                accent, !externalLast);
        TextView external = option(activity,
                "OTHER FILE MANAGER",
                externalLast ? "Last used · CX File Explorer and similar apps" : "Uses an installed file manager such as CX File Explorer",
                accent, externalLast);

        LinearLayout.LayoutParams cardLp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cardLp1.setMarginEnd(dp(activity, 7));
        LinearLayout.LayoutParams cardLp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cardLp2.setMarginStart(dp(activity, 7));
        options.addView(builtIn, cardLp1);
        options.addView(external, cardLp2);

        // Once measured, make the two side-by-side cards exactly the same height.
        // This keeps the bottom edges aligned even if one subtitle wraps to an
        // additional line on a particular display/density.
        options.post(() -> {
            if (options.getOrientation() != LinearLayout.HORIZONTAL) return;
            int equalHeight = Math.max(builtIn.getMeasuredHeight(), external.getMeasuredHeight());
            if (equalHeight <= 0) return;

            LinearLayout.LayoutParams builtInLp = (LinearLayout.LayoutParams) builtIn.getLayoutParams();
            LinearLayout.LayoutParams externalLp = (LinearLayout.LayoutParams) external.getLayoutParams();
            if (builtInLp.height != equalHeight || externalLp.height != equalHeight) {
                builtInLp.height = equalHeight;
                externalLp.height = equalHeight;
                builtIn.setLayoutParams(builtInLp);
                external.setLayoutParams(externalLp);
            }
        });

        builtIn.setOnClickListener(v -> { dialog.dismiss(); onBuiltIn.run(); });
        external.setOnClickListener(v -> { dialog.dismiss(); onExternal.run(); });

        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();

        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        if (dm.widthPixels / dm.density < 640f) {
            options.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams stacked1 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            stacked1.bottomMargin = dp(activity, 10);
            builtIn.setLayoutParams(stacked1);
            external.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.72f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            int width = Math.min(dm.widthPixels - dp(activity, 48), dp(activity, 820));
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    private static TextView option(Activity activity, String title, String subtitle, int accent, boolean highlighted) {
        TextView v = new TextView(activity);
        v.setText(title + "\n" + subtitle);
        v.setTextColor(Color.WHITE);
        v.setTextSize(15);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(dp(activity, 18), dp(activity, 18), dp(activity, 18), dp(activity, 18));
        v.setMinHeight(dp(activity, 92));
        v.setLineSpacing(0, 1.08f);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(highlighted ? blend(Color.rgb(32, 32, 32), accent, 0.08f) : Color.rgb(32, 32, 32));
        bg.setCornerRadius(dp(activity, 14));
        bg.setStroke(dp(activity, highlighted ? 2 : 1), highlighted ? accent : Color.rgb(53, 53, 53));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(activity, 14));
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 60)), bg, mask));
        return v;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    static int withAlpha(int rgb, int alpha) {
        return Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb));
    }

    static int blend(int base, int over, float amount) {
        return Color.rgb(
                Math.round(Color.red(base) + (Color.red(over) - Color.red(base)) * amount),
                Math.round(Color.green(base) + (Color.green(over) - Color.green(base)) * amount),
                Math.round(Color.blue(base) + (Color.blue(over) - Color.blue(base)) * amount));
    }

    static int dp(Activity activity, float dp) {
        return Math.round(dp * activity.getResources().getDisplayMetrics().density);
    }
}
