package com.nepalanurag.lgremote;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

/** Tiny styling helpers so the whole UI is built without XML layout files. */
public final class Ui {
    private Ui() {
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static int color(Context context, int res) {
        return context.getColor(res);
    }

    public static GradientDrawable round(Context context, int fill, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static GradientDrawable round(Context context, int fill, float radiusDp,
                                         int strokeColor, float strokeDp) {
        GradientDrawable drawable = round(context, fill, radiusDp);
        drawable.setStroke(dp(context, strokeDp), strokeColor);
        return drawable;
    }

    public static Drawable pressable(Context context, int fill, float radiusDp,
                                     int strokeColor, float strokeDp) {
        GradientDrawable content = round(context, fill, radiusDp, strokeColor, strokeDp);
        return new RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.ripple)),
                content, null);
    }

    public static Drawable pressable(Context context, int fill, float radiusDp) {
        GradientDrawable content = round(context, fill, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.ripple)),
                content, null);
    }

    public static Drawable circle(Context context, int fill, int strokeColor) {
        GradientDrawable content = new GradientDrawable();
        content.setShape(GradientDrawable.OVAL);
        content.setColor(fill);
        content.setStroke(dp(context, 1), strokeColor);
        return new RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.ripple)),
                content, null);
    }

    public static TextView text(Context context, CharSequence value, float sp, int color,
                                int typefaceStyle) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", typefaceStyle));
        view.setIncludeFontPadding(false);
        view.setLineSpacing(0f, 1.12f);
        return view;
    }

    public static TextView label(Context context, CharSequence value) {
        TextView view = text(context, value, 10f, context.getColor(R.color.muted),
                Typeface.BOLD);
        view.setLetterSpacing(0.14f);
        view.setAllCaps(true);
        return view;
    }

    public static ImageView icon(Context context, int drawable, int tint, int sizeDp) {
        ImageView view = new ImageView(context);
        view.setImageResource(drawable);
        view.setColorFilter(tint);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp)));
        return view;
    }

    public static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    public static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public static Space spacer(Context context, int heightDp) {
        Space space = new Space(context);
        space.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp)));
        return space;
    }

    public static void margins(View view, int left, int top, int right, int bottom) {
        ViewGroup.LayoutParams raw = view.getLayoutParams();
        if (raw instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) raw;
            params.setMargins(dp(view.getContext(), left), dp(view.getContext(), top),
                    dp(view.getContext(), right), dp(view.getContext(), bottom));
            view.setLayoutParams(params);
        }
    }

    public static LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    public static LinearLayout.LayoutParams dpParams(Context context, int widthDp, int heightDp) {
        return new LinearLayout.LayoutParams(dp(context, widthDp), dp(context, heightDp));
    }

    public static LinearLayout.LayoutParams weight(Context context, float weight, int heightDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, heightDp == ViewGroup.LayoutParams.WRAP_CONTENT
                ? ViewGroup.LayoutParams.WRAP_CONTENT : dp(context, heightDp));
        params.weight = weight;
        return params;
    }
}
