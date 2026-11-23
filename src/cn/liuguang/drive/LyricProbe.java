package cn.liuguang.drive;

import java.util.List;

/**
 * 判断"一段文字是歌词还是歌曲信息"。
 *
 * 播放器塞进 DISPLAY_SUBTITLE / 通知文本里的东西并不统一：可能是歌手、可能是
 * "歌手 - 专辑"、也可能是当前这句歌词。这里给每个候选打一个可信度分数，界面据此
 * 决定要不要显示：
 *
 *   STRONG  有标点或以"作词/作曲"开头，几乎可以肯定是歌词；
 *   LIKELY  五个字以上，像一句词；
 *   WEAK    两到四个字，可能是很短的歌词，也可能只是歌手名；
 *   REJECT  和歌名/歌手/专辑完全一样，或明显是状态文字。
 *
 * 界面在已经积累了两句歌词之后才接受 WEAK，这样开头不会把歌手名当成歌词显示，
 * 后面又不会漏掉"我的思念"这种短句。
 *
 * 纯 Java，可单元测试。
 */
public final class LyricProbe {
    public static final int REJECT = 0, WEAK = 1, LIKELY = 2, STRONG = 3;

    /** 歌词首行常见的制作信息，出现即认定为歌词正文（很多 LRC 前几行就是这些）。 */
    private static final String[] CREDITS = {
            "作词", "作曲", "编曲", "制作人", "监制", "混音", "母带", "和声",
            "录音", "吉他", "贝斯", "鼓", "键盘", "弦乐", "出品", "发行", "词：", "曲："
    };
    /** 明显不是歌词的状态文字。 */
    private static final String[] NOISE = {
            "正在播放", "未知", "暂无", "无歌词", "蓝牙音乐", "未连接", "请连接"
    };
    private static final String PUNCT = "，。！？、；：…～,.!?;:~—";

    private LyricProbe() {}

    /** 是否是一份带时间戳的 LRC（整份歌词）。 */
    public static boolean isLrc(String text) {
        if (text == null || text.length() < 8) return false;
        int hits = 0;
        for (int i = 0; i + 5 < text.length(); i++) {
            if (text.charAt(i) != '[') continue;
            if (!Character.isDigit(text.charAt(i + 1))) continue;
            if (!Character.isDigit(text.charAt(i + 2))) continue;
            if (text.charAt(i + 3) != ':') continue;
            if (!Character.isDigit(text.charAt(i + 4))) continue;
            if (++hits >= 2) return true;
        }
        return false;
    }

    /** 从候选文本里挑出最长的一份完整 LRC。 */
    public static String pickLrc(List<String> candidates) {
        if (candidates == null) return null;
        String best = null;
        for (String candidate : candidates) {
            if (!isLrc(candidate)) continue;
            if (best == null || candidate.length() > best.length()) best = candidate;
        }
        return best;
    }

    /** 从候选文本里挑出第一句歌词；allowWeak 时也接受两到四个字的短句。 */
    public static String pickLine(List<String> candidates, String title, String artist,
                                  String album, boolean allowWeak) {
        if (candidates == null) return null;
        for (String candidate : candidates) {
            int score = score(candidate, title, artist, album);
            if (score >= LIKELY || (allowWeak && score >= WEAK)) {
                return LyricStream.clean(candidate);
            }
        }
        return null;
    }

    public static boolean looksLikeLyric(String text, String title, String artist, String album) {
        return score(text, title, artist, album) >= LIKELY;
    }

    public static int score(String text, String title, String artist, String album) {
        if (text == null) return REJECT;
        String value = LyricStream.clean(text);
        if (value.length() < 2) return REJECT;
        if (isLrc(value)) return REJECT;

        String norm = Keys.normalize(value);
        if (norm.isEmpty()) return REJECT;
        if (norm.equals(Keys.normalize(title))) return REJECT;
        if (norm.equals(Keys.normalize(artist))) return REJECT;
        if (norm.equals(Keys.normalize(album))) return REJECT;

        // "歌手 - 专辑"、"歌手 · 专辑" 这类组合信息
        String[] parts = value.split("\\s*[-–—·|/]\\s*");
        if (parts.length == 2) {
            String left = Keys.normalize(parts[0]), right = Keys.normalize(parts[1]);
            if (!left.isEmpty() && left.equals(Keys.normalize(artist))) return REJECT;
            if (!left.isEmpty() && left.equals(Keys.normalize(album))) return REJECT;
            if (!right.isEmpty() && right.equals(Keys.normalize(artist))) return REJECT;
            if (!right.isEmpty() && right.equals(Keys.normalize(album))) return REJECT;
            if (!right.isEmpty() && right.equals(Keys.normalize(title))) return REJECT;
        }

        for (String noise : NOISE) if (value.contains(noise)) return REJECT;
        for (String credit : CREDITS) if (value.startsWith(credit)) return STRONG;

        boolean anyLetter = false;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isLetter(value.charAt(i))) { anyLetter = true; break; }
        }
        if (!anyLetter) return REJECT;

        if (containsPunct(value)) return value.length() >= 4 ? STRONG : WEAK;
        return value.length() >= 5 ? LIKELY : WEAK;
    }

    private static boolean containsPunct(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (PUNCT.indexOf(value.charAt(i)) >= 0) return true;
        }
        return false;
    }
}
