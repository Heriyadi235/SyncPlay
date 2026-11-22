package com.xigaigai.syncglance.display;

/**
 * 车机连接状态 → 通知栏正文那一句。
 *
 * <h3>文案风格（2026-11-22 定稿）</h3>
 * 对齐 SDL 官方截图里的写法：
 *
 * <pre>
 *   Connected through SDL (FULL: PLY/NAV)     ← 已连，括号里是「HMI 等级: 车机界面内容」
 *   Connecting through SDL (Registering)      ← 连接中，括号里是卡在哪一步
 *   Waiting for connection (Router listening) ← 未连，括号里是当前状态
 *   Connection closed                         ← 连过又断了
 * </pre>
 *
 * <h3>为什么只放状态枚举，不放歌名</h3>
 * 歌名/歌词/坐标每秒都可能变，挂到通知上就会不停刷新（费电又吵）。
 * 这里只有「HMI 等级」（4 种）× 「界面内容」（4 种）加几个连接阶段，
 * 变化频率极低。
 *
 * <h3>参数从哪来</h3>
 * <ul>
 *   <li>{@code hmi} / {@code content} —— {@code SyncLifecycle} 与 {@code StateRenderer}</li>
 *   <li>{@code routerAlive} —— {@code SdlRouterService} 跨进程广播上报</li>
 *   <li>{@code closedOnce} —— 本次进程起来后是否「连上过又断了」</li>
 *   <li>{@code managerStarted} —— {@code SdlManagerListener.onStart} 是否回调
 *       （= RAI 注册成功）</li>
 *   <li>{@code retryPending} / {@code retryCount} / {@code retryMax} ——
 *       秒失败后的受控重试</li>
 * </ul>
 *
 * 纯逻辑，不碰 Android —— 可单测。
 */
public final class ConnectionStatus {

    private ConnectionStatus() { }

    /**
     * @param hmi          生命周期状态；null 视作 {@code DISCONNECTED}
     * @param content      FULL 时车机上显示的内容，可为 null
     * @param routerAlive  RouterService 是否在跑
     * @param closedOnce   本次启动后是否「连上过又断开了」
     * @param managerStarted RAI 是否注册成功（{@code SdlManagerListener.onStart} 已回调）
     * @param retryPending 是否正在等「秒失败」的延迟重试
     * @param retryCount   已重试次数（第几次，从 1 开始）
     * @param retryMax     最多重试次数
     * @return 通知正文，例如「Connected through SDL (FULL: PLY)」
     */
    public static String text(SyncLifecycle.State hmi,
                              StateRenderer.State content,
                              boolean routerAlive,
                              boolean closedOnce,
                              boolean managerStarted,
                              boolean retryPending,
                              int retryCount,
                              int retryMax) {

        /*
         * ① 秒失败重试等待中 —— **必须最先判**。
         *
         *   秒失败时 handleConnectionClosed() 已经调用 lifecycle.onProxyClosed()，
         *   所以此刻 hmi 是 DISCONNECTED。不特判的话会错显示成
         *   「Waiting for connection (...)」，而实际上我们正在主动重连。
         */
        if (retryPending) {
            return "Connecting through SDL (Retrying " + retryCount + "/" + retryMax + ")";
        }

        SyncLifecycle.State s = (hmi == null) ? SyncLifecycle.State.DISCONNECTED : hmi;

        switch (s) {
            case CONNECTING:
                /*
                 * proxy 已经建好，但车机还没告诉我们该处于哪个 HMI 等级。
                 * 三种卡点，按「先远后近」排列：
                 */
                if (managerStarted) {
                    // RAI 成功 → 传输和注册都通了，只差车机下发 OnHMIStatus
                    return "Connecting through SDL (Waiting for head unit)";
                }
                // RAI 还没成功：Router 起来了说明正在绑传输/注册，没起来就是在等 Router
                return routerAlive
                        ? "Connecting through SDL (Registering)"
                        : "Connecting through SDL (Waiting for Router)";

            case NONE:
            case BACKGROUND:
            case LIMITED:
            case FULL:
                // 已连接：括号里是「HMI 等级: 车机界面内容」
                return "Connected through SDL (" + s.name() + ": " + contentLabel(content) + ")";

            case DISCONNECTED:
            default:
                if (closedOnce) {
                    // 连上过又断了（熄火 / 蓝牙断）。服务随即 stopSelf，实际几乎看不到
                    return "Connection closed";
                }
                return routerAlive
                        // Router 在注册 SDP、监听 RFCOMM，等车机来连
                        ? "Waiting for connection (Router listening)"
                        // Router 都没起来 ⇒ 蓝牙链路本身没通
                        : "Waiting for connection (Bluetooth not connected)";
        }
    }

    /**
     * 车机界面上正在显示什么（4 种）。
     *
     * <p>判定见 {@code StateRenderer#currentState()}：导航用 {@code isFresh()}
     * 而不是 {@code hasData()} —— 高德闪退/导航结束后数据会「过期」但不清空。</p>
     */
    private static String contentLabel(StateRenderer.State content) {
        if (content == null) return "IDLE";
        switch (content) {
            case C_MUSIC_ONLY: return "PLY";
            case B_NAV_ONLY:   return "NAV";
            case D_BOTH:       return "PLY/NAV";
            case A_EMPTY:
            default:           return "IDLE";
        }
    }
}
