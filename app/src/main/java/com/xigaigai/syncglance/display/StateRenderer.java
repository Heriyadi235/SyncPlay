package com.xigaigai.syncglance.display;

import android.os.Handler;
import android.os.Looper;

import com.smartdevicelink.proxy.RPCMessage;
import com.smartdevicelink.proxy.rpc.SetMediaClockTimer;
import com.smartdevicelink.proxy.rpc.SoftButton;
import com.smartdevicelink.proxy.rpc.Show;
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
    /*
     * 曾有 `MIN_INTERVAL_MS = 1000`（Show 最小间隔 1 秒）——
     * 2026-11-26 取消：payload 去重 + 导航距离取整已经足够压频率，
     * 留着它只会给每次变化加最多 1 秒延迟。详见 flush 里那段说明。
     */
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
        /** 发一条普通 RPC（Show 等），不等响应 */
        void send(RPCMessage msg);

        /**
         * 发一条，并在「车机响应回来（成功/失败都算）」之后回调 {@code onDone}。
         *
         * <p>由 {@link ClockDispatcher} 用来把<b>所有</b>时钟指令排成一条队列 ——
         * 两步法（`COUNTUP` 起表 → `PAUSE` 停住）必须严格按序，而且不能被
         * 别的时钟指令插队。实现方必须保证 {@code onDone} 最多被调用一次；
         * 没连车机时立即回调。</p>
         *
         * <p>2026-11-27：原来的 {@code sendSequential} 已删除 —— 它只保证「一次调用内」
         * 有序，两次调用之间仍会交错，正是「定格读数错乱」的根源；现在统一走
         * {@link ClockDispatcher}。</p>
         */
        void sendWithAck(RPCMessage msg, Runnable onDone);
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
    /**
     * 软键数量上限：**8 个**（车机实测上限，见 AGENTS.md 6.4）。
     * 超过 8 个车机自己会翻页/截断，没必要再往上试。
     */
    public static final int SOFT_KEY_MAX = 8;

    /** 基础软键数：播放/暂停、占位 -、计时。**不能少于这个数** */
    public static final int SOFT_KEY_DEFAULT = 3;

    /** 第 3 格固定是「计时」键（下标 2） */
    public static final int TIMER_KEY_INDEX = 2;

    /** 第 2 格是「导航」优先开关（下标 1）。仅在混合模式下才显示为「导航」 */
    public static final int NAV_KEY_INDEX = 1;

    private volatile int softKeyCount = SOFT_KEY_DEFAULT;

    /**
     * 导航优先开关（只在混合模式 {@link State#D_BOTH} 下有意义）。
     *
     * <pre>
     *   开（默认） f1/f2 = 导航（指令 / 路名）   f3/f4 = 歌词（无歌词时歌名 / 歌手）
     *   关         f1/f2 = 歌词（无歌词时歌名 / 歌手） f3/f4 = 导航（指令 / 路名）
     * </pre>
     *
     * ★ 默认 <b>true</b>：等于改动之前混合模式的排法，
     *   升级后不会突然把导航挤到第 2 屏。
     *
     * <p>⚠️ 关闭时导航退到第 2 屏，而车机在第 2 屏停留一会儿后会自己跳回第 1 屏
     * （实车行为，见 AGENTS.md 6.7）—— 也就是说关闭态的转向提示会被车机跳走。
     * 这是用户明确接受的行为，不是 bug。</p>
     */
    private volatile boolean navPriority = true;

    public boolean isNavPriority() { return navPriority; }

    /** 设置导航优先；变化时重画（软键文案与两屏内容都可能变） */
    public void setNavPriority(boolean on) {
        if (this.navPriority == on) return;
        this.navPriority = on;
        Log2.i(TAG, "导航优先: " + (on ? "开" : "关"));
        markDirty("navPriority");
    }

    /**
     * 当前是否处于混合模式（导航 + 歌曲同时有）。
     *
     * <p>「导航」软键只在这时候出现；其他状态下第 2 格退回占位 {@code -}，
     * 因为那会儿没有"谁优先"的问题，摆一个点了没反应的键只会让人困惑。</p>
     */
    public boolean isMixedState() {
        return currentState() == State.D_BOTH;
    }

    /**
     * 设置软键数量。
     *
     * ★ <b>下限锁死在 3</b>：格1 播放/暂停、格2 占位、格3 计时 都是有正常功能的，
     *   「软键测试」只能把测试键<b>追加在后面</b>，不能把它们挤掉。
     *   （以前下限是 1，调到 1 个时计时键就没了，等于测试侵犯了正常功能。）
     */
    public void setSoftKeyCount(int n) {
        this.softKeyCount = Math.max(SOFT_KEY_DEFAULT, Math.min(SOFT_KEY_MAX, n));
        Log2.i(TAG, "软键 " + softKeyCount);
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
     * 固定布局（共 4 格，第 4 格「更多…」由车机自带）：
     * <pre>
     *   格1 播放/暂停   格2 导航/占位   格3 计时
     *   9001           9002            9003
     * </pre>
     * 格 2 的文案随状态变：
     * <ul>
     *   <li>混合模式（{@link State#D_BOTH}）→ 「导航」，按压切换导航优先</li>
     *   <li>其他状态 → 「-」纯占位（那会儿没有谁优先的问题）</li>
     * </ul>
     * 「软键测试」把数量调到 >3 时，第 4 格起追加「键4」「键5」…；
     * 调到 <3 时计时键会跟着消失（那是测试模式，属预期）。
     */
    public java.util.List<String> getSoftKeyTexts() {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add(getSoftKey1());
        // 混合模式才显示「导航」，否则第 2 格是纯占位
        final boolean showNavKey = isMixedState();
        for (int i = 2; i <= softKeyCount; i++) {
            if (i - 1 == TIMER_KEY_INDEX) {
                out.add("\u8ba1\u65f6");                     // 格3「计时」：始终显示这几个字
            } else if (i - 1 == NAV_KEY_INDEX) {
                out.add(showNavKey ? "导航" : "-");          // 格2：混合模式显示「导航」
            } else {
                out.add("\u952e" + i);                       // 「键4」「键5」…
            }
        }
        return out;
    }

    /** 软键 ID：第 1 格固定 9001，依次 +1（计时键 = 9003，导航键 = 9002） */
    public static final int SOFT_BUTTON_ID = 9001;

    /**
     * 每个软键是否高亮（长度 = 实际软键个数）。
     *
     * <h3>为什么是数组而不是单个下标</h3>
     * SDL 的 {@code isHighlighted} 是 {@code SoftButton} 结构体<b>内部</b>的布尔字段
     * （见 rpc_spec 的 MOBILE_API.xml：{@code <param name="isHighlighted" type="Boolean">}），
     * 8 个按钮就是 8 个独立开关，规格里<b>没有</b>"全局只能亮一个"或互斥的约束。
     * 所以两个键同时高亮是允许的 —— 本车机是否这样渲染，需要实车确认。
     *
     * <h3>谁该亮</h3>
     * <ul>
     *   <li>格 3「计时」：时钟格此刻显示的就是计时器时间（见 {@link #isTimerOccupyingClock()}）</li>
     *   <li>格 2「导航」：<b>混合模式 + 导航优先开</b> 时才亮。
     *       非混合模式下该格是占位 {@code -}，不亮。</li>
     * </ul>
     */
    public boolean[] softKeyHighlights() {
        int n = Math.max(0, softKeyCount);
        boolean[] out = new boolean[n];

        // 格 2「导航」：只有混合模式且导航优先开着才高亮
        if (NAV_KEY_INDEX < n) {
            out[NAV_KEY_INDEX] = isMixedState() && navPriority;
        }

        // 格 3「计时」：时钟格被计时器占用时高亮
        if (TIMER_KEY_INDEX < n && isTimerOccupyingClock()) {
            out[TIMER_KEY_INDEX] = true;
        }
        return out;
    }

    // ---- 节流 ----
    private boolean dirty = true;
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
                            (lifecycle == null ? "未连接" : lifecycle.describe()) + "）");
                }

                /*
                 * ★ 不再自己续期（曾经的 `postDelayed(flush, 1500)` 已删）。
                 *
                 *   旧写法在这个分支里从不清 dirty，于是每 1.5 秒就会重新跑一遍
                 *   buildFrame()（新建 ScreenFrame、拼字符串、日文转写）——
                 *   而「没连车机」恰恰是最常见的状态，等于进程活着就永不停止地
                 *   每 1.5 秒醒一次。这是本应用最大的一处空转耗电。
                 *
                 *   去掉是安全的：数据变化有 markDirty() → schedule() 重新调度；
                 *   进入 FULL 时 onHmiStatus 会调 onLifecycleChanged() → markDirty()，
                 *   那时自然会再刷一次并真正发出去。
                 */
                return;
            }
            warnedNotFull = false;

            /*
             * ★ 2026-11-26：**取消了「Show 最小间隔 1 秒」这个节流**。
             *
             *   原来的写法是
             *     wait = MIN_INTERVAL_MS - (now - lastShowAt); if (wait > 0) 延后再来
             *   目的是防止刷屏（Ford 1.6 文档说 4 秒一次，实测这台车机 1 秒没问题，
             *   所以当时按 1 秒节流）。
             *
             *   现在不需要它了，因为「内容没变就不发」这道闸门已经够用：
             *     · doShow() 里有 payload 去重（`payload.equals(lastPayload)` 直接返回）
             *     · 导航距离已按量级取整（<50m 精确，50~999m 每 50m，≥1km 每 0.1km），
             *       所以 payload 的变化频率天然被压低了
             *
             *   去掉的收益：**延迟没了**。以前任何变化最多要等 1 秒才上屏；
             *   现在数据一变（换歌、换歌词行、导航转弯）立刻发。
             *
             *   仍然保留的两道保护：
             *     ① schedule() 里的 20ms 防抖（同一批数据变化合并成一次）
             *     ② doShow() 的 payload 去重
             *   万一将来发现车机扛不住突发（Show 被丢 / 闪一下），
             *   就把这里的最小间隔加回来 —— 代码在 git 历史里。
             */
            doShow();
        }
    };

    public StateRenderer() {
        /*
         * ★ 不在这里启动轮询。
         *
         *   StateRenderer 由 SyncPlayCore 在 Application.onCreate 里创建，
         *   构造函数里 postDelayed 等于「进程活着就一直跑」——
         *   没连车机、没放音乐也每 900ms / 2s 醒一次，白耗电。
         *
         *   改成按需：{@link #ensureTickers()} 在歌曲/导航变化时启停。
         */
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
                        lastClockFingerprint = null;
                        /*
                         * ★ 必须通知外部重推时钟。
                         *
                         *   这是「导航结束降级到空闲屏幕时时钟不消失」的直接原因：
                         *   以前这里只 markDirty 重画界面，但时钟格是车机**自己维护**
                         *   的 —— 不发新指令它就一直停在最后那个 ETA 读数上。
                         *
                         *   调 clockNeedsRefresh 让 SyncPlayCore 跑一次 pushClock()，
                         *   它会因为「没导航 ETA、没有歌」而发出 CLEAR。
                         */
                        if (clockNeedsRefresh != null) {
                            try { clockNeedsRefresh.run(); } catch (Exception ignored) { }
                        }
                        /*
                         * ★ 事件②：导航数据过期时顺手探一次 AmapLink。
                         *
                         *   数据断了有两种可能：
                         *     a) 高德还在，只是导航结束/退出了  → binder 活着
                         *     b) 高德进程被关掉了                → binder 已死
                         *   后者不做任何事的话就再也回不来了（系统不一定回调）。
                         *
                         *   这里只探一次、死了才重绑 —— 不引入任何定时器，
                         *   因为 staleWatchdog 本来就在跑（且只在有导航数据时跑）。
                         */
                        if (amapProbe != null) {
                            try { amapProbe.run(); } catch (Exception ignored) { }
                        }
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
        // 车机连上/断开会影响「有没有人在看」，位置心跳要重新评估
        ensureTickers();
        schedule();
    }

    /**
     * 「时钟格该重新推一遍了」的回调，由 {@code SyncPlayCore} 注入（内部会调用
     * {@code pushClock()}）。
     *
     * <p>为什么需要它：时钟格是<b>车机自己维护</b>的 —— 不发新指令它就停在那儿。
     * 而导航过期是<b>内部定时器</b>发现（{@link #staleWatchdog}），不经过
     * {@code SyncPlayCore.setNavigation()}，所以得反过来主动通知一次。</p>
     */
    private Runnable clockNeedsRefresh;

    public void setClockRefreshListener(Runnable r) {
        this.clockNeedsRefresh = r;
    }

    /**
     * 「导航数据过期了，顺手探一下 AmapLink」的回调，由 {@code SyncPlayCore} 注入。
     *
     * <p>导航断流有两种：导航正常结束（binder 还活着）、高德被关掉（binder 已死）。
     * 后者如果不处理就再也回不来了 —— 系统不保证回调 onServiceDisconnected。
     * 这里挂一个探活 + 必要时的重绑，复用本类已有的 staleWatchdog，
     * **不新增任何定时器**。</p>
     */
    private Runnable amapProbe;

    public void setAmapProbeListener(Runnable r) {
        this.amapProbe = r;
    }

    public void setNavigation(Navigation n) {
        this.nav = n == null ? new Navigation() : n;
        ensureTickers();
        markDirty("nav");
    }

    /** 清空导航数据（高德退出/导航结束时调用） */
    public void clearNavigation() {
        if (nav != null) nav.clear();
        navClockText = null;
        // 导航没了 → 时钟该回落，下一次 pushClock 必须重新决策
        lastClockFingerprint = null;
        ensureTickers();
        markDirty("navClear");
    }

    public void setSong(NowPlaying np) {
        this.song = np;
        ensureTickers();
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
        // 歌没了 → 时钟该回落（下次 pushClock 由指纹变化触发 CLEAR）
        lastClockFingerprint = null;
        ensureTickers();
        markDirty("songGone");
    }

    /**
     * 按需启停后台轮询 —— <b>省电的关键</b>。
     *
     * <pre>
     *   positionTicker（900ms）：前提是「有歌」<b>且「有人在看」</b>。
     *       它做三件事：拉播放位置（歌词滚动的心跳）、判断暂停超时退回欢迎页、
     *       以及触发 markDirty 让 Show/预览更新。
     *       ★ 条件必须是「有歌」而不是「在播」—— 暂停超时那段逻辑也要靠它。
     *       ★ 还必须「有人在看」：既没连车机、信息页也没打开时，歌词滚给谁看？
     *         这种情况（听歌但没上车）是最常见的闲置态，让它零轮询。
     *
     *   staleWatchdog（2s）：前提是「有导航数据」。
     *       它负责在高德闪退/退出导航后把界面降级，
     *       没有导航数据时它什么都不做，纯属空转。
     * </pre>
     *
     * 以前这两条是在构造函数里无条件启动的，而 {@code StateRenderer} 由
     * {@code SyncPlayCore} 在 {@code Application.onCreate} 就创建 ——
     * 于是不管有没有音乐/导航，整个进程生命周期都在每 900ms / 2s 醒一次。
     *
     * <p>公开是因为「有人在看」这个条件会变（信息页开关、车机连上/断开），
     * 外部需要在那些时机调用一次。</p>
     */
    public void ensureTickers() {
        // ① 播放位置心跳：有歌 + 有人在看
        main.removeCallbacks(positionTicker);
        if (song != null && anyoneWatching()) {
            main.postDelayed(positionTicker, POSITION_POLL_MS);
        }

        // ② 导航过期看门狗：有导航数据才跑
        main.removeCallbacks(staleWatchdog);
        if (nav != null && nav.hasData()) {
            main.postDelayed(staleWatchdog, STALE_CHECK_MS);
        }
    }

    /**
     * 有人在看吗 —— 车机正在显示，或者信息页的预览可见。
     *
     * 都不成立时，位置心跳纯属空转（拉到的位置没人用）。
     */
    private boolean anyoneWatching() {
        if (lifecycle != null && lifecycle.canDisplay()) return true;
        return com.xigaigai.syncglance.nav.AmapLinkClient.isPreviewVisible();
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

    /**
     * 上一帧时钟格是否被计时器接管 —— 只为让「计时器已结束」这行日志只打一次。
     *
     * <p>见 {@code pushClock()} 里那段说明：本方法在导航期间每秒被调用一次，
     * 无条件打日志会刷屏。</p>
     */
    private boolean lastTimerOccupying = false;

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

        // ★ 软键：第 1 格播放/暂停，第 3 格计时，其余测试键。
        //   车机底栏共 4 格，最后一格「更多…」是车机自带的。
        try {
            java.util.List<SoftButton> keys = new java.util.ArrayList<>();
            for (int i = 0; i < frame.softKeys.length; i++) {
                SoftButton b = new SoftButton();
                b.setType(SoftButtonType.SBT_TEXT);
                b.setText(frame.softKeys[i]);
                b.setSoftButtonID(SOFT_BUTTON_ID + i);
                b.setSystemAction(SystemAction.DEFAULT_ACTION);
                // ★ 高亮是「每格一个布尔」：格2 导航优先、格3 计时可以同时亮
                b.setIsHighlighted(i < frame.softKeyHighlighted.length
                        && frame.softKeyHighlighted[i]);
                keys.add(b);
            }
            s.setSoftButtons(keys);
        } catch (Exception e) {
            Log2.w(TAG, "构造软键失败: " + e.getMessage());
        }

        try {
            sender.send(s);
            lastPayload = payload;
            dirty = false;
            /*
             * 2026-11-26：删掉了原来的「Show[状态] f1 / f2 / f3 / f4 / T=…」——
             * 每次 Show 打一行四段文本，与 `SdlService` 的 `→ Show #id` 重复，
             * 且 raw 开着时完整包已经记了。真要看内容看 raw 即可。
             */
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
                /*
                 * ★ 混合模式：导航与歌曲争第 1 屏，由「导航优先」开关决定（车机软键 2 /
                 *   手机「导航模式」按钮，两处同一个开关）。
                 *
                 *   开（默认）  f1/f2 = 导航（指令 / 路名）     f3/f4 = 歌曲
                 *   关          f1/f2 = 歌曲                     f3/f4 = 导航（指令 / 路名）
                 *
                 *   ⚠️ 关闭态下导航退到第 2 屏，而车机在第 2 屏停留一会儿后会自己
                 *   跳回第 1 屏（AGENTS.md 6.7）—— 转向提示会被跳走。这是用户明确
                 *   接受的行为，不是 bug，不要"修"。
                 *
                 *   歌曲那两行：有歌词给歌词（跟随时间轴），没歌词退化成歌名 / 歌手。
                 *   以前没歌词时第 2 屏只有一句「无歌词信息」+ 空行，白占一格。
                 */
                boolean hasLyric = lyrics != null && !lyrics.isEmpty();
                String[] songTwo;
                if (hasLyric) {
                    songTwo = lyricLines(song.positionMs);
                } else {
                    songTwo = new String[]{nz(song.title), nz(song.artist)};
                }

                if (navPriority) {
                    // 第 1 屏：导航指令 / 路名
                    f.f1 = nz(nav.instruction);
                    f.f2 = nz(nav.road);
                    // 第 2 屏：歌词 / 歌名+歌手
                    f.f3 = songTwo[0];
                    f.f4 = songTwo[1];
                } else {
                    // 第 1 屏：歌词 / 歌名+歌手
                    f.f1 = songTwo[0];
                    f.f2 = songTwo[1];
                    // 第 2 屏：导航指令 / 路名
                    f.f3 = nz(nav.instruction);
                    f.f4 = nz(nav.road);
                }
                // mediaTrack：两种模式下都保持导航剩余距离（ETA 走时钟格，不占主文本）
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

        /*
         * ⚠ 这里曾经有一段「ETA 被拒 → 改放 mediaTrack 显示 13:58到」的兜底。
         *    那是基于错误归因加的（以为是"分钟位有上限"）。
         *    真实原因是「单步 PAUSE + startTime」这个写法无效，
         *    改成两步法（COUNTUP 起表 + PAUSE 停住）就能正常显示，兜底已删除。
         */

        /*
         * ★ track 行不再加任何状态指示符。
         *
         *   以前计时器占用时钟格时会在 track 前加一个 `§`（见下文 statusPrefix 的
         *   注释），用来让用户判断"那一格现在显示的是计时器还是别的东西"。
         *   但自从底栏第 3 格做成「计时」键、并且**计时激活时该键会高亮**之后，
         *   这个信息已经由软键高亮表达出来了 —— 两个信号重复，还白占 track 的
         *   8 字符宽度。所以移除。
         *
         *   ⚠ 不要因为"看着空"又把符号加回来：车机字库对箭头/三角/播放符
         *     一律渲染成圆点（AGENTS.md 6.8），加了反而是噪点。
         */

        // ★ 第 3 行右侧：计时器「指令」，不是算好的读数
        f.clock = currentClockInstruction();

        // ★ 底栏软键（第 1 格播放/暂停，第 3 格计时，其余是测试键）
        java.util.List<String> keys = getSoftKeyTexts();
        f.softKeys = keys.toArray(new String[0]);
        // ★ 每格一个布尔：格2 导航优先（仅混合模式）、格3 计时，可同时高亮
        f.softKeyHighlighted = softKeyHighlights();

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
     * 当前应该显示的计时器指令（预览用）。
     *
     * <p>★ <b>与实车同源</b>：和 {@code songClockRpcList()} 一样都走
     * {@link ClockPlanner#plan}，这里只取 {@code preview} 字段。
     * 以前预览和实车各算一套，已经出现过「预览 59:59、实车 00:00」这类分叉，
     * 加一个来源就要改两处 —— 现在只有一个决策点。</p>
     */
    private ClockInstruction currentClockInstruction() {
        ClockPlanner.Plan plan = ClockPlanner.plan(plannerInput(System.currentTimeMillis()));
        ClockInstruction p = plan.preview;
        return reuseClock(p.mode, p.h, p.m, p.s, p.issuedAtMs);
    }

    /**
     * 把当前状态打成一份快照交给 {@link ClockPlanner} —— <b>唯一入口</b>。
     *
     * <p>★ ETA 直接从 {@code nav} 取，<b>不</b>依赖 {@code buildFrame()} 写好的
     * {@code navClockText}。原因：{@code pushClock()} 经常在 {@code buildFrame()}
     * 之前被同步调用（{@code SyncPlayCore.setNavigation()} 是先设数据再推时钟），
     * 那时 {@code navClockText} 还是上一帧的值，会白等一个推送周期才把 ETA 打上去。</p>
     */
    private ClockPlanner.Input plannerInput(long nowMs) {
        ClockPlanner.Input in = new ClockPlanner.Input();
        in.timerActive = timerActive;
        in.timerRunning = timerRunning;
        in.timerCountdown = timerCountdown;
        in.timerH = timerH;
        in.timerM = timerM;
        in.timerS = timerS;
        in.timerStartMs = timerStartMillis;
        in.timerPausedMs = timerPausedAt;

        Navigation n = nav;
        in.etaText = (n != null && n.hasData()) ? n.etaClockNow() : null;

        in.songPresent = song != null && song.hasData();
        if (in.songPresent) {
            in.songPlaying = song.playing;
            in.songPosMs = song.positionMs;
            in.songDurationMs = song.durationMs;
            in.songTitle = nz(song.title);
            in.songArtist = nz(song.artist);
        }
        in.songEndTimeEnabled = songEndTimeEnabled;
        in.nowMs = nowMs;
        return in;
    }

    /**
     * 歌曲正计时是否带 {@code endTime = 歌曲总时长}。
     *
     * <p>默认关，第 3 批接上 UI 后打开。车机若不接受 {@code endTime} 会整条被拒，
     * 所以 {@code SdlService} 收到拒绝时会调用 {@link #setSongEndTimeEnabled(false)}
     * 并重推一次 —— 这条特性不允许"赌一把失败就什么都不显示"。</p>
     */
    private volatile boolean songEndTimeEnabled = false;

    /** 打开 / 关闭「歌曲正计时带 endTime = 总时长」 */
    public void setSongEndTimeEnabled(boolean on) {
        if (songEndTimeEnabled == on) return;
        songEndTimeEnabled = on;
        // 指令内容变了，指纹必须作废，否则不会重发
        lastClockFingerprint = null;
        Log2.i(TAG, "歌曲 endTime（总时长）" + (on ? "已启用" : "已禁用"));
    }

    /**
     * 同一条指令就沿用原来的 issuedAtMs —— 否则每次重建帧都会把走表起点重置到 0，
     * 预览里的秒数就不动了。
     *
     * <p>★ {@code anchor} 也要参与比较：计时器「暂停 → 继续」时
     * {@code timerStartMillis} 会整体平移（把暂停时长补回去），
     * 此时 mode/h/m/s 都没变，但走表起点变了。只看前四个字段就会沿用旧对象，
     * 预览会把暂停那段时长也算进去，读数凭空多出一截。</p>
     */
    private ClockInstruction reuseClock(ClockInstruction.Mode mode, int h, int m, int s, long anchor) {
        ClockInstruction last = lastClock;
        if (last != null && last.mode == mode && last.h == h && last.m == m
                && last.s == s && last.issuedAtMs == anchor) {
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

    public boolean isTimerRunning() { return timerRunning; }

    /** 计时器是否还「占着」屏幕（含暂停态） */
    public boolean isTimerActive() { return timerActive; }

    /** 记录计时器状态，供预览模拟走表 */
    public void setTimerState(int h, int m, int s, String mode) {
        /*
         * ★ 只有「开始」才允许重置方向与起始值。
         *
         *   之前这四个字段在方法最前面无条件赋值，于是点「暂停计时」时
         *   MainActivity 传的 (0,0,0,"PAUSE") 会把倒计时的起始值冲成 0、
         *   把 timerCountdown 冲成 false —— timerElapsedSeconds() 就不再走
         *   "剩余 = total - elapsed" 那条路，倒计时暂停后会显示成已过秒数
         *   （比如 5 分钟走了 10 秒，显示 00:10 而不是 04:50）。
         */
        switch (mode) {
            case "COUNTUP":
            case "COUNTDOWN":
                timerH = h;
                timerM = m;
                timerS = s;
                timerCountdown = "COUNTDOWN".equals(mode);
                timerStartMillis = System.currentTimeMillis();
                timerPausedAt = 0;
                timerRunning = true;
                timerActive = true;
                break;
            case "PAUSE":
                /*
                 * ★ 没有计时器时的 PAUSE 直接忽略。
                 *
                 *   否则 timerActive 会被置 true、读数恒为 0，两步法定格把车机
                 *   定在 00:00 —— 用户只是想按一下「暂停」，结果凭空多出一块表。
                 */
                if (!timerActive) {
                    Log2.i(TAG, "忽略 PAUSE：当前没有计时器");
                    return;
                }
                // 暂停只是"停止走秒"，方向和起始值一律不动
                timerPausedAt = System.currentTimeMillis();
                timerRunning = false;
                timerActive = true;          // ★ 暂停 = 仍然占屏，只是不走秒
                break;
            case "RESUME":
                /*
                 * ★ 没有可恢复的计时器时忽略。
                 *
                 *   以前这里照样把 timerRunning 置 true，而 timerStartMillis 还是 0 ——
                 *   预览按「now - 0」推出来的读数是 59:59（clamp 后），
                 *   而实车侧因为压根没有表在跑，RESUME 会被 REJECTED。
                 *   同一次操作，两边给出完全相反的结果。
                 */
                if (!timerActive || timerStartMillis == 0) {
                    Log2.i(TAG, "忽略 RESUME：没有可恢复的计时器");
                    return;
                }
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
         * ★ 用户主动改了计时状态 → 指纹作废，必须重新决策一次。
         *
         *   以前这里有 5 个互相打架的标记（timerClockKey / songClockStarted /
         *   lastSongKey / lastClockTextSent / lastEtaSent），加一个来源就要想清楚
         *   该清哪几个。现在只有一个指纹。
         *
         *   注意：调用方（{@code SyncPlayCore.doTimer}）发完显式指令后会用
         *   {@link #markClockFingerprintSynced()} 把它同步成「已发」，避免紧接着的
         *   {@code pushClock()} 把同一条指令再发一遍。
         */
        lastClockFingerprint = null;
        markDirty("timerState");
    }

    /**
     * 计算当前应显示的计时器秒数（正计时=已过秒数，倒计时=剩余秒数）。
     *
     * <p>委托给 {@link ClockPlanner#timerValueSeconds} —— 读数只允许有一个实现，
     * 否则预览、日志、车机指令三处各算一遍，迟早对不上。</p>
     */
    public long timerElapsedSeconds() {
        return ClockPlanner.timerValueSeconds(plannerInput(System.currentTimeMillis()));
    }

    /**
     * 用户计时器此刻是否「接管」时钟格（含暂停态）。
     *
     * <p>这是软键高亮、时钟格优先级的<b>唯一判据</b>：
     * 只要时钟格显示的是计时器读数，就算接管 —— 不管它在跑还是暂停。</p>
     */
    public boolean isTimerOccupyingClock() {
        return timerActive;
    }

    /**
     * 倒计时是否已经走完（读数为 0）但还占着屏幕。
     *
     * <p>用户 2026-11-27 的决定：<b>到期不自动清除</b> —— 屏幕上保留 {@code 00:00}，
     * 只有手动点车机底栏的「计时」键才发 CLEAR。所以这里只给判据，
     * 语义由 {@code CommandRouter.handleTimerKey} 决定。</p>
     */
    public boolean isTimerExpired() {
        return timerActive && timerCountdown && timerElapsedSeconds() <= 0;
    }

    /**
     * 车机那边「时钟格已经是当前状态」的指纹（来自 {@link ClockPlanner.Plan#fingerprint}）。
     *
     * <p>{@code null} = 还没发过、刚清零、或刚回到 FULL —— 都必须重新决策一次。
     * 这一个字段替代了以前的 {@code timerClockKey / songClockStarted / lastSongKey /
     * lastClockTextSent / lastEtaSent} 五个互相打架的标记。</p>
     */
    private volatile String lastClockFingerprint = null;

    /**
     * 把指纹标记成「车机那边就是现在这个状态」。
     *
     * <p>用于 {@code SyncPlayCore.doTimer()} 发完<b>显式</b>指令之后：那条指令已经
     * 按用户意图发出去了，紧接着的 {@code pushClock()} 不该再发一遍。
     * 清零是例外 —— 清零后要让 {@code pushClock()} 立刻决定回落
     * （ETA / 播放时间 / CLEAR），所以那边不调这个方法。</p>
     */
    public void markClockFingerprintSynced() {
        lastClockFingerprint = ClockPlanner
                .plan(plannerInput(System.currentTimeMillis())).fingerprint;
    }

    /**
     * 用户计时器指令（显式操作：开始 / 暂停 / 继续 / 清零）。
     *
     * <b>只构造，不发送</b>：发不发、发不出去怎么办，由调用方
     * （{@code SyncPlayCore}）决定。这样计时器的"本地状态"和"发 RPC"
     * 就解耦了 —— 没连车机时本地状态照样更新，屏幕预览才是对的。
     *
     * ⚠ 调用前必须先 {@link #setTimerState}：PAUSE 的定格读数依赖它记下的
     *   {@code timerPausedAt}，顺序反了会算出"还在走"的值。
     *
     * @return 要按顺序发出的指令（0~2 条）。暂停定格返回两条，见 {@link ClockRpc}
     */
    public java.util.List<SetMediaClockTimer> timerRpcList(int h, int m, int s, String mode) {
        UpdateMode um;
        try {
            um = UpdateMode.valueForString(mode);
        } catch (Exception e) {
            um = UpdateMode.COUNTUP;
        }

        switch (um) {
            case PAUSE: {
                long sec = timerElapsedSeconds();
                int hh = (int) (sec / 3600);
                int mm = (int) ((sec / 60) % 60);
                int ss = (int) (sec % 60);
                Log2.i(TAG, "计时器暂停，两步法定格在 " + ClockPlanner.fmt(hh, mm, ss));
                return asList(ClockRpc.freezeDuration(hh, mm, ss));
            }
            case RESUME:
                return asList(ClockRpc.resume());
            case CLEAR:
                return asList(ClockRpc.clear());
            case COUNTDOWN:
                return asList(ClockRpc.countDown(h, m, s, null));
            case COUNTUP:
            default:
                return asList(ClockRpc.countUp(h, m, s, null));
        }
    }

    /** 可变列表小工具（{@link ClockDispatcher} 要 List） */
    private static java.util.List<SetMediaClockTimer> asList(SetMediaClockTimer... rpcs) {
        java.util.List<SetMediaClockTimer> out = new java.util.ArrayList<>(rpcs.length);
        java.util.Collections.addAll(out, rpcs);
        return out;
    }

    /**
     * 算出「现在该往车机发的时钟指令」。
     *
     * <p>决策全部在 {@link ClockPlanner}。这里只做两件事：<b>指纹去重</b> +
     * 一条「这一格现在归谁」的日志。</p>
     *
     * <p>以前这里是 250 行手写分支，每加一个来源就要想清楚该清哪几个标记，
     * 直接导致过「ETA 覆盖计时器」「清零后不回播放时间」两个实车 bug
     * （2026-11-22 修）。现在只有一个决策点和一个指纹。</p>
     *
     * @return 要按顺序发出的 RPC；空列表 = 车机那边已经是对的，不用发
     */
    public java.util.List<SetMediaClockTimer> songClockRpcList() {
        if (sender == null) return java.util.Collections.emptyList();

        ClockPlanner.Plan plan = ClockPlanner.plan(plannerInput(System.currentTimeMillis()));

        /*
         * 日志降噪：只在「计时器从有到无 / 从无到有」的那一刻打一条。
         *
         * 本方法（pushClock）导航期间每秒被调一次（高德每秒推数据），
         * 无条件打会刷屏 —— 长途实测 5.5 小时打了 305 条。
         */
        if (plan.occupying() != lastTimerOccupying) {
            lastTimerOccupying = plan.occupying();
            if (lastTimerOccupying) {
                Log2.i(TAG, "时钟: 计时器接管");
            } else {
                Log2.i(TAG, "时钟: 交回 ETA/播放");
            }
        }

        if (plan.fingerprint.equals(lastClockFingerprint)) return java.util.Collections.emptyList();
        lastClockFingerprint = plan.fingerprint;
        Log2.i(TAG, "时钟 → " + plan.reason);
        return plan.rpcs;
    }

    /** 第3行右侧应该显示的 ETA 时刻（null = 没有导航）。预览 / 诊断用 */
    private volatile String navClockText = null;

    /** 预览用：取第3行右侧应显示的时间 */
    public String getNavClockText() { return navClockText; }

    /**
     * 清掉时钟指纹，让下一次 {@link #songClockRpcList()} 无条件重算重发。
     *
     * <p>用在「回到 HMI_FULL / 重新连上」：那一刻车机那边的时钟格<b>已经被清空</b>了，
     * 但我们这边的 ETA 没变、歌没换，指纹会判定"不用发" —— 结果那一格永远空着。</p>
     */
    public void invalidateClockCache() {
        lastClockFingerprint = null;
    }

    /*
     * 这里曾有 songLabel() / playTimeText() / fmtTime() / notEmpty() 四个小工具：
     * 分别拼「歌名 - 歌手」、把播放进度塞进 track 行、格式化毫秒。
     * 2026-11-27 清理：都没有调用方了（track 行早已改放导航距离或最干净歌名，
     * 播放进度改由 SetMediaClockTimer 显示），删掉以免被误用。
     */

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

    /*
     * 这里曾经有 statusPrefix()：计时器占用时钟格时在 track 前加 `§`（U+00A7），
     * 让用户能判断那一格现在显示的是计时器还是别的东西。
     *
     * 已移除。原因：底栏第 3 格「计时」键在计时激活时会**高亮**
     * （SoftButton.setIsHighlighted），那个信号更直观，也已经在两屏都可见；
     * 再挂一个 § 属于重复表达，还白占 track 的 8 字符宽度。
     *
     * 注意：Glyphs.ENABLED 仍然是 false，箭头/沙漏那套符号不要重新打开 ——
     * 车机对它们一律渲染成圆点（AGENTS.md 6.8）。
     */
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

    // 字符宽度统计已移除：唯一使用者是上面的 charCount()（无人调用）。
    // 需要"半角单位"宽度时用 SyncLyric / ScreenPreviewView 里那份实现。
}
