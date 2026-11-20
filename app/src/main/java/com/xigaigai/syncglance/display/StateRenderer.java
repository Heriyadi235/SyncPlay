package com.xigaigai.syncglance.display;

import android.os.Handler;
import android.os.Looper;

import com.smartdevicelink.proxy.RPCMessage;
import com.smartdevicelink.proxy.rpc.SetMediaClockTimer;
import com.smartdevicelink.proxy.rpc.SoftButton;
import com.smartdevicelink.proxy.rpc.StartTime;
import com.smartdevicelink.proxy.rpc.Show;
import com.smartdevicelink.proxy.rpc.enums.AudioStreamingIndicator;
import com.smartdevicelink.proxy.rpc.enums.SoftButtonType;
import com.smartdevicelink.proxy.rpc.enums.SystemAction;
import com.smartdevicelink.proxy.rpc.enums.TextAlignment;
import com.smartdevicelink.proxy.rpc.enums.UpdateMode;
import com.xigaigai.syncglance.lyric.Lrc;
import com.xigaigai.syncglance.lyric.Romaji;
import com.xigaigai.syncglance.lyric.SyncLyric;
import com.xigaigai.syncglance.model.Navigation;
import com.xigaigai.syncglance.model.NowPlaying;
import com.xigaigai.syncglance.util.Log2;

import java.util.List;

/**
 * 屏幕渲染器 —— 四状态的核心。
 *
 * 状态矩阵：
 *           无歌曲        有歌曲
 *   无导航    A 欢迎语     C 歌名/歌手 + 歌词
 *   有导航    B 导航+ETA   D 导航 + 歌曲
 *
 * 两个约束：
 *   1. **Show 节流到 1 秒一次** —— 旧文档说 4 秒，但实测这台车机支持 1 秒
 *   2. **只有 HMI_FULL 能发 RPC** —— 见 {@link SyncLifecycle}，
 *      非 FULL 时只记 dirty，不真发，避免刷 `no valid connection` 错误
 */
public class StateRenderer {

    private static final String TAG = "Render";
    /**
     * Show 的最小间隔。
     *
     * Ford 1.6 文档写的是"4 秒一次"，但那是旧版车机的限制。
     * 实测这台车机（固件 5.11）支持 1 秒一次刷新，所以按 1 秒节流。
     */
    private static final long MIN_INTERVAL_MS = 1000;
    /** 非 FULL 状态下的重试间隔 */
    private static final long RETRY_WHEN_NOT_FULL_MS = 1500;

    /**
     * 歌词时间轴提前量。
     *
     * 车机屏幕最快 1 秒刷一次，而我们计算歌词时拿到的是「当前这一刻」的播放位置，
     * 等 Show 真的送到车机、车机再画出来，已经过去了几百毫秒。
     * 所以查歌词时把播放位置往后拨 0.5 秒，
     * 让新的一句在它该唱的那一刻之前就送到，看起来才跟得上。
     */
    static final long LYRIC_LEAD_MS = 500;


    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * 发送出口。
     *
     * ★ 迁移 SDL 5.x 后不再直接持有 proxy 对象 —— 4.x 的 {@code SdlProxyALM}
     *   在 5.x 已被移除。这里只保留"把一条 RPC 交出去"的能力，
     *   真正的发送（HMI 门控、日志、响应回调）仍然在 {@code SdlService.sendRpc()} 里，
     *   这样"看到真正发出去的载荷"这个调试能力不会丢。
     */
    public interface Sender {
        void send(RPCMessage msg);
    }

    private Sender sender;
    private SyncLifecycle lifecycle;

    // ---- 数据源 ----
    private NowPlaying song;
    private List<Lrc.Line> lyrics;
    private Navigation nav = new Navigation();

    /**
     * 软键测试模式：往第 2、3、4、5、6 格塞测试软键，用来探测车机真实能显示几个。
     *
     * 0 = 关闭，只发第 1 个播放/暂停软键
     * N = 一共发 N 个（第 1 个仍是播放/暂停，其余是「键2」「键3」…）
     *
     * 本车 softButtonCapabilities 只声明了 1 项，但实车到底画不画多个、
     * 画了之后「更多…」还占不占第 4 格，只有试了才知道。
     */
    /**
     * 软键数量上限。
     *
     * 车机自己在底栏只画 3 格，多出来的靠 `>` / `<` 翻页，
     * 所以「声明的数量」和「一屏放得下几个」是两回事：
     * 实测送 2~6 个车机全部 SUCCESS 并自行翻页。
     *
     * 这里放到 12，用来摸车机到底能注册多少个 —— 超出上限时车机会
     * 回 INVALID_DATA 或直接截断，试出来再收窄。
     */
    public static final int SOFT_KEY_MAX = 12;

    private volatile int softKeyCount = 1;

    public void setSoftKeyCount(int n) {
        this.softKeyCount = Math.max(1, Math.min(SOFT_KEY_MAX, n));
        Log2.i(TAG, "软键数量：" + softKeyCount);
        markDirty("softKeyCount");
    }

    public int getSoftKeyCount() { return softKeyCount; }

    /**
     * 第 1 个软键（最左格）上显示的文本。
     *
     * ★ 按当前播放状态实时算，<b>不</b>依赖 doShow() 是否执行过。
     *   以前这里读的是一个只在 doShow 里赋值的字段，结果没连车机时
     *   永远是 null，预览里那一格就显示成「—」。
     */
    public String getSoftKey1() {
        // 车机字库不认 ▶/⏸，关掉符号时退回汉字「播放 / 暂停」
        return Glyphs.playKey(playingNow());
    }

    /**
     * 底栏要显示的全部软键文本（预览用）。
     *
     * 第 1 格永远是播放/暂停；后面 softKeyCount-1 个是测试键「键2」「键3」…
     * 和 doShow() 里构造 SoftButton 的逻辑保持一致。
     */
    public java.util.List<String> getSoftKeyTexts() {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add(getSoftKey1());
        for (int i = 2; i <= softKeyCount; i++) {
            out.add("\u952e" + i);                        // 「键2」「键3」…
        }
        return out;
    }

