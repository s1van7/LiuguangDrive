package cn.liuguang.drive;

import android.app.Notification;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 媒体通知监听。
 *
 * 手机通过蓝牙放歌时，车机上能拿到的东西只有两样：系统里那条媒体通知，以及它
 * 携带的 MediaSession。这里把每个播放器的媒体通知缓存下来（按包名去重），
 * 界面拿最新的那条去绑定 MediaController。
 *
 * 除了歌曲信息，通知文本本身也是歌词的兜底通道：几家播放器开启"车载歌词"后，
 * 会把当前这一句写在通知正文里，逐句刷新。
 *
 * 不读聊天、不读其他通知：只处理带 MediaSession 或 transport 类别的通知。
 */
public class MediaAccessService extends NotificationListenerService {
    public static final String CHANGED = "cn.liuguang.drive.MEDIA_CHANGED";

    interface Sink { void onMediaChanged(); }

    /** 一条媒体通知的快照。字段都是字符串，界面按需取用。 */
    static final class Snapshot {
        final String pkg;
        final MediaSession.Token token;
        final long postedAt;
        final String title, text, subText, bigText, infoText;
        final String[] lines;
        final Bitmap icon;
        final int progress, progressMax;

        Snapshot(String pkg, MediaSession.Token token, long postedAt, String title, String text,
                 String subText, String bigText, String infoText, String[] lines, Bitmap icon,
                 int progress, int progressMax) {
            this.pkg = pkg; this.token = token; this.postedAt = postedAt;
            this.title = title; this.text = text; this.subText = subText;
            this.bigText = bigText; this.infoText = infoText; this.lines = lines;
            this.icon = icon; this.progress = progress; this.progressMax = progressMax;
        }

        /** 所有可能承载歌词的文本，按可信度排序。 */
        List<String> texts() {
            List<String> out = new ArrayList<String>(6);
            add(out, subText);
            add(out, text);
            add(out, bigText);
            add(out, infoText);
            if (lines != null) for (String line : lines) add(out, line);
            return out;
        }

        private static void add(List<String> out, String value) {
            if (value == null) return;
            String text = value.trim();
            if (text.isEmpty() || out.contains(text)) return;
            out.add(text);
        }
    }

    private static final Map<String, Snapshot> SNAPSHOTS = new LinkedHashMap<String, Snapshot>();
    private static volatile Sink sink;
    private static volatile boolean connected;
    /** 防止车机长期收到多个播放器通知时，静态快照无限持有封面 Bitmap。 */
    private static final int MAX_SNAPSHOTS = 8;

    static void setSink(Sink value) { sink = value; }

    static boolean isConnected() { return connected; }

    static List<Snapshot> snapshots() {
        synchronized (SNAPSHOTS) { return new ArrayList<Snapshot>(SNAPSHOTS.values()); }
    }

    /** 最近一条媒体通知，通常就是正在放歌的那个 App。 */
    static Snapshot latest() {
        Snapshot best = null;
        synchronized (SNAPSHOTS) {
            for (Snapshot item : SNAPSHOTS.values()) {
                if (best == null || item.postedAt > best.postedAt) best = item;
            }
        }
        return best;
    }

    static Snapshot find(String pkg) {
        if (pkg == null) return null;
        synchronized (SNAPSHOTS) { return SNAPSHOTS.get(pkg); }
    }

    @Override public void onListenerConnected() {
        connected = true;
        // 服务重连时，系统不保证重新发送所有仍显示中的通知；主动回填快照，
        // 避免界面只能等下一次歌曲切换才重新拿到歌词通知。
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) onNotificationPosted(sbn);
            }
        } catch (Exception ignored) {
            // 部分车机固件可能限制读取当前通知；后续新通知仍会正常进入回调。
        }
        notifySink();
        sendBroadcast(new Intent(CHANGED).setPackage(getPackageName()));
    }

    @Override public void onListenerDisconnected() {
        connected = false;
        // 断开监听后快照不再可信，清除旧封面和歌词，防止重连前显示上一首歌。
        synchronized (SNAPSHOTS) { SNAPSHOTS.clear(); }
        notifySink();
        sendBroadcast(new Intent(CHANGED).setPackage(getPackageName()));
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        Notification notification = sbn.getNotification();
        if (notification == null || !isMedia(notification)) return;
        Snapshot snapshot = read(sbn, notification);
        if (snapshot == null) return;
        synchronized (SNAPSHOTS) {
            // LinkedHashMap 按最近一次通知更新时间排序；最多保留 8 个媒体来源。
            SNAPSHOTS.remove(sbn.getPackageName());
            SNAPSHOTS.put(sbn.getPackageName(), snapshot);
            while (SNAPSHOTS.size() > MAX_SNAPSHOTS) {
                String oldest = SNAPSHOTS.keySet().iterator().next();
                SNAPSHOTS.remove(oldest);
            }
        }
        notifySink();
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        Notification notification = sbn.getNotification();
        if (notification == null || !isMedia(notification)) return;
        boolean removed;
        synchronized (SNAPSHOTS) { removed = SNAPSHOTS.remove(sbn.getPackageName()) != null; }
        if (removed) notifySink();
    }

    private static void notifySink() {
        Sink target = sink;
        if (target == null) return;
        try { target.onMediaChanged(); } catch (Exception ignored) { }
    }

    /** 只认媒体类通知：带 MediaSession、类别是 transport、或模板是 MediaStyle。 */
    static boolean isMedia(Notification notification) {
        if (notification == null) return false;
        Bundle extras = notification.extras;
        if (extras != null && extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return true;
        if (Notification.CATEGORY_TRANSPORT.equals(notification.category)) return true;
        if (extras != null) {
            String template = extras.getString(Notification.EXTRA_TEMPLATE);
            if (template != null && template.contains("MediaStyle")) return true;
        }
        return false;
    }

    private static Snapshot read(StatusBarNotification sbn, Notification notification) {
        Bundle extras = notification.extras;
        if (extras == null) return null;
        MediaSession.Token token = null;
        Object raw = extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
        if (raw instanceof MediaSession.Token) token = (MediaSession.Token) raw;

        Bitmap icon = null;
        Object big = extras.getParcelable(Notification.EXTRA_LARGE_ICON_BIG);
        if (big instanceof Bitmap) icon = (Bitmap) big;
        if (icon == null) {
            Object small = extras.getParcelable(Notification.EXTRA_LARGE_ICON);
            if (small instanceof Bitmap) icon = (Bitmap) small;
        }

        CharSequence[] rawLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        String[] lines = null;
        if (rawLines != null && rawLines.length > 0) {
            lines = new String[Math.min(rawLines.length, 8)];
            for (int i = 0; i < lines.length; i++) lines[i] = text(rawLines[i]);
        }

        return new Snapshot(sbn.getPackageName(), token, System.currentTimeMillis(),
                text(extras.getCharSequence(Notification.EXTRA_TITLE)),
                text(extras.getCharSequence(Notification.EXTRA_TEXT)),
                text(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)),
                text(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)),
                text(extras.getCharSequence(Notification.EXTRA_INFO_TEXT)),
                lines, icon,
                extras.getInt(Notification.EXTRA_PROGRESS, -1),
                extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0));
    }

    private static String text(CharSequence value) {
        if (value == null) return null;
        String out = value.toString().trim();
        if (out.length() > 400) out = out.substring(0, 400);
        return out.isEmpty() ? null : out;
    }
}
