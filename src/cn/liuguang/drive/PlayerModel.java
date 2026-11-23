package cn.liuguang.drive;

import android.graphics.Bitmap;
import android.media.session.PlaybackState;

import java.util.Collections;
import java.util.List;

/**
 * 界面只读的播放状态模型。所有字段由 MainActivity 在媒体回调 / 定时器中写入，
 * PlayerView 只读，避免绘制与业务逻辑互相踩状态。
 */
final class PlayerModel {
    /** 歌词来源，用于在界面和诊断页说明"这句歌词是从哪来的"。 */
    static final int LYRIC_NONE = 0;
    static final int LYRIC_EMBEDDED = 1;   // 播放器把整份 LRC 塞进了媒体元数据
    static final int LYRIC_SUBTITLE = 2;   // 播放器把当前句放在 DISPLAY_SUBTITLE
    static final int LYRIC_NOTICE = 3;     // 从媒体通知的文本里逐句采集
    static final int LYRIC_CACHE = 4;      // 本机上次播放时记录下的时间轴

    String title = "";
    String artist = "";
    String album = "";
    String sourceLabel = "";
    String sourcePkg = "";
    String trackKey = "";
    String status = "";
    String hint = "";

    long duration;
    long positionMs;

    boolean playing;
    boolean hasSession;
    boolean receivedPosition;
    boolean buffering;
    int playbackState = PlaybackState.STATE_NONE;
    int lyricSource = LYRIC_NONE;
    int lyricLines;

    Bitmap artwork;
    List<LrcParser.Line> lyrics = Collections.emptyList();

    boolean empty() { return title == null || title.isEmpty(); }
}
