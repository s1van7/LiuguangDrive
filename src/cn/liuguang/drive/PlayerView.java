package cn.liuguang.drive;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 1024×600 逻辑坐标的全屏绘制界面。
 *
 * 视觉上参考手机播放器的沉浸式播放页：整屏是封面放大模糊后的底色，左侧一张
 * 大圆角封面加歌名，右侧歌词纵向居中、偏右排列，底部一条进度与三个圆形按钮。
 * 所有直角都做了圆角处理。
 *
 * 性能取舍：底色、封面、按钮底这些静态内容预渲染到一张 Bitmap，每帧只画歌词与
 * 进度；歌词滚动用缓动插值，只在动画没收敛时按屏幕刷新率重绘。
 */
final class PlayerView extends View {
    interface Host {
        void onUiAction(String id);
        void onUiSeek(float fraction);
        boolean onUiBack();
    }

    static final int W = 1024, H = 600;

    // 左侧封面与歌名
    private static final float ART_X = 56, ART_Y = 96, ART_S = 300;
    private static final float META_X = 58, TITLE_Y = 430, SUB_Y = 456;
    // 底部进度
    private static final float BAR_X = 56, BAR_W = 912, BAR_Y = 482, BAR_H = 5, TIME_Y = 508;
    private static final float PLAY_X = 512, PLAY_Y = 552, PLAY_R = 32;
    // 右侧歌词
    private static final float LYR_L = 496, LYR_R = 984, LYR_CY = 283;

    private final Host host;
    private final PlayerModel model;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    /** 绘制时复用的矩形，避免每帧都 new RectF。 */
    private final RectF scratch = new RectF();
    private final List<Hit> hits = new ArrayList<Hit>();
    private final Map<String, Hit> hitMap = new HashMap<String, Hit>();
    private final List<String> navOrder = new ArrayList<String>();
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm", Locale.ROOT);

    private Theme theme;
    private float scale = 1f, offsetX = 0f, offsetY = 0f;
    private boolean fillScreen;

    private Bitmap chrome;
    private boolean chromeDirty = true;
    private Bitmap artCache;
    private Bitmap artSource;
    private int tint;

    private boolean focusLyrics, idle, touchMode = true, dragging, idleClockEnabled = true;
    private String pressedId = "", focusId = "";
    private float dragFraction;
    private float animLine = -2f;
    private String clockText = "";
    private long clockStamp;
    private int lastSecond = -1, lastLineCount = -1;
    private String lastTitle = "", lastLyricHint = "";

    private final class Hit {
        final String id;
        final RectF rect;
        Hit(String id, float x, float y, float w, float h) {
            this.id = id;
            this.rect = new RectF(x, y, x + w, y + h);
        }
        float cx() { return rect.centerX(); }
        float cy() { return rect.centerY(); }
    }

    PlayerView(Context context, Host host, PlayerModel model, Theme theme) {
        super(context);
        this.host = host;
        this.model = model;
        this.theme = theme;
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setContentDescription("车机蓝牙音乐。左右滑动或按方向盘方向键选择控件，按确认键操作。");
        buildHits();
    }

    // ---------------------------------------------------------------- 对外接口

    void setTheme(Theme next) {
        theme = next;
        chromeDirty = true;
        invalidate();
    }

    void setIdleClockEnabled(boolean enabled) {
        if (idleClockEnabled == enabled) return;
        idleClockEnabled = enabled;
        buildHits();
        invalidate();
    }

    void setFillScreen(boolean fill) {
        if (fillScreen == fill) return;
        fillScreen = fill;
        invalidate();
    }

    void setFocusLyrics(boolean value) {
        if (focusLyrics == value) return;
        focusLyrics = value;
        chromeDirty = true;
        buildHits();
        invalidate();
    }

    boolean isFocusLyrics() { return focusLyrics; }

    /** 返回键：先退出空闲屏，再退出沉浸歌词；都处理了才返回 true。 */
    boolean onUiBack() {
        if (idle) { setIdle(false); return true; }
        if (focusLyrics) { setFocusLyrics(false); return true; }
        return false;
    }

