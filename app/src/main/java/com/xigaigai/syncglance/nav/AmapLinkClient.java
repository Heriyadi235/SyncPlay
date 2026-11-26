package com.xigaigai.syncglance.nav;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import com.xigaigai.syncglance.util.Log2;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 高德导航数据探测（AmapLink）。
 *
 * === 原理 ===
 * 高德手机版内部给智能眼镜/穿戴设备留了一条导航通道：
 *
 *   Intent("com.autonavi.minimap.service.NAVIGATION_SERVICE")
 *     → ComponentName("com.autonavi.minimap",
 *                     "com.amap.bundle.wearable.connect.glass.AmapLinkService")
 *
 * 绑上之后通过 AIDL 通信：
 *   ILinkSdkControl:
 *     transact 1 = 授权，返回 JSON（status / data.token）
 *     transact 2 = 执行命令（cmd / requestId / token / sessionId）
 *     transact 3 = 注册回调（writeStrongBinder）
 *     transact 4 = 注销回调
 *   ILinkSdkCallback:
 *     transact 1 = String（小包）
 *     transact 2 = (String metadata, ParcelFileDescriptor fd)（大包走共享 FD）
 *
 * === 回调是加密的 ===
 * Base64 解码后：[idLen][id][keyLen][key][nonce 12][ciphertext]
 * AES-GCM 解密，AAD 用 id，密钥就是信封里带的那个（不是授权 token）。
 *
 * === 参考 ===
 * https://github.com/fw867/hypercar
 *
 * 本阶段目标：**只要能拿到回调就算成功**，解析先放一边。
 */
public class AmapLinkClient {

    private static final String TAG = "AmapLink";

    private static final String PKG = "com.autonavi.minimap";
    private static final String SERVICE_ACTION = "com.autonavi.minimap.service.NAVIGATION_SERVICE";
    private static final String SERVICE_CLASS =
            "com.amap.bundle.wearable.connect.glass.AmapLinkService";
    private static final String CONTROL = "com.amap.linksdk.aidldefine.ILinkSdkControl";
    private static final String CALLBACK = "com.amap.linksdk.aidldefine.ILinkSdkCallback";

    /** 单包上限 */
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    /**
     * 最多重连次数 —— 8 次（对齐参考实现 hypercar 的 {@code LinkBindingState}）。
     *
     * <p>配合递增退避（2s → 4s → … 封顶 15s），8 次大约覆盖 <b>72 秒</b>：
     * 足够扛过"高德刚启动、服务还没起来"的竞态，又不会像固定 2 秒 × 50 次那样
     * 持续唤醒 100 秒（高德没跑时很常见，费电）。</p>
     *
     * <p>试满之后要重新连，靠这三条路重置预算（见 {@link #resetAttempts()}）：
     * 用户点「高德地图」按钮、车机连上、信息页打开。</p>
     */
    private static final int MAX_ATTEMPTS = 8;
    /** 退避基数：第 n 次重试用 {@code n × 2s}，封顶 15s */
    private static final long RETRY_BASE_MS = 2_000;
    /** 退避上限 */
    private static final long RETRY_MAX_MS = 15_000;
    /** 绑定/注册超时 */
    private static final long WATCHDOG_MS = 10_000;

    private static AmapLinkClient instance;

    /** 导航数据回调 */
    public interface NavListener {
        void onNavigation(com.xigaigai.syncglance.model.Navigation nav);
    }

    private static volatile NavListener navListener;

    public static void setNavListener(NavListener l) { navListener = l; }

    /**
     * 车机是否已连接（FULL）。
     *
     * 没连车机时导航数据没人看，跳过解密和解析能省不少 CPU
     * （AES-GCM 每秒跑 1~2 次）。
     * 但**仍然读取 fd 并记录心跳**，方便在手机上确认链路是通的。
     */
    private static volatile boolean headUnitConnected = false;

    /**
     * 预览页是否正被用户查看。
     *
     * 解码（AES-GCM）是 CPU 大头，所以按需进行：
     *   - 车机在 FULL → 一定要解码（数据要送到屏幕上）
     *   - 否则 → 只有用户打开信息页（正在看车机屏幕预览）时才解码
     * 两者都不成立时仍然读取并关闭 fd（避免泄漏），但只记心跳不解密。
     */
    private static volatile boolean previewVisible = false;

    /** 信息页 onResume/onPause 里调用 */
    public static void setPreviewVisible(boolean visible) {
        if (previewVisible == visible) return;
        previewVisible = visible;
        Log2.i(TAG, "预览页" + (visible ? "已打开（开始解析导航包）" : "已关闭（停止解析导航包）"));
        // 注意：这里**不再触发重连** —— 连接由 AmapLinkClient 自己维持，
        // 开不开预览只影响"要不要解密/解析"，见 class 注释。
    }

    public static boolean isPreviewVisible() { return previewVisible; }

