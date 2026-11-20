package com.xigaigai.syncglance.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.smartdevicelink.exception.SdlException;
import com.smartdevicelink.proxy.RPCRequest;
import com.smartdevicelink.proxy.SdlProxyALM;
import com.smartdevicelink.proxy.SdlProxyBuilder;
import com.smartdevicelink.proxy.callbacks.OnServiceEnded;
import com.smartdevicelink.proxy.callbacks.OnServiceNACKed;
import com.smartdevicelink.proxy.interfaces.IProxyListenerALM;
import com.smartdevicelink.proxy.rpc.*;
import com.smartdevicelink.proxy.rpc.enums.*;
import com.smartdevicelink.transport.MultiplexTransportConfig;
import com.xigaigai.syncglance.display.StateRenderer;
import com.xigaigai.syncglance.display.SyncLifecycle;
import com.xigaigai.syncglance.lyric.LyricManager;
import com.xigaigai.syncglance.model.Navigation;
import com.xigaigai.syncglance.model.NowPlaying;
import com.xigaigai.syncglance.util.Log2;

import java.util.Vector;

/**
 * SyncGlance 的 SDL 服务。
 *
 * 与 AppLinkPro 相同的骨架（实车验证过的）：
 *   - SdlProxyBuilder（不是 SdlManager）
 *   - MultiplexTransportConfig（车机才能发现）
 *
 * 新增：
 *   - StateRenderer 四状态渲染
 *   - LyricManager 歌词
 *   - 标题固定「西改改的Sync」
 */