    /** 软键 ID：第 1 格固定 9001，测试软键从 9002 起 */
    public static final int SOFT_BUTTON_ID = 9001;

    // ---- 节流 ----
    private boolean dirty = true;
    private long lastShowAt = 0;
    private String lastPayload;
    /** 避免反复打印同一条"不能发送"日志 */
    private boolean warnedNotFull = false;

    private final Runnable flush = new Runnable() {
        @Override
        public void run() {
            if (!dirty) return;

            // ★ 生命周期检查：非 FULL 一律不发送
            if (sender == null || lifecycle == null || !lifecycle.canSendRpc()) {
                /*
                 * ★ 即使不能发，也要把「意图帧」算出来。
                 *
                 *   预览器吃的是 lastFrame；如果这里直接 return，
                 *   没连车机时 lastFrame 永远是初始空帧，预览就是一片空白。
                 *   这也正是文档 §8.14 的老坑（预览不能依赖"真正发出去过"）。
                 */
                refreshFrameOnly();

                if (!warnedNotFull) {
                    warnedNotFull = true;
                    Log2.i(TAG, "暂不刷新（" +
                            (lifecycle == null ? "未连接" : lifecycle.describe()) + "），等 HMI_FULL");
                }
                // 过一会儿再看
                main.removeCallbacks(flush);
                main.postDelayed(flush, RETRY_WHEN_NOT_FULL_MS);
                return;
            }
            warnedNotFull = false;

            long now = System.currentTimeMillis();
            long wait = MIN_INTERVAL_MS - (now - lastShowAt);
            if (wait > 0) {
                main.removeCallbacks(flush);
                main.postDelayed(flush, wait);
                return;
            }
            doShow();
        }
    };

    public StateRenderer() {
        main.postDelayed(staleWatchdog, STALE_CHECK_MS);
        main.postDelayed(positionTicker, POSITION_POLL_MS);
    }

    /**
     * 定时检查导航数据是否过期。
     *
     * 高德正常每秒推一次，8 秒没更新基本可以判定导航已停止
     * （闪退、退出导航、手机休眠等）。
     * 每 2 秒检查一次，最坏情况 10 秒内完成降级。
     */
    private static final long STALE_CHECK_MS = 2000;
    private final Runnable staleWatchdog = new Runnable() {
        @Override
        public void run() {
            try {
                if (nav != null && nav.hasData()) {
                    long age = nav.staleAge();
                    if (age > 0) {
                        Log2.w(TAG, "导航数据已过期 " + (age / 1000)
                                + " 秒，自动降级到空闲/歌曲模式");
                        nav.clear();
                        navClockText = null;
                        // 强制重绘 —— 不能依赖下次事件
                        dirty = true;
                        schedule();
                    }
                }
            } catch (Exception ignored) { }
            main.postDelayed(this, STALE_CHECK_MS);
        }
    };

    public void setSender(Sender s) {
        this.sender = s;
        dirty = true;
        schedule();
    }

    public void setLifecycle(SyncLifecycle lc) {
        this.lifecycle = lc;
        dirty = true;
        schedule();
    }

    /** 连接状态变了，立刻尝试刷新 */
    public void onLifecycleChanged() {
        warnedNotFull = false;
        dirty = true;
        schedule();
    }

    public void setNavigation(Navigation n) {
        this.nav = n == null ? new Navigation() : n;
        markDirty("nav");
    }

    /** 清空导航数据（高德退出/导航结束时调用） */
    public void clearNavigation() {
        if (nav != null) nav.clear();
        navClockText = null;
        lastEtaSent = null;
        markDirty("navClear");
    }

    public void setSong(NowPlaying np) {
        this.song = np;
        markDirty("song");
    }

    /**
     * 清空歌曲状态 —— 播放器退出 / 媒体控件被划掉时调用。
     *
     * 和 setSong(null) 的区别：这里同时清掉歌词，并让 four-state 立刻
     * 从 C/D 退回 B/A，不等下一次通知。
     */
    public void clearSong() {
        this.song = null;
        this.lyrics = null;
        resetSongClock();
        markDirty("songGone");
    }

    /**
     * 只更新播放位置和播放状态，不改歌曲身份。
     *
     * ★ 这个方法是歌词滚动的心跳。
     *
     * 通知只在内容变化时才推，播放中的 positionMs 不会每秒主动送达，
     * 所以必须由 {@link #positionTicker} 定期拉取。
     *
     * 历史 bug：这个方法以前只在 LogActivity 的预览刷新里被调用，
     * 结果就是不打开预览页时 song.positionMs 永远停在最后一次通知的值上，
     * 歌词根本不滚动 —— 一打开预览页才「突然」跟上。
     */
    public void updatePlaybackPosition(long positionMs, boolean playing) {
        if (song == null) return;
        boolean moved = Math.abs(positionMs - song.positionMs) >= 400;
        boolean flipped = playing != song.playing;
        song.positionMs = positionMs;
        song.playing = playing;
        // 位置没怎么变就别重绘，省得白刷 Show
        if (moved || flipped) markDirty("position");
    }

    public void setLyrics(List<Lrc.Line> lines) {
        this.lyrics = lines;
        markDirty("lyrics");
    }

    // ---------------- 播放位置心跳 ----------------

    /**
     * 每次 Show 最快 1 秒，所以按 900ms 轮询足够跟上，
     * 也不会因为太密而白白唤醒 CPU（开车时费电）。
     */
    private static final long POSITION_POLL_MS = 900;

    private final Runnable positionTicker = new Runnable() {
        @Override
        public void run() {
            try {
                tickPosition();
            } catch (Exception ignored) { }
            main.postDelayed(this, POSITION_POLL_MS);
        }
    };

    /**
     * 暂停多久之后判定「用户不听了」，退回导航 / 欢迎页。
     *
     * 60 秒是折中：短到不会让一屏歌词一直干挂着，
     * 长到不会因为接个电话、等个红灯就闪回欢迎页。
     */
    private static final long LONG_PAUSE_MS = 60_000;
    /** 从什么时候开始一直暂停 */
    private long pausedSince = 0;

