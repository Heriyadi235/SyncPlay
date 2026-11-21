package com.xigaigai.syncglance.receiver;

import android.content.Intent;

import com.xigaigai.syncglance.util.Log2;

/**
 * SDL 的 Router Service（本地子类）。
 *
 * <h3>★ 为什么这个类必须叫 {@code SdlRouterService}</h3>
 *
 * 官方 Integration Basics 里标了 <b>Must</b>：
 *
 * <blockquote>The local extension of the {@code com.smartdevicelink.transport.SdlRouterService}
 * <b>must be named {@code SdlRouterService}</b>.</blockquote>
 *
 * 而且还有一条相关的 <b>Must</b>：
 *
 * <blockquote>Make sure this local class {@code SdlRouterService.java} is
 * <b>in the same package</b> of {@code SdlReceiver.java}.</blockquote>
 *
 * <p>本类原来叫 {@code SyncRouterService}（2026-11-21 前）。虽然实车能跑，
 * 但那是违反官方 Must 的 —— 已按官方要求改名。</p>
 *
 * <h3>⚠ 不要 import 基类</h3>
 *
 * 官方还特别提醒：
 *
 * <blockquote>Do not include an import for {@code com.smartdevicelink.transport.SdlRouterService}.
 * Otherwise, we will get an error for 'SdlRouterService' is already defined in this
 * compilation unit.</blockquote>
 *
 * <p>所以下面 extends 用的是<b>全限定名</b>，文件顶部没有那个 import。</p>
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
 * 库里的 {@code com.smartdevicelink.transport.SdlRouterService} 是通用实现，
 * 每个集成方必须提供自己的子类并在 Manifest 里声明
 * {@code com.smartdevicelink.router.service} 这个 action。
 * 库内部会枚举设备上所有声明了这个 action 的 service 来挑一个跑，
 * 没有声明的话车机侧根本发现不了本应用。
 *
 * <p>还有一条 <b>Must</b>：本 service 必须跑在
 * {@code com.smartdevicelink.router} 独立进程里，否则启动后自己退出。
 * 官方 FAQ（Multiple Processes）解释了目的：<b>Router 与宿主应用互不拖垮</b> ——
 * 所以手机上 App 崩了、车机通知还显示 connected 是**正常现象**。</p>
 *
 * <h3>本类唯一的额外职责：上报自己的生死</h3>
 * 通知栏要区分「未连接（等待车机）」和「未连接（Router 服务运行中）」，
 * 但 SDL 5.8 的 {@code SdlBroadcastReceiver} 里没有
 * {@code isRouterServiceRunning()} 这种 API，库内部状态也不可靠。
 *
 * <p>本类跑在独立进程，所以用**跨进程广播**告诉 {@code SdlService}
 * 自己是起来了还是退了。</p>
 *
 * <p>已知限制：进程被系统直接杀掉时 {@code onDestroy()} 不一定会回调，
 * 那之后通知会停在「Router 服务运行中」。不致命，下次 Router 起来会自愈。</p>
 */
public class SdlRouterService extends com.smartdevicelink.transport.SdlRouterService {

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
