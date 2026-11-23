package cn.liuguang.drive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 无依赖的 LRC 解析器，可脱离 Android 在桌面 JVM 上单元测试。
 *
 * 支持：[mm:ss]、[mm:ss.x]、[mm:ss.xx]、[mm:ss.xxx]、[mm:ss:xx]、
 * 一行多时间标签、[offset:±ms]、[ti:][ar:][al:][by:] 元数据、BOM。
 * 不实现逐字（增强型）LRC，避免把 A2 标签当成歌词正文显示。
 */
public final class LrcParser {
    public static final int MAX_LINES = 5000;
    public static final int MAX_TEXT = 300;

    public static final class Line {
        public final long time;
        public final String text;
        public Line(long time, String text) { this.time = time; this.text = text; }
        @Override public String toString() { return time + "|" + text; }
    }

    /** 解析结果：歌词行 + 文件头元数据。 */
    public static final class Doc {
        public final List<Line> lines;
        public final String title, artist, album, by;
        public final long offset;
        Doc(List<Line> lines, String title, String artist, String album, String by, long offset) {
            this.lines = lines; this.title = title; this.artist = artist;
            this.album = album; this.by = by; this.offset = offset;
        }
        public boolean isEmpty() { return lines.isEmpty(); }
    }

    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset\\s*:\\s*([+-]?\\d+)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("\\[(ti|ar|al|by)\\s*:\\s*([^\\]]*)\\]", Pattern.CASE_INSENSITIVE);

    private LrcParser() {}

    public static List<Line> parse(String raw) { return parseDoc(raw).lines; }

    public static Doc parseDoc(String raw) {
        List<Line> lines = new ArrayList<Line>();
        if (raw == null) return new Doc(lines, null, null, null, null, 0);

        String text = raw.replace("\ufeff", "").replace("\r\n", "\n").replace('\r', '\n');
        long offset = 0;
        Matcher o = OFFSET.matcher(text);
        if (o.find()) {
            try { offset = clamp(Long.parseLong(o.group(1)), -120000L, 120000L); }
            catch (NumberFormatException ignored) { }
        }

        String title = null, artist = null, album = null, by = null;
        Set<String> seen = new HashSet<String>();

        for (String row : text.split("\n")) {
            Matcher m = STAMP.matcher(row);
            List<Long> times = new ArrayList<Long>(2);
            int end = 0;
            boolean any = false;
            while (m.find()) {
                any = true;
                end = m.end(); // 即使时间戳无效也要吞掉，避免正文混入 "[00:99.00]"
                long min = parseLong(m.group(1), 0);
                long sec = parseLong(m.group(2), -1);
                if (sec < 0 || sec > 59) continue;
                String fraction = m.group(3);
                long ms = 0;
                if (fraction != null) {
                    String f = (fraction + "000").substring(0, 3);
                    ms = parseLong(f, 0);
                }
                times.add(Math.max(0L, min * 60000L + sec * 1000L + ms - offset));
            }
            if (!any) {
                Matcher tag = TAG.matcher(row);
                while (tag.find()) {
                    String key = tag.group(1).toLowerCase(Locale.ROOT);
                    String value = tag.group(2).trim();
                    if (value.isEmpty()) continue;
                    if (key.equals("ti") && title == null) title = value;
                    else if (key.equals("ar") && artist == null) artist = value;
                    else if (key.equals("al") && album == null) album = value;
                    else if (key.equals("by") && by == null) by = value;
                }
                continue;
            }
            String body = end <= row.length() ? row.substring(end) : "";
            body = body.trim();
            if (body.isEmpty()) continue;
            if (body.length() > MAX_TEXT) body = body.substring(0, MAX_TEXT);
            for (Long t : times) {
                if (lines.size() >= MAX_LINES) break;
                if (!seen.add(t + "\u0000" + body)) continue; // 去重，避免同一句重复显示
                lines.add(new Line(t, body));
            }
            if (lines.size() >= MAX_LINES) break;
        }

        Collections.sort(lines, new Comparator<Line>() {
            @Override public int compare(Line a, Line b) { return Long.compare(a.time, b.time); }
        });
        return new Doc(lines, title, artist, album, by, offset);
    }

    /** 二分查找当前应高亮的下标；早于第一行时返回 -1。 */
    public static int activeIndex(List<Line> lines, long position) {
        if (lines == null || lines.isEmpty()) return -1;
        int low = 0, high = lines.size() - 1, answer = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lines.get(mid).time <= position) { answer = mid; low = mid + 1; }
            else high = mid - 1;
        }
        return answer;
    }

    /** 下一行开始时间，用于做“唱完这一句”的进度动画；没有下一行返回 -1。 */
    public static long nextTime(List<Line> lines, int index) {
        if (lines == null || index < 0 || index + 1 >= lines.size()) return -1;
        return lines.get(index + 1).time;
    }

    private static long parseLong(String s, long fallback) {
        try { return Long.parseLong(s); } catch (Exception e) { return fallback; }
    }

    private static long clamp(long v, long min, long max) { return v < min ? min : v > max ? max : v; }
}