    private void tickPosition() {
        if (song == null) {
            pausedSince = 0;
            return;
        }

        if (!song.playing) {
            // 暂停中：只计时，不拉位置
            if (pausedSince == 0) pausedSince = System.currentTimeMillis();
            if (System.currentTimeMillis() - pausedSince >= LONG_PAUSE_MS) {
                Log2.i(TAG, "暂停超过 " + (LONG_PAUSE_MS / 1000) + " 秒，退出歌曲界面");
                pausedSince = 0;
                clearSong();
            }
            return;
        }
        pausedSince = 0;

        com.xigaigai.syncglance.service.NotificationCatcher nc =
                com.xigaigai.syncglance.service.NotificationCatcher.getInstance();
        if (nc == null) return;
        com.xigaigai.syncglance.model.NowPlaying latest = nc.pollNowPlaying();
        if (latest != null && latest.title != null && latest.title.equals(song.title)) {
            // 不在这里打日志：这是 900ms 一次的心跳，记下来纯噪音。
            // 真要确认位置在推进，看 Show 里歌词的变化即可。
            updatePlaybackPosition(latest.positionMs, latest.playing);
        }
    }

    public NowPlaying getSong() { return song; }
    public Navigation getNavigation() { return nav; }

    public void markDirty(String why) {
        dirty = true;
        schedule();
    }

    private void schedule() {
        main.removeCallbacks(flush);
        main.postDelayed(flush, 20);
    }

    /** 断开连接时清掉上次的显示缓存，重连后强制重绘 */
    public void onDisconnected() {
        lastPayload = null;
        lastShowAt = 0;
        warnedNotFull = false;
        dirty = true;
    }

    // ---------------- 状态判定 ----------------

    public enum State { A_EMPTY, B_NAV_ONLY, C_MUSIC_ONLY, D_BOTH }

    public State currentState() {
        // ★ 用 isFresh 而不是 hasData：
        //   高德闪退/导航结束但没发结束信号时，数据会变成"过期"，
        //   此时必须当作没有导航，否则车机会卡在最后一条指令上。
        boolean hasNav = nav != null && nav.isFresh();
        boolean hasSong = song != null && song.hasData();
        if (hasNav && hasSong) return State.D_BOTH;
        if (hasNav) return State.B_NAV_ONLY;
        if (hasSong) return State.C_MUSIC_ONLY;
        return State.A_EMPTY;
    }

    // ---------------- 真正发 Show ----------------

    /**
     * 最近一帧「如果现在发会发什么」—— 预览器从这里取。
     *
     * ★ 即使没连车机（sender == null）、甚至 Show 根本发不出去，
     *   这一帧也会被算出来并更新，预览不会卡在上一次真正发送的快照上
     *   （这就是文档 §8.14 反复踩的那个坑）。
     */
    private volatile ScreenFrame lastFrame = new ScreenFrame();

    /** 预览用：取最近一帧 */
    public ScreenFrame getLastFrame() { return lastFrame; }

    /**
     * 只更新预览帧，不发任何 RPC。
     * 用于「当前等级不能发 Show」的时候（未连车机 / 非 HMI_FULL）。
     */
    private void refreshFrameOnly() {
        ScreenFrame frame = buildFrame();
        lastFrame = frame;
        trackPage1 = frame.mediaTrack;
        trackPage2 = frame.mediaTrack;
    }

    /** 上一次用于预览的计时器指令（用来沿用 issuedAtMs，避免每次都把走表起点重置） */
    private volatile ClockInstruction lastClock = ClockInstruction.none();

    private void doShow() {
        // ① 先把「这一帧是什么」算出来 —— 无论能不能发
        ScreenFrame frame = buildFrame();
        lastFrame = frame;
        trackPage1 = frame.mediaTrack;
        trackPage2 = frame.mediaTrack;

        if (sender == null) {
            dirty = true;
            return;
        }

        // ② 内容没变就不重发
        String payload = frame.stamp();
        if (payload.equals(lastPayload)) {
            dirty = false;
            return;
        }

        Show s = new Show();
        s.setMainField1(frame.f1);
        s.setMainField2(frame.f2);
        s.setMainField3(frame.f3);
        s.setMainField4(frame.f4);
        // mediaTrack 两屏共用，所以发同一个值。
        //
        // ★ 注意：用 setMediaTrack("") 才能真正清空。
        //   如果这里跳过不设，车机会保留上一次的值 ——
        //   降级回欢迎页时就会残留「歌名」这种东西。
        s.setMediaTrack(frame.mediaTrack == null ? "" : frame.mediaTrack);
        s.setAlignment(TextAlignment.LEFT_ALIGNED);

        // ★ 软键：第 1 格固定播放/暂停，后面按测试模式追加。
        //   车机底栏共 4 格，最后一格「更多…」是车机自带的。
        try {
            java.util.List<SoftButton> keys = new java.util.ArrayList<>();
            for (int i = 0; i < frame.softKeys.length; i++) {
                SoftButton b = new SoftButton();
                b.setType(SoftButtonType.SBT_TEXT);
                b.setText(frame.softKeys[i]);
                b.setSoftButtonID(SOFT_BUTTON_ID + i);
                b.setSystemAction(SystemAction.DEFAULT_ACTION);
                keys.add(b);
            }
            s.setSoftButtons(keys);
        } catch (Exception e) {
            Log2.w(TAG, "构造软键失败: " + e.getMessage());
        }

        try {
            sender.send(s);
            lastShowAt = System.currentTimeMillis();
            lastPayload = payload;
            dirty = false;
            Log2.i(TAG, "Show[" + frame.stateLabel + "] " + frame.f1 + " / " + frame.f2
                    + " / " + frame.f3 + " / " + frame.f4
                    + (frame.mediaTrack.isEmpty() ? "" : " / T=" + frame.mediaTrack));
        } catch (Exception e) {
            Log2.e(TAG, "Show 失败", e);
            dirty = true;
        }
    }

