package cn.liuguang.drive;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 用代码搭设置面板 / 歌词库等表单，避免引入 appcompat 或 XML 布局依赖。 */
final class Panels {
    private Panels() {}

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static LinearLayout column(Context context, Theme theme) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(context, 20), dp(context, 18), dp(context, 20), dp(context, 8));
        return root;
    }

    static TextView heading(Context context, Theme theme, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(20);
        view.setTextColor(theme.text);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setPadding(0, 0, 0, dp(context, 10));
        return view;
    }

    static TextView note(Context context, Theme theme, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(theme.sub);
        view.setLineSpacing(dp(context, 3), 1f);
        view.setPadding(dp(context, 4), 0, dp(context, 4), dp(context, 12));
        return view;
    }

    static TextView section(Context context, Theme theme, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(12);
        view.setTextColor(theme.dim);
        view.setPadding(dp(context, 4), dp(context, 10), 0, dp(context, 6));
        return view;
    }

    static LinearLayout row(Context context, Theme theme, String title, String value, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(theme.surface);
        background.setCornerRadius(dp(context, 16));
        background.setStroke(dp(context, 1), Theme.alpha(theme.line, 170));
        row.setBackground(background);
        row.setClickable(true);
        row.setFocusable(true);
        if (click != null) row.setOnClickListener(click);

        TextView label = new TextView(context);
        label.setText(title);
        label.setTextSize(16);
        label.setTextColor(theme.text);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        if (value != null) {
            TextView extra = new TextView(context);
            extra.setText(value);
            extra.setTextSize(14);
            extra.setTextColor(theme.accent);
            extra.setGravity(Gravity.END);
            extra.setPadding(dp(context, 12), 0, 0, 0);
            row.addView(extra);
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(context, 8);
        row.setLayoutParams(params);
        return row;
    }

    static TextView body(Context context, Theme theme, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(15);
        view.setTextColor(theme.sub);
        view.setLineSpacing(dp(context, 4), 1f);
        view.setTextIsSelectable(true);
        return view;
    }
}
