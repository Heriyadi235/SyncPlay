package com.xigaigai.syncglance.receiver;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.smartdevicelink.transport.SdlBroadcastReceiver;
import com.smartdevicelink.transport.TransportConstants;
import com.smartdevicelink.util.AndroidTools;
import com.xigaigai.syncglance.service.SdlService;
import com.xigaigai.syncglance.util.Log2;

/**
 * SDL 官方生命周期入口。
 *
 * <h3>为什么不能只用自己的 BootReceiver</h3>
 * SDL 的连接是<b>车机主动发起</b>的，本应用能做的只有「把 RouterService 挂起来等着」。
 * 官方把这件事封装进 {@link SdlBroadcastReceiver}，它会监听三个关键事件：
 *
 * <ol>
 *   <li>{@code ACL_CONNECTED} —— 蓝牙连上，唤起 RouterService 开始监听 RFCOMM</li>
 *   <li>{@code sdl.router.startservice} —— RouterService 反过来唤醒应用进程</li>
 *   <li>{@code USB_ACCESSORY_ATTACHED} —— USB 传输（本车用不到）</li>
 * </ol>
 *
 * 自己写 receiver 只监听了第 1 个，漏掉了第 2 个 —— 而那正是
 * 「应用进程不在、RouterService 收到车机连接后需要把应用叫起来」的通道。
 *
 * <h3>onSdlEnabled 什么时候被调用</h3>
 * 只有当 RouterService <b>已经跟车机建立了连接</b>，并且信任列表校验通过时才会回调。
 * 所以它里面可以放心地直接去建 SDL proxy —— 此时传输层已经通了。
 *
 * <h3>应用后启动怎么办</h3>
 * 官方给了 {@link SdlBroadcastReceiver#queryForConnectedService(Context)}：
 * 主动去问「现在有没有已连接的 RouterService」。App 冷启动时调一次，
 * 如果上车时应用没起来、后来才打开，就能补上这一问。
 * 注意官方文档强调：<b>车机端还得重新做一次 SDP 查询</b>才知道多了这条记录，
 * 这一步不受应用控制。
 */
public class SdlReceiver extends SdlBroadcastReceiver {

    private static final String TAG = "SdlReceiver";

    /**
     * 告诉 SDL 库：本应用的 RouterService 是哪个类。
     *
     * 必须返回我们自己声明的子类，不能返回库里的基类 ——
     * 库会在运行时检查并在 debug 模式下直接抛异常。
     *
     * <p>⚠ 返回类型这里**故意写全限定名**、并且文件顶部**不 import** 库的
     * {@code SdlRouterService}：我们自己的子类跟它同名（官方 <b>Must</b>：
     * 本地类必须叫 {@code SdlRouterService}），而 Java 的**单类型 import 会遮蔽
     * 同包同名类** —— 一旦 import 了库那个，`SdlRouterService.class` 就会解析成
     * 基类，运行时直接被库判非法。官方文档也专门警告过这一点。</p>
     */
    @Override
    public Class<? extends com.smartdevicelink.transport.SdlRouterService> defineLocalSdlRouterClass() {
        return SdlRouterService.class;
    }