public class SdlService extends Service implements IProxyListenerALM,
        NotificationCatcher.MusicListener {

    private static final String TAG = "SYNC";
    private static final String CHANNEL_ID = "syncglance";
    private static final int NOTIF_ID = 1;

    /**
     * 车机上显示的应用名。
     *
     * 2026-11-20 定案：<b>车机上全部叫 SyncPlay</b>。
     *
     * 原本想「菜单里用西改改的Sync、标题栏用 SyncPlay」，但实测做不到 ——
     * 标题栏吃的是 appName 而不是 shortAppName（见 §8.20），
     * shortAppName 只影响「离开 X」这类按钮。两个字段分开设置只会让
     * 标题栏继续显示被截断的「西改改的...」，所以干脆统一。
     *
     * 手机桌面的图标名是 AndroidManifest 里的 android:label，与这里无关，
     * 仍然是「西改改的Sync」。
     */
    public static final String APP_NAME = "SyncPlay";

    /** 与 APP_NAME 一致；保留这个常量是因为 Builder 仍要显式传一次 */
    public static final String SHORT_APP_NAME = "SyncPlay";

    /** 10 位数字 appID（原 SHAID 格式），避开 "123" 以免与其他调试 app 冲突 */
    public static final String APP_ID = "1397139052";

    private static SdlService instance;
    private SdlProxyALM proxy;
    private int corrId = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private StateRenderer renderer;
    private LyricManager lyricManager;
    private SyncLifecycle lifecycle = new SyncLifecycle();
    private boolean menusBuilt = false;

    public static SdlService getInstance() { return instance; }
    public SdlProxyALM getProxy() { return proxy; }
    public SyncLifecycle getLifecycle() { return lifecycle; }
    public StateRenderer getRenderer() { return renderer; }

    /** 当前歌词（给屏幕预览用） */
    public java.util.List<com.xigaigai.syncglance.lyric.Lrc.Line> getLyrics() {
        return lyricManager == null ? null : lyricManager.getCurrent();
    }

    /** 当前歌曲的显示文字，没有就返回 null */
    public String nowPlayingText() {
        if (renderer == null) return null;
        com.xigaigai.syncglance.model.NowPlaying np = renderer.getSong();
        if (np == null || !np.hasData()) return null;
        return np.toString();
    }
    public int nextCorr() { return corrId++; }

    /** 兼容旧调用：是否 FULL */
    public boolean isHmiFull() { return lifecycle.canSendRpc(); }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log2.init(this);

        renderer = new StateRenderer();
        renderer.setLifecycle(lifecycle);
        lyricManager = new LyricManager(
                new java.io.File(getCacheDir(), "lyrics"));

        createChannel();

        // 通知监听 → 歌曲数据
        NotificationCatcher.setMusicListener(this);

        // ★ 自动拉起已按要求关闭
        //   高德由用户手动打开（或点「高德地图」按钮），网易云不主动拉起
        // main.postDelayed(this::launchCompanionApps, 1500);

        // ★ 高德导航数据：只走 AmapLink 这条主路径
        //   （广播握手已移除，手机版高德本来也不发导航广播）
        com.xigaigai.syncglance.nav.AmapLinkClient.setNavListener(nav -> {
            if (nav == null) return;
            if (nav.hasData()) {
                Log2.i(TAG, "导航数据: " + nav);
                renderer.setNavigation(nav);
            } else {
                // 空对象 = 导航结束/到达/高德退出
                // 必须立刻清空并重绘，不能等下一次音乐通知
                Log2.i(TAG, "导航已结束，立即降级显示");
                renderer.clearNavigation();
                renderer.markDirty("navEnd");
            }
        });
        com.xigaigai.syncglance.nav.AmapLinkClient.get(this).start();

        Log2.i(TAG, "SyncPlay 服务创建");
    }

    /**
     * 启动时拉起高德和网易云。
     *
     * 为什么要拉起来：
     *   - 高德不运行 → AmapLink 绑定不上 → 拿不到导航数据
     *   - 网易云不运行 → 没有媒体通知 → 拿不到歌曲信息
     *
     * 注意：用 FLAG_ACTIVITY_NEW_TASK，且不放到前台抢焦点——高德会在后台起来，
     * 用户不需要手动切换。
     */
    private void launchCompanionApps() {
        launchPackage("com.autonavi.minimap", "高德地图");
        launchPackage("com.netease.cloudmusic", "网易云音乐");
    }

    private void launchPackage(String pkg, String label) {
        try {
            android.content.Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                Log2.w(TAG, label + " 未安装，跳过");
                return;
            }
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            Log2.i(TAG, "已拉起 " + label);
        } catch (Exception e) {
            Log2.w(TAG, "拉起 " + label + " 失败: " + e.getMessage());
        }
    }

    /**
     * 启动/重连入口。
     *
     * 两种来源：
     *   1. 普通启动 —— 只在没建立过 proxy 时才建
     *   2. 蓝牙重连（带 EXTRA_FORCE_RESET）—— 强制重建
     *
     * 第 2 种是修「断连后车机搜不到 app」的关键：
     * TRANSPORT_DISCONNECT 之后 SDK 自己不会重新建 SPP 连接，
     * 必须我们把旧 proxy 扔掉重新来一遍。
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();

        boolean forceReset = intent != null
                && intent.getBooleanExtra(EXTRA_FORCE_RESET, false);

        if (forceReset) {
            Log2.i(TAG, "收到强制重置请求（蓝牙重连）");
            // 无论 proxy 对象在不在，都重建
            resetProxy();
        } else if (needRebuild()) {
            /*
             * ★ 不能只看 proxy == null。
             *
             *   onProxyClosed 之后 proxy 对象仍然存在（只是失效），
             *   而 SDK 在 TRANSPORT_DISCONNECT 之后自己不会重建 SPP 连接。
             *   如果只判断 null，就会出现：蓝牙断开 → 用户手动开 App
             *   → proxy 非 null → 跳过重建 → 监听永远死着，界面卡在「未连接」。
             *
             *   所以只要 lifecycle 是 DISCONNECTED（说明 proxy 已经 closed），
             *   就要重新来一遍。
             */
            Log2.i(TAG, "启动时发现连接不可用（proxy=" + (proxy == null ? "null" : "已关闭")
                    + "），重建 proxy");
            resetProxy();
        } else {
            Log2.i(TAG, "启动时 proxy 仍有效（" + lifecycle.describe() + "），复用");
        }

        // ★ 官方推荐的补连接动作（每个进程生命周期只做一次）。
        //
        //   场景：上车时应用进程不在 → 车机连不上就放弃 → 用户后来才打开应用。
        //   这时 RouterService 可能已经从后台退出了，主动 queryForConnectedService
        //   会让它重新挂起来监听 RFCOMM，把错过的连接补上。
        //
        //   官方文档强调：车机端还得重新做一次 SDP 查询才知道这条记录回来了，
        //   那一步不受应用控制 —— 但这是应用侧唯一能做的。
        if (!pokedConnectedService) {
            pokedConnectedService = true;
            com.xigaigai.syncglance.receiver.SdlReceiver
                    .pokeIfBluetoothConnected(getApplicationContext());
        }

        return START_STICKY;
    }

    /** 每个进程生命周期只探测一次，避免每次 startService 都触发 ServiceFinder */
    private boolean pokedConnectedService = false;

    /** 是否需要重建 proxy */
    private boolean needRebuild() {
        if (proxy == null) return true;
        // proxy 对象在，但连接已断 —— 必须重建
        return lifecycle.getState() == SyncLifecycle.State.DISCONNECTED;
    }

    /** 蓝牙重连时用的标记 */
    public static final String EXTRA_FORCE_RESET = "FORCE_RESET";

    @Override
    public void onDestroy() {
        Log2.i(TAG, "服务销毁");
        NotificationCatcher.setMusicListener(null);
        if (proxy != null) {
            try { proxy.dispose(); } catch (Exception ignored) { }
            proxy = null;
        }
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------------- 前台服务 ----------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "SyncPlay 连接状态", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    /**
     * 启动前台服务时的初始通知。
     *
     * 之后由 {@link #updateNotification()} 按连接状态持续刷新 ——
     * 常驻通知本来就是一种"保活"手段，白白定着「等待车机连接…」很浪费。
     */
    private void startAsForeground() {
        startForeground(NOTIF_ID, buildNotification());
    }

    /** 按当前连接状态构造通知 */
    private Notification buildNotification() {
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        String text = notificationText();

        b.setContentTitle(APP_NAME)
         .setContentText(text)
         .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
         .setOngoing(true)
         .setShowWhen(false)
         .setOnlyAlertOnce(true);      // 每秒刷新也不响铃

        try {
            Intent i = new Intent(this, com.xigaigai.syncglance.activity.MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? android.app.PendingIntent.FLAG_UPDATE_CURRENT
                      | android.app.PendingIntent.FLAG_IMMUTABLE
                    : android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            b.setContentIntent(android.app.PendingIntent.getActivity(this, 0, i, flags));
        } catch (Exception ignored) { }

        return b.build();
    }

    /** 通知副标题：跟着生命周期走 */
    private String notificationText() {
        if (lifecycle == null) return "正在启动…";
        switch (lifecycle.getState()) {
            case DISCONNECTED:
                return "未连接 · 等待车机";
            case CONNECTING:
                return "已就绪 · 等待车机选中";
            case NONE:
                return "已连接 · 未选中";
            case BACKGROUND:
                return "已连接 · 后台";
            case LIMITED:
                return "已连接 · 受限";
            case FULL: {
                // 尽量带一点内容，扫一眼就知道在同步什么
                com.xigaigai.syncglance.model.Navigation nav =
                        renderer == null ? null : renderer.getNavigation();
                if (nav != null && nav.hasData() && nav.instruction != null) {
                    return "显示中 · " + nav.instruction;
                }
                String song = nowPlayingText();
                if (song != null) return "显示中 · " + song;
                return "显示中";
            }
            default:
                return lifecycle.describe();
        }
    }

    /** 状态变化时刷新通知。内容没变就跳过，省电。 */
    private String lastNotifText;

    public void updateNotification() {
        try {
            String text = notificationText();
            if (text.equals(lastNotifText)) return;
            lastNotifText = text;

            // 通知被用户关掉时（importance=NONE），前台服务的可见性会打折，
            // 系统也更容易回收。这里至少让日志能看出来。
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NotificationChannel ch = nm.getNotificationChannel(CHANNEL_ID);
                    if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                        if (!warnedNoNotif) {
                            warnedNoNotif = true;
                            Log2.w(TAG, "⚠ 通知已被关闭，前台保活能力下降。"
                                    + "请在系统设置里为 SyncPlay 重新开启通知");
                        }
                        return;
                    }
                }
                nm.notify(NOTIF_ID, buildNotification());
            }
        } catch (Exception e) {
            Log2.w(TAG, "刷新通知失败: " + e.getMessage());
        }
    }

    private boolean warnedNoNotif = false;

    // ---------------- 连接 ----------------

    public void startProxy() {
        if (proxy != null) return;
        try {
            SdlProxyBuilder.Builder builder = new SdlProxyBuilder.Builder(
                    this, APP_ID, APP_NAME, true, this);

            // ★ 关键：没有这个车机搜不到
            builder.setTransportType(
                    new MultiplexTransportConfig(getApplicationContext(), APP_ID));

            // ★ 实测（2026-11-20 照片）：车机标题栏用的是 appName，不是这个短名。
            //   截图里标题栏显示「西改改的...」（被截断到约 5 个汉字宽），
            //   而底部的「离开 SyncPlay」按钮才用到 shortAppName。
            //   也就是说 shortAppName 只影响对话框/按钮这类窄处，
            //   改它<b>不会</b>让标题栏变成 SyncPlay。
            builder.setShortAppName(SHORT_APP_NAME);

            // ★ autoActivateID：持久化并在每次重连时回传。
            //
            //   官方说明它能「重新获取 HMILevel = FULL」，且没有有效期限制。
            //   作用是：App 进程被系统杀掉、或车机重新扫描应用时，
            //   车机能凭这个 ID 直接把我们的 App 重新激活成全屏，
            //   而不是要求用户再去菜单里手动点一次。
            builder.setAutoActivateID(loadOrCreateAutoActivateId());

            // ★ 语言不指定（传 null）：跟随车机当前语言。
            //
            //   之前硬编码 ZH_CN，虽然本车实测就是 ZH-CN 能对上，
            //   但硬编码会在车机语言被切换时直接注册失败（WRONG_LANGUAGE）。
            //   Ford 自家 Sync Destinations 也是留空。
            //   不调用 setLangDesired / setHMILangDesired 即为 null。

            proxy = builder.build();
            lifecycle.onProxyCreated();
            renderer.setProxy(proxy);
            updateNotification();
            Log2.i(TAG, "✓ proxy 建立，等待车机选中（文档 3.3.1.1：此时还不能发 RPC）");
        } catch (SdlException e) {
            Log2.e(TAG, "创建 proxy 失败", e);
            lifecycle.onProxyClosed();
        }
    }

    // ---------------- autoActivateID ----------------

    private static final String PREF_SDL = "sdl";
    private static final String KEY_AUTO_ACTIVATE_ID = "auto_activate_id";

    /**
     * 取出（或首次生成）autoActivateID。
     *
     * 这个值必须<b>永久稳定</b>：一旦变了，车机就认不出是同一个 App 实例，
     * 自动激活会失效。所以只在第一次生成，之后一直复用，
     * 卸载重装才会产生新的（这也符合预期）。
     */
    private String loadOrCreateAutoActivateId() {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences(PREF_SDL, MODE_PRIVATE);
            String id = sp.getString(KEY_AUTO_ACTIVATE_ID, null);
            if (id != null && !id.isEmpty()) {
                Log2.i(TAG, "复用 autoActivateID = " + id);
                return id;
            }
            // 32 位无横线 UUID，避免某些实现按长度截断
            id = java.util.UUID.randomUUID().toString().replace("-", "");
            sp.edit().putString(KEY_AUTO_ACTIVATE_ID, id).apply();
            Log2.i(TAG, "★ 首次生成 autoActivateID = " + id);
            return id;
        } catch (Exception e) {
            Log2.w(TAG, "读写 autoActivateID 失败: " + e.getMessage());
            return null;      // 拿不到就不传，退化为旧行为
        }
    }

    /**
     * 重置连接。
     * 文档 3.3.1.2 的 resetProxy：断开 → 释放资源 → 重新初始化，
     * 完成后可以接受新的 SYNC 连接。
     */
    public void resetProxy() {
        Log2.i(TAG, "重置连接");
        if (proxy != null) {
            try { proxy.dispose(); } catch (Exception ignored) { }
            proxy = null;
        }
        lifecycle.onProxyClosed();
        menusBuilt = false;
        renderer.setProxy(null);
        renderer.onDisconnected();
        startProxy();
    }

    // ---------------- 计时器状态（跨失焦保留）----------------

    private String lastTimerMode;
    private int lastTimerH, lastTimerM, lastTimerS;

    /** 回 FULL 时如果计时器之前在跑，补发一次 */
    private void resendTimerIfNeeded() {
        if (!pendingTimerResend || lastTimerMode == null) return;
        pendingTimerResend = false;
        if ("CLEAR".equals(lastTimerMode) || "PAUSE".equals(lastTimerMode)) {
            return;   // 已停的不用补
        }
        Log2.i(TAG, "补发计时器（失焦期间被车机终止）: " + lastTimerMode);
        doTimer(lastTimerH, lastTimerM, lastTimerS, lastTimerMode);
    }

    // ---------------- 发送 ----------------

    /**
     * 统一发送入口。
     *
     * 文档 3.3.1.1：**只有 HMI_FULL 才能发 RPC**。
     * 非 FULL 时直接丢弃并记日志，避免抛 SdlException 刷屏。
     */
    public void sendRpc(RPCRequest req) {
        if (proxy == null) {
            Log2.w(TAG, "✗ 未连接，丢弃 " + req.getFunctionName());
            return;
        }
        if (!lifecycle.canSendRpc()) {
            Log2.w(TAG, "✗ 当前 " + lifecycle.describe() + "，不能发 "
                    + req.getFunctionName() + "（需 HMI_FULL）");
            return;
        }
        try {
            proxy.sendRPCRequest(req);
            Log2.i(TAG, "→ " + req.getFunctionName() + " #" + req.getCorrelationID());
            logRawJson("→", req);
        } catch (SdlException e) {
            Log2.e(TAG, "发送失败: " + req.getFunctionName(), e);
        }
    }

    /**
     * 发一条可滚动消息 —— 专供「字符测试」用。
     *
     * 为什么用 ScrollableMessage 而不是 Show：
     *   scrollableMessageBody 的宽度是 200，是所有文本字段里最宽的，
     *   一次能塞下几十个待测字符，一屏就能对比出哪些字有字形、哪些是点。
     *   Show 的字段每行只有 14 半角宽，测一个字符就得刷一次屏。
     *
     * @param body 要显示的内容
     */
    public void sendScrollableTest(String body) {
        ScrollableMessage msg = new ScrollableMessage();
        msg.setScrollableMessageBody(body == null ? "" : body);
        msg.setTimeout(20);              // 秒，车机会自动收起
        msg.setCorrelationID(nextCorr());
        sendRpc(msg);
    }

    private void logRawJson(String dir, com.smartdevicelink.proxy.RPCMessage m) {        if (!Log2.isRawEnabled() || m == null) return;
        try {
            org.json.JSONObject o = m.serializeJSON();
            Log2.raw(dir, o == null ? null : o.toString());
        } catch (Exception e) {
            Log2.raw(dir, "（序列化失败: " + e.getMessage() + "）");
        }
    }

    // ---------------- 动作 ----------------

    /** 计时器 */
    public void doTimer(int h, int m, int s, String mode) {
        // 记住参数，失焦被车机终止后回来能补发
        lastTimerMode = mode;
        lastTimerH = h;
        lastTimerM = m;
        lastTimerS = s;
        pendingTimerResend = false;

        UpdateMode um;
        try { um = UpdateMode.valueForString(mode); }
        catch (Exception e) { um = UpdateMode.COUNTUP; }

        // ★ 先把本地计时状态冻结（记下 timerPausedAt），
        //   否则下面 pausedClockValue() 算出来的是"还在走"的值。
        if (um == UpdateMode.PAUSE) {
            renderer.setTimerState(h, m, s, "PAUSE");
        }

        SetMediaClockTimer t;
        switch (um) {
            case PAUSE: {
                // ★ 暂停时也要把时间停在屏上。
                //
                //   pauseWithPlayPauseIndicator() 只发 UPDATE_MODE=PAUSE，
                //   不带 startTime，车机很可能把那一格清空。
                //   这里用 updatePauseWithNewStartTime(startTime, endTime, indicator)，
                //   把「暂停那一刻的读数」写成新的 startTime，再置 PAUSE，
                //   车机就会定格显示这个数字。
                int[] v = pausedClockValue();
                t = SetMediaClockTimer.updatePauseWithNewStartTime(
                        clockTime(v[0], v[1], v[2]), null,
                        AudioStreamingIndicator.PAUSE);
                Log2.i(TAG, "计时器暂停，定格在 " + v[0] + ":" + v[1] + ":" + v[2]);
                break;
            }
            case RESUME:
                t = SetMediaClockTimer.resumeWithPlayPauseIndicator(AudioStreamingIndicator.PLAY);
                break;
            case CLEAR:
                t = SetMediaClockTimer.clearWithPlayPauseIndicator(AudioStreamingIndicator.PAUSE);
                break;
            case COUNTDOWN:
                t = SetMediaClockTimer.countDownFromStartTime(
                        clockTime(h, m, s), null, AudioStreamingIndicator.PLAY);
                break;
            default:
                t = SetMediaClockTimer.countUpFromStartTime(
                        clockTime(h, m, s), null, AudioStreamingIndicator.PLAY);
                break;
        }
        t.setCorrelationID(nextCorr());
        sendRpc(t);

        // 记录完整状态供预览模拟走表（内部会维护 timerRunning / timerActive）
        renderer.setTimerState(h, m, s, mode);
        // 用户计时器接管后，歌曲计时作废
        renderer.resetSongClock();
    }

    /**
     * 构造车机认识的时间值。
     *
     * SYNC 1.1 的媒体时钟格只有 mm:ss，没有小时位：
     *   - 照片实测那一格渲染成 "1:55"（分:秒）
     *   - hours=16 → REJECTED（ETA 试过，连拒两次）
     *   - minutes=99 → INVALID_DATA
     *
     * 所以凡是往 StartTime 里塞时间都必须把小时压成 0，
     * 超过 59:59 的读数夹到上限，宁可停格也不要被车机整条拒掉。
     */
    private static StartTime clockTime(int h, int m, int s) {
        long total = h * 3600L + m * 60L + s;
        if (total < 0) total = 0;
        if (total > 3599) total = 3599;      // 59:59
        return new StartTime(0, (int) (total / 60), (int) (total % 60));
    }

    /**
     * 算出「暂停这一刻」应该定格在什么时间。
     *
     * 正计时 → 已经走过的秒数
     * 倒计时 → 还剩下的秒数
     *
     * 数据源是 StateRenderer 里那份本地计时状态（它一直在维护），
     * 所以不需要额外记一遍。
     */
    private int[] pausedClockValue() {
        long sec = renderer.timerElapsedSeconds();

        // 倒计时把剩余秒数换算成 HH:MM:SS 直接当起始时间；
        // 正计时同样。车机只认 StartTime，所以这里统一成时分秒。
        int h = (int) (sec / 3600);
        int m = (int) ((sec / 60) % 60);
        int s = (int) (sec % 60);
        return new int[]{h, m, s};
    }

    /** 改名（固定标题） */
    public void doChangeName(String name) {
        ChangeRegistration c = new ChangeRegistration();
        c.setAppName(name);
        c.setLanguage(Language.ZH_CN);
        c.setHmiDisplayLanguage(Language.ZH_CN);
        c.setCorrelationID(nextCorr());
        sendRpc(c);
    }

    /** AddCommand */
    public void doAddCommand(int cmdId, String menuName, String vrCommand, Integer parentId) {
        AddCommand c = new AddCommand();
        c.setCmdID(cmdId);

        MenuParams mp = new MenuParams();
        mp.setMenuName(menuName);
        if (parentId != null) mp.setParentID(parentId);
        c.setMenuParams(mp);

        Vector<String> vr = new Vector<>();
        vr.add(menuName);      // vrCommands 不能是空串，用菜单名兜底
        c.setVrCommands(vr);

        c.setCorrelationID(nextCorr());
        sendRpc(c);
    }

    /** AddSubMenu */
    public void doAddSubMenu(int menuId, String name) {
        AddSubMenu c = new AddSubMenu();
        c.setMenuID(menuId);
        c.setMenuName(name);
        c.setCorrelationID(nextCorr());
        sendRpc(c);
    }

    /**
     * 连上车机后自动建菜单：3 个父菜单 + 12 个子项。
     * 子项必须带 parentID，否则会挂成顶级项（AppLinkPro 实测过的坑）。
     */
    public void buildMenus() {
        if (menusBuilt) return;
        menusBuilt = true;
        Log2.i(TAG, "构建菜单");

        doAddSubMenu(CommandRouter.MENU_MUSIC, "音乐");
        doAddSubMenu(CommandRouter.MENU_NAV, "导航");
        doAddSubMenu(CommandRouter.MENU_TIMER, "计时");

        doAddCommand(CommandRouter.CMD_PREV, "上一首", "上一首", CommandRouter.MENU_MUSIC);
        doAddCommand(CommandRouter.CMD_NEXT, "下一首", "下一首", CommandRouter.MENU_MUSIC);
        doAddCommand(CommandRouter.CMD_PLAY_PAUSE, "播放暂停", "播放暂停", CommandRouter.MENU_MUSIC);
        doAddCommand(CommandRouter.CMD_SHOW_LYRIC, "显示歌词", "显示歌词", CommandRouter.MENU_MUSIC);
        doAddCommand(CommandRouter.CMD_HIDE_LYRIC, "隐藏歌词", "隐藏歌词", CommandRouter.MENU_MUSIC);

        doAddCommand(CommandRouter.CMD_NAV_ON, "开启导航模式", "开启导航", CommandRouter.MENU_NAV);
        doAddCommand(CommandRouter.CMD_NAV_OFF, "关闭导航模式", "关闭导航", CommandRouter.MENU_NAV);

        doAddCommand(CommandRouter.CMD_TIMER_UP, "开始正计时", "开始正计时", CommandRouter.MENU_TIMER);
        doAddCommand(CommandRouter.CMD_TIMER_DOWN, "开始倒计时", "开始倒计时", CommandRouter.MENU_TIMER);
        doAddCommand(CommandRouter.CMD_TIMER_PAUSE, "暂停", "计时暂停", CommandRouter.MENU_TIMER);
        doAddCommand(CommandRouter.CMD_TIMER_RESUME, "继续", "计时继续", CommandRouter.MENU_TIMER);
        doAddCommand(CommandRouter.CMD_TIMER_CLEAR, "清零", "计时清零", CommandRouter.MENU_TIMER);

        Log2.i(TAG, "✓ 菜单已建：3 父 + 12 子");
    }

    /**
     * 订阅按键 —— 只订阅车机声明支持的。
     *
     * 之前踩过的坑：
     *   - OK 订阅了两次 → 第二次返回 IGNORED
     *   - TUNEUP / TUNEDOWN 车机不支持 → UNSUPPORTED_RESOURCE
     *
     * 现在从 RegisterAppInterfaceResponse 里读 buttonCapabilities，
     * 只发车机确实支持的，换车机也不用改代码。
     */
    public void subscribeAllKeys() {
        java.util.Set<ButtonName> supported = supportedButtons();
        java.util.Set<ButtonName> sent = new java.util.HashSet<>();
        int ok = 0, skipped = 0;

        for (ButtonName b : KeyMap.ALL_SUBSCRIBE) {
            if (sent.contains(b)) {           // 去重
                Log2.w(TAG, "跳过重复的按键 " + b);
                continue;
            }
            if (!supported.isEmpty() && !supported.contains(b)) {
                Log2.i(TAG, "跳过不支持的按键 " + b);
                skipped++;
                continue;
            }
            sent.add(b);
            doSubscribe(b);
            ok++;
        }
        Log2.i(TAG, "已发 " + ok + " 个 SubscribeButton"
                + (skipped > 0 ? "（跳过 " + skipped + " 个不支持）" : ""));
    }

    /** 车机声明支持的按键集合；读不到就返回空集（表示不做过滤） */
    private java.util.Set<ButtonName> supportedButtons() {
        java.util.Set<ButtonName> out = new java.util.HashSet<>();
        if (proxy == null) return out;
        try {
            RegisterAppInterfaceResponse rai = proxy.getRegisterAppInterfaceResponse();
            if (rai == null) return out;
            java.util.List<ButtonCapabilities> caps = rai.getButtonCapabilities();
            if (caps == null) return out;
            for (ButtonCapabilities c : caps) {
                if (c != null && c.getName() != null) out.add(c.getName());
            }
        } catch (Exception e) {
            Log2.w(TAG, "读按键能力失败: " + e.getMessage());
        }
        return out;
    }

    /** 重连后允许重新建菜单 */

    /** SubscribeButton */
    public void doSubscribe(ButtonName name) {
        SubscribeButton s = new SubscribeButton();
        s.setButtonName(name);
        s.setCorrelationID(nextCorr());
        sendRpc(s);
    }

    /** Speak */
    public void doSpeak(String text) {
        Speak s = new Speak();
        TTSChunk chunk = new TTSChunk();
        chunk.setType(SpeechCapabilities.TEXT);
        chunk.setText(text);
        Vector<TTSChunk> v = new Vector<>();
        v.add(chunk);
        s.setTtsChunks(v);
        s.setCorrelationID(nextCorr());
        sendRpc(s);
    }

    // ---------------- 数据入口 ----------------

    /** 来自 NotificationCatcher 的歌曲更新 */
    @Override
    public void onNowPlaying(NowPlaying np) {
        if (np == null) return;

        NowPlaying cur = renderer.getSong();
        boolean songChanged = cur == null || !np.sameSong(cur);

        renderer.setSong(np);
        updateNotification();

        // 换歌了，重新拉歌词
        if (songChanged) {
            lyricManager.load(np, (song, lines) -> {
                renderer.setLyrics(lines);
            });
        }

        // 用 SetMediaClockTimer 起一个播放计时（SYNC 规范做法）
        pushClock();
    }

    /**
     * 把「当前该显示的时间」推给车机。
     *
     * 调用点有三处：换歌、导航数据更新、计时器状态变化。
     * 以前只在换歌时调，结果导航一路更新、ETA 却永远停在第一次推送的时刻。
     *
     * songClockRpc() 内部自带门槛（ETA 没变就不发、歌没换就不重发），
     * 所以这里可以放心被高频调用。
     */
    private void pushClock() {
        SetMediaClockTimer clock = renderer.songClockRpc();
        if (clock == null) return;
        clock.setCorrelationID(nextCorr());
        sendRpc(clock);
    }

    /**
     * 音乐通知消失 —— 播放器退出，或用户把媒体控件划掉了。
     *
     * 此时不能继续挂着最后一首歌，要把歌曲状态整个清掉，
     * 让屏幕退回「纯导航」或「欢迎页」。
     */
    @Override
    public void onMusicGone() {
        Log2.i(TAG, "音乐通知消失，清空歌曲状态并降级显示");
        renderer.clearSong();
        updateNotification();
    }

    /** 设置导航数据 */
    public void setNavigation(Navigation nav) {
        renderer.setNavigation(nav);
        // ★ 导航数据变了，ETA 也可能变了 —— 必须顺手推一次时钟，
        //   否则到达时刻只会在换歌那一瞬间刷新。
        pushClock();
    }

    /** 手动切换导航模式 */
    public void setManualNavMode(boolean on) {
        Navigation n = renderer.getNavigation();
        if (n == null) { n = new Navigation(); }
        n.manualMode = on;
        renderer.setNavigation(n);
        Log2.i(TAG, "导航模式: " + (on ? "开" : "关"));
    }

    public boolean isManualNavMode() {
        Navigation n = renderer.getNavigation();
        return n != null && n.manualMode;
    }

    // ---------------- 生命周期回调（核心）----------------

    /**
     * HMI 状态变化 —— 整个连接生命周期的中心。
     *
     * 文档 3.3.1 的流程：
     *   NONE(firstRun=true) → BACKGROUND → LIMITED → FULL
     *
     * 规则：
     *   - 只有 FULL 能发 RPC
     *   - 首次进入 FULL 要做完整初始化（建菜单、订阅按键）
     *   - 从 FULL 掉出去时，车机会自动终止我们正在进行的操作
     */
    @Override
    public void onOnHMIStatus(OnHMIStatus n) {
        Log2.rawMsg("←", n);
        SyncLifecycle.State prev = lifecycle.onHmiStatus(
                n.getHmiLevel(), n.getFirstRun(),
                n.getSystemContext(), n.getAudioStreamingState());

        Log2.i(TAG, "OnHMIStatus: " + prev + " → " + lifecycle.describe()
                + " firstRun=" + lifecycle.isFirstRun()
                + " ctx=" + lifecycle.getContext());

        // 同步给 AmapLink —— 没连车机时跳过解密省 CPU
        com.xigaigai.syncglance.nav.AmapLinkClient.setHeadUnitConnected(
                lifecycle.canSendRpc());

        // 通知栏状态跟着走
        updateNotification();

        // 通知渲染器：连上/断开了都要重新评估能不能画
        renderer.onLifecycleChanged();

        if (lifecycle.justBecameFull(prev)) {
            Log2.i(TAG, "★ 进入 FULL —— 开始初始化");
            initializeForConnection();
        } else if (lifecycle.justLostFocus(prev)) {
            // 文档 3.4.4.4：FULL/LIMITED 期间发起的操作会被车机自动终止
            Log2.w(TAG, "失去焦点（" + lifecycle.getState() + "），进行中的操作已被车机终止");
            onLostFocus();
        }
    }

    /**
     * 把 RegisterAppInterface 的响应完整 dump 出来。
     *
     * 这是**车机能力清单**，包含：
     *   - 每个文本行的宽度和字符集
     *   - 可用的物理按键列表
     *   - 语音识别能力
     *   - 支持的语言、显示类型
     *
     * 分析屏幕行为时这是最权威的依据。
     */
    private void dumpRaiResponse() {
        if (proxy == null) return;
        try {
            RegisterAppInterfaceResponse rai = proxy.getRegisterAppInterfaceResponse();
            if (rai == null) {
                Log2.w(TAG, "RegisterAppInterfaceResponse 为空");
                return;
            }
            Log2.rawMsg("←RAI", rai);

            StringBuilder sb = new StringBuilder("=== 车机能力清单 ===\n");

            try {
                SdlMsgVersion v = rai.getSdlMsgVersion();
                if (v != null) {
                    sb.append("协议版本: ").append(v.getMajorVersion())
                      .append('.').append(v.getMinorVersion()).append('\n');
                }
            } catch (Exception ignored) { }

            sb.append("语言: ").append(rai.getLanguage())
              .append("  HMI语言: ").append(rai.getHmiDisplayLanguage()).append('\n');

            // 显示能力 —— 这是最关键的部分
            DisplayCapabilities dc = rai.getDisplayCapabilities();
            if (dc != null) {
                sb.append("显示类型: ").append(dc.getDisplayType()).append('\n');
                java.util.List<TextField> fields = dc.getTextFields();
                if (fields != null) {
                    sb.append("文本字段: ").append(fields.size()).append(" 个\n");
                    for (TextField tf : fields) {
                        sb.append("   ").append(tf.getName())
                          .append("  width=").append(tf.getWidth())
                          .append("  rows=").append(tf.getRows())
                          .append("  charset=").append(tf.getCharacterSet())
                          .append('\n');
                    }
                }
                java.util.List<MediaClockFormat> mcf = dc.getMediaClockFormats();
                if (mcf != null) sb.append("媒体时钟格式: ").append(mcf).append('\n');
            }

            // 按键能力
            java.util.List<ButtonCapabilities> bc = rai.getButtonCapabilities();
            if (bc != null) {
                sb.append("按键: ").append(bc.size()).append(" 个\n");
                for (ButtonCapabilities b : bc) {
                    sb.append("   ").append(b.getName())
                      .append(" short=").append(b.getShortPressAvailable())
                      .append(" long=").append(b.getLongPressAvailable())
                      .append(" updown=").append(b.getUpDownAvailable())
                      .append('\n');
                }
            }

            // 语音能力
            java.util.List<SpeechCapabilities> sc = rai.getSpeechCapabilities();
            if (sc != null) sb.append("语音能力: ").append(sc).append('\n');
            java.util.List<VrCapabilities> vc = rai.getVrCapabilities();
            if (vc != null) sb.append("VR能力: ").append(vc).append('\n');

            // 版本信息 —— 能确认固件代际
            sb.append("SDL版本: ").append(rai.getSdlVersion()).append('\n');
            sb.append("系统软件版本: ").append(rai.getSystemSoftwareVersion()).append('\n');
            sb.append("Proxy版本: ").append(rai.getProxyVersionInfo()).append('\n');

            // 软键能力（预期为空，但值得确认）
            java.util.List<SoftButtonCapabilities> sbc = rai.getSoftButtonCapabilities();
            sb.append("软键能力: ")
              .append(sbc == null || sbc.isEmpty() ? "无（固件不支持）" : sbc.size() + " 个")
              .append('\n');

            // 预设键
            try {
                PresetBankCapabilities pbc = rai.getPresetBankCapabilities();
                sb.append("预设键区: ")
                  .append(pbc == null ? "无" : "有")
                  .append('\n');
            } catch (Exception ignored) { }

            // HMI 能力
            try {
                HMICapabilities hc = rai.getHmiCapabilities();
                if (hc != null) {
                    sb.append("HMI能力: navi=").append(hc.isNavigationAvailable())
                      .append(" phone=").append(hc.isPhoneCallAvailable())
                      .append(" video=").append(hc.isVideoStreamingAvailable())
                      .append('\n');
                }
            } catch (Exception ignored) { }

            // 诊断模式支持
            java.util.List<Integer> diag = rai.getSupportedDiagModes();
            sb.append("诊断模式: ")
              .append(diag == null || diag.isEmpty() ? "无" : diag.toString())
              .append('\n');

            Log2.i(TAG, sb.toString());
        } catch (Exception e) {
            Log2.w(TAG, "dump 车机能力失败: " + e.getMessage());
        }
    }

    /**
     * 每次进入 FULL 都要做的初始化。
     *
     * 文档 3.4.2.2 明确：OnProxyClosed 之后，
     * 车机上所有 menu command / choice set / button subscription 都被删了，
     * 重连必须重建。
     */
    private void initializeForConnection() {
        if (lifecycle.isInitialized()) {
            // 本次连接已经初始化过（比如从 BACKGROUND 回到 FULL），不用重建
            Log2.i(TAG, "本次连接已初始化过，跳过");
            renderer.markDirty("reFULL");
            return;
        }
        lifecycle.markInitialized();

        // 0. dump 车机能力清单（只在第一次）
        dumpRaiResponse();

        // 1. 建菜单（父菜单 + 子项）
        buildMenus();

        // 2. 订阅按键
        subscribeAllKeys();

        // 3. 补发计时器（如果失焦前在跑）
        resendTimerIfNeeded();

        // 4. 画初始界面
        renderer.markDirty("init");
    }

    /**
     * 失去焦点时的处理。
     * 文档：非 FULL 时不能发 RPC，所以这里只做本地状态处理。
     */
    private void onLostFocus() {
        menusBuilt = false;   // 下次回到 FULL 需要重建
        // 清掉显示缓存，回到 FULL 时强制重绘
        renderer.onDisconnected();
        // 计时器状态在本地保留（车机侧的可能被终止，回 FULL 时补发）
        pendingTimerResend = true;
    }

    /** 回 FULL 时需要补发计时器（失焦时车机会停掉它） */
    private boolean pendingTimerResend = false;

    @Override
    public void onProxyClosed(String info, Exception e, SdlDisconnectedReason reason) {
        SyncLifecycle.State prev = lifecycle.getState();
        Log2.i(TAG, "onProxyClosed: " + reason + " " + info + "（原状态 " + prev + "）");

        lifecycle.onProxyClosed();
        updateNotification();
        // 断开了，导航数据不用解码了（省 CPU）
        com.xigaigai.syncglance.nav.AmapLinkClient.setHeadUnitConnected(false);
        // 文档 3.4.2.2：车机侧所有菜单/订阅都已被删除，重连要全量重建
        menusBuilt = false;
        pendingTimerResend = true;
        renderer.onDisconnected();
        renderer.setProxy(null);

        // 文档 3.3.1.2：判断是"可重连的断开"还是"需要重建的不可恢复错误"
        if (reason != null) {
            switch (reason) {
                case USER_EXIT:
                case IGNITION_OFF:
                case BLUETOOTH_OFF:
                case TRANSPORT_DISCONNECT:
                case TRANSPORT_ERROR:
                case HB_TIMEOUT:
                    Log2.i(TAG, "可恢复断开（" + reason + "），proxy 会继续监听重连");
                    break;
                case APPLICATION_REQUESTED_DISCONNECT:
                    Log2.i(TAG, "应用主动断开");
                    break;
                case RPC_SESSION_ENDED:
                case REQUEST_WHILE_IN_NONE_HMI_LEVEL:
                    Log2.w(TAG, "会话结束（" + reason + "），下次 FULL 需要重建");
                    break;
                default:
                    Log2.w(TAG, "断开原因 " + reason + "，按可重连处理");
                    break;
            }
        }
    }

    @Override
    public void onError(String info, Exception e) {
        Log2.e(TAG, "onError: " + info, e);
    }

    @Override
    public void onGenericResponse(GenericResponse r) {
        Log2.rawMsg("←", r);
        Log2.w(TAG, "GenericResponse（车机不支持该 RPC）: " + r.getResultCode()
                + (r.getInfo() == null ? "" : " " + r.getInfo()));
    }

    @Override
    public void onOnCommand(OnCommand n) {
        Log2.rawMsg("←", n);
        Log2.i(TAG, "OnCommand cmdID=" + n.getCmdID());
        CommandRouter.handle(this, n.getCmdID());
    }

    @Override
    public void onOnButtonPress(OnButtonPress n) {
        Log2.rawMsg("←", n);
        Log2.i(TAG, "OnButtonPress " + n.getButtonName() + " " + n.getButtonPressMode()
                + (n.getCustomButtonID() == null ? "" : " id=" + n.getCustomButtonID()));

        // ★ 软键走独立分支：它不在物理键表里，也没有 SubscribeButton
        if (n.getButtonName() == ButtonName.CUSTOM_BUTTON) {
            CommandRouter.handleSoftButton(this, n.getCustomButtonID());
            return;
        }
        CommandRouter.handleButton(this, n.getButtonName(), n.getButtonPressMode());
    }

    @Override
    public void onOnButtonEvent(OnButtonEvent n) {
        Log2.rawMsg("←", n);
        Log2.i(TAG, "OnButtonEvent " + n.getButtonName() + " " + n.getButtonEventMode()
                + (n.getCustomButtonID() == null ? "" : " id=" + n.getCustomButtonID()));
    }

    @Override
    public void onOnDriverDistraction(OnDriverDistraction n) {
        Log2.rawMsg("←", n);
        Log2.i(TAG, "OnDriverDistraction " + n.getState());
    }

    @Override
    public void onOnLockScreenNotification(OnLockScreenStatus n) {
        Log2.rawMsg("←", n);
        try { Log2.i(TAG, "OnLockScreen " + n.getHMILevel()); } catch (Exception ignored) { }
    }

    @Override
    public void onOnPermissionsChange(OnPermissionsChange n) {
        Log2.rawMsg("←", n);
        Log2.i(TAG, "OnPermissionsChange");
    }

    // --- 响应回调（全部记原始 JSON）---

    @Override
    public void onShowResponse(ShowResponse r) {
        Log2.rawMsg("←", r);
        if (r.getResultCode() != Result.SUCCESS) {
            Log2.w(TAG, "Show ← " + r.getResultCode()
                    + (r.getInfo() == null ? "" : " " + r.getInfo()));
        }
    }

    @Override
    public void onSetMediaClockTimerResponse(SetMediaClockTimerResponse r) {
        Log2.rawMsg("←", r);
        Log2.i(TAG, "SetMediaClockTimer ← " + r.getResultCode());
    }

    @Override
    public void onChangeRegistrationResponse(ChangeRegistrationResponse r) {
        Log2.rawMsg("←", r);
        Log2.i(TAG, "ChangeRegistration ← " + r.getResultCode());
    }

    @Override
    public void onAddCommandResponse(AddCommandResponse r) {
        Log2.rawMsg("←", r);
        Log2.i(TAG, "AddCommand ← " + r.getResultCode());
    }

    @Override
    public void onAddSubMenuResponse(AddSubMenuResponse r) {
        Log2.rawMsg("←", r);
        Log2.i(TAG, "AddSubMenu ← " + r.getResultCode());
    }

    @Override
    public void onSubscribeButtonResponse(SubscribeButtonResponse r) {
        Log2.rawMsg("←", r);
        Log2.i(TAG, "SubscribeButton ← " + r.getResultCode()
                + (r.getInfo() == null ? "" : " " + r.getInfo()));
    }

    @Override
    public void onSpeakResponse(SpeakResponse r) {
        Log2.rawMsg("←", r);
        Log2.i(TAG, "Speak ← " + r.getResultCode());
    }

    @Override public void onServiceEnded(OnServiceEnded o) { Log2.i(TAG, "onServiceEnded"); }
    @Override public void onServiceNACKed(OnServiceNACKed o) { Log2.i(TAG, "onServiceNACKed"); }
    @Override public void onOnStreamRPC(OnStreamRPC o) { Log2.rawMsg("←", o.toString()); }
    @Override public void onStreamRPCResponse(StreamRPCResponse o) { Log2.rawMsg("←", o); }
    @Override public void onServiceDataACK(int i) { }

    // --- 其余空实现（4.12.2 接口要求）---

    @Override public void onScrollableMessageResponse(ScrollableMessageResponse r) { }
    @Override public void onAlertResponse(AlertResponse r) { }
    @Override public void onDeleteCommandResponse(DeleteCommandResponse r) { }
    @Override public void onDeleteSubMenuResponse(DeleteSubMenuResponse r) { }
    @Override public void onCreateInteractionChoiceSetResponse(CreateInteractionChoiceSetResponse r) { }
    @Override public void onDeleteInteractionChoiceSetResponse(DeleteInteractionChoiceSetResponse r) { }
    @Override public void onPerformInteractionResponse(PerformInteractionResponse r) { }
    @Override public void onUnsubscribeButtonResponse(UnsubscribeButtonResponse r) { }
    @Override public void onUnsubscribeVehicleDataResponse(UnsubscribeVehicleDataResponse r) { }
    @Override public void onGetVehicleDataResponse(GetVehicleDataResponse r) { }
    @Override public void onSetGlobalPropertiesResponse(SetGlobalPropertiesResponse r) { }
    @Override public void onResetGlobalPropertiesResponse(ResetGlobalPropertiesResponse r) { }
    @Override public void onOnTBTClientState(OnTBTClientState o) { }
    @Override public void onSubscribeVehicleDataResponse(SubscribeVehicleDataResponse r) { }
    @Override public void onOnVehicleData(OnVehicleData o) { }
    @Override public void onPutFileResponse(PutFileResponse r) { }
    @Override public void onDeleteFileResponse(DeleteFileResponse r) { }
    @Override public void onListFilesResponse(ListFilesResponse r) { }
    @Override public void onSetAppIconResponse(SetAppIconResponse r) { }
    @Override public void onSetDisplayLayoutResponse(SetDisplayLayoutResponse r) { }
    @Override public void onOnLanguageChange(OnLanguageChange o) { }
    @Override public void onOnSystemRequest(OnSystemRequest o) { }
    @Override public void onSystemRequestResponse(SystemRequestResponse r) { }
    @Override public void onOnKeyboardInput(OnKeyboardInput o) { }
    @Override public void onOnTouchEvent(OnTouchEvent o) { }
    @Override public void onDialNumberResponse(DialNumberResponse r) { }
    @Override public void onSendLocationResponse(SendLocationResponse r) { }
    @Override public void onShowConstantTbtResponse(ShowConstantTbtResponse r) { }
    @Override public void onAlertManeuverResponse(AlertManeuverResponse r) { }
    @Override public void onUpdateTurnListResponse(UpdateTurnListResponse r) { }
    @Override public void onGetWayPointsResponse(GetWayPointsResponse r) { }
    @Override public void onSubscribeWayPointsResponse(SubscribeWayPointsResponse r) { }
    @Override public void onUnsubscribeWayPointsResponse(UnsubscribeWayPointsResponse r) { }
    @Override public void onReadDIDResponse(ReadDIDResponse r) { }
    @Override public void onGetDTCsResponse(GetDTCsResponse r) { }
    @Override public void onDiagnosticMessageResponse(DiagnosticMessageResponse r) { }
    @Override public void onOnAudioPassThru(OnAudioPassThru o) { }
    @Override public void onPerformAudioPassThruResponse(PerformAudioPassThruResponse r) { }
    @Override public void onEndAudioPassThruResponse(EndAudioPassThruResponse r) { }
    @Override public void onShowAppMenuResponse(ShowAppMenuResponse r) { }

    // --- 其他 SDL 2.x 扩展，这台车机不会用到，但接口要求实现 ---
    @Override public void onOnHashChange(OnHashChange o) { }
    @Override public void onSliderResponse(SliderResponse r) { }
    @Override public void onOnWayPointChange(OnWayPointChange o) { }
    @Override public void onGetSystemCapabilityResponse(GetSystemCapabilityResponse r) { }
    @Override public void onGetInteriorVehicleDataResponse(GetInteriorVehicleDataResponse r) { }
    @Override public void onCreateWindowResponse(CreateWindowResponse r) { }
    @Override public void onDeleteWindowResponse(DeleteWindowResponse r) { }
    @Override public void onButtonPressResponse(ButtonPressResponse r) { }
    @Override public void onSetInteriorVehicleDataResponse(SetInteriorVehicleDataResponse r) { }
    @Override public void onOnInteriorVehicleData(OnInteriorVehicleData o) { }
    @Override public void onSendHapticDataResponse(SendHapticDataResponse r) { }
    @Override public void onOnRCStatus(OnRCStatus o) { }
    @Override public void onSetCloudAppProperties(SetCloudAppPropertiesResponse r) { }
    @Override public void onGetCloudAppProperties(GetCloudAppPropertiesResponse r) { }
    @Override public void onPublishAppServiceResponse(PublishAppServiceResponse r) { }
    @Override public void onGetAppServiceDataResponse(GetAppServiceDataResponse r) { }
    @Override public void onGetFileResponse(GetFileResponse r) { }
    @Override public void onPerformAppServiceInteractionResponse(PerformAppServiceInteractionResponse r) { }
    @Override public void onOnAppServiceData(OnAppServiceData o) { }
    @Override public void onGetInteriorVehicleDataConsentResponse(GetInteriorVehicleDataConsentResponse r) { }
    @Override public void onReleaseInteriorVehicleDataModuleResponse(ReleaseInteriorVehicleDataModuleResponse r) { }
    @Override public void onOnSystemCapabilityUpdated(OnSystemCapabilityUpdated o) { }
    @Override public void onCloseApplicationResponse(CloseApplicationResponse r) { }
    @Override public void onCancelInteractionResponse(CancelInteractionResponse r) { }
    @Override public void onUnpublishAppServiceResponse(UnpublishAppServiceResponse r) { }
}