    void setIdle(boolean value) {
        if (idle == value) return;
        idle = value;
        buildHits();
        invalidate();
    }

    boolean isIdle() { return idle; }

    void resetLyricAnimation() {
        animLine = -2f;
        lastTitle = "";
        invalidate();
    }

    void invalidateChrome() {
        chromeDirty = true;
        invalidate();
    }

    void redraw() { invalidate(); }

    /** 定时器每次进来都调；只有真的变了才重绘。 */
    void refresh() {
        int second = (int) (model.positionMs / 1000L);
        int lineCount = model.lyrics == null ? 0 : model.lyrics.size();
        if (second != lastSecond || lineCount != lastLineCount || model.playing
                || !lastTitle.equals(model.title) || !lastLyricHint.equals(model.hint)) {
            lastSecond = second;
            lastLineCount = lineCount;
            lastTitle = model.title;
            lastLyricHint = model.hint;
            invalidate();
        }
        if (Math.abs(targetLine() - animLine) > 0.01f) invalidate();
    }

    // ---------------------------------------------------------------- 命中区

    private void computeScale() {
        if (getWidth() == 0 || getHeight() == 0) return;
        scale = fillScreen
                ? Math.max(getWidth() / (float) W, getHeight() / (float) H)
                : Math.min(getWidth() / (float) W, getHeight() / (float) H);
        offsetX = (getWidth() - W * scale) / 2f;
        offsetY = (getHeight() - H * scale) / 2f;
    }

    private void hit(String id, float x, float y, float w, float h) {
        Hit item = new Hit(id, x, y, w, h);
        hits.add(item);
        hitMap.put(id, item);
    }

    private void buildHits() {
        hits.clear();
        hitMap.clear();
        navOrder.clear();
        if (idle) {
            hit("idle", 0, 0, W, H);
            return;
        }
        hit("transport.prev", 448 - 34, PLAY_Y - 34, 68, 68); navOrder.add("transport.prev");
        hit("transport.play", PLAY_X - 40, PLAY_Y - 40, 80, 80); navOrder.add("transport.play");
        hit("transport.next", 576 - 34, PLAY_Y - 34, 68, 68); navOrder.add("transport.next");
        hit("seek", BAR_X, BAR_Y - 20, BAR_W, 44);
        hit("ui.lyrics", 800, 16, 48, 48); navOrder.add("ui.lyrics");
        hit("ui.settings", 856, 16, 48, 48); navOrder.add("ui.settings");
        if (idleClockEnabled) { hit("ui.idle", 906, 12, 66, 56); navOrder.add("ui.idle"); }
        if (!focusLyrics) { hit("art", ART_X, ART_Y, ART_S, ART_S); navOrder.add("art"); }
    }

