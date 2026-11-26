package com.xigaigai.syncglance.core;

import android.content.Context;

import com.smartdevicelink.proxy.rpc.SetMediaClockTimer;
import com.xigaigai.syncglance.display.ClockDispatcher;
import com.xigaigai.syncglance.display.StateRenderer;
import com.xigaigai.syncglance.display.SyncLifecycle;
import com.xigaigai.syncglance.lyric.Lrc;
import com.xigaigai.syncglance.lyric.LyricManager;
import com.xigaigai.syncglance.model.Navigation;
import com.xigaigai.syncglance.model.NowPlaying;
import com.xigaigai.syncglance.nav.AmapLinkClient;
import com.xigaigai.syncglance.service.NotificationCatcher;
import com.xigaigai.syncglance.util.Log2;

import java.io.File;
import java.util.List;

/**
 * 应用级中枢：**数据采集 + 渲染状态 + 歌词**，与 SDL 连接完全解耦。
 *
 * <h3>为什么要拆出这一层</h3>
 * 之前 {@code StateRenderer} / {@code LyricManager} 都活在 {@code SdlService} 里，
 * 一旦服务因为「没连车机」而退出（SDL 5.x 的正常语义），
 * 界面上的车机预览就拿不到任何状态，永远是空画布。
 *
 * 现在：数据从哪里来（通知、高德 AIDL）和「场景长什么样」（StateRenderer）
 * 都归这里管；{@link com.xigaigai.syncglance.service.SdlService} 退化成一个
 * 「连接 + 发送」的薄层，通过 {@link #attachSender} 挂上来。
 * 服务不在时，预览仍然实时反映手机上的歌曲/导航。
 *
 * <h3>为将来抽成独立库做准备</h3>
 * 这里刻意不依赖任何 SDL 具体实现，只依赖：
 *   - 数据源：{@link NotificationCatcher}（通知）、{@link AmapLinkClient}（高德）
 *   - 出口：{@link StateRenderer.Sender}（把一条 RPC 交出去）
 * 换车机 / 换 AppLink 应用时，替换数据源与 Sender 即可，渲染逻辑可整体搬走。
 */
public final class SyncPlayCore {

    private static final String TAG = "Core";

    private static volatile SyncPlayCore instance;

    public static SyncPlayCore get() {
        if (instance == null) {
            synchronized (SyncPlayCore.class) {
                if (instance == null) instance = new SyncPlayCore();
            }
        }
        return instance;
    }

    // ---------------- 核心状态 ----------------

    private final StateRenderer renderer = new StateRenderer();
    private final SyncLifecycle lifecycle = new SyncLifecycle();

    private LyricManager lyricManager;
    private volatile boolean inited;

    /**
     * 当前真正能把 RPC 送出去的人（SdlService 挂上来的）。
     * 服务不在时为 null —— 渲染器照常算，只是发不出去。
     */
    private volatile StateRenderer.Sender sender;

    /**
     * 时钟指令的<b>唯一</b>发送队列。
     *
     * <p>所有 {@code SetMediaClockTimer} 都从这里出去：单 in-flight、严格按序、
     * 新批次覆盖排队中的旧批次。没有它，两次独立的两步法可能交错成
     * {@code COUNTUP(ETA) → COUNTUP(timer) → PAUSE → PAUSE}，定格读数就错了。
     * 见 {@link ClockDispatcher}。</p>
     */
    private final ClockDispatcher clockDispatcher = new ClockDispatcher(this::sendClockRpc);

    /** 数据变化通知（服务用它刷新通知栏，界面不需要订阅） */
    private volatile Runnable dataChangedListener;

    private SyncPlayCore() { }

    // ---------------- 初始化 ----------------