    /**
     * 车机已经连上 RouterService，把 SDL 服务拉起来建 proxy。
     *
     * <h3>为什么两条路都要走（不再二选一）</h3>
     *
     * 官方 Integration Basics 给的写法是「有 PendingIntent 就发它，否则自己 start」。
     * 我们照抄后出过事（2026-11-21 实车日志）：
     *
     * <pre>
     *   12:49:37.867  [SdlReceiver] ★ onSdlEnabled —— 车机已连通 RouterService
     *   12:49:37.873  [SdlReceiver] 已通过 PendingIntent 启动 SdlService
     *   （之后没有任何 [SYNC] SyncPlay 服务创建）
     *   ★ 上面的 [SYNC] 是当时的日志 TAG，2026-11-21 起已改名 [SyncPlay]
     *     （SYNC 是车机的设备名，容易看混）。
     *   12:50:11.415  [Boot] ACL_DISCONNECTED      ← 车机等不及，超时
     * </pre>
     *
     * {@code pendingIntent.send()} 返回成功（没抛 CanceledException），但它启动的
     * <b>不是我们的 SdlService</b>（onCreate 压根没执行），而代码又提前 return 了，
     * 把可靠的那条路跳过 —— 车机连上 Router 后没人完成 RAI，最后超时。
     *
     * <p>所以现在改成<b>两条都走</b>：自己 start 保证能起来，PendingIntent 作为
     * 官方推荐的补充路径（用于系统对后台启动前台服务有限制的场景）。
     * Service 重复启动是幂等的：{@code onStartCommand} 里 needRebuild() 会判断
     * 「已连接就复用」，不会重复建 SdlManager。</p>
     */
    @Override
    public void onSdlEnabled(Context context, Intent intent) {
        Log2.i(TAG, "★ onSdlEnabled —— 车机已连通 RouterService，启动 SdlService");

        /*
         * ★ 先确保 Router 在前台窗口（2026-11-22 加，配合受控重试）。
         *
         *   实车发现：onSdlEnabled 触发 ≠ Router 已经 ready ——
         *   它只是收到 sdl.router.startservice 广播，而广播到达时 Router 的
         *   SDP/RFCOMM 可能还没就绪。5.x 的 TransportManager 绑不上就同步 onDestroy，
         *   于是出现「onSdlEnabled → 服务创建 → 5ms 秒退」。
         *
         *   这里先 wakeRouter() 让 Router 重新进前台、重新挂监听，
         *   给 SdlManager 一个更高概率能绑上的状态。这是辅助手段，
         *   真正的兜底在 SdlService 的受控重试（秒失败时延迟重建）。
         */
        wakeRouter(context);

        /*
         * ★ 官方 Must（Android 14 / API 34 及以上）：先检查有没有
         *   connectedDevice 类型的前台服务权限，没有就**直接放弃启动**。
         *
         *   官方示例：
         *     if (!AndroidTools.hasForegroundServiceTypePermission(context)) {
         *         DebugTool.logInfo(TAG, "Permission missing for ForegroundServiceType ...");
         *         return;
         *     }
         *
         *   为什么要这条：API 34 起，启动带 connectedDevice 类型的 FGS 必须有
         *   「已连接的设备」或对应权限，否则 startForegroundService 会抛
         *   ForegroundServiceStartNotAllowedException。以前我们没检查，
         *   异常被 try/catch 吞掉，服务起不来，车机侧表现为「正在连接」直到超时。
         *
         *   本机是 Android 15（API 35），早就在这条规则的适用范围内。
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                if (!AndroidTools.hasForegroundServiceTypePermission(context)) {
                    Log2.w(TAG, "缺少 connectedDevice 前台服务权限，按官方要求放弃启动 SdlService");
                    return;
                }
            } catch (Throwable t) {
                // 检查本身出问题不能挡住启动，记一笔继续
                Log2.w(TAG, "检查前台服务类型权限时异常: "
                        + t.getClass().getSimpleName() + " " + t.getMessage());
            }
        }

        // ① 可靠路径：自己拉起来
        startSelf(context, intent);

        // ② 官方 Android 12+ 路径：再发一次 Router 递过来的 PendingIntent（尽力而为）
        startViaPendingIntent(context, intent);
    }

    /** 自己 startForegroundService / startService */
    private void startSelf(Context context, Intent intent) {
        try {
            Intent i = new Intent(context, SdlService.class);
            if (intent != null) {
                // 把 SDL 给的附加信息透传下去（目前用不到，但保持官方推荐写法）
                i.putExtras(intent);
                i.setAction(intent.getAction());
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
            Log2.i(TAG, "已直接启动 SdlService（startForegroundService）");
        } catch (Exception e) {
            Log2.e(TAG, "onSdlEnabled 直接启动 SdlService 失败", e);
        }
    }

    /**
     * 手动唤醒 RouterService，重新打开「等待车机连接」的前台窗口。
     *
     * <h3>为什么需要这个</h3>
     * 本车机（SYNC 1.1）**不会自动发现 App**，必须用户在车机上点「查找移动应用」。
     * 而 RouterService 的前台窗口只有 10~20 秒（那条带倒计时的 SDL 通知），
     * 错过就退到后台，之后车机再查找也找不到。
     *
     * <p>于是加了这个「手动开窗」：用户点一下，Router 重新进前台、
     * 倒计时重新开始，这时候在车机上点「查找移动应用」就能连上。</p>
     *
     * <h3>实现依据（SdlRouterService 源码）</h3>
     * {@code onStartCommand} 里：
     * <pre>
     *   if (intent.getBooleanExtra(FOREGROUND_EXTRA, false)) {
     *       if (!isPrimaryTransportConnected()) {
     *           enterForeground("Waiting for connection...", timeout, false);
     *           resetForegroundTimeOut(timeout);
     *       } else {
     *           enterForeground(createConnectedNotificationText(), 0, true);
     *       }
     *   }
     * </pre>
     * 所以只要带 {@link TransportConstants#FOREGROUND_EXTRA} = true 启动它即可。
     *
     * <p>用 {@code startForegroundService} 是安全的：这条路径里库自己会调
     * {@code enterForeground}，不会触发「5 秒没 startForeground」的 ANR。</p>
     *
     * @return true = 请求已发出
     */
    public static boolean wakeRouter(Context context) {
        if (context == null) return false;
        try {
            Intent i = new Intent(context, SdlRouterService.class);
            i.setAction(TransportConstants.ROUTER_SERVICE_ACTION);
            i.putExtra(TransportConstants.FOREGROUND_EXTRA, true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
            Log2.i(TAG, "已请求唤醒 RouterService（重新打开等待窗口）");
            return true;
        } catch (Exception e) {
            Log2.w(TAG, "唤醒 RouterService 失败: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return false;
        }
    }

    /**
     * 尝试走官方 Android 12+ 的 PendingIntent 启动方式。
     *
     * @return true = 已经通过 PendingIntent 启动，调用方不要再自己 start
     */
    private boolean startViaPendingIntent(Context context, Intent intent) {
        if (intent == null) return false;
        Object extra;
        try {
            extra = intent.getParcelableExtra(TransportConstants.PENDING_INTENT_EXTRA);
        } catch (Exception e) {
            return false;
        }
        if (!(extra instanceof PendingIntent)) return false;

        try {
            ((PendingIntent) extra).send(context, 0, intent);
            Log2.i(TAG, "已通过 PendingIntent 启动 SdlService（Android 12+ 官方推荐方式）");
            return true;
        } catch (PendingIntent.CanceledException e) {
            Log2.w(TAG, "PendingIntent 已失效，退回 startForegroundService");
            return false;
        } catch (Exception e) {
            Log2.w(TAG, "PendingIntent.send 失败（" + e.getClass().getSimpleName()
                    + "），退回 startForegroundService");
            return false;
        }
    }

    /**
     * 应用冷启动时补一次「现在连上了吗」。
     *
     * 场景：上车时应用没起来 → 车机连不上放弃 → 用户后来才打开应用。
     * 这时 RouterService 可能已经退到后台，主动问一句能让它重新挂起来。
     *
     * ★ 官方推荐写法，但要注意 debug 包下库会先跑 IntegrationValidator，
     *   配置不符会抛 RuntimeException，所以这里必须吞掉异常，
     *   否则用户一开应用就崩。
     *
     * @return true = 调用成功；false = 抛异常了（调用方可以稍后重试）
     */
    public static boolean pokeIfBluetoothConnected(Context context) {
        if (context == null) return false;
        try {
            queryForConnectedService(context);
            Log2.i(TAG, "已调用 queryForConnectedService（探测已连接的车机）");
            return true;
        } catch (Exception e) {
            // 打上类型名：这条常常 message 为空，只打 getMessage() 查不出东西
            Log2.w(TAG, "queryForConnectedService 失败: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return false;
        }
    }
}