    /**
     * 是否应该**解密并解析**导航包（车机连着，或者用户正在看预览页）。
     *
     * <p>★ 这个条件<b>只管解码</b>，不再管连接 —— 两者已经解耦
     * （用户 2026-11-27 决定，方案 B）。以前它同时决定"要不要重连"，
     * 导致没连车机时 AmapLink 47 毫秒就放弃、界面卡在重连中。</p>
     */
    private static boolean shouldDecode() {
        return headUnitConnected || previewVisible;
    }

    public static void setHeadUnitConnected(boolean connected) {
        headUnitConnected = connected;
    }

    public static boolean isHeadUnitConnected() { return headUnitConnected; }

    public static synchronized AmapLinkClient get(Context ctx) {
        if (instance == null) instance = new AmapLinkClient(ctx.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    // 绑定代际，防止旧回调污染
    private long generation = 0;
    private boolean closed = false;
    private int attempts = 0;
    private Attempt current;
    private int remoteUid = -1;

    // 统计
    private volatile int callbackCount = 0;
    private volatile boolean authenticated = false;
    private volatile boolean registered = false;
    private final Map<Integer, String> lastByType = new ConcurrentHashMap<>();

    private AmapLinkClient(Context ctx) {
        this.ctx = ctx;
        try {
            remoteUid = ctx.getPackageManager()
                    .getApplicationInfo(PKG, 0).uid;
        } catch (Exception e) {
            Log2.w(TAG, "找不到高德包");
        }
    }

    /** 当前状态摘要，给 UI 显示 */
    public String status() {
        if (closed) return "已停止";
        if (registered) return "已注册回调（收到 " + callbackCount + " 条）";
        if (authenticated) return "已授权，等待注册";
        if (current != null) return "已绑定，认证中";
        return "未连接（尝试 " + attempts + "/" + MAX_ATTEMPTS + "）";
    }

    // ---------------- 主动探测 ----------------

    /**
     * 主动探测：连接是否<b>真的</b>还活着。
     *
     * <h3>为什么必须主动探，不能等回调</h3>
     * 高德被 force-stop / 从最近任务划掉时，系统<b>不保证</b>回调
     * {@code onServiceDisconnected} 或 {@code onBindingDied} ——
     * 实车日志里高德被杀掉后一条回调都没有（见 docs/development.md 8.28）。
     * 只靠回调判断，{@code registered} 会永远停在 true，界面一直显示"已连接"。
     *
     * <h3>为什么 IBinder 能答</h3>
     * binder 驱动层自己维护对端进程的生死。对端一死，本地 binder 对象立刻被标记为
     * 死亡状态，{@link IBinder#isBinderAlive()} 立即返回 false ——
     * <b>纯本地查询，不唤醒高德、不产生 IPC</b>。
     *
     * <p>{@link IBinder#pingBinder()} 会再发一次空事务，能覆盖
     * "进程活着但服务异常" 的情况，代价是一次极轻的 IPC。
     * 所以这里先 isBinderAlive（零成本），为真再 ping（更严格）。</p>
     *
     * @return true = 连接仍然有效
     */
    public boolean isLinkAlive() {
        Attempt a = current;
        if (!registered || a == null) return false;
        IBinder b = a.remote;
        if (b == null) return false;
        try {
            // ① 零成本：binder 驱动层的本地状态
            if (!b.isBinderAlive()) return false;
            // ② 更严格：真发一次空事务，覆盖"进程在、服务异常"
            return b.pingBinder();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 在「可能出事」的事件点调用：探一下，死了就重建，活着就什么都不做。
     *
     * <h3>只在这些事件点调用，不做轮询</h3>
     * <ul>
     *   <li>收到高德的「导航中」通知（说明它活着并在工作）</li>
     *   <li>导航数据超过 8 秒没更新（{@code StateRenderer.staleWatchdog}）</li>
     *   <li>用户点「刷新连接状态」/「重新连接」按钮</li>
     * </ul>
     *
     * @param reconnectIfDead true = 发现已死就重置预算重绑；false = 只探测不动连接
     * @return 探测结果（true = 连接有效）
     */
    public boolean probe(boolean reconnectIfDead) {
        if (!registered) {
            // 压根没连上，交给正常的重试链路；这里不掺和
            return false;
        }
        boolean alive = isLinkAlive();
        if (!alive) {
            Log2.w(TAG, "⚠ 主动探测发现连接已死（binder 不可用），高德可能被关闭");
            if (reconnectIfDead) {
                // 把假活的 registered 清掉，否则 wakeUp 会以为"已连接"直接返回
                releaseDeadBinding();
                wakeUpFromMapLaunch();
            }
        }
        return alive;
    }

    /**
     * 静态便捷入口：探活，死了就重绑。供 `SyncPlayCore` 之类拿不到实例的地方调用。
     *
     * @return 连接是否有效
     */
    public static boolean probeAndReconnect() {
        AmapLinkClient inst = instance;
        if (inst == null) return false;
        return inst.probe(true);
    }

    /**
     * 清掉已经死掉的绑定状态。
     *
     * <p>不能只置 {@code registered = false} —— 旧的 {@code Attempt} 还占着
     * {@code current}，会导致 {@code bind()} 里的 {@code if (current != null) return}
     * 永远挡住新连接。必须 retire 掉。</p>
     */
    private void releaseDeadBinding() {
        Attempt old;
        synchronized (this) {
            old = current;
            current = null;
            registered = false;
            authenticated = false;
        }
        if (old != null) old.retire();
    }

    /**
     * 主界面「导航」那一行显示的状态。
     *
     *   已连接（导航中）—— 连上了且有导航数据
     *   已连接           —— 连上了但还没开始导航
     *   连接已失效       —— 高德进程没了（主动探测发现的）
     *   尝试重连中 (n/8) —— 正在重试
     *   高德地图未启动   —— 试满次数都没连上
     */
    public String displayStatus() {
        if (registered) {
            /*
             * ★ 这里要做一次探活。
             *
             *   高德被 force-stop 时系统不一定回调，registered 会是**假的 true** ——
             *   以前这里直接返回"已连接（导航中）"，界面就一直在骗人
             *   （实车日志：高德杀掉后主界面仍显示导航中，见 docs/development.md 8.28）。
             *
             *   探活是纯本地的 binder 状态查询，不唤醒高德、不产生 IPC，
             *   而 displayStatus() 只在界面刷新时调用（页面可见时才跑），开销可忽略。
             */
            if (!isLinkAlive()) return "⚠ 连接已失效";
            return nav.hasData() ? "已连接（导航中）" : "已连接";
        }
        if (current != null) {
            return "尝试重连中 (" + attempts + "/" + MAX_ATTEMPTS + ")";
        }
        if (attempts >= MAX_ATTEMPTS) {
            return "高德地图未启动";
        }
        /*
         * ★ 不再有 `if (attempts > 0) return "尝试重连中"` 这条分支。
         *
         *   它曾导致「打开 App 没启动高德时，界面永远卡在 尝试重连中 (1/8)」：
         *   走到这里说明 current == null（没有正在进行的尝试），
         *   却因为残留的 attempts > 0 而谎报"正在重连"。
         *
         *   真实情况只有两种：
         *     · 刚启动、还没试过（attempts == 0）
         *     · 试了但还没有进行中的尝试（即将按退避重试，或已放弃）
         *   两者都该显示「未连接」，“重连中”只属于 current != null。
         */
        return attempts > 0 ? "未连接（即将重试）" : "未连接";
    }

    /**
     * 是否处于「已连接且确实有效」的状态。
     *
     * <p>⚠ 这不是 {@code registered} 的别名：高德被强制关闭时系统不一定回调，
     * {@code registered} 可能是<b>假的 true</b>。所以加一道 binder 探活。</p>
     */
    public boolean isConnected() {
        return registered && isLinkAlive();
    }

    public boolean isRegistered() { return registered; }
    public int getCallbackCount() { return callbackCount; }

    // ---------------- 启动 / 停止 ----------------

    public void start() {
        synchronized (this) {
            if (!closed && current != null) {
                Log2.i(TAG, "已在运行");
                return;
            }
            closed = false;
            attempts = 0;
        }
        Log2.i(TAG, "开始绑定高德导航服务…");
        bind();
    }

    public void stop() {
        synchronized (this) {
            closed = true;
            generation++;
        }
        Attempt a = current;
        current = null;
        if (a != null) a.retire();
        authenticated = false;
        registered = false;
        nav.clear();
        clearListener();
        Log2.i(TAG, "已停止");
    }

    /** 通知监听方：导航数据没了 */
    private void clearListener() {
        NavListener l = navListener;
        if (l != null) {
            try {
                com.xigaigai.syncglance.model.Navigation empty =
                        new com.xigaigai.syncglance.model.Navigation();
                empty.clear();
                l.onNavigation(empty);
            } catch (Exception ignored) { }
        }
    }

    private synchronized boolean beginAttempt() {
        if (closed || attempts >= MAX_ATTEMPTS) return false;
        attempts++;
        generation++;
        return true;
    }

    private synchronized boolean accepts(long token) {
        return !closed && generation == token;
    }

    /**
     * 重试间隔。
     *
     * <p>以前是固定 2 秒 × 50 次 ≈ 100 秒的持续唤醒：高德没在跑时（很常见），
     * 开车路上就会一直 bindService + 起 watchdog，非常费电。</p>
     *
     * <p>现在：前 5 次 2 秒（覆盖"高德刚启动、需要快点连上"的场景），
     * 之后拉长到 10 秒，总共 10 次 ≈ 60 秒就停。唤醒次数从 50 降到 10。</p>
     */
    /**
     * 重试间隔：递增退避，与参考实现 hypercar 一致。
     *
     * <pre>
     *   第 1 次 2s → 第 2 次 4s → 第 3 次 6s … 封顶 15s
     * </pre>
     *
     * 比起固定 2 秒，同样能把"高德刚起"的竞态覆盖住，
     * 但试到后面时唤醒间隔拉长，总唤醒次数少很多。
     */
    private long retryDelay() {
        return Math.min(RETRY_BASE_MS * Math.max(1, attempts), RETRY_MAX_MS);
    }

    /**
     * 外部唤醒：重新开始一轮重试。
     *
     * 触发时机：
     *   - 用户点了「高德地图」/「重新连接」按钮
     *   - 收到高德的通知（说明它启动了）
     *   - 主界面 onResume 补一次
     */
    public void wakeUp() {
        wakeUp(false);
    }

    /**
     * 用户**拉起高德地图**时的唤醒 —— 对应参考实现 hypercar 的
     * {@code AmapLinkProbe.start(context, mapStarted = true)}。
     *
     * <h3>为什么它和普通 wakeUp 不同</h3>
     * "用户刚打开高德"是最确定的信号：地图马上会起服务。这时值得
     * <b>无条件重置重试预算、并且打断当前正在进行的尝试立刻重绑</b>，
     * 而不是等退避定时器慢慢到期。
     *
     * <p>这正是解决「高德被关闭后再打开、导航数据不再推送」的关键入口：
     * 试满 8 次后预算耗尽，用户点一下「高德地图」按钮就能满血复活。</p>
     */
    public void wakeUpFromMapLaunch() {
        Log2.i(TAG, "用户拉起高德地图 → 重置重试预算并立即重绑");
        wakeUp(true);
    }

    /**
     * @param force true = 立即打断当前尝试重新绑定（ignores "正在连接中"）
     */
    private void wakeUp(boolean force) {
        Attempt old = null;
        synchronized (this) {
            /*
             * ★ 用 isLinkAlive() 而不是 registered 判断"已连接"。
             *
             *   registered 可能是**假的 true** —— 高德被强制关闭时系统
             *   不一定回调 onServiceDisconnected，标志就一直挂着。
             *   只看 registered 会导致 wakeUp 永远静默返回，
             *   界面显示"已连接不再重连"（实车踩过，见 docs/development.md 8.28）。
             */
            if (registered && !force && isLinkAlive()) {
                return;                          // 真的活着，静默返回（通知触发会很频繁）
            }
            if (registered && !isLinkAlive()) {
                Log2.w(TAG, "registered=true 但 binder 已死，按未连接处理");
                old = current;
                current = null;
                registered = false;
                authenticated = false;
            }
            if (closed) {
                Log2.i(TAG, "服务已关闭，重新启动");
                closed = false;
            }
            if (current != null) {
                if (!force) return;             // 正在连接中，静默忽略
                old = current;                  // force：先把旧尝试作废
                current = null;
            }
            attempts = 0;                       // 重置重试计数
        }
        if (old != null) old.retire();
        Log2.i(TAG, "收到唤醒，重新尝试连接高德");
        bind();
    }

    private void bind() {
        main.post(() -> {
            if (closed) return;
            if (current != null) return;

            if (remoteUid < 0) {
                Log2.w(TAG, "高德未安装，退出");
                return;
            }
            if (!beginAttempt()) {
                Log2.w(TAG, "已重试 " + MAX_ATTEMPTS + " 次，停止。"
                        + "检查高德是否在跑、有没有给本应用「关联启动」权限");
                return;
            }

            Attempt a = new Attempt(generation);
            current = a;

            Intent intent = new Intent(SERVICE_ACTION)
                    .setComponent(new ComponentName(PKG, SERVICE_CLASS));

            try {
                a.bound = ctx.bindService(intent, a.connection, Context.BIND_AUTO_CREATE);
            } catch (Exception e) {
                Log2.e(TAG, "bindService 异常", e);
                a.bound = false;
            }

            if (a.bound) {
                main.postDelayed(a.watchdog, WATCHDOG_MS);
            } else {
                retry(a, "未接受绑定（需要「关联启动」权限？）");
            }
        });
    }

    private void retry(Attempt a, String reason) {
        if (closed || current != a) return;

        /*
         * ★ 连接策略与界面显示解耦（用户 2026-11-27 决定，方案 B）。
         *
         *   以前这里有一条 `if (!shouldDecode()) { 挂起; return; }` ——
         *   shouldDecode() 是「车机连着 || 预览可见」，本来是给**解密**用的
         *   （AES-GCM 是 CPU 大头，没人看就是白解密）。
         *
         *   但把**重连**也挂在这个条件上，后果是：
         *     没连车机 + 没开预览 → 绑定 47 毫秒后就放弃
         *     → 界面卡在「尝试重连中 (1/8)」不动
         *     → 高德启动也连不上，用户必须先开一次预览才能用
         *
         *   现在两者彻底分开：
         *     · 连接（bind / 注册 / 收回调）—— 维持，不受没人看影响
         *     · 解密 + 解析导航包 —— 仍然受 shouldDecode() 约束（在 handleXxx 里判断）
         *
         *   保持连接的代价很小：不解密时，一条回调只是把字符串读出来再丢掉。
         */
        a.retire();
        current = null;
        main.postDelayed(this::bind, retryDelay());
    }

    // ---------------- 单次绑定 ----------------

    private class Attempt {
        final long token;
        volatile boolean bound = false;
        volatile boolean retired = false;
        volatile boolean authed = false;
        volatile boolean regged = false;
        volatile IBinder remote;
        final long sessionId = SystemClock.elapsedRealtime() % 900000 + 100000;

        Attempt(long token) {
            this.token = token;
        }

        final Runnable watchdog = () -> {
            if (!retired && !regged) retry(this, "绑定/认证超时");
        };

        final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (retired) return;
                remote = service;
                Log2.i(TAG, "✓ 服务已连接，descriptor=" + safeDescriptor(service));
                worker.execute(() -> register(service));
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                remote = null;
                retry(Attempt.this, "服务断开");
            }

            @Override
            public void onBindingDied(ComponentName name) {
                remote = null;
                retry(Attempt.this, "绑定失效");
            }

            @Override
            public void onNullBinding(ComponentName name) {
                retry(Attempt.this, "空绑定");
            }
        };

        /** 回调 Binder —— 高德反向调我们 */
        final Binder callback = new Binder() {
            @Override
            public boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code == INTERFACE_TRANSACTION) {
                    if (reply != null) reply.writeString(CALLBACK);
                    return true;
                }
                if (code < 1 || code > 2) return false;

                // ★ 防伪造：只认高德的 UID
                if (Binder.getCallingUid() != remoteUid) {
                    Log2.w(TAG, "拒绝伪造回调，uid=" + Binder.getCallingUid());
                    return false;
                }

                data.enforceInterface(CALLBACK);

                if (code == 1) {
                    String text = data.readString();
                    if (text != null) handleSmallPayload(text);
                } else {
                    String metadata = data.readString();
                    ParcelFileDescriptor fd = data.readInt() != 0
                            ? ParcelFileDescriptor.CREATOR.createFromParcel(data) : null;
                    if (fd != null) handleFdPayload(metadata, fd);
                }

                if (reply != null) reply.writeNoException();
                return true;
            }
        };

        void retire() {
            retired = true;
            authed = false;
            regged = false;
            main.removeCallbacks(watchdog);

            IBinder svc = remote;
            boolean wasRegged = regged;
            remote = null;
            regged = false;

            if (svc != null && wasRegged) {
                try {
                    worker.execute(() -> {
                        try { call(svc, 4, p -> p.writeStrongBinder(callback)).recycle(); }
                        catch (Exception ignored) { }
                    });
                } catch (Exception ignored) { }
            }
            if (bound) {
                bound = false;
                try { ctx.unbindService(connection); } catch (Exception ignored) { }
            }
        }
    }

