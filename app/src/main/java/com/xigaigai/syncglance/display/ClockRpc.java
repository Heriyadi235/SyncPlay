package com.xigaigai.syncglance.display;

import com.smartdevicelink.proxy.rpc.SetMediaClockTimer;
import com.smartdevicelink.proxy.rpc.StartTime;
import com.smartdevicelink.proxy.rpc.enums.AudioStreamingIndicator;

/**
 * {@code SetMediaClockTimer} 的<b>唯一</b>构造入口。
 *
 * <h3>三条铁律（都是实车踩出来的）</h3>
 * <ol>
 *   <li><b>{@code PAUSE} / {@code RESUME} / {@code CLEAR} 绝不能带 {@code startTime}
 *       或 {@code endTime}</b> —— 车机会直接拒绝（用户 2026-11-27 再次确认）。
 *       只有 {@code COUNTUP} / {@code COUNTDOWN} 才接受时间参数。</li>
 *   <li><b>把那一格定在某个读数必须两步法</b>：{@code COUNTUP(startTime)} 起表
 *       再 {@code PAUSE} 停住。单条 {@code PAUSE + startTime} 是无效的 ——
 *       车机会忽略时间参数，没有表在跑时还会直接 {@code REJECTED}
 *       （见 docs/design.md §2.8.1）。</li>
 *   <li><b>{@code hours} 不再夹成 0</b>：车机时钟格实测支持小时位
 *       （2026-11-27），{@code 1:23:45} 能正常渲染。</li>
 * </ol>
 *
 * <h3>为什么要有这个类</h3>
 * 以前「两步法」在 5 个地方各写一遍（计时器定格、ETA 定格、暂停定格……），
 * 任何一处忘了「PAUSE 不带时间」就会在实车上被拒，而且很难看出是谁发的。
 * 现在所有时钟 RPC 都必须从这里出去，规则只需要守住这一个文件。
 */
public final class ClockRpc {

    /**
     * 小时位软上限。
     *
     * <p>SDL 的 {@code StartTime.hours} 是 0~59。车机实测能到多少尚未逐一试过
     * （用户会另行摸索），这里先按协议上限夹住，避免发出 {@code INVALID_DATA}。</p>
     */
    public static final int HOURS_MAX = 59;

    private ClockRpc() { }

    // ---------------- 时间 ----------------

    /**
     * 把「h 时 m 分 s 秒」规范化成 {@link StartTime}。
     *
     * <p>分 / 秒一律 0~59；小时保留真实值（不再夹 0），只按协议上限 59 兜底。
     * 负数按 0 处理。</p>
     */
    public static StartTime time(int h, int m, int s) {
        long total = h * 3600L + m * 60L + s;
        if (total < 0) total = 0;
        long hh = total / 3600;
        if (hh > HOURS_MAX) hh = HOURS_MAX;
        return new StartTime((int) hh, (int) ((total / 60) % 60), (int) (total % 60));
    }

    /** 毫秒 → {@link StartTime}（歌曲总时长这类来源用它） */
    public static StartTime fromMillis(long ms) {
        long sec = Math.max(0, ms) / 1000;
        return time((int) (sec / 3600), (int) ((sec / 60) % 60), (int) (sec % 60));
    }

    // ---------------- 单条指令 ----------------

    /** 正计时。{@code endTime} 可为 null */
    public static SetMediaClockTimer countUp(int h, int m, int s, StartTime endTime) {
        return SetMediaClockTimer.countUpFromStartTime(
                time(h, m, s), endTime, AudioStreamingIndicator.PLAY);
    }

    /** 倒计时。{@code endTime} 可为 null */
    public static SetMediaClockTimer countDown(int h, int m, int s, StartTime endTime) {
        return SetMediaClockTimer.countDownFromStartTime(
                time(h, m, s), endTime, AudioStreamingIndicator.PLAY);
    }

    /** 暂停当前那块表 —— <b>不带任何时间参数</b> */
    public static SetMediaClockTimer pause() {
        return SetMediaClockTimer.pauseWithPlayPauseIndicator(AudioStreamingIndicator.PAUSE);
    }

    /** 继续当前那块表 —— <b>不带任何时间参数</b> */
    public static SetMediaClockTimer resume() {
        return SetMediaClockTimer.resumeWithPlayPauseIndicator(AudioStreamingIndicator.PLAY);
    }

    /** 清空那一格 —— <b>不带任何时间参数</b> */
    public static SetMediaClockTimer clear() {
        return SetMediaClockTimer.clearWithPlayPauseIndicator(AudioStreamingIndicator.PAUSE);
    }

    // ---------------- 两步法定格 ----------------

    /**
     * 把时钟格定死在某个<b>时长读数</b>（如「计时器走了 1:23:45」）。
     *
     * <pre>
     *   [0] COUNTUP(startTime=读数)   起一块表
     *   [1] PAUSE                     停住
     * </pre>
     * 两条必须按顺序发（见 {@link StateRenderer.Sender#sendSequential}）。
     *
     * @return 长度固定为 2 的数组
     */
    public static SetMediaClockTimer[] freezeDuration(int h, int m, int s) {
        return new SetMediaClockTimer[]{ countUp(h, m, s, null), pause() };
    }

    /**
     * 把时钟格定死在某个<b>字面时刻</b>（ETA 借位用）。
     *
     * <p>车机那格是 {@code mm:ss} 语义，但 ETA 要显示 {@code 18:11}：
     * 填 {@code (minutes=18, seconds=11)} 就渲染成 {@code 18:11}。
     * 所以这里<b>不能</b>走 {@link #time} 的时长换算（18:11 会被算成 65460 秒）。</p>
     *
     * @param hh 要显示在「分钟」位置的数（0~59）
     * @param mm 要显示在「秒」位置的数（0~59）
     */
    public static SetMediaClockTimer[] freezeLiteral(int hh, int mm) {
        int a = Math.max(0, Math.min(59, hh));
        int b = Math.max(0, Math.min(59, mm));
        SetMediaClockTimer up = SetMediaClockTimer.countUpFromStartTime(
                new StartTime(0, a, b), null, AudioStreamingIndicator.PLAY);
        return new SetMediaClockTimer[]{ up, pause() };
    }
}
