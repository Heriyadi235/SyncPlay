package com.xigaigai.syncglance.receiver;

import android.content.Intent;

import com.xigaigai.syncglance.util.Log2;

/**
 * SDL 的 Router Service。
 *
 * <h3>它是什么</h3>
 * SDL 从 4.4 开始把蓝牙传输拆成两层：
 * <ul>
 *   <li><b>RouterService</b>（这个类）—— 常驻进程，负责监听 RFCOMM 通道、
 *       注册 SDP 记录、让车机发现「这台手机上有 SDL 应用」，
 *       并把同一个传输连接多路复用给多个 SDL 应用。</li>
 *   <li><b>SdlService</b>（我们的前台服务）—— 真正跟车机跑 RAI/Show 的那层，
 *       通过 RouterService 拿到的连接跟车机对话。</li>
 * </ul>
 *
 * <h3>为什么必须自己继承一个空类</h3>
 * SDL 库里的 {@code com.smartdevicelink.transport.SdlRouterService} 是通用实现，
 * 每个集成方必须提供一个自己的子类并在 Manifest 里声明
 * {@code com.smartdevicelink.router.service} 这个 action。
 * 库内部会枚举设备上所有声明了这个 action 的 service 来挑一个跑，
 * 没有声明的话车机侧根本发现不了本应用。
 *
 * <h3>连接时序（官方文档 Establishing a Transport）</h3>
 * 蓝牙 A2DP + HFP 连上之后，车机只有很短的窗口去连 SDL 的 RFCOMM 通道：
 * <ul>
 *   <li>从没见过这台车机 → RouterService 前台保持约 10 秒</li>
 *   <li>见过但没成功连过 → 立即退到后台</li>
 *   <li>成功连过 → 前台保持约 20 秒</li>
 * </ul>
 * 窗口内没连上，RouterService 退到后台等系统回收，而车机不会再回头连 ——
 * 这正是「App 没启动时车机卡在连接中」的机制来源。
 *
 * <h3>本类唯一的额外职责：上报自己的生死</h3>
 * 通知栏要区分「未连接（等待车机）」和「未连接（Router 服务运行中）」，
 * 但 SDL 5.8 的 {@code SdlBroadcastReceiver} 里没有
 * {@code isRouterServiceRunning()} 这种 API，库内部状态也不可靠。
 *
 * 本类跑在独立进程（{@code com.smartdevicelink.router}），所以用**跨进程广播**
 * 告诉 {@code SdlService} 自己是起来了还是退了。
 *
 * 已知限制：进程被系统直接杀掉时 {@code onDestroy()} 不一定会回调，
 * 那之后通知会停在「Router 服务运行中」。不致命，下次 Router 起来会自愈。
 */
public class SyncRouterService extends com.smartdevicelink.transport.SdlRouterService {

    private static final String TAG = "Router";

    /** RouterService 起来了（正在监听 RFCOMM） */
    public static final String ACTION_ROUTER_ALIVE =
            "com.xigaigai.syncglance.action.ROUTER_ALIVE";

    /** RouterService 退了（不再监听，车机连上来也没人接） */
    public static final String ACTION_ROUTER_DEAD =
            "com.xigaigai.syncglance.action.ROUTER_DEAD";

    @Override
    public void onCreate() {
        super.onCreate();
        report(true);
    }

    /**
     * 库会在不同时机反复 start 这个 Service（车机发现、应用冷启动等），
     * 每次 start 都重报一次"我在"，这样即使主进程晚起来也能收到。
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int r = super.onStartCommand(intent, flags, startId);
        report(true);
        return r;
    }

    @Override
    public void onDestroy() {
        report(false);
        super.onDestroy();
    }

    private void report(boolean alive) {
        try {
            Intent i = new Intent(alive ? ACTION_ROUTER_ALIVE : ACTION_ROUTER_DEAD);
            // 显式指定本应用，避免广播外泄（也绕开隐式广播限制）
            i.setPackage(getPackageName());
            sendBroadcast(i);
            Log2.i(TAG, "RouterService " + (alive ? "上线" : "下线") + "，已广播给主进程");
        } catch (Exception ignored) {
            // 进程正在退出时 sendBroadcast 可能失败，不影响功能
        }
    }
}
