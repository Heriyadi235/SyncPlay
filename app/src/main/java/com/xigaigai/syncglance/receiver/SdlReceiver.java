package com.xigaigai.syncglance.receiver;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.smartdevicelink.transport.SdlBroadcastReceiver;
import com.smartdevicelink.transport.SdlRouterService;
import com.smartdevicelink.transport.TransportConstants;
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
     */
    @Override
    public Class<? extends SdlRouterService> defineLocalSdlRouterClass() {
        return SyncRouterService.class;
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
