package com.xigaigai.syncglance.display;

import com.smartdevicelink.proxy.rpc.SetMediaClockTimer;
import com.smartdevicelink.proxy.rpc.StartTime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 时钟格「这一格现在该显示什么」的<b>唯一决策点</b>。
 *
 * <h3>职责边界</h3>
 * 纯函数、零 Android 依赖：输入是一份快照（计时器状态 + 导航 ETA + 歌曲位置），
 * 输出是「要发的指令序列 + 预览指令 + 指纹」。发不发、怎么发由调用方
 * （{@link StateRenderer} → {@code SyncPlayCore} → {@code ClockDispatcher}）决定。
 *
 * <h3>优先级（用户 2026-11-21 确认，2026-11-27 补充到期语义）</h3>
 * <pre>
 *   ① 用户计时器（<b>未清零</b>即占屏，含暂停态）
 *   ② 导航 ETA（借 mm:ss 位显示到达时刻）
 *   ③ 歌曲播放时间（在播 COUNTUP，暂停则定格）
 *   ④ 都没有 → CLEAR 收掉
 * </pre>
 *
 * <h3>指纹</h3>
 * 每个 {@link Plan} 带一个字符串指纹。指纹相同 = 「车机那边已经是对的了，
 * 不用再发」，用它替代以前散在 StateRenderer 里的 5 个互相打架的标记
 * （timerClockKey / songClockStarted / lastSongKey / lastClockTextSent / lastEtaSent）。
 *
 * <p>⚠ 指纹里<b>不</b>放「走表读数」：车机自己走表，读数每秒变很正常，
 * 放进去会让每帧都重发。跨失焦重投由调用方清指纹（{@code invalidateClockCache}）。</p>
 */
public final class ClockPlanner {

    /** 这一格现在归谁 */
    public enum Source { TIMER, ETA, SONG, EMPTY }

    /** 决策输入快照 */
    public static final class Input {
        // ---- 用户计时器 ----
        /** 未清零即为 true（含暂停态）——「占屏」的判据 */
        public boolean timerActive;
        /** 真正在走秒 */
        public boolean timerRunning;
        /** 倒计时方向 */
        public boolean timerCountdown;
        public int timerH, timerM, timerS;
        /** 计时起点（RESUME 时会被平移） */
        public long timerStartMs;
        /** 暂停时刻（0 = 没在暂停） */
        public long timerPausedMs;

        // ---- 导航 ETA ----
        /** {@code "HH:mm"} 形式，null/空 = 没有导航 */
        public String etaText;

        // ---- 歌曲 ----
        public boolean songPresent;
        public boolean songPlaying;
        public long songPosMs;
        public long songDurationMs;
        public String songTitle = "";
        public String songArtist = "";
        /** 是否给正计时带 endTime（= 歌曲总时长）。默认关，实车验证后再开 */
        public boolean songEndTimeEnabled;

        public long nowMs;
    }

    /** 决策结果 */
    public static final class Plan {
        public final Source source;
        /** 按顺序要发的指令（可能为空时也无所谓，调用方靠指纹去重） */
        public final List<SetMediaClockTimer> rpcs;
        /** 预览器用的指令（与实车同源，避免预览/实车分叉） */
        public final ClockInstruction preview;
        /** 见类注释 */
        public final String fingerprint;
        /** 人话，进日志 */
        public final String reason;

        private Plan(Source source, List<SetMediaClockTimer> rpcs,
                     ClockInstruction preview, String fingerprint, String reason) {
            this.source = source;
            this.rpcs = rpcs;
            this.preview = preview;
            this.fingerprint = fingerprint;
            this.reason = reason;
        }

        public boolean occupying() {
            return source == Source.TIMER;
        }
    }

    private ClockPlanner() { }

    // ---------------- 决策 ----------------

