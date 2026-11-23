package cn.liuguang.drive;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 纯 JVM 自检，不依赖 Android。覆盖 LRC 解析、歌词时间轴拼接、歌词判定与曲目键。
 * 运行：bash tests/run.sh
 */
public class SelfTest {
    private static int passed;

    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError("FAIL " + description);
        passed++;
        System.out.println("PASS " + description);
    }

    private static void checkEquals(Object expected, Object actual, String description) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) throw new AssertionError("FAIL " + description + " 期望=" + expected + " 实际=" + actual);
        passed++;
        System.out.println("PASS " + description);
    }

    public static void main(String[] args) {
        testLrcParser();
        testLyricStream();
        testLyricProbe();
        testKeys();
        testSessionPick();
        System.out.println("\n全部通过：" + passed + " 项");
    }

    private static void testSessionPick() {
        // 只有一个会话
        checkEquals(0, SessionPick.choose(new int[]{SessionPick.PLAYING}, new long[]{1L}, SessionPick.NONE),
                "只有一个在放的会话");
        checkEquals(SessionPick.NONE, SessionPick.choose(new int[0], new long[0], SessionPick.NONE),
                "没有会话时返回 NONE");

        // 有在放的，优先它，而不是列表第一个；多个播放会话选择最近更新的
        int[] states = {SessionPick.PAUSED, SessionPick.PLAYING, SessionPick.PAUSED};
        long[] times = {900L, 100L, 800L};
        checkEquals(1, SessionPick.choose(states, times, SessionPick.NONE), "优先选正在播放的会话");
        checkEquals(2, SessionPick.choose(
                new int[]{SessionPick.PLAYING, SessionPick.PLAYING},
                new long[]{100L, 900L}, SessionPick.NONE), "无当前会话时选择最近更新的播放会话");

        // 当前这个还在放，就别切走（两个播放器同时放时不来回跳）
        int[] bothPlaying = {SessionPick.PLAYING, SessionPick.PLAYING};
        checkEquals(0, SessionPick.choose(bothPlaying, new long[]{100L, 900L}, 0),
                "两个都在放时保持当前会话");

        // 都在暂停：选最近更新过状态的那个（用户刚按了暂停，界面不该空掉）
        int[] paused = {SessionPick.PAUSED, SessionPick.PAUSED, SessionPick.PAUSED};
        checkEquals(2, SessionPick.choose(paused, new long[]{100L, 300L, 800L}, SessionPick.NONE),
                "都暂停时选最近更新的会话");

        // 缓冲中的也算活跃；多个缓冲会话同样按最近更新时间选择
        int[] buffering = {SessionPick.PAUSED, SessionPick.BUFFERING};
        checkEquals(1, SessionPick.choose(buffering, new long[]{900L, 100L}, SessionPick.NONE),
                "缓冲中的会话也算活跃");
        checkEquals(2, SessionPick.choose(
                new int[]{SessionPick.BUFFERING, SessionPick.BUFFERING},
                new long[]{100L, 900L}, SessionPick.NONE), "多个缓冲会话选择最近更新的");

        // 全是 STOPPED/NONE：退回第一个，保证界面有东西可显示
        int[] idle = {0, 0, 0};
        checkEquals(0, SessionPick.choose(idle, new long[]{0L, 0L, 0L}, SessionPick.NONE),
                "全部空闲时退回第一个会话");

        check(SessionPick.isActive(SessionPick.PLAYING), "播放算活跃");
        check(SessionPick.isActive(SessionPick.BUFFERING), "缓冲算活跃");
        check(!SessionPick.isActive(SessionPick.PAUSED), "暂停不算活跃");
    }

    private static void testLrcParser() {
        List<LrcParser.Line> lines = LrcParser.parse(
                "[ti:沿海公路]\n[ar:流光]\n[00:01.00]第一句\n[00:05.50]第二句\n[00:09.25]第三句");
        checkEquals(3, lines.size(), "解析出三行歌词");
        checkEquals(1000L, lines.get(0).time, "毫秒时间戳");
        checkEquals(5500L, lines.get(1).time, "百分秒时间戳");
        checkEquals(9250L, lines.get(2).time, "两位小数时间戳");

        checkEquals(1500L, LrcParser.parse("[00:01:50]冒号分隔").get(0).time, "冒号分隔按 1.50 秒解析");
        checkEquals(2, LrcParser.parse("[00:01.00][00:03.00]重复句").size(), "一行多时间标签");
        checkEquals(1, LrcParser.parse("[00:01.00]同一句\n[00:01.00]同一句").size(), "同一时间同一句去重");

        List<LrcParser.Line> dirty = LrcParser.parse("[00:99.00]无效时间戳不能变成正文");
        check(dirty.isEmpty(), "无效时间戳不会把时间戳当歌词");

        // LRC 规范：offset 为正表示歌词提前出现，所以 time = time - offset
        checkEquals(1500L, LrcParser.parse("[offset:500]\n[00:02.00]提前半秒").get(0).time, "正 offset 让歌词提前");
        checkEquals(2500L, LrcParser.parse("[offset:-500]\n[00:02.00]推后半秒").get(0).time, "负 offset 让歌词延后");

        List<LrcParser.Line> sorted = LrcParser.parse("[00:09.00]后\n[00:01.00]前");
        checkEquals("前", sorted.get(0).text, "按时间排序");

        checkEquals(-1, LrcParser.activeIndex(lines, 0), "早于第一行时返回 -1（界面自行钳到第一行）");
        checkEquals(1, LrcParser.activeIndex(lines, 6000), "定位到第二行");
        checkEquals(9250L, LrcParser.nextTime(lines, 1), "下一行时间");
        checkEquals(-1L, LrcParser.nextTime(lines, 2), "最后一行没有下一行");
        checkEquals(0.0f, LrcParser.lineProgress(lines, 1, 5500L, 12000L), "当前句开始时进度为零");
        checkEquals(0.5f, LrcParser.lineProgress(lines, 1, 7375L, 12000L), "当前句中途进度正确");
        checkEquals(1.0f, LrcParser.lineProgress(lines, 1, 10000L, 12000L), "超过下一句时进度封顶");
        checkEquals(0.5f, LrcParser.lineProgress(lines, 2, 11250L, 13250L), "最后一句使用歌曲时长计算进度");
    }

    private static void testLyricStream() {
        LyricStream stream = new LyricStream();
        check(stream.observe("第一句", 1000), "记录第一句");
        check(!stream.observe("第一句", 1600), "同一句重复上报不新增");
        check(stream.observe("第二句", 5000), "记录第二句");
        check(stream.observe("第三句", 9000), "记录第三句");
        checkEquals(3, stream.size(), "三句歌词");
        checkEquals(1, stream.indexAt(5200), "按时间定位到第二句");

        LyricStream held = new LyricStream();
        held.observe("长句歌词慢慢唱", 1000);
        held.observe("长句歌词慢慢唱", 6000);
        held.observe("长句歌词慢慢唱", 11000);
        checkEquals(1, held.size(), "同一句持续上报只记一次，不会重复显示");

        // 位置抖动超过 4 秒，说明是另一遍副歌，应记成新的一行
        check(stream.observe("第一句", 60000), "副歌重复出现记为新的一行");
        checkEquals(4, stream.size(), "重复段落单独成行");

        check(stream.observe("  第四句  ", 12000), "去掉首尾空白后记录");
        checkEquals("第四句", stream.lines().get(stream.indexAt(12000)).text, "记录的是清理后的文本");
        checkEquals(12000L, stream.lines().get(3).time, "新句子按时间插入到正确位置");

        check(!stream.observe("", 13000), "空歌词忽略");
        check(!stream.observe(null, 14000), "null 歌词忽略");
        check(!stream.observe("   ", 15000), "纯空白忽略");

        stream.clear();
        check(stream.isEmpty(), "清空后为空");

        // 整份 LRC 覆盖
        LyricStream full = new LyricStream();
        full.load(LrcParser.parse("[00:00.00]词：某人\n[00:04.00]第一句\n[00:08.00]第二句"));
        checkEquals(3, full.size(), "整份歌词覆盖时间轴");
        check(!full.observe("第一句", 4100), "整份歌词已有时不重复添加");
        checkEquals("第一句", full.lines().get(1).text, "整份歌词内容正确");

        // 最后一句会一直显示到结束，位置变远不能算新的一句
        LyricStream tail = new LyricStream();
        tail.load(LrcParser.parse("[00:10.00]第一句\n[00:30.00]第二句\n[00:50.00]最后一句"));
        tail.observe("最后一句", 210000);
        checkEquals(3, tail.size(), "最后一句显示到歌曲结束也不会重复");

        // 从头再放一遍：旧时间轴作废，不能出现同一句两次
        LyricStream replay = new LyricStream();
        replay.load(LrcParser.parse("[02:30.00]作词：单色凌\n[02:56.00]作曲：单色凌\n[03:22.00]把忙碌留在昨天的路口"));
        replay.observe("作词：单色凌", 1000);
        checkEquals(1, replay.size(), "从头重放时丢弃对不上的旧时间轴");
        checkEquals(1000L, replay.lines().get(0).time, "重建后以当前播放位置为准");

        // 小幅回拖不算重放，时间轴保留
        LyricStream seek = new LyricStream();
        seek.load(LrcParser.parse("[00:10.00]第一句\n[00:30.00]第二句\n[00:50.00]第三句"));
        seek.observe("第二句", 20000);
        checkEquals(3, seek.size(), "回拖十秒不重建时间轴");

        // 清理规则
        checkEquals("你好世界", LyricStream.clean("• 你好世界"), "去掉行首项目符号");
        checkEquals("a b", LyricStream.clean("a\n\nb"), "换行折叠成空格");
        check(LyricStream.clean("x".concat(repeat("y", 400))).length() <= 200, "超长行被截断");
    }

    private static void testLyricProbe() {
        String title = "沿海公路", artist = "流光", album = "日落之后";

        check(LyricProbe.looksLikeLyric("沿着海岸线慢慢向前", title, artist, album), "长句判定为歌词");
        check(LyricProbe.looksLikeLyric("沿着海岸线", title, artist, album), "五字短句也判定为歌词");
        check(LyricProbe.looksLikeLyric("作词：单色凌", title, artist, album), "制作信息判定为歌词");
        check(LyricProbe.looksLikeLyric("我的思念，似六月里的小雨", title, artist, album), "带标点的短句判定为歌词");
        checkEquals(LyricProbe.WEAK, LyricProbe.score("我的思念", title, artist, album), "四字无标点算弱信号");
        check(!LyricProbe.looksLikeLyric("我的思念", title, artist, album), "弱信号默认不当歌词");
        check("我的思念".equals(LyricProbe.pickLine(Arrays.asList("我的思念"), title, artist, album, true)),
                "已经确认是歌词流后接受短句");

        check(!LyricProbe.looksLikeLyric("沿海公路", title, artist, album), "歌名不是歌词");
        check(!LyricProbe.looksLikeLyric("流光", title, artist, album), "歌手不是歌词");
        check(!LyricProbe.looksLikeLyric("日落之后", title, artist, album), "专辑不是歌词");
        check(!LyricProbe.looksLikeLyric("流光 - 日落之后", title, artist, album), "歌手加专辑不是歌词");
        check(!LyricProbe.looksLikeLyric("未知歌手", title, artist, album), "未知歌手不是歌词");
        check(!LyricProbe.looksLikeLyric("正在播放", title, artist, album), "状态文字不是歌词");
        check(!LyricProbe.looksLikeLyric("12345", title, artist, album), "纯数字不是歌词");

        check(LyricProbe.isLrc("[00:01.00]第一句\n[00:02.00]第二句"), "识别整份 LRC");
        check(!LyricProbe.isLrc("只有一句歌词，没有时间戳"), "单句不是 LRC");

        List<String> candidates = Arrays.asList(
                "流光 - 日落之后",
                "沿着海岸线慢慢向前",
                "[00:01.00]a\n[00:02.00]b");
        checkEquals("[00:01.00]a\n[00:02.00]b", LyricProbe.pickLrc(candidates), "优先取整份 LRC");
        checkEquals("沿着海岸线慢慢向前", LyricProbe.pickLine(candidates, title, artist, album, false), "跳过歌曲信息取歌词");

        List<String> onlyMeta = Arrays.asList("流光", "日落之后");
        check(LyricProbe.pickLine(onlyMeta, title, artist, album, true) == null, "全是歌曲信息时不给歌词");
    }

    private static void testKeys() {
        checkEquals("abc123", Keys.normalize(" A B C - 1 2 3 "), "归一化去掉空格与符号");
        checkEquals(Keys.trackKey("com.tencent.qqmusic", "晴天", "周杰伦"),
                Keys.trackKey("com.tencent.qqmusic", " 晴天 ", "周杰伦"), "同一首歌得到同一个键");
        check(!Keys.trackKey("com.tencent.qqmusic", "晴天", "周杰伦")
                .equals(Keys.trackKey("com.netease.cloudmusic", "晴天", "周杰伦")), "不同来源分开缓存");
    }

    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