    private String hitAt(float x, float y) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            if (hits.get(i).rect.contains(x, y)) return hits.get(i).id;
        }
        return "";
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeScale();
    }

    // ---------------------------------------------------------------- 绘制

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() == 0 || getHeight() == 0) return;
        computeScale();
        if (ensureArt()) chromeDirty = true;
        if (chromeDirty || chrome == null) buildChrome();

        canvas.drawColor(theme.dark ? Color.BLACK : theme.bgBottom);
        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);

        if (idle) {
            drawIdle(canvas);
            canvas.restore();
            return;
        }
        if (chrome != null) canvas.drawBitmap(chrome, 0f, 0f, null);
        updateClock();
        drawTopBar(canvas);
        if (!focusLyrics) drawMeta(canvas);
        drawLyrics(canvas);
        drawTransport(canvas);
        drawFocus(canvas);
        canvas.restore();

        if (Math.abs(targetLine() - animLine) > 0.004f) postInvalidateOnAnimation();
    }

    private void updateClock() {
        long now = System.currentTimeMillis();
        if (now - clockStamp > 20000 || clockText.isEmpty()) {
            clockStamp = now;
            clockText = clockFormat.format(new Date(now));
        }
    }

    /** 静态层：模糊封面底色 + 压暗覆盖 + 圆形按钮底 + 封面。 */
    private void buildChrome() {
        if (chrome != null && !chrome.isRecycled()) chrome.recycle();
        chrome = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(chrome);
        chromeDirty = false;

        // 底色：把封面缩到 18×18 再拉伸回去，得到柔和的色块，比高斯模糊便宜得多
        boolean painted = false;
        if (artCache != null && !artCache.isRecycled()) {
            try {
                Bitmap tiny = Bitmap.createScaledBitmap(artCache, 18, 18, true);
                c.drawBitmap(tiny, null, new RectF(0, 0, W, H), artPaint);
                tiny.recycle();
                painted = true;
            } catch (Exception ignored) {
                painted = false;
            }
        }
        if (!painted) {
            gradient(c, new LinearGradient(0, 0, W * 0.4f, H, theme.bgTop, theme.bgBottom, Shader.TileMode.CLAMP));
        }

        // 覆盖层：把封面压成主题底色，保证文字始终有对比度
        gradient(c, new LinearGradient(0, 0, W * 0.25f, H,
                Theme.alpha(theme.bgTop, theme.veilTop), Theme.alpha(theme.bgBottom, theme.veilBottom),
                Shader.TileMode.CLAMP));

        // 左上角一团柔光，让画面不至于死平
        gradient(c, new RadialGradient(240, 150, 620,
                new int[]{Theme.alpha(0xFFFFFF, theme.dark ? 12 : 46), Theme.alpha(0xFFFFFF, 0)},
                null, Shader.TileMode.CLAMP));

        // 顶部按钮底
        circle(c, 824, 40, 21, Theme.alpha(theme.surface, theme.dark ? 200 : 170));
        circle(c, 880, 40, 21, Theme.alpha(theme.surface, theme.dark ? 200 : 170));

        if (!focusLyrics) drawArt(c);
    }

    private void drawArt(Canvas c) {
        if (artCache == null) return;
        for (int i = 3; i >= 1; i--) {
            round(c, ART_X - i * 2, ART_Y - i * 2 + 10, ART_S + i * 4, ART_S + i * 4,
                    24 + i * 2, Theme.alpha(theme.shadow, 10));
        }
        c.drawBitmap(artCache, ART_X, ART_Y, artPaint);
    }

    private void drawTopBar(Canvas c) {
        fill(c, theme.text);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        paint.setTextSize(21);
        paint.setTextAlign(Paint.Align.LEFT);
        c.drawText("蓝牙音乐", META_X, 46, paint);

        String source = model.sourceLabel.isEmpty() ? "等待手机播放" : model.sourceLabel;
        text(c, source, META_X, 72, 12.5f, theme.sub, false, 420);

        // 来源小圆点，用 App 品牌色，一眼看出是哪个播放器
        if (!model.sourcePkg.isEmpty()) {
            int dot = AppProfile.of(model.sourcePkg).color;
            float w = measure(source, 12.5f, false);
            circle(c, META_X + Math.min(w, 420) + 14, 68, 4, Theme.alpha(dot, 235));
        }

        textRight(c, clockText, 968, 48, 21, Theme.alpha(theme.text, 235), true);

        iconButton(c, "ui.lyrics", 824, 40, 21, focusLyrics);
        iconButton(c, "ui.settings", 880, 40, 21, false);
    }

    /** 顶部圆形图标按钮：id 决定画什么图标。 */
    private void iconButton(Canvas c, String id, float cx, float cy, float r, boolean active) {
        boolean pressed = pressedId.equals(id);
        boolean focused = !touchMode && focusId.equals(id);
        int bg = active ? theme.accent : Theme.alpha(theme.surface, theme.dark ? 200 : 170);
        if (pressed) bg = Theme.blend(bg, theme.text, 0.12f);
        circle(c, cx, cy, pressed ? r - 1 : r, bg);
        int ink = active ? theme.accentInk : theme.sub;
        if ("ui.lyrics".equals(id)) {
            float w = r * 0.62f;
            round(c, cx - w, cy - r * 0.36f, w * 2, 2.6f, 1.3f, ink);
            round(c, cx - w, cy - 1.3f, w * 2, 2.6f, 1.3f, ink);
            round(c, cx - w, cy + r * 0.36f - 2.6f, w * 1.2f, 2.6f, 1.3f, ink);
        } else {
            gearIcon(c, cx, cy, r * 0.56f, ink);
        }
        if (focused) {
            stroke(c, theme.accent, 2f);
            c.drawCircle(cx, cy, r + 4, paint);
        }
    }

    private void gearIcon(Canvas c, float cx, float cy, float r, int color) {
        Path star = new Path();
        for (int i = 0; i < 16; i++) {
            double angle = Math.PI * 2 * i / 16 - Math.PI / 2;
            float radius = (i % 2 == 0) ? r : r * 0.76f;
            float x = cx + (float) Math.cos(angle) * radius;
            float y = cy + (float) Math.sin(angle) * radius;
            if (i == 0) star.moveTo(x, y); else star.lineTo(x, y);
        }
        star.close();
        stroke(c, color, 2f);
        c.drawPath(star, paint);
        c.drawCircle(cx, cy, r * 0.34f, paint);
    }

    /** 左侧歌名、歌手、专辑。 */
    private void drawMeta(Canvas c) {
        String title = model.title == null || model.title.isEmpty() ? "等待手机播放" : model.title;
        int titleColor = model.title == null || model.title.isEmpty() ? theme.dim : theme.text;
        text(c, title, META_X, TITLE_Y, 26, titleColor, true, ART_S + 24);
        text(c, secondaryLine(), META_X, SUB_Y, 13.5f, theme.sub, false, ART_S + 24);
    }

    private String secondaryLine() {
        String artist = model.artist == null ? "" : model.artist.trim();
        String album = model.album == null ? "" : model.album.trim();
        if (artist.isEmpty() && album.isEmpty()) {
            return model.hasSession ? "未提供歌手信息" : "连上车机蓝牙，用手机播放器放歌";
        }
        if (album.isEmpty()) return artist;
        if (artist.isEmpty()) return album;
        return artist + "  ·  " + album;
    }

    // ---------------------------------------------------------------- 歌词

    private float targetLine() {
        List<LrcParser.Line> lines = model.lyrics;
        if (lines == null || lines.isEmpty()) return 0f;
        return Math.max(0, LrcParser.activeIndex(lines, model.positionMs));
    }

    private void drawLyrics(Canvas c) {
        List<LrcParser.Line> lines = model.lyrics;
        if (lines == null || lines.isEmpty()) {
            drawLyricHint(c);
            return;
        }
        // 每帧把滚动位置缓动到当前行；onDraw 末尾会在没收敛时继续请求下一帧
        float target = targetLine();
        if (animLine < -1f) animLine = target;
        else animLine += (target - animLine) * 0.22f;
        if (Math.abs(target - animLine) < 0.008f) animLine = target;

        float left = focusLyrics ? 140 : LYR_L;
        float right = focusLyrics ? 884 : LYR_R;
        float centerY = focusLyrics ? 300 : LYR_CY;
        float lineHeight = focusLyrics ? 74 : 62;
        float big = (focusLyrics ? 33 : 28) * (theme.largeText ? 1.12f : 1f);
        float small = (focusLyrics ? 22 : 19) * (theme.largeText ? 1.12f : 1f);
        float span = 4.2f;

        for (int i = 0; i < lines.size(); i++) {
            float d = i - animLine;
            if (d < -span || d > span) continue;
            float distance = Math.min(1f, Math.abs(d));
            float y = centerY + d * lineHeight;
            float size = big + (small - big) * distance;

            // 上下边界淡出，避免出现被硬切一半的字
            float edge = Math.min(y - 84, (BAR_Y - 26) - y);
            float edgeFade = Math.min(1f, Math.max(0f, edge / 34f));
            if (edgeFade <= 0.02f) continue;
            float fade = 1f - Math.max(0f, Math.abs(d) - 2.2f) / (span - 2.2f);
            float alpha = Math.max(0.06f, Math.min(1f, fade * edgeFade));

            int color;
            if (distance < 0.35f) color = theme.text;
            else if (distance < 1.2f) color = Theme.blend(theme.text, theme.sub, 0.55f + 0.3f * distance);
            else color = theme.dim;
            color = Theme.alpha(color, (int) (255 * alpha));

            if (focusLyrics) {
                textMiddle(c, lines.get(i).text, (left + right) / 2f, y, size, color,
                        distance < 0.35f, right - left);
            } else {
                text(c, lines.get(i).text, left, y, size, color, distance < 0.35f, right - left);
            }
        }
    }

    private void drawLyricHint(Canvas c) {
        float left = focusLyrics ? 140 : LYR_L;
        float right = focusLyrics ? 884 : LYR_R;
        float cy = focusLyrics ? 300 : LYR_CY;
        String head, body;
        if (!model.hasSession) {
            head = "还没有收到手机的音乐";
            body = "在手机上连好车机蓝牙，用汽水音乐、QQ音乐、网易云音乐或咪咕音乐播放";
        } else if (model.playing) {
            head = "正在等待歌词";
            body = "播放器把每一句歌词写进媒体信息后，这里会逐句出现";
        } else {
            head = "已暂停";
            body = "继续播放后，歌词会自动出现";
        }
        text(c, head, left, cy - 10, 21, theme.sub, false, right - left);
        text(c, body, left, cy + 26, 14, theme.dim, false, right - left);
    }

    // ---------------------------------------------------------------- 进度与播放键

    private void drawTransport(Canvas c) {
        long duration = model.duration;
        float fraction = dragging ? dragFraction
                : duration > 0 ? Math.max(0f, Math.min(1f, model.positionMs / (float) duration)) : 0f;
        long shown = dragging ? (long) (duration * dragFraction) : model.positionMs;
        boolean showTime = model.receivedPosition || dragging;

        round(c, BAR_X, BAR_Y, BAR_W, BAR_H, BAR_H / 2f, Theme.alpha(theme.text, theme.dark ? 34 : 40));
        if (fraction > 0) {
            round(c, BAR_X, BAR_Y, Math.max(BAR_H, BAR_W * fraction), BAR_H, BAR_H / 2f, theme.accent);
        }
        if (duration > 0) {
            float x = BAR_X + BAR_W * fraction;
            circle(c, x, BAR_Y + BAR_H / 2f, 11, Theme.alpha(theme.accent, 40));
            circle(c, x, BAR_Y + BAR_H / 2f, 6, theme.accent);
        }
        text(c, showTime ? clock(shown) : "--:--", BAR_X, TIME_Y, 12.5f, theme.sub, false, 120);
        textRight(c, duration > 0 ? clock(duration) : "--:--", BAR_X + BAR_W, TIME_Y, 12.5f, theme.sub, false);

        transportButton(c, "transport.prev", 448, PLAY_Y, 30, 0);
        transportButton(c, "transport.play", PLAY_X, PLAY_Y, PLAY_R, 1);
        transportButton(c, "transport.next", 576, PLAY_Y, 30, 2);
    }

    /** kind: 0 上一首 1 播放/暂停 2 下一首 */
    private void transportButton(Canvas c, String id, float cx, float cy, float r, int kind) {
        boolean pressed = pressedId.equals(id);
        boolean focused = !touchMode && focusId.equals(id);
        float radius = pressed ? r - 1.5f : r;

        if (kind == 1) {
            circle(c, cx, cy, radius + 6, Theme.alpha(theme.accent, 30));
            circle(c, cx, cy, radius, pressed ? Theme.blend(theme.accent, theme.text, 0.2f) : theme.accent);
            int ink = theme.accentInk;
            if (model.playing) {
                round(c, cx - 10, cy - 12, 7, 24, 3.5f, ink);
                round(c, cx + 3, cy - 12, 7, 24, 3.5f, ink);
            } else {
                Path triangle = new Path();
                triangle.moveTo(cx - 7, cy - 13);
                triangle.lineTo(cx + 12, cy);
                triangle.lineTo(cx - 7, cy + 13);
                triangle.close();
                fill(c, ink);
                c.drawPath(triangle, paint);
            }
        } else {
            circle(c, cx, cy, radius, pressed ? theme.surfaceHi : Theme.alpha(theme.surface, theme.dark ? 210 : 190));
            int color = theme.text;
            float dir = kind == 2 ? 1 : -1;
            Path triangle = new Path();
            triangle.moveTo(cx - 8 * dir, cy - 9);
            triangle.lineTo(cx + 6 * dir, cy);
            triangle.lineTo(cx - 8 * dir, cy + 9);
            triangle.close();
            fill(c, color);
            c.drawPath(triangle, paint);
            round(c, kind == 2 ? cx + 7 : cx - 11, cy - 9, 4, 18, 2, color);
        }
        if (focused) {
            stroke(c, theme.accent, 2.2f);
            c.drawCircle(cx, cy, radius + 5, paint);
        }
    }

    private void drawFocus(Canvas c) {
        if (touchMode || focusId.isEmpty()) return;
        Hit item = hitMap.get(focusId);
        if (item == null) return;
        RectF r = new RectF(item.rect);
        r.inset(-4, -4);
        round(c, r.left - 3, r.top - 3, r.width() + 6, r.height() + 6, 26, Theme.alpha(theme.accent, 30));
        stroke(c, theme.accent, 2.2f);
        c.drawRoundRect(r, 24, 24, paint);
    }

    // ---------------------------------------------------------------- 空闲屏

    private void drawIdle(Canvas c) {
        fill(c, theme.dark ? 0xFF000000 : theme.bgTop);
        c.drawRect(0, 0, W, H, paint);
        updateClock();
        textCenter(c, clockText, W / 2f, 312, 132, Theme.alpha(theme.text, 232), false);
        if (!model.title.isEmpty()) {
            textCenter(c, model.title, W / 2f, 382, 23, theme.sub, false);
        }
        textCenter(c, "轻触屏幕继续", W / 2f, 552, 13, Theme.alpha(theme.dim, 200), false);
    }

    // ---------------------------------------------------------------- 交互

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = (event.getX() - offsetX) / scale;
        float y = (event.getY() - offsetY) / scale;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchMode = true;
                if (idle) { setIdle(false); host.onUiAction("wake"); return true; }
                pressedId = hitAt(x, y);
                if ("seek".equals(pressedId)) { dragging = true; dragFraction = fractionAt(x); }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) { dragFraction = fractionAt(x); invalidate(); }
                else if (!pressedId.isEmpty() && !hitAt(x, y).equals(pressedId)) { pressedId = ""; invalidate(); }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    dragging = false;
                    host.onUiSeek(fractionAt(x));
                    pressedId = "";
                    invalidate();
                    return true;
                }
                if (!pressedId.isEmpty() && pressedId.equals(hitAt(x, y))) {
                    String id = pressedId;
                    pressedId = "";
                    activate(id);
                    invalidate();
                    return true;
                }
                pressedId = "";
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressedId = "";
                dragging = false;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private float fractionAt(float x) {
        return Math.max(0f, Math.min(1f, (x - BAR_X) / BAR_W));
    }

    private void activate(String id) {
        switch (id) {
            case "idle": setIdle(false); host.onUiAction("wake"); break;
            case "art": setFocusLyrics(true); host.onUiAction("ui.lyrics"); break;
            case "ui.lyrics": setFocusLyrics(!focusLyrics); host.onUiAction("ui.lyrics"); break;
            case "ui.idle": setIdle(true); host.onUiAction("ui.idle"); break;
            case "seek": break;
            default: host.onUiAction(id); break;
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (idle) {
            if (keyCode == KeyEvent.KEYCODE_BACK) return host.onUiBack();
            setIdle(false);
            host.onUiAction("wake");
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return moveFocus(-1, 0);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return moveFocus(1, 0);
            case KeyEvent.KEYCODE_DPAD_UP: return moveFocus(0, -1);
            case KeyEvent.KEYCODE_DPAD_DOWN: return moveFocus(0, 1);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (touchMode || focusId.isEmpty()) { focusId = "transport.play"; touchMode = false; }
                activate(focusId);
                invalidate();
                return true;
            case KeyEvent.KEYCODE_BACK:
                return onUiBack();
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                host.onUiAction("transport.play");
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                host.onUiAction("transport.next");
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                host.onUiAction("transport.prev");
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private boolean moveFocus(int dx, int dy) {
        touchMode = false;
        if (focusId.isEmpty() || !hitMap.containsKey(focusId)) {
            focusId = "transport.play";
            invalidate();
            return true;
        }
        Hit current = hitMap.get(focusId);
        Hit best = null;
        float bestScore = Float.MAX_VALUE;
        for (String id : navOrder) {
            Hit candidate = hitMap.get(id);
            if (candidate == null || candidate == current) continue;
            float vx = candidate.cx() - current.cx();
            float vy = candidate.cy() - current.cy();
            float along = vx * dx + vy * dy;
            if (along < 8f) continue;
            float perp = Math.abs(vx * dy - vy * dx);
            float score = along + perp * 2.4f;
            if (score < bestScore) { bestScore = score; best = candidate; }
        }
        if (best != null) focusId = best.id;
        invalidate();
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    // ---------------------------------------------------------------- 封面与调色

    private boolean ensureArt() {
        Bitmap source = model.artwork;
        if (source == artSource && artCache != null) return false;
        artSource = source;
        if (artCache != null && !artCache.isRecycled()) artCache.recycle();
        Bitmap base = source != null ? source : placeholder();
        artCache = squareCrop(base, (int) ART_S);
        tint = dominant(base);
        if (base != source) base.recycle();
        return true;
    }

    private Bitmap squareCrop(Bitmap source, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        int side = Math.min(source.getWidth(), source.getHeight());
        Rect src = new Rect((source.getWidth() - side) / 2, (source.getHeight() - side) / 2,
                (source.getWidth() + side) / 2, (source.getHeight() + side) / 2);
        Path clip = new Path();
        clip.addRoundRect(new RectF(0, 0, size, size), 24, 24, Path.Direction.CW);
        c.clipPath(clip);
        c.drawBitmap(source, src, new RectF(0, 0, size, size), artPaint);
        stroke(c, Theme.alpha(0xFFFFFF, 30), 1.2f);
        c.drawRoundRect(new RectF(1, 1, size - 1, size - 1), 24, 24, paint);
        return out;
    }

    /** 没有封面时的占位图：主题色渐变 + 蓝牙标记，不掺任何演示内容。 */
    private Bitmap placeholder() {
        int size = (int) ART_S;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        int top = Theme.blend(theme.surfaceHi, theme.accent, 0.16f);
        int bottom = Theme.blend(theme.surface, theme.bgBottom, 0.45f);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, size * 0.6f, size, top, bottom, Shader.TileMode.CLAMP));
        paint.setColor(0xFFFFFFFF);
        c.drawRect(0, 0, size, size, paint);
        paint.setShader(null);

        float cx = size / 2f, cy = size / 2f - 8;
        int ink = Theme.alpha(theme.accent, 190);
        stroke(c, ink, 3.4f);
        c.drawLine(cx, cy - 46, cx, cy + 46, paint);
        Path up = new Path();
        up.moveTo(cx, cy - 46); up.lineTo(cx + 26, cy - 20); up.lineTo(cx - 26, cy + 20);
        c.drawPath(up, paint);
        Path down = new Path();
        down.moveTo(cx, cy + 46); down.lineTo(cx + 26, cy + 20); down.lineTo(cx - 26, cy - 20);
        c.drawPath(down, paint);

        textCenter(c, "蓝牙音乐", cx, size - 42, 15, Theme.alpha(theme.sub, 200), false);
        return bitmap;
    }

    private int dominant(Bitmap bitmap) {
        long r = 0, g = 0, b = 0;
        int count = 0;
        for (int i = 2; i <= 8; i++) {
            for (int j = 2; j <= 8; j++) {
                int px = bitmap.getPixel(bitmap.getWidth() * i / 10, bitmap.getHeight() * j / 10);
                if (Color.alpha(px) < 40) continue;
                r += Color.red(px); g += Color.green(px); b += Color.blue(px); count++;
            }
        }
        if (count == 0) return theme.accent;
        float[] hsv = new float[3];
        Color.colorToHSV(Color.rgb((int) (r / count), (int) (g / count), (int) (b / count)), hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.2f);
        hsv[2] = Math.max(0.42f, Math.min(1f, hsv[2]));
        return Color.HSVToColor(hsv);
    }

    // ---------------------------------------------------------------- 画笔工具

    /**
     * 画整屏渐变。必须先把画笔颜色重置为不透明白：
     * Paint.setColor 会把颜色里的 alpha 一起写进画笔，而 setShader 不会清掉它，
     * 于是上一帧画淡出歌词时留下的低透明度会乘到渐变上，背景就会发白。
     */
    private void gradient(Canvas c, Shader shader) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(shader);
        paint.setColor(0xFFFFFFFF);
        c.drawRect(0, 0, W, H, paint);
        paint.setShader(null);
    }

    private void fill(Canvas c, int color) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
    }

    private void stroke(Canvas c, int color, float width) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    private void round(Canvas c, float x, float y, float w, float h, float radius, int color) {
        fill(c, color);
        scratch.set(x, y, x + w, y + h);
        c.drawRoundRect(scratch, radius, radius, paint);
    }

    private void circle(Canvas c, float cx, float cy, float radius, int color) {
        fill(c, color);
        c.drawCircle(cx, cy, radius, paint);
    }

    private float measure(String value, float size, boolean bold) {
        paint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        paint.setTextSize(size);
        return paint.measureText(value == null ? "" : value);
    }

    private void text(Canvas c, String value, float x, float baseline, float size, int color,
                      boolean bold, float maxWidth) {
        if (value == null || value.isEmpty()) return;
        fill(c, color);
        paint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.LEFT);
        String out = value;
        if (paint.measureText(out) > maxWidth) out = ellipsize(out, maxWidth);
        c.drawText(out, x, baseline, paint);
    }

    /** 居中且带省略号的文本，用于沉浸歌词。 */
    private void textMiddle(Canvas c, String value, float cx, float baseline, float size, int color,
                            boolean bold, float maxWidth) {
        if (value == null || value.isEmpty()) return;
        fill(c, color);
        paint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.CENTER);
        String out = paint.measureText(value) > maxWidth ? ellipsize(value, maxWidth) : value;
        c.drawText(out, cx, baseline, paint);
    }

    private void textCenter(Canvas c, String value, float cx, float baseline, float size, int color, boolean bold) {
        if (value == null || value.isEmpty()) return;
        fill(c, color);
        paint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(value, cx, baseline, paint);
    }

    private void textRight(Canvas c, String value, float x, float baseline, float size, int color, boolean bold) {
        if (value == null || value.isEmpty()) return;
        fill(c, color);
        paint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.RIGHT);
        c.drawText(value, x, baseline, paint);
    }

    private String ellipsize(String value, float maxWidth) {
        String ellipsis = "…";
        float width = paint.measureText(ellipsis);
        int end = value.length();
        while (end > 0 && paint.measureText(value, 0, end) + width > maxWidth) end--;
        return value.substring(0, Math.max(0, end)) + ellipsis;
    }

    private String clock(long ms) {
        if (ms < 0) ms = 0;
        long total = ms / 1000L;
        long minutes = total / 60, seconds = total % 60;
        // 不用 String.format：Formatter 每帧建一堆对象，这里只是拼个 "m:ss"
        return seconds < 10 ? minutes + ":0" + seconds : minutes + ":" + seconds;
    }
}
