package cn.liuguang.drive;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 唯一界面 + 全部业务逻辑。
 *
 * 数据只有一条来源：手机上那个正在放歌的播放器暴露的 MediaSession。歌曲、歌手、
 * 专辑、封面、时长、进度、播放状态都从 MediaController 读；歌词从媒体元数据和
 * 媒体通知里逐句采集，拼成时间轴（见 LyricStream）。没有本地曲库、没有演示数据、
 * 不联网、不导入文件。
 */
public class MainActivity extends Activity implements PlayerView.Host, MediaAccessService.Sink {
    private static final String PREFS = "liuguang";

    /** 播放中的刷新间隔：进度条与歌词要跟手。 */
    private static final long TICK_PLAYING_MS = 250L;
    /** 暂停 / 无会话时的刷新间隔：只为等新状态，没必要那么勤。 */
    private static final long TICK_IDLE_MS = 1000L;

    private static final int IMMERSIVE = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;

    private SharedPreferences prefs;
    private Theme theme;
    private PlayerModel model;
    private PlayerView view;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LyricStream lyricStream;
    private LyricCache lyricCache;
    private BluetoothStatus bluetooth;

    private MediaController controller;
    private MediaSessionManager sessionManager;
    private boolean sessionsListening;
    private String boundPkg = "";
    private MediaSession.Token boundToken;
    private MediaMetadata metadata;
    private boolean embeddedApplied, cacheChecked;
    private int lyricVersion = -1;
    private boolean resumed;
    private boolean introShown;

    private int themeId, dimLevel;
    private boolean largeText, fillScreen, idleClock, autoIdle;
    private long lastInteraction, lastTick, lastCacheSave;

    private Dialog currentDialog;
    private boolean accessGranted;

    // ------------------------------------------------------------------ 生命周期

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLog.install(this);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        themeId = prefs.getInt("theme", Theme.MODERN);
        dimLevel = prefs.getInt("dim", 0);
        largeText = prefs.getBoolean("large", false);
        fillScreen = prefs.getBoolean("fill", false);
        idleClock = prefs.getBoolean("idle", true);
        autoIdle = prefs.getBoolean("autoIdle", false);
        theme = Theme.of(themeId, largeText);

        model = new PlayerModel();
        lyricStream = new LyricStream();
        lyricCache = new LyricCache(getFilesDir());

        view = new PlayerView(this, this, model, theme);
        view.setFillScreen(fillScreen);
        view.setIdleClockEnabled(idleClock);
        setContentView(view);

