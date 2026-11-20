package com.xigaigai.syncglance.core;

import android.content.Context;

import com.smartdevicelink.proxy.rpc.SetMediaClockTimer;
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

    /** 数据变化通知（服务用它刷新通知栏，界面不需要订阅） */
    private volatile Runnable dataChangedListener;

    /** 上一次记进日志的导航摘要（降噪用） */
    private String lastNavLog;

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
            // 渲染器的所有出站 RPC 都先到这里，再转发给当前挂着的发送者
            renderer.setSender(msg -> {
                StateRenderer.Sender s = sender;
                if (s != null) s.send(msg);
            });

            // 数据源：音乐通知 + 高德导航
            NotificationCatcher.setMusicListener(musicListener);
            AmapLinkClient.setNavListener(navListener);
            AmapLinkClient.get(app).start();

            Log2.i(TAG, "SyncPlayCore 初始化完成（数据采集与连接层已解耦）");
        }
    }

    // ---------------- 连接层挂钩 ----------------

    /** SdlService 连上时把发送通道挂上来 */
    public void attachSender(StateRenderer.Sender s) {
        sender = s;
        renderer.onLifecycleChanged();
        Log2.i(TAG, "发送通道已挂载（可以往车机发了）");
    }

    /** 连接结束时摘掉发送通道；渲染与数据采集不受影响 */
    public void detachSender() {
        sender = null;
        renderer.onDisconnected();
        Log2.i(TAG, "发送通道已摘除（预览仍可工作）");
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
     *   ③ **有连接才发**；没连接就记一个待发标记，等连上后由
     *      {@link #resendTimerIfPending()} 补发
     */
    public void doTimer(int h, int m, int s, String mode) {
        lastTimerMode = mode;
        lastTimerH = h;
        lastTimerM = m;
        lastTimerS = s;

        // ① 先冻结本地状态（PAUSE 要靠它记下 timerPausedAt）
        renderer.setTimerState(h, m, s, mode);
        // 用户计时器接管后，歌曲计时作废
        renderer.resetSongClock();

        // ② 构造 RPC
        SetMediaClockTimer t;
        try {
            t = renderer.timerRpc(h, m, s, mode);
        } catch (Exception e) {
            Log2.w(TAG, "构造计时器 RPC 失败: " + e.getMessage());
            return;
        }
        if (t == null) return;

        // ③ 有连接才发
        StateRenderer.Sender sn = sender;
        if (sn == null || !lifecycle.canSendRpc()) {
            timerPendingSend = true;
            Log2.i(TAG, "计时器已更新本地状态（未连接，暂不发送）: " + mode);
            return;
        }
        timerPendingSend = false;
        t.setCorrelationID(nextCorr());
        sn.send(t);
        Log2.i(TAG, "计时器已发送: " + mode);
    }

    /**
     * 连上并进入 FULL 后调用：把"没发出去的计时器指令"补发一次。
     *
     * 场景：用户没连车机时设了计时器（本地状态已生效），之后车机连上。
     */
    public void resendTimerIfPending() {
        if (!timerPendingSend) return;
        if (sender == null || !lifecycle.canSendRpc()) return;
        Log2.i(TAG, "补发计时器指令: " + lastTimerMode);
        timerPendingSend = false;
        doTimer(lastTimerH, lastTimerM, lastTimerS, lastTimerMode);
    }

    public boolean isTimerPendingSend() { return timerPendingSend; }

    /** correlation ID 由本类统一分配（服务没起来时也要能用） */
    private int corrId = 1000;

    private int nextCorr() { return corrId++; }

    private volatile boolean timerPendingSend = false;
    private volatile int lastTimerH = 0, lastTimerM = 0, lastTimerS = 0;
    private volatile String lastTimerMode = "COUNTUP";

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

    public void setManualNavMode(boolean on) {
        Navigation n = renderer.getNavigation();
        if (n == null) n = new Navigation();
        n.manualMode = on;
        renderer.setNavigation(n);
        Log2.i(TAG, "导航模式: " + (on ? "开" : "关"));
    }

    public boolean isManualNavMode() {
        Navigation n = renderer.getNavigation();
        return n != null && n.manualMode;
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
     * songClockRpc() 内部自带门槛（ETA 没变就不发、歌没换就不重发），
     * 所以可以放心被高频调用。发送者不在时这里是空操作。
     */
    private void pushClock() {
        com.smartdevicelink.proxy.rpc.SetMediaClockTimer clock = renderer.songClockRpc();
        if (clock == null) return;
        StateRenderer.Sender s = sender;
        if (s == null) return;
        s.send(clock);
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
            Log2.i(TAG, "音乐通知消失，清空歌曲状态并降级显示");
            renderer.clearSong();
            notifyDataChanged();
        }
    };

    private final AmapLinkClient.NavListener navListener = nav -> {
        if (nav == null) return;
        if (nav.hasData()) {
            setNavigation(nav);

            // 日志降噪：高德每秒都推，只在关键字段变化时记一条
            String summary = nav.instruction + "|" + nav.road + "|"
                    + nav.remainDistance + "|" + nav.etaClockNow();
            if (!summary.equals(lastNavLog)) {
                lastNavLog = summary;
                Log2.i(TAG, "导航数据: " + nav);
            }
        } else {
            // 空对象 = 导航结束/到达/高德退出，必须立刻降级
            Log2.i(TAG, "导航已结束，立即降级显示");
            lastNavLog = null;
            renderer.clearNavigation();
            renderer.markDirty("navEnd");
        }
        notifyDataChanged();
    };
}