    /**
     * 幂等初始化。由 {@code SyncGlanceApp.onCreate()} 调用，
     * 之后任何组件都可以直接 {@link #get()} 拿状态。
     */
    public void init(Context ctx) {
        synchronized (this) {
            if (inited) return;
            inited = true;

            Context app = ctx.getApplicationContext();
            Log2.init(app);

            lyricManager = new LyricManager(new File(app.getCacheDir(), "lyrics"));

            renderer.setLifecycle(lifecycle);
            /*
             * 播放时间的正计时带 endTime = 歌曲总时长。
             *
             * 这样车机知道"这块表走到哪算完"，不再是一块没有终点的表。
             * ⚠ 车机若不接受 endTime 会整条 REJECTED（连播放时间都不显示），
             *   所以 SdlService 收到拒绝时会调 {@link #disableSongEndTime()}
             *   自动降级重推 —— 不要假设它一定被接受。
             */
            renderer.setSongEndTimeEnabled(true);
            // 渲染器的所有出站 RPC 都先到这里，再转发给当前挂着的发送者
            renderer.setSender(new StateRenderer.Sender() {
                @Override
                public void send(com.smartdevicelink.proxy.RPCMessage msg) {
                    StateRenderer.Sender s = sender;
                    if (s != null) s.send(msg);
                }

                @Override
                public void sendWithAck(com.smartdevicelink.proxy.RPCMessage msg, Runnable onDone) {
                    StateRenderer.Sender s = sender;
                    if (s != null) s.sendWithAck(msg, onDone);
                    else onDone.run();
                }
            });

            /*
             * 导航过期是渲染器内部定时器发现的（staleWatchdog），不经过
             * setNavigation()，所以要让它反过来通知我们推一次时钟 ——
             * 否则导航结束降级到空闲后，车机上那个 ETA 读数会一直停着。
             */
            renderer.setClockRefreshListener(this::pushClock);

            // 导航数据过期时顺手探一次 AmapLink：binder 死了就重绑
            // （高德被关闭时系统不保证回调，只能靠这里兜住）
            renderer.setAmapProbeListener(() -> com.xigaigai.syncglance.nav.AmapLinkClient.probeAndReconnect());

            // 数据源：音乐通知 + 高德导航
            NotificationCatcher.setMusicListener(musicListener);
            AmapLinkClient.setNavListener(navListener);
            AmapLinkClient.get(app).start();

            Log2.i(TAG, "Core 初始化");
        }
    }

    // ---------------- 连接层挂钩 ----------------

    /** SdlService 连上时把发送通道挂上来 */
    public void attachSender(StateRenderer.Sender s) {
        sender = s;
        renderer.onLifecycleChanged();
        Log2.i(TAG, "发送通道挂载");
    }

    /** 连接结束时摘掉发送通道；渲染与数据采集不受影响 */
    public void detachSender() {
        sender = null;
        renderer.onDisconnected();
        Log2.i(TAG, "发送通道摘除");
    }

    public boolean canSend() {
        return sender != null && lifecycle.canSendRpc();
    }

    public void setOnDataChanged(Runnable r) { this.dataChangedListener = r; }

    private void notifyDataChanged() {
        Runnable r = dataChangedListener;
        if (r != null) {
            try { r.run(); } catch (Exception ignored) { }
        }
    }

    // ---------------- 对外只读状态（界面/预览用） ----------------

    public StateRenderer renderer() { return renderer; }

    public SyncLifecycle lifecycle() { return lifecycle; }

    public List<Lrc.Line> lyrics() {
        return lyricManager == null ? null : lyricManager.getCurrent();
    }

    public NowPlaying song() { return renderer.getSong(); }

    public Navigation navigation() { return renderer.getNavigation(); }

    // ---------------- 动作 ----------------

