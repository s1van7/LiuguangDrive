package cn.liuguang.drive;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 自绘模态卡片。
 *
 * 不用 AlertDialog 的三个原因：它的自定义视图会被套进自己的 ScrollView，
 * 长列表会出现嵌套滚动、内容被截断；它没有沉浸标记，弹出时状态栏会重新出现；
 * 它的高度固定，短内容会留下大片空白。
 *
 * 高度是算出来的，不是量出来的：先按内容宽度量一遍正文，得到想要的高度，
 * 超过上限才封顶，并把 ScrollView 的高度固定成"上限减去标题和按钮"。
 * 如果让 ScrollView 自己在不确定高度下测量，它会按内容撑开，于是自己认为自己
 * 不需要滚动，超出的部分就再也滚不到了。
 */
final class Sheet {
    interface Listener { void onAction(int index); }

    private static final int IMMERSIVE = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;

    private Sheet() {}

    static Dialog show(final Context context, Theme theme, String title, View body,
                       String[] actions, final Listener listener) {
        final Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) wm.getDefaultDisplay().getRealMetrics(metrics);
        final int cardWidth = (int) (metrics.widthPixels * 0.76f);
        final int maxHeight = (int) (metrics.heightPixels * 0.9f);

        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setPadding(dp(context, 26), dp(context, 22), dp(context, 26), dp(context, 12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(theme.surface);
        background.setCornerRadius(dp(context, 28));
        background.setStroke(dp(context, 1), Theme.alpha(theme.line, 160));
        card.setBackground(background);

        View header = null;
        if (title != null && !title.isEmpty()) {
            header = Panels.heading(context, theme, title);
            card.addView(header);
        }

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.addView(scroll, scrollParams);

        LinearLayout bar = null;
        if (actions != null && actions.length > 0) {
            bar = new LinearLayout(context);
            bar.setGravity(Gravity.END);
            bar.setPadding(0, dp(context, 8), 0, 0);
            for (int i = 0; i < actions.length; i++) {
                final int index = i;
                TextView button = new TextView(context);
                button.setText(actions[i]);
                button.setTextSize(16);
                button.setTextColor(theme.accent);
                button.setGravity(Gravity.CENTER);
                button.setPadding(dp(context, 18), dp(context, 12), dp(context, 18), dp(context, 12));
                button.setClickable(true);
                button.setFocusable(true);
                GradientDrawable pill = new GradientDrawable();
                pill.setColor(Color.TRANSPARENT);
                pill.setCornerRadius(dp(context, 16));
                button.setBackground(pill);
                button.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        dialog.dismiss();
                        if (listener != null) listener.onAction(index);
                    }
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.leftMargin = dp(context, 4);
                bar.addView(button, params);
            }
            card.addView(bar);
        }

        // 按内容宽度预量正文，算出卡片真正需要的高度
        int inner = cardWidth - card.getPaddingLeft() - card.getPaddingRight();
        int bodyHeight = measure(body, inner);
        int headerHeight = header == null ? 0 : measure(header, inner);
        int barHeight = bar == null ? 0 : measure(bar, inner);
        int padding = card.getPaddingTop() + card.getPaddingBottom();
        int desired = bodyHeight + headerHeight + barHeight + padding;
        int cardHeight = Math.min(desired, maxHeight);
        // 封顶时把滚动区固定成剩余高度，ScrollView 才知道自己该滚
        scrollParams.height = Math.max(0, cardHeight - headerHeight - barHeight - padding);
        scroll.setLayoutParams(scrollParams);

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(0xAA000000);
        root.setPadding(dp(context, 24), dp(context, 18), dp(context, 24), dp(context, 18));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                cardWidth, cardHeight, Gravity.CENTER);
        root.addView(card, cardParams);
        root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });

        dialog.setContentView(root);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.getDecorView().setSystemUiVisibility(IMMERSIVE);
        }
        dialog.show();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.getDecorView().setSystemUiVisibility(IMMERSIVE);
        }
        return dialog;
    }

    private static int measure(View view, int width) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return view.getMeasuredHeight();
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