    private static String safeDescriptor(IBinder b) {
        try { return b == null ? "null" : b.getInterfaceDescriptor(); }
        catch (Exception e) { return "?"; }
    }

    // ---------------- 注册流程 ----------------

    private void register(IBinder service) {
        Attempt a = current;
        if (a == null || a.retired) return;

        try {
            String desc = service.getInterfaceDescriptor();
            if (!CONTROL.equals(desc)) {
                Log2.w(TAG, "接口描述符不匹配: " + desc + "（期望 " + CONTROL + "）");
            }

            // --- transact 1：授权 ---
            Parcel authReply = call(service, 1, p -> { });
            String authJson;
            try {
                authJson = authReply.readString();
            } finally {
                authReply.recycle();
            }

            Log2.i(TAG, "授权返回: " + abbreviate(authJson, 500));
            Log2.raw("←auth", authJson);

            JSONObject authObj = null;
            try { authObj = new JSONObject(authJson == null ? "{}" : authJson); }
            catch (Exception ignored) { }

            boolean ok = authObj != null && "success".equals(authObj.optString("status"));
            a.authed = ok;
            authenticated = ok;

            if (!ok) {
                Log2.w(TAG, "授权未通过，全部字段：" + (authObj == null ? "解析失败" : authObj.toString()));
                main.post(() -> retry(a, "授权被拒"));
                return;
            }
            Log2.i(TAG, "✓ 授权成功");

            // --- transact 3：注册回调 ---
            Parcel regReply = call(service, 3, p -> p.writeStrongBinder(a.callback));
            boolean accepted;
            try {
                accepted = regReply.readInt() != 0;
            } finally {
                regReply.recycle();
            }

            a.regged = accepted;
            registered = accepted;
            Log2.i(TAG, (accepted ? "✓ 回调注册成功" : "✗ 回调注册被拒"));

            if (accepted) {
                main.post(() -> {
                    if (!a.retired) {
                        main.removeCallbacks(a.watchdog);
                        attempts = 0;
                    }
                });
                // 主动查一次导航数据：cmd 5 = 获取导航结构化数据
                String token = authObj.optJSONObject("data") == null ? ""
                        : authObj.optJSONObject("data").optString("token");
                if (!token.isEmpty()) {
                    for (int cmd : new int[]{7, 5}) {
                        try {
                            JSONObject req = new JSONObject()
                                    .put("cmd", cmd)
                                    .put("requestId", (int) (System.nanoTime() % 900000 + 100000))
                                    .put("token", token)
                                    .put("sessionId", "syncglance-" + a.sessionId);
                            Parcel r = call(service, 2, p -> p.writeString(req.toString()));
                            String resp;
                            try { resp = r.readString(); } finally { r.recycle(); }
                            Log2.i(TAG, "查询 cmd=" + cmd + " 返回: " + abbreviate(resp, 300));
                            Log2.raw("←cmd" + cmd, resp);
                        } catch (Exception e) {
                            Log2.w(TAG, "查询 cmd=" + cmd + " 失败: " + e.getMessage());
                        }
                    }
                } else {
                    Log2.w(TAG, "授权返回里没有 token，跳过主动查询");
                }
            } else {
                main.post(() -> retry(a, "注册不被接受"));
            }

        } catch (Exception e) {
            Log2.e(TAG, "注册流程异常", e);
            main.post(() -> retry(a, "注册失败"));
        }
    }