    /**
     * 计算当前这一帧。只读状态，不发任何 RPC。
     *
     * 这里集中了「屏幕上每个位置放什么」的唯一实现：
     * 四状态决策、歌词分行、track 前缀、假名转写、底栏软键。
     * 实车发送与屏幕预览都消费同一个结果，所以不可能不一致。
     */
    private ScreenFrame buildFrame() {
        ScreenFrame f = new ScreenFrame();
        State st = currentState();
        f.stateLabel = stateLabel(st);
        f.connected = lifecycle != null && lifecycle.canSendRpc();
        f.title = f.connected ? "SyncPlay" : "SyncPlay (未连接)";

        switch (st) {
            case A_EMPTY:
                f.f1 = greeting();
                f.f2 = "西改改";
                f.f3 = "";
                f.f4 = "";
                f.mediaTrack = "";
                break;

            case B_NAV_ONLY:
                // 第 1 屏：转向+距离 / 路名
                f.f1 = nz(nav.instruction);
                f.f2 = nz(nav.road);
                // 第 2 屏：ETA 文字 / 剩余距离
                f.f3 = nz(nav.etaText());
                f.f4 = nz(nav.remainDistance);
                // mediaTrack：剩余距离
                f.mediaTrack = nz(nav.remainDistance);
                break;

            case C_MUSIC_ONLY: {
                /*
                 * ★ 车机会在第 2 屏停留一会儿后自己跳回第 1 屏（实车行为，见 AGENTS.md 6.7）。
                 *
                 *   所以第 1 屏放「必须一直看」的歌词，歌名/歌手退到第 2 屏；
                 *   反过来做的话，歌词刚出来就被跳走，屏幕上只剩歌名歌手。
                 *
                 *   没歌词时没有"要一直看"的东西，就保持歌名歌手在第 1 屏、
                 *   第 3 行给一句提示。
                 */
                if (lyrics != null && !lyrics.isEmpty()) {
                    // 第 1 屏：两行全给歌词
                    String[] fl = lyricLines(song.positionMs);
                    f.f1 = fl[0];
                    f.f2 = fl[1];
                    // 第 2 屏：歌名 + 歌手
                    f.f3 = nz(song.title);
                    f.f4 = nz(song.artist);
                } else {
                    // 第 1 屏：歌名 + 歌手
                    f.f1 = nz(song.title);
                    f.f2 = nz(song.artist);
                    // 第 2 屏：提示
                    f.f3 = "无歌词信息";
                    f.f4 = "";
                }
                // mediaTrack：只有 8 字符宽（4 汉字），放「最干净的歌名」
                f.mediaTrack = nz(trackTitle(song.title));
                break;
            }

            case D_BOTH:
            default: {
                // 第 1 屏：导航指令 / 路名
                f.f1 = nz(nav.instruction);
                f.f2 = nz(nav.road);

                // 第 2 屏：两行全给歌词；没歌词时第一行提示
                if (lyrics != null && !lyrics.isEmpty()) {
                    String[] fl = lyricLines(song.positionMs);
                    f.f3 = fl[0];
                    f.f4 = fl[1];
                } else {
                    f.f3 = "无歌词信息";
                    f.f4 = "";
                }
                // mediaTrack：始终显示导航剩余距离（导航优先）
                f.mediaTrack = nz(nav.remainDistance);
                break;
            }
        }

        // ETA 时刻每帧重算（不走 60 秒缓存），供时钟指令与预览使用
        navClockText = nav != null && nav.hasData() ? nav.etaClockNow() : null;

        // ★ 所有要发出去的字符串统一做日文转写（规则见 Romaji / docs/design.md）
        f.f1 = Romaji.convert(f.f1);
        f.f2 = Romaji.convert(f.f2);
        f.f3 = Romaji.convert(f.f3);
        f.f4 = Romaji.convert(f.f4);
        f.mediaTrack = Romaji.convert(f.mediaTrack);

        // ★ track 行前面加状态指示符（看「这一行装的是什么」，track 为空时不加）
        f.mediaTrack = statusPrefix(f.mediaTrack) + f.mediaTrack;

        // ★ 第 3 行右侧：计时器「指令」，不是算好的读数
        f.clock = currentClockInstruction();

        // ★ 底栏软键（第 1 格播放/暂停，其余是测试键）
        java.util.List<String> keys = getSoftKeyTexts();
        f.softKeys = keys.toArray(new String[0]);

        return f;
    }

    private static String stateLabel(State st) {
        switch (st) {
            case A_EMPTY: return "空闲";
            case B_NAV_ONLY: return "仅导航";
            case C_MUSIC_ONLY: return "仅歌曲";
            case D_BOTH:
            default: return "导航+歌曲";
        }
    }

    /**
     * mediaTrack 只显示歌名，且那一格只有 <b>8 个字符（4 个汉字）</b>宽。
     *
     * 所以显示「最干净的歌名」—— 就是查询歌词时送给 lrclib 的那个名字：
     * 剥掉译名 / 出处这类括号（A 类），保留 Live / Remix / 伴奏这类版本标签（B 类）。
     * 规则见 {@link com.xigaigai.syncglance.lyric.TitleNorm#queryTitle}。
     */
    private static String trackTitle(String title) {
        if (title == null || title.trim().isEmpty()) return "";
        return com.xigaigai.syncglance.lyric.TitleNorm.queryTitle(title);
    }

