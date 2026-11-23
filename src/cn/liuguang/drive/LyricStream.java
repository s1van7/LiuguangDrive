package cn.liuguang.drive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 蓝牙歌词时间轴。
 *
 * 蓝牙协议本身只传"当前在播什么"，不传歌词。国内几家播放器（QQ 音乐、网易云、
 * 咪咕、汽水）的"车载歌词"实际做法是：把当前这一句写进媒体通知或
 * MediaMetadata 的 DISPLAY_SUBTITLE，播放到下一句时再改一次。
 *
 * 所以这里做的是"边听边拼"：每看到一句新词，就把当时的播放位置记成它的时间戳，
 * 拼成一条时间轴；同一句再次出现（合唱、重复段落）只要间隔超过 4 秒就记成新的一行，
 * 间隔很近则认为是同一次出现、只做时间修正。整首歌放完一遍后，时间轴就是完整的，
 * 之后由 LyricCache 落盘，下次再放这首歌立刻有全曲歌词。
 *
 * 纯 Java，无 Android 依赖，可直接在桌面 JVM 上测。
 */
public final class LyricStream {
    public static final int MAX_LINES = 600;
    /** 同一句歌词两次出现的判定窗口：小于它算同一次（位置抖动），大于它算重复段落。 */
    static final long SAME_OCCURRENCE_MS = 15000L;
    /** 同一句出现在比记录早这么多的位置，说明又从头放了一遍，旧时间轴该作废。 */
    static final long RESTART_GAP_MS = 45000L;

    private final List<LrcParser.Line> lines = new ArrayList<LrcParser.Line>();
    /** 上一次看到的歌词文本。播放器会反复上报同一句，靠它判断"这一句还没换"。 */
    private String lastText = "";

    public void clear() {
        lines.clear();
        lastText = "";
    }

    /** 用整份 LRC 覆盖时间轴（播放器内嵌歌词时走这条路）。 */
    public void load(List<LrcParser.Line> source) {
        lines.clear();
        lastText = "";
        if (source == null) return;
        for (int i = 0; i < source.size() && lines.size() < MAX_LINES; i++) {
            LrcParser.Line line = source.get(i);
            if (line == null) continue;
            String text = clean(line.text);
            if (text.isEmpty()) continue;
            lines.add(new LrcParser.Line(Math.max(0L, line.time), text));
        }
        sort();
    }

    /** 记录一句当前歌词，返回时间轴是否变化（变化才需要重绘）。 */
    public boolean observe(String raw, long positionMs) {
        String text = clean(raw);
        if (text.isEmpty()) return false;
        // 还是同一句：说明播放器只是重发了一遍通知，不是新的一句歌词
        if (text.equals(lastText)) return false;
        lastText = text;
        long pos = Math.max(0L, positionMs);
        int existing = indexOf(text);
        if (existing >= 0) {
            long time = lines.get(existing).time;
            if (Math.abs(time - pos) <= SAME_OCCURRENCE_MS) return false;
            // 最后一句会一直显示到歌曲结束，位置变远不代表又来了一遍
            if (existing == lines.size() - 1 && pos >= time) return false;
            // 这一句出现在比记录中早很多的位置：多半是又从头放了一遍。
            // 旧时间轴对不上，继续用会出现同一句显示两次，直接按这一遍重建。
            if (lines.size() >= 3 && pos + RESTART_GAP_MS < time) {
                lines.clear();
                lines.add(new LrcParser.Line(pos, text));
                return true;
            }
        }
        if (lines.size() >= MAX_LINES) return false;
        lines.add(new LrcParser.Line(pos, text));
        sort();
        return true;
    }

    private int indexOf(String text) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).text.equals(text)) return i;
        }
        return -1;
    }

    /** 同一句可能出现多次（副歌），返回离给定位置最近的那次的时间；没有返回 -1。 */
    public long nearestTime(String text, long positionMs) {
        String value = clean(text);
        if (value.isEmpty()) return -1;
        long best = -1;
        for (int i = 0; i < lines.size(); i++) {
            LrcParser.Line line = lines.get(i);
            if (!line.text.equals(value)) continue;
            if (best < 0 || Math.abs(line.time - positionMs) < Math.abs(best - positionMs)) best = line.time;
        }
        return best;
    }

    public List<LrcParser.Line> lines() {
        return Collections.unmodifiableList(lines);
    }

    public int size() { return lines.size(); }

    public boolean isEmpty() { return lines.isEmpty(); }

    public int indexAt(long positionMs) { return LrcParser.activeIndex(lines, positionMs); }

    /** 歌词里最早的一句的时间，用来判断"前奏还没唱"还是"根本没歌词"。 */
    public long firstTime() { return lines.isEmpty() ? -1 : lines.get(0).time; }

    private void sort() {
        Collections.sort(lines, new Comparator<LrcParser.Line>() {
            @Override public int compare(LrcParser.Line a, LrcParser.Line b) {
                return Long.compare(a.time, b.time);
            }
        });
    }

    /** 去掉首尾空白、行首项目符号与连续空白；过长的行直接截断，避免撑爆一行。 */
    static String clean(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(raw.length());
        boolean space = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c == ' ') { space = out.length() > 0; continue; }
            if (c < 0x20) continue;
            if (space) { out.append(' '); space = false; }
            out.append(c);
        }
        String text = out.toString().trim();
        while (text.length() > 0) {
            char c = text.charAt(0);
            if (c == '•' || c == '·' || c == '●' || c == '—' || c == '-' || c == '>') text = text.substring(1).trim();
            else break;
        }
        if (text.length() > 200) text = text.substring(0, 200);
        return text;
    }
}
