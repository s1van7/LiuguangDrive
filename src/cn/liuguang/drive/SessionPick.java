package cn.liuguang.drive;

/**
 * 手机上可能同时挂着好几个播放器的会话。车机屏幕只有一个，必须稳定地挑选来源。
 *
 * 优先级：
 * 1. 当前绑定的会话仍在播放：保持不变，避免多个播放器同时播放时画面抖动；
 * 2. 其他正在播放的会话：选择最近更新状态的那个；
 * 3. 缓冲中的会话：选择最近更新状态的那个；
 * 4. 暂停中的会话：选择最近更新状态的那个；
 * 5. 都没有可识别状态时，退回列表第一个。
 *
 * 纯 Java，不依赖 Android，可以在桌面 JVM 上直接测。
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

        int playing = newest(states, updatedAt, PLAYING);
        if (playing != NONE) return playing;

        int buffering = newest(states, updatedAt, BUFFERING);
        if (buffering != NONE) return buffering;

        int paused = newest(states, updatedAt, PAUSED);
        return paused != NONE ? paused : 0;
    }

    /** 在指定状态的会话中选择最近更新的一个；时间相同时保持列表顺序。 */
    private static int newest(int[] states, long[] updatedAt, int wantedState) {
        int best = NONE;
        long bestTime = Long.MIN_VALUE;
        for (int i = 0; i < states.length; i++) {
            if (states[i] != wantedState) continue;
            long time = updatedAt == null || i >= updatedAt.length ? 0L : updatedAt[i];
            if (best == NONE || time > bestTime) {
                best = i;
                bestTime = time;
            }
        }
        return best;
    }

    /** 状态值是否算活跃（播放或缓冲）。 */
    static boolean isActive(int state) {
        return state == PLAYING || state == BUFFERING;
    }
}