    /**
     * 当前应该发给车机的计时器指令。
     *
     * ★ 这不是「算好的读数」：SYNC 的计时器是 App 下指令、车机自己走表。
     *   预览器拿这条指令自己模拟走表，因此它与
     *   「Show 节流（1 秒）」「歌词选句（时间轴）」是三条独立的时间线。
     *
     * 优先级与实车一致：用户计时器 &gt; 导航 ETA &gt; 播放进度。
     */
    private ClockInstruction currentClockInstruction() {
        long now = System.currentTimeMillis();

        // ① 用户计时器（走秒或暂停都占着那一格）
        if (timerActive) {
            if (timerRunning) {
                ClockInstruction.Mode mode = timerCountdown
                        ? ClockInstruction.Mode.COUNTDOWN : ClockInstruction.Mode.COUNTUP;
                // 起始值固定，靠 anchor（真实开始时刻）推着走
                return reuseClock(mode, timerH, timerM, timerS, timerStartMillis);
            }
            // 暂停：定格在暂停那一刻的读数
            long sec = timerElapsedSeconds();
            int h = (int) (sec / 3600);
            int m = (int) ((sec / 60) % 60);
            int s = (int) (sec % 60);
            return reuseClock(ClockInstruction.Mode.PAUSE, h, m, s, now);
        }

        // ② 导航 ETA：借 mm:ss 位显示到达时刻（hh 塞进分钟位）
        String eta = navClockText;
        if (eta != null && eta.indexOf(':') > 0) {
            try {
                int c = eta.indexOf(':');
                int hh = Integer.parseInt(eta.substring(0, c));
                int mm = Integer.parseInt(eta.substring(c + 1));
                return reuseClock(ClockInstruction.Mode.PAUSE, 0, hh, mm, now);
            } catch (Exception ignored) { }
        }

        // ③ 播放进度：从当前位置起表，车机自己走
        if (song != null && song.hasData() && song.playing && song.positionMs > 0) {
            long pos = song.positionMs;
            int m = (int) ((pos / 60_000) % 60);
            int s = (int) ((pos / 1000) % 60);
            return reuseClock(ClockInstruction.Mode.COUNTUP, 0, m, s, now);
        }

        /*
         * ④ 没有任何新指令时：**沿用上一条还在走的指令**。
         *
         *   车机那一格不会因为歌曲暂停而清空 —— 它只会按最后一条指令继续走，
         *   直到我们显式发 CLEAR。预览器照同样的规则处理，才不会出现
         *   「暂停后那一格突然空了」的假象。
         */
        ClockInstruction last = lastClock;
        if (last != null && last.isRunning()) return last;

        return ClockInstruction.none();
    }

    /**
     * 同一条指令就沿用原来的 issuedAtMs —— 否则每次重建帧都会把走表起点重置到 0，
     * 预览里的秒数就不动了。
     */
    private ClockInstruction reuseClock(ClockInstruction.Mode mode, int h, int m, int s, long anchor) {
        ClockInstruction last = lastClock;
        if (last != null && last.mode == mode && last.h == h && last.m == m && last.s == s) {
            return last;
        }
        ClockInstruction c = ClockInstruction.of(mode, h, m, s, anchor);
        lastClock = c;
        return c;
    }


    /** 第 1 屏和第 2 屏的 track 值（可能不同，预览要用） */
    private volatile String trackPage1 = "";
    private volatile String trackPage2 = "";

    /** 预览用：取第 1 屏的 track */
    public String getTrackPage1() { return trackPage1; }

    /** 预览用：取第 2 屏的 track */
    public String getTrackPage2() { return trackPage2; }

    // ---------------- 问候语 / 时间槽 ----------------

