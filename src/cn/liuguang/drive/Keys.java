package cn.liuguang.drive;

import java.security.MessageDigest;
import java.util.Locale;

/** 曲目键与文本归一化。纯 Java，可单元测试。 */
public final class Keys {
    private Keys() {}

    /** 小写、去掉空白与标点，只保留字母、数字与中日韩文字。 */
    public static String normalize(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    public static String hash(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            return hex.toString();
        } catch (Exception e) {
            return "f" + Integer.toHexString((text == null ? "" : text).hashCode());
        }
    }

    /**
     * 曲目键：来源 + 歌名 + 歌手。刻意不含专辑——同一条蓝牙曲目在不同播放器里
     * 专辑名常写成"单曲"或为空，带上专辑会让歌词缓存每次都失效。
     */
    public static String trackKey(String source, String title, String artist) {
        return hash(normalize(source) + "|" + normalize(title) + "|" + normalize(artist));
    }
}