    public static Plan plan(Input in) {
        long now = in.nowMs > 0 ? in.nowMs : System.currentTimeMillis();

        // ① 用户计时器：未清零就占着这一格，ETA / 播放时间一律让位
        if (in.timerActive) {
            if (in.timerRunning) {
                long v = timerValueSeconds(in);
                int vh = (int) (v / 3600), vm = (int) ((v / 60) % 60), vs = (int) (v % 60);
                boolean down = in.timerCountdown;
                // ★ startTime 用「当前读数」而不是「起始值」：首次开始两者相等，
                //   跨失焦重投时用读数才不会让车机重新从起点走（会慢一截）。
                SetMediaClockTimer rpc = down
                        ? ClockRpc.countDown(vh, vm, vs, null)
                        : ClockRpc.countUp(vh, vm, vs, null);
                long anchor = in.timerStartMs > 0 ? in.timerStartMs : now;
                // 指纹按「起始值 + 起点」而不是读数 —— 车机自己走表，读数变化不需要重发
                String fp = "timer|run|" + (down ? "d" : "u")
                        + "|" + in.timerH + ":" + in.timerM + ":" + in.timerS + "@" + in.timerStartMs;
                return new Plan(Source.TIMER, one(rpc),
                        ClockInstruction.of(down ? ClockInstruction.Mode.COUNTDOWN
                                : ClockInstruction.Mode.COUNTUP,
                                in.timerH, in.timerM, in.timerS, anchor),
                        fp, (down ? "倒计时走表 " : "正计时走表 ") + fmt(vh, vm, vs));
            }
            // 暂停：两步法定格在暂停那一刻的读数
            long v = timerValueSeconds(in);
            int vh = (int) (v / 3600), vm = (int) ((v / 60) % 60), vs = (int) (v % 60);
            /*
             * 定格指令的 issuedAtMs 传 0：PAUSE 的读数只由 h/m/s 决定，与时间无关。
             * 传 now 会让 StateRenderer 的 reuseClock 每秒都判定"变了"而重建对象。
             */
            return new Plan(Source.TIMER, pair(ClockRpc.freezeDuration(vh, vm, vs)),
                    ClockInstruction.of(ClockInstruction.Mode.PAUSE, vh, vm, vs, 0),
                    "timer|pause|" + v, "计时器暂停定格 " + fmt(vh, vm, vs));
        }

        // ② 导航 ETA：借 mm:ss 位显示「几点到」
        if (in.etaText != null && in.etaText.indexOf(':') > 0) {
            int hh = 0, mm = 0;
            try {
                int c = in.etaText.indexOf(':');
                hh = Integer.parseInt(in.etaText.substring(0, c).trim());
                mm = Integer.parseInt(in.etaText.substring(c + 1).trim());
            } catch (Exception ignored) {
                // 解析失败按 00:00 处理：至少不会把上一次的计时器读数留在屏上
            }
            return new Plan(Source.ETA, pair(ClockRpc.freezeLiteral(hh, mm)),
                    ClockInstruction.of(ClockInstruction.Mode.PAUSE, 0, hh, mm, 0),
                    "eta|" + in.etaText, "导航到达时刻 " + in.etaText + "（借 mm:ss 位）");
        }

        // ③ 歌曲播放时间
        if (in.songPresent) {
            long pos = Math.max(0, in.songPosMs);
            int ph = (int) (pos / 3_600_000);
            int pm = (int) ((pos / 60_000) % 60);
            int ps = (int) ((pos / 1000) % 60);

            if (in.songPlaying) {
                StartTime end = null;
                /*
                 * endTime = 歌曲总时长。只在「总长有效、且明显大于当前位置」时才带：
                 * 很多播放器不给 DURATION（=0），而「进度已经贴近结尾」时带上
                 * endTime 可能小于 startTime，车机有理由直接拒 —— 一旦被拒，
                 * SdlService 会关掉整个特性，那就连播放时间都不显示了。
                 */
                if (in.songEndTimeEnabled && in.songDurationMs > 1000
                        && in.songDurationMs - 1000 > pos) {
                    end = ClockRpc.fromMillis(in.songDurationMs);
                }
                String fp = "song|play|" + in.songTitle + "|" + in.songArtist
                        + "|end=" + (end == null ? "-"
                                : end.getHours() + ":" + end.getMinutes() + ":" + end.getSeconds());
                return new Plan(Source.SONG, one(ClockRpc.countUp(ph, pm, ps, end)),
                        // 歌曲预览从「这次采样时刻」起表：位置心跳每 900ms 采样一次，
                        // 用 now 做 anchor 才能让预览跟着真实播放位置走。
                        ClockInstruction.of(ClockInstruction.Mode.COUNTUP, ph, pm, ps, now),
                        fp,
                        "播放时间 " + fmt(ph, pm, ps) + (end == null ? "" : " / 总长 " + fmt(
                                end.getHours(), end.getMinutes(), end.getSeconds())));
            }
            // 暂停：定格在最后已知位置（车机不会自己停）
            String fp = "song|pause|" + pos + "|" + in.songTitle;
            return new Plan(Source.SONG, pair(ClockRpc.freezeDuration(ph, pm, ps)),
                    ClockInstruction.of(ClockInstruction.Mode.PAUSE, ph, pm, ps, 0),
                    fp, "播放暂停定格 " + fmt(ph, pm, ps));
        }

        // ④ 什么都不该显示 → 收掉
        return new Plan(Source.EMPTY, one(ClockRpc.clear()), ClockInstruction.none(),
                "empty", "无导航 / 无播放 → 清空");
    }

    // ---------------- 读数 ----------------

    /**
     * 计时器当前读数（正计时 = 已过秒数，倒计时 = 剩余秒数）。
     *
     * <p>倒计时走完返回 0 —— 此时<b>不再自动清零</b>：屏幕上保留 {@code 00:00}，
     * 只有用户手动点「计时」键才发 CLEAR（用户 2026-11-27 的决定）。</p>
     */
    public static long timerValueSeconds(Input in) {
        if (in.timerStartMs <= 0) return 0;
        long ref = in.timerPausedMs > 0
                ? in.timerPausedMs
                : (in.nowMs > 0 ? in.nowMs : System.currentTimeMillis());
        long elapsed = (ref - in.timerStartMs) / 1000;
        if (elapsed < 0) elapsed = 0;
        long total = in.timerH * 3600L + in.timerM * 60L + in.timerS;
        if (in.timerCountdown) return Math.max(0, total - elapsed);
        return elapsed;
    }

    // ---------------- 小工具 ----------------

    private static List<SetMediaClockTimer> one(SetMediaClockTimer rpc) {
        return Collections.singletonList(rpc);
    }

    private static List<SetMediaClockTimer> pair(SetMediaClockTimer[] two) {
        List<SetMediaClockTimer> out = new ArrayList<>(2);
        out.add(two[0]);
        out.add(two[1]);
        return out;
    }

    /** 日志/指纹用的统一格式：有小时才画小时段，与车机一致 */
    static String fmt(long h, long m, long s) {
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }
}
