package com.xigaigai.syncglance.display;

/**
 * 车机计时器指令（对应 {@code SetMediaClockTimer} 的语义）。
 *
 * <h3>为什么是「指令」而不是「读数」</h3>
 * SYNC 的计时器是 **App 下指令、车机自己走表**：
 * App 只告诉车机「从 3:20 开始正着数」或者「停在 16:21」，
 * 之后车机内部自己维护，App 不再每秒推送。
 *
 * 所以预览器也不能收「算好的秒数字符串」，否则它就跟 App 的刷新频率绑在一起了。
 * 它应该收这条指令，自己模拟一台车机计时器去走 —— 这样它与
 * 「Show 节流（1 秒）」「歌词选句（按时间轴）」是**三条互不相干的时间线**。
 *
 * 时间基准用 {@link System#currentTimeMillis()}，与 StateRenderer 里的
 * timerStartMillis 保持同一套时钟。
 */
public final class ClockInstruction {

    public enum Mode {
        /** 该格什么都不显示 */
        NONE,
        /** 正计时：从 start 往上数 */
        COUNTUP,
        /** 倒计时：从 start 往下数，到 0 停 */
        COUNTDOWN,
        /** 定格：就显示 start（ETA 时刻、暂停中的计时器都用它） */
        PAUSE,
        /** 清空 */
        CLEAR
    }

    /** 车机时钟格是 mm:ss，没有小时位；超过 59:59 车机会停格 */
    private static final long MAX_SECONDS = 3599;

    public final Mode mode;
    /** 起始时间的小时位。车机没有小时位，这里恒为 0（ETA 借位时也放 0） */
    public final int h;
    /** 分钟位。ETA 场景下这里借用来放「小时」 */
    public final int m;
    /** 秒位。ETA 场景下这里借用来放「分钟」 */
    public final int s;
    /** 指令下发时刻，用来推算已经走了多少秒 */
    public final long issuedAtMs;

    private ClockInstruction(Mode mode, int h, int m, int s, long issuedAtMs) {
        this.mode = mode;
        this.h = h;
        this.m = m;
        this.s = s;
        this.issuedAtMs = issuedAtMs;
    }

    // ---------------- 工厂 ----------------

    public static ClockInstruction of(Mode mode, int h, int m, int s, long issuedAtMs) {
        return new ClockInstruction(mode, h, m, s, issuedAtMs);
    }

    public static ClockInstruction none() {
        return new ClockInstruction(Mode.NONE, 0, 0, 0, System.currentTimeMillis());
    }

    public static ClockInstruction clear() {
        return new ClockInstruction(Mode.CLEAR, 0, 0, 0, System.currentTimeMillis());
    }

    /** 正计时（mm:ss 语义） */
    public static ClockInstruction countUp(int h, int m, int s) {
        return new ClockInstruction(Mode.COUNTUP, h, m, s, System.currentTimeMillis());
    }

    /** 倒计时（mm:ss 语义） */
    public static ClockInstruction countDown(int h, int m, int s) {
        return new ClockInstruction(Mode.COUNTDOWN, h, m, s, System.currentTimeMillis());
    }

    /**
     * 定格显示某个时刻。
     * <p>ETA 用法：{@code pauseAt(0, 16, 21)} 在车机上会原样显示成 {@code 16:21}
     * （因为那一格就是 mm:ss，16 被当成"分钟"渲染）。
     */
    public static ClockInstruction pauseAt(int h, int m, int s) {
        return new ClockInstruction(Mode.PAUSE, h, m, s, System.currentTimeMillis());
    }

    // ---------------- 推算 ----------------

    /** 当前应显示的秒数；不该显示时返回 -1 */
    public long valueSeconds() {
        long start = h * 3600L + m * 60L + s;
        if (mode == Mode.PAUSE) return clamp(start);
        if (mode == Mode.COUNTUP || mode == Mode.COUNTDOWN) {
            long elapsed = (System.currentTimeMillis() - issuedAtMs) / 1000L;
            if (elapsed < 0) elapsed = 0;
            if (mode == Mode.COUNTUP) return clamp(start + elapsed);
            return Math.max(0, Math.min(MAX_SECONDS, start - elapsed));
        }
        return -1;
    }

    private static long clamp(long v) {
        if (v < 0) return 0;
        return Math.min(v, MAX_SECONDS);
    }

    /**
     * 车机上那一格此刻显示的文本，例如 {@code 03:21} / {@code 16:21}。
     * 不该显示时返回空串。
     */
    public String displayText() {
        long v = valueSeconds();
        if (v < 0) return "";
        long mm = v / 60;
        long ss = v % 60;
        return String.format(java.util.Locale.US, "%02d:%02d", mm, ss);
    }

    /** 这条指令是否需要一个走表的刷新（PAUSE/NONE/CLEAR 不用） */
    public boolean isRunning() {
        return mode == Mode.COUNTUP || mode == Mode.COUNTDOWN;
    }

    /** 与另一条指令是否"同一个显示状态"（用来决定预览要不要重画） */
    public boolean sameAs(ClockInstruction o) {
        return o != null && o.mode == mode && o.h == h && o.m == m && o.s == s;
    }

    @Override
    public String toString() {
        return mode + "(" + h + "," + m + "," + s + ")";
    }
}
