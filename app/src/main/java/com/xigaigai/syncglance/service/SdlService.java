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

import com.smartdevicelink.managers.SdlManager;
import com.smartdevicelink.managers.SdlManagerListener;
import com.smartdevicelink.managers.lifecycle.LifecycleConfigurationUpdate;
import com.smartdevicelink.protocol.enums.FunctionID;
import com.smartdevicelink.proxy.RPCNotification;
import com.smartdevicelink.proxy.RPCRequest;
import com.smartdevicelink.proxy.RPCResponse;
import com.smartdevicelink.proxy.rpc.*;
import com.smartdevicelink.proxy.rpc.enums.*;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCNotificationListener;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCResponseListener;
import com.smartdevicelink.transport.MultiplexTransportConfig;
import com.smartdevicelink.util.DebugTool;
import com.smartdevicelink.util.SystemInfo;
import com.xigaigai.syncglance.display.StateRenderer;
import com.xigaigai.syncglance.display.SyncLifecycle;
import com.xigaigai.syncglance.lyric.LyricManager;import com.xigaigai.syncglance.model.Navigation;
import com.xigaigai.syncglance.model.NowPlaying;
import com.xigaigai.syncglance.receiver.SyncRouterService;
import com.xigaigai.syncglance.util.Log2;

import java.util.Vector;

/**
 * SyncGlance 的 SDL 服务。
 *
 * <b>底座：sdl_android 5.8.0</b>（4.12.2 的旧实现完整保留在 `legacy/sdl4` 分支）。
 * 5.x 已经把 proxy 层（SdlProxyALM / SdlProxyBuilder / IProxyListenerALM）整体移除，
 * 所以连接入口换成 {@link SdlManager} + {@link SdlManagerListener}；
 * RPC 回调改成按 FunctionID 注册的 OnRPCNotificationListener。
 *
 * **屏幕渲染（Show）仍然全部由 StateRenderer 手搓**，没有交给 ScreenManager：
 * 这台车的显示规则（一行 14 半角、mediaTrack 单值共用、ETA 借 mm:ss 位…）
 * 都是实车倒推出来的，需要字节级控制权和「看到发出去的原始载荷」的能力。
 */
public class SdlService extends Service {

    private static final String TAG = "SYNC";
    private static final String CHANNEL_ID = "syncglance";
    private static final int NOTIF_ID = 1;

    /**
     * 车机上显示的应用名 —— 也是车机策略表里认应用的<b>唯一标识</b>。
     *
     * ★ 一旦定下就永不改：改了等于换了个 App，车机 AppTable 里会多出一条新记录。
     *
     * 迁移期曾临时叫过 SyncPlay5（为了和 4.12.2 版并存做 A/B 对比）；
     * 现在 SDL5 转正、旧版退回 legacy/sdl4 分支，名字已改回 SyncPlay。
     *
     * 手机桌面的图标名由 AndroidManifest 的 android:label 控制（西改改的Sync），与这里无关。
     */
    public static final String APP_NAME = "SyncPlay";

    /** 与 APP_NAME 一致；保留这个常量是因为 Builder 仍要显式传一次 */
    public static final String SHORT_APP_NAME = "SyncPlay";

    /**
     * 10 位数字 appID（原 SHAID 格式）。
     *
     * 它<b>不是</b>车机侧的身份（车机认 {@link #APP_NAME}），
     * 只用于 MultiplexTransportConfig 给广播通道去重。
     *
     * 迁移并存期从 1397139052 换成了 1397139053，转正后沿用 1397139053 —— 
     * 旧版已不在设备上，改回历史值没有收益，反而多一次通道状态变化。
     */
    public static final String APP_ID = "1397139053";

