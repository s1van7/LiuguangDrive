package cn.liuguang.drive;

import java.util.Locale;

/**
 * 手机端播放器的识别信息。
 *
 * 蓝牙 AVRCP 只传媒体会话，不传"这是哪个 App"。包名是唯一可靠的线索，
 * 拿它来显示来源、并在诊断页说明该 App 的歌词从哪条通道出来。
 */
final class AppProfile {
    final String pkg;
    final String name;
    final int color;

    private AppProfile(String pkg, String name, int color) {
        this.pkg = pkg;
        this.name = name;
        this.color = color;
    }

    static AppProfile of(String pkg) {
        if (pkg == null) return unknown("");
        switch (pkg) {
            case "com.luna.music": return new AppProfile(pkg, "汽水音乐", 0xFFFF5A5F);
            case "com.tencent.qqmusic": return new AppProfile(pkg, "QQ音乐", 0xFF2FCC71);
            case "com.tencent.qqmusicpad": return new AppProfile(pkg, "QQ音乐", 0xFF2FCC71);
            case "com.netease.cloudmusic": return new AppProfile(pkg, "网易云音乐", 0xFFE23B3B);
            case "cmccwm.mobilemusic": return new AppProfile(pkg, "咪咕音乐", 0xFFFF7A1A);
            case "com.kugou.android": return new AppProfile(pkg, "酷狗音乐", 0xFF2CA9E1);
            case "com.kugou.android.lite": return new AppProfile(pkg, "酷狗音乐", 0xFF2CA9E1);
            case "cn.kuwo.player": return new AppProfile(pkg, "酷我音乐", 0xFFFFC300);
            case "com.ximalaya.ting.android": return new AppProfile(pkg, "喜马拉雅", 0xFFF86442);
            case "com.google.android.apps.youtube.music":
                return new AppProfile(pkg, "YouTube Music", 0xFFFF0033);
            case "com.spotify.music": return new AppProfile(pkg, "Spotify", 0xFF1DB954);
            case "com.android.bluetooth": return new AppProfile(pkg, "蓝牙音频", 0xFF7C8794);
            default: return unknown(pkg);
        }
    }

    private static AppProfile unknown(String pkg) {
        String name = pkg == null || pkg.isEmpty() ? "手机音乐" : shortName(pkg);
        return new AppProfile(pkg, name, 0xFF8A8A8A);
    }

    /** 未知包名时取最后一段，至少比一串 com.xxx.yyy 好读。 */
    private static String shortName(String pkg) {
        int dot = pkg.lastIndexOf('.');
        String tail = dot >= 0 && dot + 1 < pkg.length() ? pkg.substring(dot + 1) : pkg;
        if (tail.isEmpty()) return pkg;
        return tail.substring(0, 1).toUpperCase(Locale.ROOT) + tail.substring(1);
    }

    boolean known() { return color != 0xFF8A8A8A; }
}