    /** 发一次 transact 调用 */
    private Parcel call(IBinder target, int code, WriteAction write) {
        Parcel req = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            req.writeInterfaceToken(CONTROL);
            write.write(req);
            if (!target.transact(code, req, reply, 0)) {
                throw new IllegalStateException("transact " + code + " 返回 false");
            }
            reply.readException();
            return reply;
        } catch (Exception e) {
            reply.recycle();
            throw new RuntimeException(e);
        } finally {
            req.recycle();
        }
    }

    private interface WriteAction {
        void write(Parcel p);
    }

    // ---------------- 回调处理 ----------------

    /** 小包回调 */
    private void handleSmallPayload(String raw) {
        callbackCount++;

        // 没人看导航（车机没连、预览也没开）：不解密，只记心跳。
        // ★ 注意这**不影响连接** —— 连接由 retry()/bind() 独立维持。
        if (!shouldDecode()) {
            if (shouldLogHeartbeat()) {
                Log2.i(TAG, "收到回调 #" + callbackCount
                        + "（小包 " + raw.length() + " 字符，没人看跳过解码）");
            }
            return;
        }

        if (shouldLogHeartbeat()) {
            Log2.i(TAG, "收到回调 #" + callbackCount + "（小包 " + raw.length() + " 字符）");
        }

        String plain = LinkEnvelope.decode(raw);
        if (plain == null) {
            Log2.w(TAG, "信封解密失败（" + raw.length() + " 字符）");
            return;
        }
        onDecrypted(plain);
    }

    /** 心跳日志：前几次 + 2 的幂次，避免刷屏 */
    private boolean shouldLogHeartbeat() {
        return callbackCount <= 3 || (callbackCount & (callbackCount - 1)) == 0;
    }

    /**
     * 大包回调（走共享 FD）。
     *
     * 车机没连时：**仍然读完 fd 并关闭**（否则会泄漏），但跳过解密和解析。
     */
    private void handleFdPayload(String metadata, ParcelFileDescriptor fd) {
        worker.execute(() -> {
            try (ParcelFileDescriptor.AutoCloseInputStream in =
                         new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
                String raw = readAll(in);
                callbackCount++;

                if (!shouldDecode()) {
                    if (shouldLogHeartbeat()) {
                        Log2.i(TAG, "收到回调 #" + callbackCount
                                + "（大包 " + raw.length() + " 字符，未连车机跳过解码）");
                    }
                    return;
                }

                String plain = LinkEnvelope.decode(raw);
                if (plain == null) {
                    Log2.w(TAG, "FD 信封解密失败（" + raw.length() + " 字符）");
                    return;
                }
                onDecrypted(plain);
            } catch (Exception e) {
                Log2.w(TAG, "FD 处理失败: " + e.getMessage());
            }
        });
    }

    /** 正在累积的导航状态 */
    private final com.xigaigai.syncglance.model.Navigation nav =
            new com.xigaigai.syncglance.model.Navigation();

    /** 解密后的业务数据 */
    private void onDecrypted(String plain) {
        try {
            JSONObject json = new JSONObject(plain);
            int bizType = json.optInt("bizType", -1);

            if (bizType != 113) {
                Log2.i(TAG, "解密后 bizType=" + bizType + " 内容: " + abbreviate(plain, 300));
                Log2.raw("←plain", plain);
                return;
            }

            // bizType 113 = 导航业务数据
            Object datasRaw = json.opt("datas");
            String datas = datasRaw instanceof String ? (String) datasRaw : String.valueOf(datasRaw);
            org.json.JSONArray arr = new org.json.JSONArray(datas);

            boolean changed = false;

            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                int type = item.optInt("type", -1);
                Object val = item.opt("data");
                JSONObject data = val instanceof JSONObject ? (JSONObject) val
                        : (val instanceof String ? tryParse((String) val) : null);

                if (applyToNav(type, data)) changed = true;

                String summary = summarize(type, data);
                if (summary == null) continue;

                String prev = lastByType.get(type);
                if (!summary.equals(prev)) {
                    lastByType.put(type, summary);
                    Log2.i(TAG, "导航事件 type=" + type + "  " + summary);
                }
            }

            if (changed) {
                dispatch(criticalEvent);
                criticalEvent = false;
            }

        } catch (Exception e) {
            Log2.w(TAG, "解析失败: " + e.getMessage() + "  原文: " + abbreviate(plain, 300));
            Log2.raw("←plain", plain);
        }
    }

    /** 把事件写入 Navigation 对象 */
    private boolean applyToNav(int type, JSONObject d) {
        switch (type) {
            case 4: {
                if (d == null) return false;
                int maneuver = d.optInt("curManeuverID", -1);
                long segRemain = d.optLong("segmentRemainDist", -1);
                // 距离缺失时 formatDistance 返回空串，trim 掉多余尾空格
                nav.instruction = (maneuverName(maneuver) + " " + formatDistance(segRemain)).trim();
                nav.road = d.optString("nextRouteName", "");
                long remain = d.optLong("routeRemainDist", -1);
                long remainSec = d.optLong("routeRemainTime", -1);
                nav.remainDistanceMeters = remain;
                nav.remainSeconds = remainSec;
                nav.remainDistance = formatDistance(remain);
                nav.etaMillis = remainSec > 0
                        ? System.currentTimeMillis() + remainSec * 1000 : -1;
                nav.lastUpdateMillis = System.currentTimeMillis();
                return true;
            }
            case 5: {
                if (d == null) return false;
                int maneuver = d.optInt("maneuverID", -1);
                // ★ type 5 比 type 4 早到约 200ms，而且两者的编号经常不同：
                //   5 的 maneuverID 是"下一条路的入口图标"，
                //   4 的 curManeuverID 才是"当前这一段该做的动作"。
                //   以前拿 5 直接改写指令文字，会让箭头先跳到新图标、
                //   200ms 后又被 4 改回来，肉眼就是闪一下。
                //   所以 5 只在一句话都还没有的时候兜底。
                if (nav.instruction == null || nav.instruction.trim().isEmpty()) {
                    nav.instruction = maneuverName(maneuver);
                    nav.lastUpdateMillis = System.currentTimeMillis();
                    return true;
                }
                return false;
            }
            case 7:
                Log2.i(TAG, "★ 导航开始，清空旧状态");
                nav.clear();
                nav.lastUpdateMillis = System.currentTimeMillis();
                criticalEvent = true;
                return true;
            case 100:
            case 200:
                Log2.i(TAG, "★ 导航结束/到达，清空导航状态");
                nav.clear();
                nav.lastUpdateMillis = 0;
                criticalEvent = true;      // 必须立刻通知显示层降级
                return true;
            default:
                return false;
        }
    }

    /** 本次解析里是否包含需要立即推送的关键事件 */
    private boolean criticalEvent = false;

    private static long lastDispatchAt = 0;

    private void dispatch() {
        dispatch(false);
    }

    /**
     * 推送导航状态给显示层。
     *
     * @param force true 表示立即推送、不受 2 秒节流限制。
     *              导航开始/结束这类关键事件必须立刻送达，
     *              否则界面会卡在旧状态等下一次音乐通知。
     */
    private void dispatch(boolean force) {
        NavListener l = navListener;
        if (l == null) return;

        long now = System.currentTimeMillis();
        if (!force && now - lastDispatchAt < 2000) return;
        lastDispatchAt = now;
        try {
            com.xigaigai.syncglance.model.Navigation copy =
                    new com.xigaigai.syncglance.model.Navigation();
            copy.instruction = nav.instruction;
            copy.road = nav.road;
            copy.remainDistance = nav.remainDistance;
            copy.remainDistanceMeters = nav.remainDistanceMeters;
            copy.remainSeconds = nav.remainSeconds;
            copy.etaMillis = nav.etaMillis;
            copy.lastUpdateMillis = nav.lastUpdateMillis;
            l.onNavigation(copy);
        } catch (Exception e) {
            Log2.w(TAG, "推送失败: " + e.getMessage());
        }
    }

    /**
     * 距离格式化，**按量级分档取整**。
     *
     * <h3>为什么不用精确到米（2026-11-26 改）</h3>
     * 高德每秒推一次「段剩余/全程剩余」，如果显示到米，`Show` 的 payload 每秒都变
     * → 每秒一条 Show RPC。长途实测：20 秒里发了 18 条 Show（全部 SUCCESS，
     * 功能没问题，但纯属浪费蓝牙带宽）。
     *
     * <p>分档后 100~999 m 每 50 m 才变一次（60 km/h 下约 3 秒一次），
     * 1 km 以上每 100 m 变一次，Show 频率降到原来的 1/3 ~ 1/6。
     * 另外「350 m」比「347 m」在 4 寸屏上也更好读。</p>
     *
     * <table>
     *   <tr><td>&lt; 50 m</td><td>精确到 1 m —— 马上要转弯，需要精度</td></tr>
     *   <tr><td>&lt; 1000 m</td><td>50 m 一档</td></tr>
     *   <tr><td>≥ 1000 m</td><td>0.1 km</td></tr>
     * </table>
     *
     * <p>注意：{@code Navigation.remainDistanceMeters} 仍然保存**原始米数**，
     * ETA 等计算不受影响 —— 只有给人看的那两个字符串被取整。</p>
     */
    private static String formatDistance(long meters) {
        if (meters < 0) return "";
        if (meters < 50) return meters + " m";
        if (meters < 1000) return (Math.round(meters / 50.0) * 50) + " m";
        return String.format(java.util.Locale.US, "%.1f km", meters / 1000.0);
    }

    /** 按 type 生成一行摘要 */
    private static String summarize(int type, JSONObject d) {
        if (d == null) return null;
        switch (type) {
            case 4: {
                // 行中导航信息
                return "转向=" + maneuverName(d.optInt("curManeuverID", -1))
                        + " 段剩余=" + d.opt("segmentRemainDist") + "m"
                        + " 全程剩余=" + d.opt("routeRemainDist") + "m"
                        + " 剩余时间=" + d.opt("routeRemainTime") + "s"
                        + " 下一条路=" + d.optString("nextRouteName")
                        + " 当前路=" + d.optString("curRouteName");
            }
            case 5: {
                return "转向图标=" + maneuverName(d.optInt("maneuverID", -1));
            }
            case 7:  return "开始导航";
            case 100: return "导航结束";
            case 200: return "已到达";
            case 14: {
                return "车道数=" + d.opt("laneCount")
                        + " back=[" + d.opt("backLane") + "]"
                        + " front=[" + d.opt("frontLane") + "]";
            }
            default:
                return null;   // 其他类型不打，避免刷屏
        }
    }

    /**
     * 高德转向图标 ID → 中文。
     *
     * 编号依据高德导航 SDK v11.1.0 的 AmapIconType（与车机 ManeuverIconID 同源）：
     * 0/1 是占位（无定义、自车），2 起才是真正的转向图标。
     * 29~54 是骑行/步行专有图标，本 App 只在驾车场景用到，做了兜底但不细究。
     * 65/66 是 v11 新增的"靠左/靠右"，2012 Focus 实测 66 出现在辅路分岔处。
     *
     * 未收录的编号一律显示"行驶"，避免把"未知(66)"这种内部编号暴露给驾驶者。
     */
    public static String maneuverName(int id) {
        switch (id) {
            case 2:  return "左转";
            case 3:  return "右转";
            case 4:  return "左前方";
            case 5:  return "右前方";
            case 6:  return "左后方";
            case 7:  return "右后方";
            case 8:  return "掉头";
            case 9:  return "直行";
            case 10: return "途经点";
            case 11: return "进环岛";
            case 12: return "出环岛";
            case 13: return "服务区";
            case 14: return "收费站";
            case 15: return "到达终点";
            case 16: return "隧道出口";
            case 17: return "进环岛";
            case 18: return "出环岛";
            case 19: return "掉头";
            case 20: return "顺行";
            case 21: return "环岛左转";
            case 22: return "环岛右转";
            case 23: return "环岛直行";
            case 24: return "环岛掉头";
            case 25: return "环岛左转";
            case 26: return "环岛右转";
            case 27: return "环岛直行";
            case 28: return "环岛掉头";
            case 29: return "人行横道";
            case 30: return "过街天桥";
            case 31: return "地下通道";
            case 32: return "广场";
            case 33: return "公园";
            case 34: return "扶梯";
            case 35: return "直梯";
            case 36: return "索道";
            case 37: return "空中通道";
            case 38: return "通道";
            case 39: return "行人道路";
            case 40: return "游船路线";
            case 41: return "观光路线";
            case 42: return "滑道";
            case 43: return "阶梯";
            case 44: return "斜坡";
            case 45: return "过桥";
            case 46: return "轮渡";
            case 47: return "地铁通道";
            case 48: return "进入建筑";
            case 49: return "离开建筑";
            case 50: return "电梯换层";
            case 51: return "楼梯换层";
            case 52: return "扶梯换层";
            case 53: return "红绿灯路口";
            case 54: return "路口";
            case 65: return "靠左";
            case 66: return "靠右";
            default: return "行驶";
        }
    }

    private static JSONObject tryParse(String s) {
        try { return new JSONObject(s); } catch (Exception e) { return null; }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (out.size() + n > MAX_BYTES) throw new IllegalStateException("超过 4MiB");
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