    private static SdlService instance;
    /** 5.x 的连接入口（4.x 的 SdlProxyALM 已被上游移除） */
    private volatile SdlManager sdlManager;
    /** 我们自己主动 dispose 时为 true，避免 onDestroy 回调里重复收尾 */
    private volatile boolean disposing = false;
    /** 连接代际：每次 startProxy 自增，用来忽略旧连接对象迟到的回调 */
    private volatile long generation = 0;
    private int corrId = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    /** 渲染器与生命周期都来自 SyncCore（应用级），这里只是引用 */
    private StateRenderer renderer;
    private SyncLifecycle lifecycle = new SyncLifecycle();
    private boolean menusBuilt = false;

    public static SdlService getInstance() { return instance; }
    public SdlManager getProxy() { return sdlManager; }
    public SyncLifecycle getLifecycle() { return lifecycle; }
    public StateRenderer getRenderer() { return renderer; }

    /** 当前歌词（给屏幕预览用）—— 数据源在 SyncCore */
    public java.util.List<com.xigaigai.syncglance.lyric.Lrc.Line> getLyrics() {
        return com.xigaigai.syncglance.core.SyncCore.get().lyrics();
    }

    /**
     * 「显示歌词」菜单：重新加载当前歌曲的歌词。
     *
     * 之前这里只做 markDirty，但「隐藏歌词」已经把 renderer 的歌词清成 null 了，
     * 于是「隐藏 → 显示」永远回不来。重新走一遍 load 即可：
     * 内存缓存命中会立刻同步回调，不会重新联网。
     */
    public void reloadLyrics() {
        com.xigaigai.syncglance.core.SyncCore.get().reloadLyrics();
    }

    /**
     * 当前歌曲的显示文字，没有就返回 null。
     *
     * ★ 只给日志/调试用 —— **不要往通知里塞**。通知只报状态枚举，
     *   否则切歌、歌词变化都会刷通知栏（见 {@code notificationText()}）。
     */
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

        /*
         * ★ 数据采集与渲染状态都归 SyncCore 管（应用级单例，由 SyncGlanceApp 初始化）。
         *
         *   SdlService 只负责「连接 + 发送」：
         *     连上  → attachSender()，渲染器的出站 RPC 就能送出去
         *     断开  → detachSender()，渲染与预览照常工作
         *
         *   这样即使服务因为「没连车机」而退出（SDL 5.x 的正常语义），
         *   界面上的车机预览也不会变成空画布。
         */
        com.xigaigai.syncglance.core.SyncCore core =
                com.xigaigai.syncglance.core.SyncCore.get();
        core.init(this);
        core.setOnDataChanged(this::updateNotification);
        renderer = core.renderer();
        lifecycle = core.lifecycle();

        createChannel();
        registerRouterWatcher();

