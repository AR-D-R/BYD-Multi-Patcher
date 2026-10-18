package com.bydspotifymanager.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

// give the user a choice between the built-in file browser and an external file manager -
// the IVI's native file manager only has launch intent support
final class BrowseChoiceDialog {
    static final String METHOD_BUILT_IN = "builtin";
    static final String METHOD_EXTERNAL = "external";

    private static final int CARD_FILL = Color.rgb(32, 32, 32);
    private static final int CARD_STROKE = Color.rgb(53, 53, 53);

    private BrowseChoiceDialog() {}

    static Dialog show(Activity activity, int accent, String subtitle, String lastMethod,
                       Runnable onBuiltIn, Runnable onExternal) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_browse_choice);
        dialog.setCanceledOnTouchOutside(true);

        ((TextView) dialog.findViewById(R.id.txtBrowseChoiceSubtitle)).setText(subtitle);
        dialog.findViewById(R.id.btnBrowseChoiceClose).setOnClickListener(v -> dialog.dismiss());

        boolean externalLast = METHOD_EXTERNAL.equals(lastMethod);
        boolean builtInLast = METHOD_BUILT_IN.equals(lastMethod);

        View builtIn = dialog.findViewById(R.id.optionBuiltIn);
        View external = dialog.findViewById(R.id.optionExternal);
        styleCard(activity, builtIn, accent, !externalLast);
        styleCard(activity, external, accent, externalLast);
        styleIcon(activity, dialog.findViewById(R.id.imgOptionBuiltIn), accent, true);
        styleIcon(activity, dialog.findViewById(R.id.imgOptionExternal), accent, false);

        TextView builtInTag = dialog.findViewById(R.id.tagOptionBuiltIn);
        builtInTag.setText(builtInLast ? "LAST USED" : "RECOMMENDED");
        styleTag(activity, builtInTag, accent, true);
        TextView externalTag = dialog.findViewById(R.id.tagOptionExternal);
        externalTag.setVisibility(externalLast ? View.VISIBLE : View.GONE);
        styleTag(activity, externalTag, accent, false);

        builtIn.setOnClickListener(v -> { dialog.dismiss(); onBuiltIn.run(); });
        external.setOnClickListener(v -> { dialog.dismiss(); onExternal.run(); });

        // Side-by-side cards on the wide head-unit screen, stacked on narrow portrait screens.
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        float widthDp = dm.widthPixels / dm.density;
        LinearLayout options = dialog.findViewById(R.id.browseChoiceOptions);
        if (widthDp >= 640) {
            // Equal-height cards: size both to the taller one once measured.
            options.post(() -> {
                int h = Math.max(builtIn.getHeight(), external.getHeight());
                for (View card : new View[]{builtIn, external}) {
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
                    lp.height = h;
                    card.setLayoutParams(lp);
                }
            });
        } else {
            options.setOrientation(LinearLayout.VERTICAL);
            for (View card : new View[]{builtIn, external}) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
                lp.width = LinearLayout.LayoutParams.MATCH_PARENT;
                lp.height = LinearLayout.LayoutParams.WRAP_CONTENT;
                lp.weight = 0;
                card.setLayoutParams(lp);
            }
        }

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.72f);
            int width = Math.min(dm.widthPixels - dp(activity, 48), dp(activity, 780));
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        return dialog;
    }

    private static void styleCard(Activity activity, View card, int accent, boolean highlighted) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(highlighted ? blend(CARD_FILL, accent, 0.08f) : CARD_FILL);
        bg.setCornerRadius(dp(activity, 14));
        bg.setStroke(dp(activity, highlighted ? 2 : 1), highlighted ? accent : CARD_STROKE);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(activity, 14));
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 60)), bg, mask));
    }

    private static void styleIcon(Activity activity, ImageView icon, int accent, boolean primary) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(primary ? withAlpha(accent, 46) : Color.rgb(48, 48, 48));
        icon.setBackground(circle);
        icon.setImageTintList(ColorStateList.valueOf(primary ? accent : Color.rgb(200, 200, 200)));
    }

    private static void styleTag(Activity activity, TextView tag, int accent, boolean primary) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(activity, 6));
        bg.setColor(primary ? withAlpha(accent, 40) : Color.rgb(52, 52, 52));
        tag.setBackground(bg);
        tag.setTextColor(primary ? accent : Color.rgb(200, 200, 200));
        tag.setLetterSpacing(0.06f);
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