    /**
     * 用户计时器：正计时 / 倒计时 / 暂停 / 继续 / 清零。
     *
     * <h3>为什么放在这里，而不是 SdlService</h3>
     * 计时器是**本地状态**（车机拿到指令后自己走表），跟连接没有必然关系：
     * 没连车机时也该能设、屏幕预览也该能看到。
     *
     * 而 {@code SdlService} 现在是「断开就 stopSelf」（官方语义），平时根本不存在，
     * 挂在它上面的按钮会直接 "服务未启动" 而什么都不做 —— 之前就踩了这个坑。
     *
     * 所以流程改成：
     *   ① 先更新 {@link StateRenderer} 的本地计时状态（预览立刻正确）
     *   ② 再构造 RPC
     *   ③ **有连接才发**；没连接就只留在本地状态里，等回到 FULL 时由
     *      {@link #pushClockForce()} 按当前状态重投
     */
    public void doTimer(int h, int m, int s, String mode) {
        // ① 先冻结本地状态（PAUSE 要靠它记下 timerPausedAt）
        renderer.setTimerState(h, m, s, mode);

        /*
         * ② CLEAR 不在这里发「显式指令」，交给下面的 pushClock() 决策。
         *
         *   清零之后这一格该显示什么，取决于当下有没有导航 / 有没有在播 ——
         *   可能是 ETA、可能是播放时间、也可能真的该空着。以前这里固定发一条
         *   CLEAR，然后等下一次事件才重推，于是「清零后那一格空着」会一直持续到
         *   下一分钟 ETA 变化为止（有歌无导航时更久，因为通知不常来）。
         *
         *   现在由 ClockPlanner 一步算出来：要么直接切到 ETA / 播放时间，
         *   要么发 CLEAR —— 中间不存在空白期。
         */
        if (!"CLEAR".equals(mode)) {
            sendExplicitTimerRpc(h, m, s, mode);
            /*
             * 显式指令已按用户意图发出 → 把指纹同步过去，
             * 免得紧接着的 pushClock() 把同一条指令再发一遍。
             */
            renderer.markClockFingerprintSynced();
        }

        // ③ 再按「现在该显示什么」决策一次：清零后立刻回落，其余情况被指纹挡住
        pushClock();
    }

    /**
     * 发送用户计时器的<b>显式</b>指令（开始 / 暂停定格 / 继续）。
     *
     * <p>为什么不走 {@link #pushClock()}：那不是「用户按了什么」的语义。
     * 「继续」要发的是 {@code RESUME}（让车机接着走那块表），而 pushClock 只会
     * 按当前读数重投一条 {@code COUNTUP}。两者显示效果一样，但 RESUME 更贴合协议、
     * 也少一条指令。</p>
     */
    private void sendExplicitTimerRpc(int h, int m, int s, String mode) {
        java.util.List<SetMediaClockTimer> list;
        try {
            list = renderer.timerRpcList(h, m, s, mode);
        } catch (Exception e) {
            Log2.w(TAG, "构造计时器 RPC 失败: " + e.getMessage());
            return;
        }
        if (list == null || list.isEmpty()) return;

        if (sender == null || !lifecycle.canSendRpc()) {
            Log2.i(TAG, "计时器本地更新（未连接，暂不发）: " + mode);
            return;
        }
        // 统一进时钟队列：和导航 ETA / 播放时间的两步法共享同一条串行线
        clockDispatcher.submit(list, "计时器 " + mode);
    }

    /**
     * {@link ClockDispatcher} 的出口：把一条指令转给当前挂着的 {@link StateRenderer.Sender}。
     *
     * <p>没连接时立刻回调 {@code onDone} —— 队列不能因为"发不出去"而卡住。</p>
     */
    private void sendClockRpc(com.smartdevicelink.proxy.RPCMessage msg, Runnable onDone) {
        StateRenderer.Sender s = sender;
        if (s == null) {
            onDone.run();
            return;
        }
        s.sendWithAck(msg, onDone);
    }

    /** 「显示歌词」：重新走一遍加载（内存缓存命中会立刻回调，不联网） */
    public void reloadLyrics() {
        if (lyricManager == null) return;
        NowPlaying np = renderer.getSong();
        if (np == null) return;
        lyricManager.load(np, (song, lines) -> renderer.setLyrics(lines));
    }

    public void setNavigation(Navigation nav) {
        renderer.setNavigation(nav);
        // ★ 导航一变，ETA 可能也变了 —— 必须顺手推一次时钟，
        //   否则车机上那个到达时刻只会在换歌的瞬间刷新（历史 bug）。
        pushClock();
    }

    /**
     * 清空歌词缓存并强制重新查一次当前这首歌。
     *
     * @return 删掉的文件个数
     */
    public int clearLyricCache() {
        int removed = lyricManager == null ? 0 : lyricManager.clearCache();
        // 屏上的旧歌词也要清掉，否则看起来像"没反应"
        renderer.setLyrics(null);
        reloadLyrics();
        return removed;
    }

