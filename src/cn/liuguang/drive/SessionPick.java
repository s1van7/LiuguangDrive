package cn.liuguang.drive;

/**
 * 手机上可能同时挂着好几个播放器的会话（QQ音乐暂停着、网易云在放、汽水音乐刚打开）。
 * 车机屏幕只有一个，得挑一个最值得显示的。
 *
 * 规则参考系统媒体面板与几个开源媒体控制器的做法，按优先级：
 * 1. 当前绑定的那个还在播放 → 不动，避免两个播放器来回抢屏；
 * 2. 任意一个正在播放的；
 * 3. 最近更新过状态的暂停 / 缓冲中的（用户刚按了暂停，界面不该立刻空掉）；
 * 4. 都没有，就取列表第一个。
 *
 * 纯 Java、不依赖 Android，可以在桌面 JVM 上直接测。
 */
final class SessionPick {
    /** 与 PlaybackState.STATE_PAUSED / STATE_PLAYING / STATE_BUFFERING 的取值一致。 */
    static final int PAUSED = 2;
    static final int PLAYING = 3;
    static final int BUFFERING = 6;

    static final int NONE = -1;

    private SessionPick() { }

    /**
     * @param states    每个会话的 PlaybackState.getState()
     * @param updatedAt 每个会话 PlaybackState.getLastPositionUpdateTime()
     * @param current   当前绑定的下标，没有传 {@link #NONE}
     * @return 选中的下标；一个都没有时返回 {@link #NONE}
     */
    static int choose(int[] states, long[] updatedAt, int current) {
        if (states == null || states.length == 0) return NONE;
        int count = states.length;

        if (current >= 0 && current < count && states[current] == PLAYING) return current;
        for (int i = 0; i < count; i++) {
            if (states[i] == PLAYING) return i;
        }
        // 缓冲中的多半是用户刚点的那个，比"暂停着"的更值得显示
        for (int i = 0; i < count; i++) {
            if (states[i] == BUFFERING) return i;
        }

        int best = NONE;
        long bestTime = Long.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            if (states[i] != PAUSED) continue;
            long time = updatedAt == null || i >= updatedAt.length ? 0L : updatedAt[i];
            if (time > bestTime) { bestTime = time; best = i; }
        }
        return best != NONE ? best : 0;
    }

    /** 状态值是否算"在放"（含缓冲，界面应显示为播放中）。 */
    static boolean isActive(int state) {
        return state == PLAYING || state == BUFFERING;
    }
}
