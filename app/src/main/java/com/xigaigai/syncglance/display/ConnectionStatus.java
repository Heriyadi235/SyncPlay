package com.xigaigai.syncglance.display;

/**
 * 车机连接状态 → 通知栏里的那一句话。
 *
 * <h3>为什么要单独一个类</h3>
 * 通知文案以前散在 {@code SdlService.notificationText()} 里，还顺手把歌名、导航指令
 * 拼了进去。那两个东西每秒都可能变，一旦挂到通知上，通知栏就会不停刷新 ——
 * 既费电又吵。所以这里只保留**状态枚举**：
 *
 * <pre>
 *   未连接（蓝牙未连接）          RouterService 没在跑 —— 根因就是车机蓝牙没连上
 *   未连接（Router 服务运行中）    Router 在监听 RFCOMM，等车机来连
 *   未连接（连接关闭）            这次连上过，现在断了
 *   已连接（未选中/后台/受限）    HMI 不是 FULL，车机上其实什么都没显示
 *   已连接（正在注册）            传输层通了，车机还没回第一条 OnHMIStatus
 *   已连接（空闲/歌曲/导航/歌曲·导航）   FULL，且能说清正在显示什么
 * </pre>
 *
 * 纯逻辑，不碰 Android —— 可以单测。调用方见 {@code SdlService#notificationText()}。
 */
public final class ConnectionStatus {

    private ConnectionStatus() { }

    /**
     * @param hmi         生命周期状态；null 视作 {@code DISCONNECTED}
     * @param routerAlive RouterService 是否在跑（由 {@code SdlRouterService} 广播上报）
     * @param closedOnce  本次启动后是否"连上过又断开了"
     * @param content     FULL 时车机上显示的内容，可为 null
     * @return 通知正文，例如「未连接（蓝牙未连接）」「已连接（歌曲）」
     */
    public static String text(SyncLifecycle.State hmi,
                              boolean routerAlive,
                              boolean closedOnce,
                              StateRenderer.State content) {
        SyncLifecycle.State s = (hmi == null) ? SyncLifecycle.State.DISCONNECTED : hmi;

        switch (s) {
            case CONNECTING:
                // 传输层已经通了，只是车机还没回第一条 OnHMIStatus
                return "已连接（正在注册）";
            case NONE:
                return "已连接（未选中）";
            case BACKGROUND:
                return "已连接（后台）";
            case LIMITED:
                return "已连接（受限）";
            case FULL:
                return "已连接（" + contentLabel(content) + "）";
            case DISCONNECTED:
            default:
                /*
                 * 三种"未连接"的区分：
                 *   连过又断   → 连接关闭（最可能是车机熄火 / 蓝牙断开）
                 *   Router 在跑 → 正在监听，等车机主动连
                 *   Router 没跑 → 蓝牙压根没连上（Router 由 ACL_CONNECTED 触发，
                 *                没有蓝牙链路就不会起来）。这是**常态**，不是故障。
                 */
                if (closedOnce) return "未连接（连接关闭）";
                return routerAlive ? "未连接（Router 服务运行中）" : "未连接（蓝牙未连接）";
        }
    }

    /** FULL 时车机上显示的四种内容 */
    private static String contentLabel(StateRenderer.State content) {
        if (content == null) return "空闲";
        switch (content) {
            case C_MUSIC_ONLY: return "歌曲";
            case B_NAV_ONLY:   return "导航";
            case D_BOTH:       return "歌曲·导航";
            case A_EMPTY:
            default:           return "空闲";
        }
    }
}