    /**
     * 把「当前该显示的时间」推给车机。
     *
     * <h3>为什么是"多条"</h3>
     * 车机的 `PAUSE` 会忽略 `startTime`，只暂停当前正在跑的那块表
     * （实车 RPC Builder 验证，见 AGENTS.md §6.6.1）。
     * 所以「把那一格定在某个读数」必须两步：
     * <pre>
     *   ① COUNTUP(startTime)  起表
     *   ② PAUSE               停住
     * </pre>
     * `songClockRpcList()` 返回 0~2 条，这里按顺序发。
     *
     * <p>内部自带门槛（ETA 没变就不发、歌没换就不重发），可以放心高频调用。
     * 发送者不在时是空操作。</p>
     */
    private void pushClock() {
        pushClock(false);
    }

    /**
     * @param force true = 清掉指纹后重算重发。
     *              用于「回到 HMI_FULL / 重新连上」这种<b>车机那边的时钟格已经被清空</b>
     *              的场景 —— 那时 ETA 没变、歌没换，指纹会判定"不用发"，
     *              结果车机上那一格永远空着。
     */
    private void pushClock(boolean force) {
        if (force) renderer.invalidateClockCache();

        java.util.List<com.smartdevicelink.proxy.rpc.SetMediaClockTimer> list =
                renderer.songClockRpcList();
        if (list == null || list.isEmpty()) return;
        if (sender == null) return;

        // 统一进时钟队列（两步法必须严格按序，且不能被别的时钟指令插队）
        clockDispatcher.submit(list, "时钟更新 " + list.size() + " 条");
    }

    /**
     * 回到 HMI_FULL / 重连后调用：按当前状态<b>强制</b>重推一次时钟。
     *
     * <p>覆盖这些情况：</p>
     * <ul>
     *   <li>计时器在跑 → 按本地记录的起始值重算读数，重投（车机那边已经丢了）</li>
     *   <li>计时器暂停 → 两步法重投定格读数（绝不能单条 PAUSE + startTime）</li>
     *   <li>倒计时已经走完 → 仍然显示 00:00（不自动清除，等用户手动清零）</li>
     *   <li>没计时器 → 重投 ETA / 播放时间 / CLEAR</li>
     * </ul>
     */
    public void pushClockForce() {
        Log2.i(TAG, "强制重推时钟（回到 FULL / 重连）");
        pushClock(true);
    }

    /**
     * 车机不接受 {@code endTime} 时由连接层调用：关掉「歌曲总时长」并重推一次。
     *
     * <p>不降级的话，那条带 endTime 的 COUNTUP 会被整条拒掉 —— 播放时间干脆不显示了。</p>
     */
    public void disableSongEndTime() {
        renderer.setSongEndTimeEnabled(false);
        pushClockForce();
    }

    // ---------------- 数据源回调 ----------------

    private final NotificationCatcher.MusicListener musicListener = new NotificationCatcher.MusicListener() {
        @Override
        public void onNowPlaying(NowPlaying np) {
            if (np == null) return;

            NowPlaying cur = renderer.getSong();
            boolean songChanged = cur == null || !np.sameSong(cur);

            renderer.setSong(np);

            // 换歌了，重新拉歌词
            if (songChanged && lyricManager != null) {
                // 先把上一首的歌词清掉：新歌词要查网络时可能要等几秒，
                // 不清会拿新歌的播放进度去切旧歌的时间轴，显示出一段「时间错位」的歌词。
                renderer.setLyrics(null);
                lyricManager.load(np, (song, lines) -> {
                    renderer.setLyrics(lines);
                    notifyDataChanged();
                });
            }

            pushClock();
            notifyDataChanged();
        }

        @Override
        public void onMusicGone() {
            Log2.i(TAG, "音乐通知消失 → 清空歌曲");
            renderer.clearSong();
            notifyDataChanged();
        }
    };

    private final AmapLinkClient.NavListener navListener = nav -> {
        if (nav == null) return;
        if (nav.hasData()) {
            setNavigation(nav);

            /*
             * 2026-11-26：删掉了原来的 `导航数据: nav[...]` 日志 ——
             * 它与 AmapLink 那条「导航 type4 左转 段316m 全程…」完全重复，
             * 同一份数据每变化一次打两遍。导航侧要看字段变化看 AmapLink 那条即可。
             */
        } else {
            // 空对象 = 导航结束/到达/高德退出，必须立刻降级
            Log2.i(TAG, "导航结束 → 降级");
            renderer.clearNavigation();
            renderer.markDirty("navEnd");
        }
        notifyDataChanged();
    };
}
