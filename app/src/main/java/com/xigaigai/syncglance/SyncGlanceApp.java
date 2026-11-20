package com.xigaigai.syncglance;

import android.app.Application;
import android.os.Build;

import com.xigaigai.syncglance.core.SyncCore;
import com.xigaigai.syncglance.receiver.SdlReceiver;
import com.xigaigai.syncglance.util.Log2;

/**
 * 应用入口。
 *
 * <h3>两件事</h3>
 * <ol>
 *   <li>把 {@link SyncCore} 尽早初始化 —— 这样即使 SDL 服务还没起来
 *       （甚至因为没连车机而退出），数据采集与车机屏幕预览也能独立工作。
 *       这是把预览器从连接层解耦的前提。</li>
 *   <li><b>官方推荐的「补连接」</b>：启动时主动问一句「现在有没有已连接的
 *       RouterService」。场景是「上车时应用没起来 → 车机连不上放弃 →
 *       用户后来才打开应用」，这一问能让 Router 重新挂起来监听 RFCOMM，
 *       把错过的连接补上。</li>
 * </ol>
 *
 * <h3>为什么补连接放在这里而不是 SdlService 里</h3>
 * 之前挂在 {@code SdlService.onStartCommand()} 里，靠一个实例字段标记
 * 「只做一次」。但服务现在是「断开就 stopSelf、靠广播重新拉起」，
 * 每次重建都是新实例 → 标记重置 → 实际语义变成「每个实例一次」，
 * 和注释写的「每个进程一次」不符。
 *
 * 官方推荐的位置本来就是**应用启动时**，所以挪到 Application 里，
 * 用进程级的 {@code static} 保证每个进程真的只做一次。
 *
 * <h3>⚠ 必须限定主进程</h3>
 * {@code SyncRouterService} 跑在独立进程
 * （{@code com.smartdevicelink.router}），它<b>也会</b>触发本 Application 的
 * {@code onCreate}。在那个进程里调 {@code queryForConnectedService}
 * 等于「Router 问自己有没有连上」，没有意义还可能干扰时序。
 * 所以这里用进程名过滤，只在主进程做补连接。
 *
 * 将来把预览器抽成独立库时，宿主 App 只要在自己的 Application 里调一次
 * {@code SyncCore.get().init(this)} 即可。
 */
public class SyncGlanceApp extends Application {

    private static final String TAG = "Boot";

    @Override
    public void onCreate() {
        super.onCreate();

        /*
         * ★ 日志能力两个进程都要（Router 进程也要打日志），
         *   但数据采集层只要主进程要。
         *
         *   RouterService 跑在独立进程（com.smartdevicelink.router），
         *   它如果也 init SyncCore，会连带做这些完全没用的事：
         *     - 初始化 StateRenderer / LyricManager（主进程才需要）
         *     - 绑定高德 NaviService（两个进程抢同一个绑定）
         *     - 注册音乐通知监听回调
         *   既浪费内存，也可能干扰主进程的数据采集。
         */
        Log2.init(this);

        if (!isMainProcess()) return;

        SyncCore.get().init(this);

        Log2.i(TAG, "应用启动，补一次「现在有没有已连接的车机」探测");
        if (SdlReceiver.pokeIfBluetoothConnected(this)) return;

        /*
         * ★ 首次安装后的第一次启动会失败（实测：第二次启动就成功）。
         *
         *   Application.onCreate 跑得极早，此时蓝牙权限 / 库内部初始化可能还没就绪，
         *   queryForConnectedService 会抛异常（message 往往为空）。
         *   所以失败时延迟 2 秒再补一次 —— 那时进程已经完全起来了。
         *
         *   仍然失败也没关系：ACL_CONNECTED 广播那条主链路会兜住。
         */
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            Log2.i(TAG, "补连接首次失败，2 秒后重试一次");
            SdlReceiver.pokeIfBluetoothConnected(this);
        }, 2000);
    }

    /**
     * 当前进程名是否就是包名（即主进程）。
     *
     * {@code Application.getProcessName()} 是 API 28 才有的，
     * 低版本退回读 {@code /proc/self/cmdline}。
     */
    private boolean isMainProcess() {
        String proc = currentProcessName();
        // 拿不到进程名时按主进程处理：补连接本身是安全的，多问一次无害
        return proc.isEmpty() || getPackageName().equals(proc);
    }

    private static String currentProcessName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return Application.getProcessName();
            } catch (Exception ignored) {
                // 落到下面读 /proc
            }
        }
        try {
            byte[] buf = new byte[128];
            try (java.io.FileInputStream in =
                         new java.io.FileInputStream("/proc/self/cmdline")) {
                int n = in.read(buf);
                if (n <= 0) return "";
                int end = 0;
                while (end < n && buf[end] != 0) end++;   // cmdline 用 \0 分隔
                return new String(buf, 0, end, "UTF-8").trim();
            }
        } catch (Exception e) {
            return "";
        }
    }
}