    /** 按时间段返回问候语 */
    private static String greeting() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (hour >= 5 && hour < 9) return "早上好";
        if (hour >= 9 && hour < 12) return "上午好";
        if (hour >= 12 && hour < 18) return "下午好";
        return "晚上好";
    }

    /**
     * track 行显示什么时间。
     *
     * 优先级：
     *   1. 用户计时器在跑 → 让给计时器（返回 null）
     *   2. 当前页是导航信息 → 显示预计到达时间
     *   3. 当前页是歌曲/歌词 → 显示播放进度
     *
     * @param navPage 这一屏显示的是不是导航内容
     */
    private static String nz(String s) { return s == null ? "" : s; }

    /** 当前是否在播放（软键文字用） */
    private boolean playingNow() {
        return song != null && song.playing;
    }

    // ---------------- 计时器 / 播放时间 ----------------

    /**
     * 计时器是否在运行。
     * 由 SdlService 在调用 doTimer 时设置。
     */
    private volatile boolean timerRunning = false;

    /**
     * 计时器是否「存在」（正在进行 或 暂停中）。
     *
     * 和 {@link #timerRunning} 的区别：
     *   timerRunning  —— 只有真正在走秒时才为 true
     *   timerActive   —— 只要用户还没清零就算，包含暂停态
     *
     * 显示用 active，发 RPC 用 running。
     * 这样暂停时数字定格在屏幕上，而不会被歌曲/导航时间抢走。
     */
    private volatile boolean timerActive = false;

    // ---- 本地计时器状态（给预览模拟用）----
    private volatile long timerStartMillis = 0;
    private volatile int timerH = 0, timerM = 0, timerS = 0;
    private volatile boolean timerCountdown = false;
    private volatile long timerPausedAt = 0;

    public void setTimerRunning(boolean running) {
        this.timerRunning = running;
        if (running) timerActive = true;      // 开始走 = 一定存在
        markDirty("timerState");
    }

    public boolean isTimerRunning() { return timerRunning; }

    /** 计时器是否还「占着」屏幕（含暂停态） */
    public boolean isTimerActive() { return timerActive; }

    /** 记录计时器状态，供预览模拟走表 */
    public void setTimerState(int h, int m, int s, String mode) {
        timerH = h;
        timerM = m;
        timerS = s;
        timerCountdown = "COUNTDOWN".equals(mode);

        switch (mode) {
            case "COUNTUP":
            case "COUNTDOWN":
                timerStartMillis = System.currentTimeMillis();
                timerPausedAt = 0;
                timerRunning = true;
                timerActive = true;
                break;
            case "PAUSE":
                timerPausedAt = System.currentTimeMillis();
                timerRunning = false;
                timerActive = true;          // ★ 暂停 = 仍然占屏，只是不走秒
                break;
            case "RESUME":
                if (timerPausedAt > 0) {
                    // 把暂停期间的时间补回去
                    timerStartMillis += System.currentTimeMillis() - timerPausedAt;
                    timerPausedAt = 0;
                }
                timerRunning = true;
                timerActive = true;
                break;
            case "CLEAR":
                timerStartMillis = 0;
                timerPausedAt = 0;
                timerRunning = false;
                timerActive = false;         // ★ 只有清零才真正释放屏幕
                break;
        }

        /*
         * ★ 必须 markDirty，否则预览不跟着变。
         *
         *   上一版漏了这一行：setTimerRunning() 里有、这里没有。
         *   于是点「正计时/倒计时/暂停/清零」时，只有 RPC 发了出去（车机正常），
         *   而 lastFrame 不重建 —— LogActivity 里的屏幕预览拿到的还是旧帧，
         *   计时器那一格完全不动（有歌在播时才碰巧对，因为播放位置心跳会顺带刷新）。
         */
        markDirty("timerState");
    }

    public long getTimerStartMillis() { return timerStartMillis; }
    public int getTimerStartHours() { return timerH; }
    public int getTimerStartMinutes() { return timerM; }
    public int getTimerStartSeconds() { return timerS; }
    public boolean isTimerCountdown() { return timerCountdown; }

    /** 计算当前应显示的计时器秒数（正计时=已过秒数，倒计时=剩余秒数） */
    public long timerElapsedSeconds() {
        if (timerStartMillis == 0) return 0;
        long ref = (timerPausedAt > 0) ? timerPausedAt : System.currentTimeMillis();
        long elapsed = (ref - timerStartMillis) / 1000;
        if (elapsed < 0) elapsed = 0;

        if (timerCountdown) {
            long total = timerH * 3600L + timerM * 60L + timerS;
            return Math.max(0, total - elapsed);
        }
        return elapsed;
    }

    /**
     * 歌曲播放计时器是否已由 SetMediaClockTimer 启动。
     * 启动后不要再往 track 里塞时间，否则会和计时器打架。
     */
    private volatile boolean songClockStarted = false;
    private String lastSongKey = null;

    /**
     * 用 SetMediaClockTimer 设置第 3 行右侧的时间。
     *
     * 优先级：
     *   1. 用户计时器在跑 → 不动（让用户计时器占着）
     *   2. 导航模式 → 显示 ETA 时刻
     *   3. 歌曲模式 → COUNTUP 从播放位置起表
     *
     * @return 需要发送的 RPC；不需要返回 null
     */
    /**
     * 用户计时器指令 → {@link SetMediaClockTimer}。
     *
     * <b>只构造，不发送</b>：发不发、发不出去怎么办，由调用方
     * （{@code SyncPlayCore}）决定。这样计时器的"本地状态"和"发 RPC"
     * 就解耦了 —— 没连车机时本地状态照样更新，屏幕预览才是对的。
     *
     * ⚠ 调用前必须先 {@link #setTimerState}：PAUSE 的定格读数依赖它记下的
     *   {@code timerPausedAt}，顺序反了会算出"还在走"的值。
     */
    public SetMediaClockTimer timerRpc(int h, int m, int s, String mode) {
        UpdateMode um;
        try {
            um = UpdateMode.valueForString(mode);
        } catch (Exception e) {
            um = UpdateMode.COUNTUP;
        }

        switch (um) {
            case PAUSE: {
                // 暂停时也要把时间停在屏上。
                // pauseWithPlayPauseIndicator() 只发 UPDATE_MODE=PAUSE、不带 startTime，
                // 车机很可能把那一格清空；所以把定格读数写成新的 startTime 再置 PAUSE。
                long sec = timerElapsedSeconds();
                int hh = (int) (sec / 3600);
                int mm = (int) ((sec / 60) % 60);
                int ss = (int) (sec % 60);
                Log2.i(TAG, "计时器暂停，定格在 " + hh + ":" + mm + ":" + ss);
                return SetMediaClockTimer.updatePauseWithNewStartTime(
                        clockStart(hh, mm, ss), null, AudioStreamingIndicator.PAUSE);
            }
            case RESUME:
                return SetMediaClockTimer.resumeWithPlayPauseIndicator(AudioStreamingIndicator.PLAY);
            case CLEAR:
                return SetMediaClockTimer.clearWithPlayPauseIndicator(AudioStreamingIndicator.PAUSE);
            case COUNTDOWN:
                return SetMediaClockTimer.countDownFromStartTime(
                        clockStart(h, m, s), null, AudioStreamingIndicator.PLAY);
            case COUNTUP:
            default:
                return SetMediaClockTimer.countUpFromStartTime(
                        clockStart(h, m, s), null, AudioStreamingIndicator.PLAY);
        }
    }

    /**
     * 车机时钟格只有 mm:ss，没有小时位：
     *   - hours ≠ 0 会被判 REJECTED（实测 16:21 连拒两次）
     *   - 超过 59:59 的读数夹到上限，宁可停格也不要被整条拒掉
     */
    private static StartTime clockStart(int h, int m, int s) {
        long total = h * 3600L + m * 60L + s;
        if (total < 0) total = 0;
        if (total > 3599) total = 3599;
        return new StartTime(0, (int) (total / 60), (int) (total % 60));
    }

    public SetMediaClockTimer songClockRpc() {
        if (timerRunning) return null;
        if (sender == null) return null;

        // 导航模式优先显示 ETA
        if (navClockText != null && !navClockText.isEmpty()) {
            // ★ 只有"确实要发出去"时才更新 lastClockTextSent。
            //
            //   以前的写法是先赋值、再检查，只要中间任一步 return（etaClockNow 取不到值、
            //   ETA 恰好没变），这张"票"就被白白用掉了 —— 之后每次进来都在上一行
            //   `navClockText.equals(last)` 处直接返回，ETA 永久卡死，直到导航结束。
            //   实测就是这样：整个导航期间一条 SetMediaClockTimer 都没发出去。
            if (navClockText.equals(lastClockTextSent)) return null;
            if (nav == null) return null;

            String clock = nav.etaClockNow();
            if (clock == null) return null;          // 暂时拿不到到达时刻，下次再试，不消耗标记

            if (!shouldResendEta(clock)) {
                // ETA 时刻本身没变：记下当前值避免反复尝试，但等待它变化
                lastClockTextSent = navClockText;
                return null;
            }

            // ★ 直接把到达时刻打在计时器上，然后让它停住。
            //
            //   高德数据包里本来就带着 ETA 时刻，没必要换算成"还剩多少"再倒计时，
            //   也没必要从 0 往上数。setStartTime = 到达的 hh:mm，
            //   updateMode = PAUSE → 车机就定格显示这个时间，不再走秒。
            //
            //   ★ 关键：车机的时钟格是 mm:ss，没有小时位。
            //   照片实测那一格显示的是 "1:55"（分:秒），
            //   所以只要 hours ≠ 0 车机就判 REJECTED（实测 16:21 连拒两次）。
            //   对策是把 hh:mm 拆成 minutes=hh、seconds=mm，
            //   车机会原样渲染成 "16:21"，正好是我们要的到达时刻。
            int h = 0, m = 0;
            try {
                int c = clock.indexOf(':');
                h = Integer.parseInt(clock.substring(0, c));
                m = Integer.parseInt(clock.substring(c + 1));
            } catch (Exception ignored) { }
            Log2.i(TAG, "计时器：显示到达时刻 " + clock
                    + " → StartTime(0," + h + "," + m + ")（mm:ss 位）");

            SetMediaClockTimer t = new SetMediaClockTimer();
            t.setStartTime(new StartTime(0, h, m));
            t.setUpdateMode(UpdateMode.PAUSE);
            t.setAudioStreamingIndicator(AudioStreamingIndicator.PLAY);

            lastClockTextSent = navClockText;        // ★ 真要发了才记
            return t;
        }

        // 歌曲模式：从播放位置起表
        lastClockTextSent = null;
        if (song == null || !song.hasData() || !song.playing) return null;

        String key = song.title + "|" + song.artist;
        boolean songChanged = !key.equals(lastSongKey);
        if (!songChanged && songClockStarted) return null;

        long pos = song.positionMs;
        if (pos < 0) pos = 0;
        int h = (int) (pos / 3_600_000);
        int m = (int) ((pos / 60_000) % 60);
        int s = (int) ((pos / 1000) % 60);

        lastSongKey = key;
        songClockStarted = true;
        Log2.i(TAG, "启动播放计时 " + String.format(java.util.Locale.US,
                "%02d:%02d:%02d", h, m, s));

        return SetMediaClockTimer.countUpFromStartTime(
                new StartTime(h, m, s), null, AudioStreamingIndicator.PLAY);
    }

    /** 上次真正发给车机的 ETA 时刻（hh:mm） */
    private String lastEtaSent = null;

    /**
     * 这个新的 ETA 值要不要重发。
     *
     * 规则简单：到达时刻变了就发，没变就不发。
     *
     * 高德的 ETA 是按分钟算的，同一分钟内反复推送也是同一个值，
     * 所以不需要额外的门槛 —— 剩下的就是"变了一次就更新一次"。
     */
    private boolean shouldResendEta(String clock) {
        if (clock == null) return false;
        if (clock.equals(lastEtaSent)) return false;
        Log2.i(TAG, "ETA 变化：" + lastEtaSent + " → " + clock);
        lastEtaSent = clock;
        return true;
    }

    /** 第3行右侧应该显示的 ETA 时刻（null 表示交给其它逻辑） */
    private volatile String navClockText = null;

    /** 预览用：取第3行右侧应显示的时间 */
    public String getNavClockText() { return navClockText; }

    /** 上次发给车机的时钟文本，避免重复发送 */
    private volatile String lastClockTextSent = null;

    /** "歌名 - 歌手" 格式，给 mediaTrack 用 */
    private String songLabel() {
        if (song == null || !song.hasData()) return "";
        StringBuilder sb = new StringBuilder();
        if (notEmpty(song.title)) sb.append(song.title);
        if (notEmpty(song.artist)) {
            if (sb.length() > 0) sb.append(" - ");
            sb.append(song.artist);
        }
        return sb.toString();
    }

    private static boolean notEmpty(String s) { return s != null && !s.trim().isEmpty(); }

    /** 用户计时器变化时调用，用来抢占 / 归还 track 行 */
    public void resetSongClock() {
        songClockStarted = false;
    }

    /**
     * track 行显示的文字。
     *
     * 计时器存在（走秒或暂停）→ 返回 null（让计时器独占那一行）
     * 计时器没跑 → 返回歌曲播放时间 "00:00"
     */
    private String playTimeText() {
        if (timerActive) return null;           // 计时器占用（含暂停态）
        if (song == null || !song.hasData()) return "";
        long pos = song.positionMs;
        if (pos <= 0) return "";
        long total = pos / 1000;
        return String.format(java.util.Locale.US, "%02d:%02d", total / 60, total % 60);
    }

    private static String fmtTime(long ms) {
        if (ms < 0) ms = 0;
        long total = ms / 1000;
        long m = total / 60;
        long s = total % 60;
        return String.format(java.util.Locale.US, "%d:%02d", m, s);
    }

    // ---------------- track 状态指示符 ----------------

    /**
     * track 这一行当前装的是哪类信息。
     *
     * ★ 刻意<b>不</b>和 A/B/C/D 状态绑在一起。
     *   以后想加新布局（比如导航占满、歌词单独一屏），
     *   只要在填 track 的时候标一下它装的是什么，指示符就自动对了。
     */
    public enum TrackKind {
        /** 空 —— 什么都不显示，指示符也为空 */
        NONE,
        /** 歌曲信息（歌名），指示符 = 播放/暂停 */
        SONG,
        /** 导航信息（剩余距离），指示符 = 方位箭头 */
        NAV
    }

    /**
     * track 这一行<b>当前</b>装的是哪类信息。
     *
     * ★ 按现在的状态实时推导，<b>不</b>依赖 doShow() 是否执行过。
     *   以前它是一个只在 doShow 里赋值的字段，没连车机时永远是 NONE，
     *   于是预览里的指示符全都不见了。
     *
     * ★ 也刻意不和 A/B/C/D 硬绑定 —— 这里只是给出「当前布局下 track 装什么」，
     *   以后加新布局，改这一个方法即可，指示符逻辑不用动。
     */
    public TrackKind getTrackKind() {
        switch (currentState()) {
            case A_EMPTY:
                return TrackKind.NONE;          // 欢迎页 track 是空的
            case B_NAV_ONLY:
                return TrackKind.NAV;           // 装剩余距离
            case C_MUSIC_ONLY:
                return TrackKind.SONG;          // 装歌名
            case D_BOTH:
            default:
                return TrackKind.NAV;           // 导航优先，track 给剩余距离
        }
    }

    /**
     * @param trackText 这一行实际要显示的内容（<b>不含</b>指示符）
     */
    private String statusPrefix(String trackText) {
        // ① 计时器最优先 —— 哪怕 track 本身是空的也要显示沙漏。
        //    用户明确要求：计时器激活时，状态指示符始终是计时器图标。
        if (timerActive) return Glyphs.clock();

        // ② track 是空的 → 不加内容类指示符。
        //    否则欢迎页上空荡荡一行前面孤零零挂个箭头/播放符，很怪。
        if (trackText == null || trackText.isEmpty()) return "";

        // ③ 按 track 里实际装的内容决定
        switch (getTrackKind()) {
            case NAV:
                return Glyphs.arrow(bearingArrow());
            case SONG:
                return Glyphs.play(playingNow());
            case NONE:
            default:
                return "";
        }
    }

    /**
     * 计时器激活时的符号：沙漏。
     *
     * 用 U+29D7 ⧗ —— 数学符号集里的黑白几何沙漏。
     * <b>实测车机不认识</b>，会渲染成一个圆点，所以现在由 {@link Glyphs} 统一关闭。
     * 字符本身移到了 {@link Glyphs#clock()}。
     */

    /**
     * 八方向箭头：终点相对当前朝向的方位。
     *
     * 高德只给「转向指令 + 剩余距离」，没有方位角，所以用指令文本近似。
     * 全部用单字符箭头（U+2190~U+2199），比 ▲▼◀▶ 更省宽度。
     */
    private String bearingArrow() {
        if (nav == null) return "\u2191";                    // ↑
        String ins = nav.instruction == null ? "" : nav.instruction;

        boolean left = ins.contains("\u5de6");
        boolean right = ins.contains("\u53f3");
        boolean rear = ins.contains("\u540e")
                || ins.contains("\u6389\u5934") || ins.contains("\u8c03\u5934");

        if (rear && left) return "\u2199";                   // ↙ 左后
        if (rear && right) return "\u2198";                  // ↘ 右后
        if (rear) return "\u2193";                           // ↓ 掉头/后方
        if (left && ins.contains("\u524d")) return "\u2196";  // ↖ 左前
        if (right && ins.contains("\u524d")) return "\u2197"; // ↗ 右前
        if (left) return "\u2190";                           // ←
        if (right) return "\u2192";                          // →
        return "\u2191";                                     // ↑ 直行 / 兜底
    }

    // ---------------- 歌词分行 ----------------

    /**
     * 一行能放的<b>半角宽度</b>（= 7 个汉字，或 14 个英文字母）。
     *
     * 车机一行 7 汉字是实车照片测出来的；英文字母占半格，
     * 所以统一换算成半角单位来算，中文和德语都能正确处理。
     *
     * ★ 真正的值定义在 {@link SyncLyric#LINE_UNITS}，这里只是别名：
     *   sync 缓存的文件名与签名里也写着这两个数，必须只有一个来源，
     *   否则改了这边、旧缓存还能命中，显示就会错位。
     */
    private static final int LYRIC_LINE_UNITS = SyncLyric.LINE_UNITS;
    /** 一屏给歌词几行 */
    private static final int LYRIC_PAGES = SyncLyric.PAGES;

    /**
     * 算出此刻两行歌词该显示什么。<b>实例方法</b>，用当前持有的歌词。
     */
    private String[] lyricLines(long pos) {
        return computeLyricLines(lyrics, pos);
    }

    /**
     * 歌词分行<b>唯一实现</b>，实车与预览共用。
     *
     * 两条路径：
     *   ① <b>成品歌词</b>（{@link SyncLyric} 预生成的 sync 缓存）：
     *      每行已经是一屏两行（U+0001 分隔），这里只做「按时间取行 + 拆两行」，
     *      不再分词、不再转写、不再翻页 —— 这是省算力的关键。
     *   ② 没有成品缓存（首次播放 / 生成失败）：现场走旧路径
     *      {@link Lrc#paged} → 转写 → 折行。
     *
     * @param positionMs 原始播放位置，内部会自动加 {@link #LYRIC_LEAD_MS}
     */
    public static String[] computeLyricLines(List<Lrc.Line> lyrics, long positionMs) {
        String[] out = new String[]{"", ""};
        if (lyrics == null || lyrics.isEmpty()) return out;

        long pos = positionMs + LYRIC_LEAD_MS;

        // ① 成品歌词：直接拆屏
        String[] ready = SyncLyric.pageAt(lyrics, pos);
        if (ready != null) {
            out[0] = ready[0];
            out[1] = ready[1];
            return out;
        }

        // ② 降级路径：先转写再分行，否则分行按假名算、显示却是罗马音，长度对不上
        String[] page = Lrc.paged(lyrics, pos, LYRIC_LINE_UNITS, LYRIC_PAGES);
        String cur = Romaji.convert(nz(page[0])).trim();
        if (cur.isEmpty()) return out;

        String[] two = SyncLyric.splitTwo(cur);
        out[0] = two[0];
        out[1] = two[1];
        return out;
    }

    // ---------------- 字符工具 ----------------

    /** 统计"汉字个数"（英文不计） */
    private static int charCount(String s) {
        if (s == null) return 0;
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            count += isWideChar(c) ? 1 : 0;
        }
        // 全是英文的情况按字符数算
        if (count == 0) return s.length();
        return count;
    }

    private static boolean isWideChar(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // 汉字
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x3040 && c <= 0x30FF)  // 假名
                || (c >= 0xFF00 && c <= 0xFF60);
    }
}