        bluetooth = new BluetoothStatus(this, new BluetoothStatus.Listener() {
            @Override public void onBluetoothChanged(String text, int connected, boolean enabled) {
                model.status = text;
                if (!model.hasSession) {
                    model.sourceLabel = connected > 0 ? "蓝牙已连接 · 等待手机播放" : "蓝牙未连接";
                }
                view.redraw();
            }
        });
        bluetooth.start();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        lastInteraction = lastTick = SystemClock.elapsedRealtime();
        applySystemBars();
        MediaAccessService.setSink(this);
        registerReceiver(changeReceiver, new IntentFilter(MediaAccessService.CHANGED));
        startSessionWatch();
        bindSession();
        handler.removeCallbacks(tick);
        handler.post(tick);
        if (!hasAccess() && !introShown) {
            introShown = true;
            handler.postDelayed(new Runnable() {
                @Override public void run() { introPanel(); }
            }, 400);
        }
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        stopSessionWatch();
        MediaAccessService.setSink(null);
        try { unregisterReceiver(changeReceiver); } catch (Exception ignored) { }
        handler.removeCallbacks(tick);
        saveLyricCache();
    }

    @Override protected void onDestroy() {
        detach();
        if (bluetooth != null) bluetooth.stop();
        dismissDialog();
        handler.removeCallbacksAndMessages(null);
        if (view != null) view.releaseCaches();
        if (model != null) model.artwork = null;
        metadata = null;
        super.onDestroy();
    }

    /** 系统内存告急或界面退到后台时，释放可重建的封面与整屏缓存。 */
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && view != null) {
            view.releaseCaches();
        }
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        if (view != null) view.releaseCaches();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applySystemBars();
    }

    private void applySystemBars() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        int flags = IMMERSIVE;
        if (!theme.dark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
        // 万一某台车机不认沉浸标记，状态栏也应该是主题色而不是一条黑边
        window.setStatusBarColor(theme.dark ? 0xFF000000 : theme.bgTop);
        window.setNavigationBarColor(theme.dark ? 0xFF000000 : theme.bgTop);

        WindowManager.LayoutParams params = window.getAttributes();
        float[] levels = {1f, 0.72f, 0.46f, 0.26f};
        params.screenBrightness = levels[Math.max(0, Math.min(levels.length - 1, dimLevel))];
        window.setAttributes(params);
    }

    private final BroadcastReceiver changeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { bindSession(); }
    };

    // ------------------------------------------------------------------ 媒体会话

    @Override public void onMediaChanged() {
        handler.post(new Runnable() {
            @Override public void run() {
                if (resumed) bindSession();
            }
        });
    }

    private void bindSession() {
        MediaAccessService.Snapshot snapshot = MediaAccessService.latest();
        if (snapshot == null) {
            trySystemSessions();
            return;
        }
        if (snapshot.pkg.equals(boundPkg) && controller != null) {
            refreshState();
            return;
        }
        // 新来的通知属于另一个 App。如果当前这个还在放歌，不直接切过去——
        // 手机里同时挂着两三个播放器很常见，得让统一的策略判断谁更该显示。
        if (controller != null && model.playing && trySystemSessions()) return;
        if (snapshot.token != null) attach(snapshot.token, snapshot.pkg);
        else trySystemSessions();
    }

    private ComponentName listenerComponent() {
        return new ComponentName(this, MediaAccessService.class);
    }

    /**
     * 监听系统活跃会话列表的变化。
     *
     * 只靠通知回调不够：手机从一个播放器切到另一个时，通知不一定重发，
     * 界面就会停在旧的那个上。系统这个监听能立刻拿到新列表。
     */
    private void startSessionWatch() {
        if (sessionsListening || !hasAccess()) return;
        try {
            if (sessionManager == null) {
                sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            }
            if (sessionManager == null) return;
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent(), handler);
            sessionsListening = true;
        } catch (Exception ignored) {
            sessionsListening = false;
        }
    }

    private void stopSessionWatch() {
        if (!sessionsListening || sessionManager == null) { sessionsListening = false; return; }
        try { sessionManager.removeOnActiveSessionsChangedListener(sessionsListener); } catch (Exception ignored) { }
        sessionsListening = false;
    }

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override public void onActiveSessionsChanged(List<MediaController> controllers) {
                    if (!resumed) return;
                    trySystemSessions();
                }
            };

    /** 通知里没有 MediaSession 的机型，退回公开的会话列表。返回是否绑定到了东西。 */
    private boolean trySystemSessions() {
        if (!hasAccess()) { clearSession(); return false; }
        try {
            if (sessionManager == null) {
                sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            }
            if (sessionManager == null) { clearSession(); return false; }

            List<MediaController> list = sessionManager.getActiveSessions(listenerComponent());
            if (list == null || list.isEmpty()) { clearSession(); return false; }

            int count = list.size();
            int[] states = new int[count];
            long[] updatedAt = new long[count];
            int current = SessionPick.NONE;
            for (int i = 0; i < count; i++) {
                MediaController item = list.get(i);
                PlaybackState state = item.getPlaybackState();
                states[i] = state == null ? PlaybackState.STATE_NONE : state.getState();
                updatedAt[i] = state == null ? 0L : state.getLastPositionUpdateTime();
                if (boundToken != null && boundToken.equals(item.getSessionToken())) current = i;
            }

            int index = SessionPick.choose(states, updatedAt, current);
            if (index == SessionPick.NONE) { clearSession(); return false; }
            MediaController chosen = list.get(index);
            attach(chosen.getSessionToken(), chosen.getPackageName());
            return controller != null;
        } catch (Exception e) {
            // 没有通知使用权时 getActiveSessions 会直接抛异常，界面回到等待状态
            clearSession();
            return false;
        }
    }

    private void attach(MediaSession.Token token, String pkg) {
        if (token == null) { clearSession(); return; }
        if (controller != null && pkg != null && pkg.equals(boundPkg) && token.equals(boundToken)) {
            refreshState();
            return;
        }
        detach();
        try {
            controller = new MediaController(this, token);
            controller.registerCallback(callback, handler);
            boundPkg = pkg == null ? "" : pkg;
            boundToken = token;
            refreshState();
        } catch (Exception e) {
            controller = null;
            clearSession();
        }
    }

    private void detach() {
        if (controller != null) {
            try { controller.unregisterCallback(callback); } catch (Exception ignored) { }
            controller = null;
        }
        boundToken = null;
    }

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata md) { refreshState(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { refreshState(); }
        @Override public void onSessionDestroyed() {
            saveLyricCache();
            detach();
            boundPkg = "";
            clearSession();
            bindSession();
        }
    };

    private void clearSession() {
        if (model.hasSession) saveLyricCache();
        model.hasSession = false;
        model.title = "";
        model.artist = "";
        model.album = "";
        model.sourcePkg = "";
        model.sourceLabel = bluetoothText();
        model.duration = 0;
        model.positionMs = 0;
        model.playing = false;
        model.receivedPosition = false;
        model.artwork = null;
        model.lyrics = Collections.emptyList();
        model.lyricSource = PlayerModel.LYRIC_NONE;
        model.lyricLines = 0;
        lyricStream.clear();
        embeddedApplied = false;
        view.resetLyricAnimation();
        view.redraw();
    }

    private String bluetoothText() {
        if (model.status == null || model.status.isEmpty()) return "等待手机播放";
        return model.status;
    }

    private void refreshState() {
        if (controller == null) { clearSession(); return; }
        metadata = controller.getMetadata();
        PlaybackState state = controller.getPlaybackState();
        if (metadata == null && state == null) return;

        applyMetadata(metadata);
        applyPlayback(state);
        updateLyric();
        view.refresh();
    }

    private void applyMetadata(MediaMetadata md) {
        String title = text(md, MediaMetadata.METADATA_KEY_TITLE);
        String artist = text(md, MediaMetadata.METADATA_KEY_ARTIST);
        String album = text(md, MediaMetadata.METADATA_KEY_ALBUM);

        MediaAccessService.Snapshot snapshot = MediaAccessService.find(boundPkg);
        if (title.isEmpty()) title = text(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (title.isEmpty() && snapshot != null && snapshot.title != null) title = snapshot.title;
        if (artist.isEmpty()) artist = text(md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (artist.isEmpty()) artist = text(md, MediaMetadata.METADATA_KEY_AUTHOR);
        if (artist.isEmpty() && snapshot != null) artist = artistFromNotice(snapshot, title);

        long duration = 0;
        if (md != null) {
            try { duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION); } catch (Exception ignored) { }
        }
        if (duration <= 0 && snapshot != null && snapshot.progressMax > 0) duration = snapshot.progressMax;

        Bitmap artwork = bitmap(md, MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (artwork == null) artwork = bitmap(md, MediaMetadata.METADATA_KEY_ART);
        if (artwork == null) artwork = bitmap(md, MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (artwork == null && snapshot != null) artwork = snapshot.icon;

        model.title = title;
        model.artist = artist;
        model.album = album;
        model.duration = duration;
        model.artwork = artwork;
        model.hasSession = true;
        model.sourcePkg = boundPkg;
        AppProfile profile = AppProfile.of(boundPkg);
        model.sourceLabel = profile.name + " · 蓝牙播放";

        String key = Keys.trackKey(boundPkg, title, artist);
        if (!key.equals(model.trackKey) && !title.isEmpty()) {
            saveLyricCache();
            model.trackKey = key;
            embeddedApplied = false;
            cacheChecked = false;
            lyricStream.clear();
            List<LrcParser.Line> cached = lyricCache.load(key);
            if (!cached.isEmpty()) {
                lyricStream.load(cached);
                model.lyricSource = PlayerModel.LYRIC_CACHE;
            }
            view.resetLyricAnimation();
        }
    }

    private void applyPlayback(PlaybackState state) {
        if (state == null) return;
        int status = state.getState();
        model.playbackState = status;
        model.playing = status == PlaybackState.STATE_PLAYING;
        model.buffering = status == PlaybackState.STATE_BUFFERING
                || status == PlaybackState.STATE_CONNECTING;
        model.receivedPosition = status != PlaybackState.STATE_NONE
                && status != PlaybackState.STATE_ERROR;

        long position = state.getPosition();
        if (model.playing) {
            long elapsed = SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime();
            float speed = state.getPlaybackSpeed();
            if (speed <= 0f) speed = 1f;
            position += (long) (Math.max(0L, elapsed) * speed);
        }
        if (model.duration > 0) position = Math.min(position, model.duration);
        model.positionMs = Math.max(0L, position);
    }

    // ------------------------------------------------------------------ 歌词

    private void updateLyric() {
        List<String> metaTexts = new ArrayList<String>(3);
        List<String> extraTexts = new ArrayList<String>(3);
        List<String> noticeTexts = new ArrayList<String>(5);

        if (metadata != null) {
            add(metaTexts, text(metadata, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
            add(metaTexts, text(metadata, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION));
            // 有些播放器把整份 LRC 挂在自定义的元数据键上，键名里通常带 lyric / lrc
            for (String key : metadata.keySet()) {
                if (key == null) continue;
                String lower = key.toLowerCase(Locale.ROOT);
                if (!lower.contains("lyric") && !lower.contains("lrc")) continue;
                try { add(extraTexts, metadata.getString(key)); } catch (Exception ignored) { }
            }
        }
        MediaAccessService.Snapshot snapshot = MediaAccessService.find(boundPkg);
        if (snapshot != null) {
            for (String text : snapshot.texts()) add(noticeTexts, text);
        }

        // 1) 整份 LRC：播放器把歌词直接塞进媒体元数据时最好用
        if (!embeddedApplied) {
            List<String> all = new ArrayList<String>(extraTexts);
            all.addAll(metaTexts);
            all.addAll(noticeTexts);
            String lrc = LyricProbe.pickLrc(all);
            if (lrc != null) {
                lyricStream.load(LrcParser.parse(lrc));
                embeddedApplied = true;
                model.lyricSource = PlayerModel.LYRIC_EMBEDDED;
            }
        }

        // 2) 逐句歌词：优先媒体元数据，其次媒体通知
        if (!embeddedApplied) {
            // 已经攒到两句以上，说明播放器确实在逐句推歌词，这时才接受两三字的短句
            boolean relaxed = lyricStream.size() >= 2;
            String line = LyricProbe.pickLine(metaTexts, model.title, model.artist, model.album, relaxed);
            int source = PlayerModel.LYRIC_SUBTITLE;
            if (line == null) {
                line = LyricProbe.pickLine(noticeTexts, model.title, model.artist, model.album, relaxed);
                source = PlayerModel.LYRIC_NOTICE;
            }
            if (line == null) {
                line = LyricProbe.pickLine(extraTexts, model.title, model.artist, model.album, relaxed);
                source = PlayerModel.LYRIC_NOTICE;
            }
            if (line != null) verifyCache(line);
            if (line != null && lyricStream.observe(line, model.positionMs)) {
                if (model.lyricSource != PlayerModel.LYRIC_CACHE) model.lyricSource = source;
                // 边听边存，防止用户直接关掉车机电源、走不到 onPause 就丢掉整首歌的歌词
                long now = SystemClock.elapsedRealtime();
                if (now - lastCacheSave > 15000L) {
                    lastCacheSave = now;
                    saveLyricCache();
                }
            }
        }

        // 时间轴没变就不要重新拷一份：歌词攒到几百行时，每 250 ms 拷一次
        // 是白白制造垃圾，弱一点的车机 SoC 上能看到 GC 抖动。
        if (lyricVersion != lyricStream.version()) {
            lyricVersion = lyricStream.version();
            List<LrcParser.Line> lines = new ArrayList<LrcParser.Line>(lyricStream.lines());
            model.lyrics = lines;
            model.lyricLines = lines.size();
            if (lines.isEmpty()) model.lyricSource = PlayerModel.LYRIC_NONE;
        }
    }

    /** 元数据里没有歌手时，媒体通知的正文通常就是歌手，用它兜底。 */
    private String artistFromNotice(MediaAccessService.Snapshot snapshot, String title) {
        if (snapshot == null || snapshot.text == null) return "";
        String value = snapshot.text.trim();
        int dash = value.indexOf(" - ");
        if (dash > 0) value = value.substring(0, dash).trim();
        if (value.isEmpty() || value.length() > 30) return "";
        if (value.equalsIgnoreCase(title)) return "";
        if (LyricProbe.looksLikeLyric(value, title, "", "")) return "";
        return value;
    }

    /**
     * 本机缓存是上一遍听的时候记下的时间轴。如果这次同一句的位置对不上十几秒，
     * 说明缓存属于另一个版本（或上次是从中间开始听的），直接丢掉重建，
     * 否则会出现同一句歌词显示两次。
     */
    private void verifyCache(String line) {
        if (cacheChecked || model.lyricSource != PlayerModel.LYRIC_CACHE || lyricStream.isEmpty()) return;
        cacheChecked = true;
        List<LrcParser.Line> lines = lyricStream.lines();
        if (lines.isEmpty()) return;
        long last = lines.get(lines.size() - 1).time;
        // 唱完最后一句之后位置会继续往前走，这段距离不算对不上
        if (model.positionMs >= last) return;
        long cached = lyricStream.nearestTime(line, model.positionMs);
        if (cached >= 0 && Math.abs(cached - model.positionMs) > 12000L) {
            lyricStream.clear();
            model.lyricSource = PlayerModel.LYRIC_NONE;
        }
    }

    private void saveLyricCache() {
        if (model.trackKey == null || model.trackKey.isEmpty()) return;
        List<LrcParser.Line> lines = lyricStream.lines();
        if (lines.size() < 3) return;
        lyricCache.save(model.trackKey, lines);
    }

    private void add(List<String> out, String value) {
        if (value == null) return;
        String text = value.trim();
        if (text.isEmpty() || out.contains(text)) return;
        out.add(text);
    }

    private static String text(MediaMetadata md, String key) {
        if (md == null) return "";
        try {
            CharSequence value = md.getText(key);
            return value == null ? "" : value.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static Bitmap bitmap(MediaMetadata md, String key) {
        if (md == null) return null;
        try { return md.getBitmap(key); } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------------ 定时器

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            long now = SystemClock.elapsedRealtime();
            if (controller != null) {
                applyPlayback(controller.getPlaybackState());
                updateLyric();
            }
            if (autoIdle && idleClock && model.playing
                    && now - lastInteraction > 45000L && !view.isIdle()) {
                view.setIdle(true);
            }
            view.refresh();
            lastTick = now;
            // 播放中要跟住歌词和进度，暂停后没必要还每 250 ms 醒一次：
            // 车机常电，降频能少烧一点 CPU，也少一点无谓重绘。
            handler.postDelayed(this, model.playing ? TICK_PLAYING_MS : TICK_IDLE_MS);
        }
    };

    // ------------------------------------------------------------------ 界面动作

    @Override public void onUiAction(String id) {
        lastInteraction = SystemClock.elapsedRealtime();
        if ("ui.settings".equals(id)) { settingsPanel(); return; }
        if ("ui.lyrics".equals(id) || "wake".equals(id) || "ui.idle".equals(id)) {
            view.redraw();
            return;
        }
        if (controller == null) { toast("手机还没有在播放音乐"); return; }
        MediaController.TransportControls controls = controller.getTransportControls();
        try {
            switch (id) {
                case "transport.play": {
                    PlaybackState state = controller.getPlaybackState();
                    boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
                    if (playing) controls.pause(); else controls.play();
                    break;
                }
                case "transport.next": controls.skipToNext(); break;
                case "transport.prev": controls.skipToPrevious(); break;
                default: break;
            }
        } catch (Exception e) {
            toast("操作失败，请用手机控制");
        }
        handler.postDelayed(new Runnable() {
            @Override public void run() { refreshState(); }
        }, 300);
    }

    @Override public void onUiSeek(float fraction) {
        if (controller == null || model.duration <= 0) return;
        long target = (long) (model.duration * Math.max(0f, Math.min(1f, fraction)));
        try {
            controller.getTransportControls().seekTo(target);
            model.positionMs = target;
        } catch (Exception e) {
            toast("跳转失败");
        }
        handler.postDelayed(new Runnable() {
            @Override public void run() { refreshState(); }
        }, 300);
    }

    @Override public boolean onUiBack() {
        if (currentDialog != null && currentDialog.isShowing()) { dismissDialog(); return true; }
        return false;
    }

    @Override public void onBackPressed() {
        if (view.onUiBack()) return;
        if (currentDialog != null && currentDialog.isShowing()) { dismissDialog(); return; }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ 面板

    private boolean hasAccess() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            if (enabled == null) return false;
            ComponentName target = new ComponentName(this, MediaAccessService.class);
            for (String part : enabled.split(":")) {
                ComponentName name = ComponentName.unflattenFromString(part);
                if (name != null && target.equals(name)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void introPanel() {
        accessGranted = hasAccess();
        LinearLayout column = Panels.column(this, theme);
        column.addView(Panels.heading(this, theme, "先打开媒体读取权限"));
        column.addView(Panels.note(this, theme,
                "手机通过蓝牙放歌时，车机只能从系统的媒体通知里拿到歌名、歌手、封面和歌词。"
                        + "打开这个权限后，汽水音乐、QQ音乐、网易云音乐、咪咕音乐的信息会自动同步过来。\n\n"
                        + "只读取媒体类通知，不读聊天和其他通知，也不联网。"));
        column.addView(Panels.row(this, theme, "当前状态", accessGranted ? "已授权" : "未授权", null));
        showSheet("首次使用", column, new String[]{"去授权", "稍后"}, new Sheet.Listener() {
            @Override public void onAction(int index) {
                if (index == 0) openAccessSettings();
            }
        });
    }

    private void openAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            toast("请找到「流光车机音乐」并打开");
        } catch (Exception e) {
            toast("请在系统设置里打开通知使用权");
        }
    }

    private void settingsPanel() {
        LinearLayout column = Panels.column(this, theme);

        column.addView(Panels.section(this, theme, "主题"));
        column.addView(themeRow());
        column.addView(Panels.section(this, theme, "显示"));

        final LinearLayout large = Panels.row(this, theme, "歌词大字", largeText ? "开" : "关", null);
        large.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                largeText = !largeText;
                prefs.edit().putBoolean("large", largeText).apply();
                applyTheme();
                setRowValue(large, largeText ? "开" : "关");
            }
        });
        column.addView(large);

        final LinearLayout fill = Panels.row(this, theme, "界面铺满（裁掉留白）", fillScreen ? "铺满" : "完整", null);
        fill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                fillScreen = !fillScreen;
                prefs.edit().putBoolean("fill", fillScreen).apply();
                view.setFillScreen(fillScreen);
                setRowValue(fill, fillScreen ? "铺满" : "完整");
            }
        });
        column.addView(fill);

        final LinearLayout idle = Panels.row(this, theme, "空闲大时钟", idleClock ? "开" : "关", null);
        idle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                idleClock = !idleClock;
                prefs.edit().putBoolean("idle", idleClock).apply();
                view.setIdleClockEnabled(idleClock);
                setRowValue(idle, idleClock ? "开" : "关");
            }
        });
        column.addView(idle);

        final LinearLayout auto = Panels.row(this, theme, "停车 45 秒后进入空闲屏", autoIdle ? "开" : "关", null);
        auto.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                autoIdle = !autoIdle;
                prefs.edit().putBoolean("autoIdle", autoIdle).apply();
                setRowValue(auto, autoIdle ? "开" : "关");
            }
        });
        column.addView(auto);

        final boolean autoStartEnabled = prefs.getBoolean("autoStart", false);
        final LinearLayout autoStart = Panels.row(this, theme, "开机自动启动", autoStartEnabled ? "开" : "关", null);
        autoStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean enabled = !prefs.getBoolean("autoStart", false);
                prefs.edit().putBoolean("autoStart", enabled).apply();
                setRowValue(autoStart, enabled ? "开" : "关");
                toast(enabled ? "已开启开机自动启动" : "已关闭开机自动启动");
            }
        });
        column.addView(autoStart);

        final LinearLayout dim = Panels.row(this, theme, "屏幕调光", dimLabel(), null);
        dim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dimLevel = (dimLevel + 1) % 4;
                prefs.edit().putInt("dim", dimLevel).apply();
                applySystemBars();
                setRowValue(dim, dimLabel());
            }
        });
        column.addView(dim);

        column.addView(Panels.section(this, theme, "连接"));
        column.addView(Panels.row(this, theme, "媒体读取权限",
                hasAccess() ? "已授权" : "未授权", new View.OnClickListener() {
                    @Override public void onClick(View v) { dismissDialog(); openAccessSettings(); }
                }));
        column.addView(Panels.row(this, theme, "当前播放器",
                model.sourcePkg.isEmpty() ? "未检测到" : AppProfile.of(model.sourcePkg).name, null));

        column.addView(Panels.section(this, theme, "其他"));
        column.addView(Panels.row(this, theme, "兼容性诊断", null, new View.OnClickListener() {
            @Override public void onClick(View v) { diagnosticPanel(); }
        }));
        column.addView(Panels.row(this, theme, "歌词缓存", lyricCache.count() + " 首", new View.OnClickListener() {
            @Override public void onClick(View v) {
                lyricCache.clear();
                toast("已清空歌词缓存");
                settingsPanel();
            }
        }));
        column.addView(Panels.row(this, theme, "关于与隐私", null, new View.OnClickListener() {
            @Override public void onClick(View v) { aboutPanel(); }
        }));

        showSheet("设置", column, new String[]{"关闭"}, null);
    }

    private View themeRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Panels.dp(this, 2), 0, Panels.dp(this, 10));
        for (int i = 0; i < Theme.NAMES.length; i++) {
            final int id = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setClickable(true);
            item.setFocusable(true);
            item.setPadding(Panels.dp(this, 8), Panels.dp(this, 8),
                    Panels.dp(this, 8), Panels.dp(this, 8));
            GradientDrawable background = new GradientDrawable();
            background.setColor(theme.surface);
            background.setCornerRadius(Panels.dp(this, 18));
            background.setStroke(Panels.dp(this, id == themeId ? 2 : 1),
                    id == themeId ? theme.accent : Theme.alpha(theme.line, 170));
            item.setBackground(background);
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (themeId == id) return;
                    themeId = id;
                    prefs.edit().putInt("theme", id).apply();
                    applyTheme();
                    settingsPanel();
                }
            });

            View swatch = new View(this);
            GradientDrawable dot = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{Theme.of(id, false).accent, Theme.of(id, false).bgTop});
            dot.setCornerRadius(Panels.dp(this, 14));
            swatch.setBackground(dot);
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(
                    Panels.dp(this, 52), Panels.dp(this, 52));
            swatch.setLayoutParams(dotParams);
            item.addView(swatch);

            TextView label = new TextView(this);
            label.setText(Theme.NAMES[i]);
            label.setTextSize(12);
            label.setTextColor(id == themeId ? theme.accent : theme.sub);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, Panels.dp(this, 6), 0, 0);
            item.addView(label);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.rightMargin = Panels.dp(this, 6);
            item.setLayoutParams(params);
            row.addView(item);
        }
        return row;
    }

    private void diagnosticPanel() {
        StringBuilder out = new StringBuilder();
        out.append("媒体读取权限：").append(hasAccess() ? "已授权" : "未授权").append('\n');
        out.append("通知监听服务：").append(MediaAccessService.isConnected() ? "已连接" : "未连接").append('\n');
        out.append("检测到的媒体通知：").append(MediaAccessService.snapshots().size()).append(" 个\n");
        if (!boundPkg.isEmpty()) {
            out.append("当前绑定：").append(AppProfile.of(boundPkg).name)
                    .append("（").append(boundPkg).append("）\n");
        }
        out.append('\n');
        out.append("歌名：").append(empty(model.title)).append('\n');
        out.append("歌手：").append(empty(model.artist)).append('\n');
        out.append("专辑：").append(empty(model.album)).append('\n');
        out.append("封面：").append(model.artwork != null ? "有" : "无").append('\n');
        out.append("时长：").append(model.duration > 0 ? model.duration / 1000 + " 秒" : "未提供").append('\n');
        out.append("播放状态：").append(stateName()).append('\n');
        out.append('\n');
        out.append("歌词行数：").append(model.lyricLines).append('\n');
        out.append("歌词来源：").append(lyricSourceName()).append('\n');
        out.append("本机缓存：").append(lyricCache.count()).append(" 首\n");
        if (!model.hasSession) {
            out.append("\n还没有收到媒体会话。请确认手机已连上车机蓝牙，并在手机上点了播放。\n");
        } else if (model.lyricLines == 0) {
            out.append("\n播放器没有把歌词写进媒体信息。可以在播放器设置里打开「车载歌词」或「蓝牙歌词」开关。\n");
        }

        LinearLayout column = Panels.column(this, theme);
        column.addView(Panels.heading(this, theme, "兼容性诊断"));
        column.addView(Panels.body(this, theme, out.toString()));
        lastDiagnostic = out.toString();
        showSheet("诊断", column, new String[]{"复制到剪贴板", "关闭"}, new Sheet.Listener() {
            @Override public void onAction(int index) {
                if (index == 0) {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("诊断", lastDiagnostic));
                        toast("已复制");
                    }
                }
            }
        });
    }

    private String lastDiagnostic = "";

    private void aboutPanel() {
        LinearLayout column = Panels.column(this, theme);
        column.addView(Panels.heading(this, theme, "关于"));
        column.addView(Panels.body(this, theme,
                "流光车机音乐 0.7.0\n\n"
                        + "这是一个纯显示端：歌曲、歌手、专辑、封面、时长、进度、播放状态和歌词全部来自"
                        + "手机上正在播放的播放器，通过蓝牙传到车机。软件本身不存歌、不联网、不导入任何文件。\n\n"
                        + "已适配：汽水音乐、QQ音乐、网易云音乐、咪咕音乐、酷狗、酷我、喜马拉雅。\n\n"
                        + "歌词说明：蓝牙协议本身不传歌词，软件读取的是播放器写进媒体信息和媒体通知里的"
                        + "当前歌词。第一次听某首歌会逐句累积，放完一遍后歌词时间轴会存在本机，下次直接可用。\n\n"
                        + "隐私：只读取媒体类通知（带 MediaSession 的通知），不读取聊天、短信或其他通知；"
                        + "没有网络权限，不会上传任何数据。"));
        showSheet("关于", column, new String[]{"关闭"}, null);
    }

    private String lyricSourceName() {
        switch (model.lyricSource) {
            case PlayerModel.LYRIC_EMBEDDED: return "播放器内嵌歌词";
            case PlayerModel.LYRIC_SUBTITLE: return "媒体元数据歌词";
            case PlayerModel.LYRIC_NOTICE: return "媒体通知歌词";
            case PlayerModel.LYRIC_CACHE: return "本机记录的时间轴";
            default: return "未获取到";
        }
    }

    private String dimLabel() {
        String[] names = {"100%", "72%", "46%", "26%"};
        return names[Math.max(0, Math.min(names.length - 1, dimLevel))];
    }

    private String stateName() {
        switch (model.playbackState) {
            case PlaybackState.STATE_PLAYING: return "播放中";
            case PlaybackState.STATE_PAUSED: return "已暂停";
            case PlaybackState.STATE_STOPPED: return "已停止";
            case PlaybackState.STATE_BUFFERING: return "缓冲中";
            case PlaybackState.STATE_NONE: return "无会话";
            case PlaybackState.STATE_ERROR: return "出错";
            default: return "状态 " + model.playbackState;
        }
    }

    private static String empty(String value) {
        return value == null || value.isEmpty() ? "未提供" : value;
    }

    private void applyTheme() {
        theme = Theme.of(themeId, largeText);
        view.setTheme(theme);
        applySystemBars();
    }

    private void setRowValue(LinearLayout row, String value) {
        if (row == null || row.getChildCount() < 2) return;
        View child = row.getChildAt(row.getChildCount() - 1);
        if (child instanceof TextView) ((TextView) child).setText(value);
    }

    // ------------------------------------------------------------------ 面板工具

    /**
     * 弹面板。若已有面板，先关掉再在下一帧重建：同一个点击里 dismiss 之后立刻 show，
     * 新窗口会被旧窗口的移除流程一起带走，表现就是"点了没反应"。
     */
    private void showSheet(final String title, final View body, final String[] actions,
                           final Sheet.Listener listener) {
        Dialog old = currentDialog;
        currentDialog = null;
        if (old != null && old.isShowing()) {
            try { old.dismiss(); } catch (Exception ignored) { }
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    currentDialog = Sheet.show(MainActivity.this, theme, title, body, actions, listener);
                    reapplyBarsSoon();
                }
            }, 150);
        } else {
            currentDialog = Sheet.show(this, theme, title, body, actions, listener);
            reapplyBarsSoon();
        }
    }

    /** 弹窗出现/消失时系统会把状态栏放回来，稍后再收一次。 */
    private void reapplyBarsSoon() {
        handler.postDelayed(new Runnable() {
            @Override public void run() { applySystemBars(); }
        }, 700);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private void dismissDialog() {
        if (currentDialog != null && currentDialog.isShowing()) {
            try { currentDialog.dismiss(); } catch (Exception ignored) { }
        }
        currentDialog = null;
        reapplyBarsSoon();
    }
}