        Log2.i(TAG, "SyncPlay 服务创建（连接层）");
    }

    // ---------------- RouterService 存活探测 ----------------

    /** RouterService 是否在跑（由 {@link SyncRouterService} 在 onCreate/onDestroy 时广播上报） */
    private volatile boolean routerAlive = false;

    /**
     * 这次进程起来之后，是否"连上过又断开了"。
     *
     * 用途：区分 {@code 未连接（连接关闭）} 和 {@code 未连接（等待车机）}——
     * 前者说明车机本来是通的、刚掉线（多半熄火/蓝牙断）；后者说明压根没连上过。
     */
    private volatile boolean closedOnce = false;

    private android.content.BroadcastReceiver routerWatcher;

    /**
     * 监听 RouterService 的生死。
     *
     * 为什么要自己上报：SDL 5.8 的 {@code SdlBroadcastReceiver} 里没有
     * {@code isRouterServiceRunning()} 这类 API，库内部状态又不可靠；
     * 而 RouterService 是我们自己的子类，加两行广播最准。
     *
     * 注意 RouterService 跑在独立进程（{@code com.smartdevicelink.router}），
     * 所以这里必须用**跨进程广播**，不能指望共享内存。
     */
    private void registerRouterWatcher() {
        if (routerWatcher != null) return;
        routerWatcher = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                boolean alive = SyncRouterService.ACTION_ROUTER_ALIVE.equals(action);
                if (alive == routerAlive) return;
                routerAlive = alive;
                Log2.i(TAG, "RouterService " + (alive ? "已启动" : "已退出") + "，刷新通知");
                updateNotification();
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(SyncRouterService.ACTION_ROUTER_ALIVE);
        f.addAction(SyncRouterService.ACTION_ROUTER_DEAD);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(routerWatcher, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(routerWatcher, f);
            }
        } catch (Exception e) {
            Log2.w(TAG, "注册 Router 状态广播失败: " + e.getMessage());
        }
    }

    private void unregisterRouterWatcher() {
        if (routerWatcher == null) return;
        try { unregisterReceiver(routerWatcher); } catch (Exception ignored) { }
        routerWatcher = null;
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
             * ★ 不能只看连接对象 == null。
             *
             *   连接断开后 SdlManager 会自己 dispose 并在 onDestroy() 里把它置空，
             *   但如果连接只是失效、对象还在，SDK 也不会自动重建 SPP 连接。
             *   所以只要 lifecycle 回到 DISCONNECTED，就要重新来一遍，
             *   否则会出现「蓝牙断开 → 用户手动开 App → 跳过重建 → 监听永远死着」。
             */
            Log2.i(TAG, "启动时发现连接不可用（SdlManager=" + (sdlManager == null ? "null" : "已失效")
                    + "），重建连接");
            if (sdlManager == null) {
                startProxy();          // 还没建过，直接建（不用走 reset 的销毁流程）
            } else {
                resetProxy();
            }
        } else {
            Log2.i(TAG, "启动时连接仍有效（" + lifecycle.describe() + "），复用");
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

    /** 是否需要重建连接 */
    private boolean needRebuild() {
        if (sdlManager == null) return true;
        // 连接对象在，但连接已断 —— 必须重建
        return lifecycle.getState() == SyncLifecycle.State.DISCONNECTED;
    }

    /** 蓝牙重连时用的标记 */
    public static final String EXTRA_FORCE_RESET = "FORCE_RESET";

    @Override
    public void onDestroy() {
        Log2.i(TAG, "服务销毁（连接层退出，数据采集与预览不受影响）");
        unregisterRouterWatcher();
        /*
         * ★ 这里绝对不能再 NotificationCatcher.setMusicListener(null)。
         *
         *   那个监听器现在是 SyncCore（应用级）注册的，服务只是"借用连接"。
         *   服务一销毁就把它置空，等于把数据采集也掐死了：
         *   界面上预览会一直停在「空闲 / 问候语」，歌曲永远进不来（踩过）。
         */
        com.xigaigai.syncglance.core.SyncCore.get().detachSender();
        disposing = true;
        SdlManager old = sdlManager;
        sdlManager = null;
        if (old != null) {
            try { old.dispose(); } catch (Exception ignored) { }
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

    /**
     * 通知副标题：只报**状态枚举**，绝不拼歌名/导航指令。
     *
     * 教训：以前这里是「显示中 · <歌名>」/「显示中 · <导航指令>」，
     * 切歌、导航指令一变就刷一次通知栏，很吵。现在只在
     * 「连接态 / HMI 等级 / 车机上显示什么类别 / Router 死活」变化时才变。
     *
     * 文案规则见 {@link com.xigaigai.syncglance.display.ConnectionStatus}。
     */
    private String notificationText() {
        return com.xigaigai.syncglance.display.ConnectionStatus.text(
                lifecycle == null ? null : lifecycle.getState(),
                routerAlive,
                closedOnce,
                renderer == null ? null : renderer.currentState());
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
        if (sdlManager != null) return;
        try {
            /*
             * ★ 打开 SDL 内部日志（仅 debug 包，与官方 hello_sdl 一致）。
             *
             *   这是唯一能看到 RAI / 传输层 / RouterService 内部过程的通道，
             *   日志 tag 是 "SDL"，用 `adb logcat -s SDL:*` 抓。
             *   我们自己的日志看不到这部分 —— 之前"卡在恢复连接"就是因为缺这份信息。
             */
            // 只有可调试包才开到 SDL 内部日志（AGP 8+ 默认不生成 BuildConfig，
            // 所以用 ApplicationInfo 的 FLAG_DEBUGGABLE 判断，等价于 BuildConfig.DEBUG）
            boolean debuggable = (getApplicationInfo().flags
                    & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            if (debuggable) {
                DebugTool.enableDebugTool();
                Log2.i(TAG, "SDL 内部日志已开启（logcat tag: SDL）");
            }

            /*
             * ★ 5.x 的建连入口：SdlManager。
             *
             *   与 4.12.2 的差异（逐条对应，别漏）：
             *     1. isMediaApplication 没有直接 setter —— 只能靠 setAppTypes([MEDIA]) 推导。
             *        漏了它 → isMediaApplication=false → 车机很可能不再给 mediaTrack 和第 3/4 行，
             *        整个双屏布局会崩。这是本次迁移最容易踩的坑。
             *     2. autoActivateID 在新版 SDK 里<b>没有等价 API</b>（只剩 setResumeHash，
             *        那是 RPC 会话恢复，需要车机支持 resumption，SYNC 1.1 大概率不支持）。
             *        所以这个能力在迁移版里是缺失的，属于已知取舍。
             *     3. 语言：新版 Builder 在 build() 时若 hmiLanguage 为 null 会强制设成 EN_US。
             *        实测这台车机是 ZH-CN，所以这里显式写死 ZH_CN（按用户要求）。
             *     4. build() 之后要调 start()，是异步的；onStart() 才是"注册完成"。
             */
            final long gen = ++generation;      // 本次连接的代际，旧 connection 的回调会被它过滤掉

            Vector<AppHMIType> appTypes = new Vector<>();
            appTypes.add(AppHMIType.MEDIA);

            java.util.Map<FunctionID, OnRPCNotificationListener> listeners = new java.util.HashMap<>();
            listeners.put(FunctionID.ON_HMI_STATUS, notified(n -> onHmiStatus((OnHMIStatus) n)));
            listeners.put(FunctionID.ON_COMMAND, notified(n -> onCommand((OnCommand) n)));
            listeners.put(FunctionID.ON_BUTTON_PRESS, notified(n -> onButtonPress((OnButtonPress) n)));
            listeners.put(FunctionID.ON_BUTTON_EVENT, notified(n -> onButtonEvent((OnButtonEvent) n)));
            listeners.put(FunctionID.ON_DRIVER_DISTRACTION, notified(n -> onDriverDistraction((OnDriverDistraction) n)));
            listeners.put(FunctionID.ON_PERMISSIONS_CHANGE, notified(n -> onPermissionsChange((OnPermissionsChange) n)));

            SdlManager.Builder builder = new SdlManager.Builder(
                    this, APP_ID, APP_NAME, newManagerListener(gen));
            // 关键：没有这个车机搜不到（MultiplexTransportConfig）
            builder.setTransportType(new MultiplexTransportConfig(getApplicationContext(), APP_ID));
            builder.setShortAppName(SHORT_APP_NAME);
            builder.setAppTypes(appTypes);                       // → isMediaApplication = true
            builder.setLanguage(Language.ZH_CN);                  // 硬写中文
            builder.setRPCNotificationListeners(listeners);       // 注册前预挂，避免漏掉早期通知

            sdlManager = builder.build();
            lifecycle.onProxyCreated();
            // 把发送通道挂到 SyncCore（渲染器与数据采集都在那边）
            com.xigaigai.syncglance.core.SyncCore.get()
                    .attachSender(msg -> sendRpc((RPCRequest) msg));
            sdlManager.start();
            updateNotification();
            Log2.i(TAG, "✓ SdlManager 已启动，等待车机连上并完成注册"
                    + "（appName=" + APP_NAME + ", appId=" + APP_ID + "）");
        } catch (Exception e) {
            Log2.e(TAG, "创建 SdlManager 失败", e);
            sdlManager = null;
            lifecycle.onProxyClosed();
        }
    }

    /**
     * 小工具：5.x 的 {@link OnRPCNotificationListener} 是抽象类不是接口，
     * 不能直接写 lambda，这里包一层。参数用 Consumer 只是为了让调用处保持简洁。
     */
    private static OnRPCNotificationListener notified(
            java.util.function.Consumer<RPCNotification> handler) {
        return new OnRPCNotificationListener() {
            @Override
            public void onNotified(RPCNotification notification) {
                handler.accept(notification);
            }
        };
    }

    // ---------------- 关于 autoActivateID（迁移版缺失项）----------------

    /*
     * 4.12.2 时我们持久化并回传 autoActivateID，让车机能凭它把 App 直接重新激活到
     * HMILevel=FULL（不用用户再去车机菜单里点一次）。
     *
     * 5.8.0 的 SdlManager.Builder 里<b>没有</b>对应 API —— 只有 setResumeHash，
     * 而那是 RPC 会话恢复（resumption）机制，需要车机支持 SDL 4.0+ 的 resumption，
     * SYNC 1.1（SDL 2.1）基本不可能支持。
     *
     * 所以迁移版暂时放弃这个能力；如果上车验证发现"杀掉 App 后车机不再自动激活"
     * 影响使用，得考虑退回 4.12.2 或自定义 RAI。
     */

    /**
     * 重置连接。
     * 断开 → 释放资源 → 重新初始化，完成后可以接受新的 SYNC 连接。
     */
    public void resetProxy() {
        Log2.i(TAG, "重置连接");
        disposing = true;                 // 告诉 onDestroy()：这是我们主动拆的，别重复收尾
        SdlManager old = sdlManager;
        sdlManager = null;
        com.xigaigai.syncglance.core.SyncCore.get().detachSender();
        if (old != null) {
            try { old.dispose(); } catch (Exception ignored) { }
        }
        lifecycle.onProxyClosed();
        menusBuilt = false;
        renderer.onDisconnected();
        disposing = false;
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
        if (sdlManager == null) {
            Log2.w(TAG, "✗ 未连接，丢弃 " + req.getFunctionName());
            return;
        }
        if (!lifecycle.canSendRpc()) {
            Log2.w(TAG, "✗ 当前 " + lifecycle.describe() + "，不能发 "
                    + req.getFunctionName() + "（需 HMI_FULL）");
            return;
        }
        try {
            /*
             * ★ 5.x 没有全局的"响应回调"了（4.x 是一个巨长的 IProxyListenerALM 接口）。
             *   为了保住原来的日志能力（看到每条 RPC 的结果码），
             *   这里给每条请求挂一个通用 OnRPCResponseListener，
             *   等价于 4.x 里的 onShowResponse / onSetMediaClockTimerResponse …
             */
            req.setOnRPCResponseListener(new OnRPCResponseListener() {
                @Override
                public void onResponse(int correlationId, RPCResponse response) {
                    if (response == null) return;
                    Log2.rawMsg("←", response);
                    if (!response.getSuccess()) {
                        Log2.w(TAG, response.getFunctionName() + " ← " + response.getResultCode()
                                + (response.getInfo() == null ? "" : " " + response.getInfo()));
                    }
                }
            });
            sdlManager.sendRPC(req);
            Log2.i(TAG, "→ " + req.getFunctionName() + " #" + req.getCorrelationID());
            logRawJson("→", req);
        } catch (Exception e) {
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
        msg.setTimeout(20000);           // ★ 单位是**毫秒**，不是秒。填 20 会被车机判
                                         //   INVALID_DATA（.timeout out of range）—— 实测过。
                                         //   20 秒后车机自动收起。
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
        if (sdlManager == null) return out;
        try {
            RegisterAppInterfaceResponse rai = sdlManager.getRegisterAppInterfaceResponse();
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

    /*
     * 歌曲 / 导航的数据采集已经搬进 SyncCore（与连接解耦）：
     *   - 通知监听  → SyncCore.musicListener
     *   - 高德 AIDL → SyncCore.navListener
     *   - 推送时钟  → SyncCore.pushClock()
     * 服务这边只需要在需要时读 renderer，不再承担数据入口的职责。
     */

    /** 设置导航数据（外部手动调用时用；正常数据流走 SyncCore） */
    public void setNavigation(Navigation nav) {
        com.xigaigai.syncglance.core.SyncCore.get().setNavigation(nav);
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
     * 5.x 里这个通知通过 {@code FunctionID.ON_HMI_STATUS} 注册（见 startProxy），
     * 不再是 4.x 那个 {@code onOnHMIStatus} 接口方法。
     *
     * 规则：
     *   - 只有 FULL 能发 RPC
     *   - 首次进入 FULL 要做完整初始化（建菜单、订阅按键）
     *   - 从 FULL 掉出去时，车机会自动终止我们正在进行的操作
     */
    public void onHmiStatus(OnHMIStatus n) {
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
        closedOnce = false;        // 又连上了，"连接关闭"作废
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
        if (sdlManager == null) return;
        try {
            RegisterAppInterfaceResponse rai = sdlManager.getRegisterAppInterfaceResponse();
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


    // ---------------- 通知回调（5.x 按 FunctionID 注册，替代 4.x 的巨型监听接口）----------------

    /** 菜单项被点（不走 OnButtonPress） */
    private void onCommand(OnCommand n) {
        Log2.rawMsg("←", n);
        Log2.i(TAG, "OnCommand cmdID=" + n.getCmdID());
        CommandRouter.handle(this, n.getCmdID());
    }

    /** 物理键 + 软键回调 */
    private void onButtonPress(OnButtonPress n) {
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

    private void onButtonEvent(OnButtonEvent n) {
        Log2.rawMsg("←", n);
        Log2.i(TAG, "OnButtonEvent " + n.getButtonName() + " " + n.getButtonEventMode()
                + (n.getCustomButtonID() == null ? "" : " id=" + n.getCustomButtonID()));
    }

    private void onDriverDistraction(OnDriverDistraction n) {
        Log2.rawMsg("←", n);
        Log2.i(TAG, "OnDriverDistraction " + n.getState());
    }

    private void onPermissionsChange(OnPermissionsChange n) {
        Log2.rawMsg("←", n);
        Log2.i(TAG, "OnPermissionsChange");
    }

    // ---------------- SdlManagerListener（5.x 的连接生命周期）----------------

    /**
     * 5.x 要求把连接回调挂到一个 {@link SdlManagerListener} 上。
     *
     * ★ 必须用内部类，<b>不能</b>让 SdlService 直接 implement 这个接口：
     *   它的回调叫 {@code onDestroy()}，和 Service 自己的 {@code onDestroy()}
     *   同名同签名 —— 一个类里只有一个方法，两套语义会打架
     *   （连接一断开就误跑 Service 的销毁逻辑，把 instance 置空，UI 就找不到服务了）。
     *
     * ★ 每次 startProxy() 都新建一个，并带上「代际」：
     *   dispose() 是异步的，旧 manager 的 onDestroy 可能在新 manager 建好之后才到，
     *   届时如果照单全收，会把刚建好的连接和 sender 一起清掉。
     *   所以回调里先比对代际，过期的直接忽略。
     */
    private SdlManagerListener newManagerListener(final long gen) {
        return new SdlManagerListener() {
            @Override
            public void onStart() {
                if (gen != generation) return;
                Log2.i(TAG, "✓ SdlManager onStart —— 已注册完成，等待车机下发 HMI 状态");
                /*
                 * ★ 注册一完成就把车机能力清单 dump 出来。
                 *
                 *   以前只在「进入 FULL」时 dump（initializeForConnection）。
                 *   但真正出问题时往往进不到 FULL —— 注册被拒、或者车机一直不给 HMI。
                 *   那种情况下 App 内日志里一条车机回执都没有，等于瞎猜。
                 *   放在这里，无论后面能不能到 FULL，车机返回的 RAI 响应都会留在日志里。
                 */
                dumpRaiResponse();
            }

            @Override
            public void onDestroy() {
                if (gen != generation) {
                    Log2.i(TAG, "忽略过期连接对象的 onDestroy（代际 " + gen
                            + " ≠ 当前 " + generation + "）");
                    return;
                }
                if (disposing) return;      // 我们自己 dispose 的，收尾已在调用处做过
                handleConnectionClosed();
            }

            @Override
            public void onError(String info, Exception e) {
                Log2.e(TAG, "onError: " + info, e);
            }

            @Override
            public boolean onSystemInfoReceived(SystemInfo systemInfo) {
                return true;                // 不做系统信息拦截
            }

            /**
             * 车机回报的语言与请求不符时，5.x 会问我们要不要改注册信息。
             * 我们硬写 ZH_CN（本车实测就是 ZH-CN），返回 null 表示"不改"，
             * 让 SDK 走它自己的 ChangeRegistration 流程。
             */
            @Override
            public LifecycleConfigurationUpdate managerShouldUpdateLifecycle(Language language, Language hmiLanguage) {
                Log2.w(TAG, "车机语言与请求不符：language=" + language + " hmi=" + hmiLanguage);
                return null;
            }
        };
    }

    /**
     * 连接结束时的本地收尾（等价于 4.x 的 onProxyClosed）。
     *
     * ★ <b>完全照官方 hello_sdl 的做法：收尾之后把 Service 停掉</b>（`stopSelf`）。
     *
     *   用户原则（`AGENTS.md` §1.1）：**连接生命周期控制始终保持与 SDL 官方一致，
     *   没必要自己乱改，除非发现了难以解决的兼容性问题。**
     *
     *   官方没有应用层重试 —— 重连靠 SDL 官方链路：
     *     车机重连 → ACL_CONNECTED → SdlReceiver/SyncRouterService 起路由
     *     → sdl.router.startservice / onSdlEnabled → 系统重新拉起本 Service → 重建 SdlManager
     *
     *   所以前台通知只在**本 Service 存活期间**存在（正常就是车机连接期间）。
     *   断开后通知会跟着消失 —— 这是官方语义的必然结果，不是 bug。
     *
     *   千万不要加「每 5 秒重建、最多 12 次」这类应用层重试：会让车机侧
     *   反复看到同一个 appName 的注册请求，卡在「恢复连接」不动（踩过）。
     *   也不要不 stopSelf 来硬撑常驻通知（试过，为了常驻通知而偏离官方语义，
     *   最终按用户要求改回来了）。
     */
    private void handleConnectionClosed() {
        Log2.i(TAG, "连接结束（原状态 " + lifecycle.getState() + "），按官方语义停掉 Service");
        /*
         * 「连接关闭」只在**真的跟车机对上过话**之后才成立。
         *
         * 停在 CONNECTING 就断的，说明 SdlManager 建好了但车机一条 OnHMIStatus 都没回
         * （RouterService 没跑 / 车机没选中），这时报「未连接（连接关闭）」会误导。
         *
         * 注意：本方法末尾就 stopSelf 了，所以这个状态实际上几乎看不到；
         * 留着是为了「服务因为别的原因仍活着」的情况下语义正确。
         */
        SyncLifecycle.State beforeClose = lifecycle.getState();
        closedOnce = beforeClose != SyncLifecycle.State.CONNECTING
                && beforeClose != SyncLifecycle.State.DISCONNECTED;
        sdlManager = null;
        lifecycle.onProxyClosed();
        // 断开了，导航数据不用解码了（省 CPU）
        com.xigaigai.syncglance.nav.AmapLinkClient.setHeadUnitConnected(false);
        // 车机侧所有菜单/订阅都已被删除，重连要全量重建
        menusBuilt = false;
        pendingTimerResend = true;
        renderer.onDisconnected();
        com.xigaigai.syncglance.core.SyncCore.get().detachSender();

        // 断开瞬间先把通知刷成「未连接（连接关闭）」，随后 stopSelf 会连通知一起收走
        updateNotification();
        stopSelf();          // ★ 官方语义：断开就退出，等广播链路重新拉起
    }
}