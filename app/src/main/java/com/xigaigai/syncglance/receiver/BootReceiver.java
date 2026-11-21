package com.xigaigai.syncglance.receiver;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.xigaigai.syncglance.util.Log2;

/**
 * 蓝牙/开机事件 → <b>只记日志</b>（不建连接，也不调探测 API）。
 *
 * <h3>它不负责连接，也不负责探测（2026-11-21 修正）</h3>
 *
 * 这个类两度踩雷，两样都被拿掉了：
 *
 * <ol>
 *   <li><b>不启动服务</b>。原来这里 {@code startForegroundService(SdlService)}，
 *       那是 4.12.2 的做法。5.x 里它等于立刻建一条 {@code SdlManager} 连接，
 *       而库要 ~110ms 才把 Router 叫起来 → 5~11ms 自毁 →
 *       车机卡「正在连接」2 分钟（修复提交 `fcbef0a`）。</li>
 *   <li><b>也不调 {@code queryForConnectedService}</b>。
 *       广播的 context 是 {@code ReceiverRestrictedContext}，库会拿它去
 *       {@code bindService} → {@code ReceiverCallNotAllowedException} →
 *       **进程闪退**（2026-11-21 18:31 / 18:33 实车各一次）。</li>
 * </ol>
 *
 * <p>探测改由 {@link com.xigaigai.syncglance.SyncGlanceApp#onCreate} 和
 * {@code MainActivity.onCreate} 承担 —— 那里是 Application / Activity 的
 * context，调同一个 API 是安全的。连接本身则由
 * {@link SdlReceiver#onSdlEnabled} 那一条官方链路负责。</p>
 *
 * <p>所以本类现在的唯一价值：</p>
 * <ul>
 *   <li>蓝牙设备白名单判断（只认真车机，忽略耳机音箱）</li>
 *   <li>把「启动来源」写进日志，排查时能看出来是谁叫醒的应用</li>
 * </ul>
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "Boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        Log2.init(context);
        String action = intent.getAction();
        Log2.i(TAG, "收到: " + action);

        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
                /*
                 * 只记日志，不做任何连接动作。
                 *
                 * 进程是被这条广播拉起来的 → SyncGlanceApp.onCreate 必然先跑，
                 * 它已经用 Application context 探测过了。
                 * 这里再探测一次不但多余，还**会闪退** —— 原因见下面 ACL_CONNECTED 的注释。
                 */
                Log2.i(TAG, "【启动来源】系统开机");
                break;

            case "android.bluetooth.device.action.ACL_CONNECTED": {
                // ★ 只对目标车机 / 测试设备响应，避免蓝牙耳机、音箱误触发
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String name = deviceName(d);
                String addr = d == null ? null : d.getAddress();

                if (!isSyncHeadUnit(context, name, addr)) {
                    Log2.i(TAG, "蓝牙连上但不在白名单（" + name + " / " + addr + "），忽略");
                    return;
                }

                Log2.i(TAG, "【启动来源】蓝牙 ACL_CONNECTED ← " + name + " / " + addr);
                dumpKnownDevices(context);
                /*
                 * ★ 这里什么都不做，只记日志和白名单。
                 *
                 * <h3>① 绝不 startForegroundService(SdlService)</h3>
                 * 那是 4.12.2 的做法。在 5.x 上它的实际效果不是「保活」，
                 * 而是立刻 `onCreate → startProxy() → SdlManager.start()`。
                 * 而此刻库还没把 Router 叫起来（库走异步约 110ms，我们只要 5~20ms），
                 * 于是必然在 Router 还没 listen 时去 bind → 5~11ms 自毁 →
                 * 车机卡「正在连接」**2 分钟**。
                 * （2026-11-21 四轮实车，修复提交 `fcbef0a`）
                 *
                 * <h3>② 也绝不在这里调 queryForConnectedService</h3>
                 * BroadcastReceiver 拿到的 `context` 是
                 * **`ReceiverRestrictedContext`**，而库内部会把它一路传到
                 * `RouterServiceValidator.createTrustedListRequest`
                 * → `SdlRouterStatusProvider.checkIsConnected` → **`bindService`**。
                 * Android 明令禁止在广播里 bindService，于是：
                 *
                 * <pre>
                 *   ReceiverCallNotAllowedException:
                 *     BroadcastReceiver components are not allowed to bind to services
                 * </pre>
                 *
                 * **整个进程闪退**。2026-11-21 18:31:45 和 18:33:12 各崩一次，
                 * 都是「车机已连上 Router 之后再次上车」的场景
                 * （Router 在跑 → ServiceFinder 能找到 → 才会走 bindService 校验）。
                 * 用 Application / Activity 的 context 调同一个 API 不会崩 ——
                 * 差别只是 context 的包装类型。
                 *
                 * <h3>那连接靠谁</h3>
                 * 全靠 SDL 官方链路：
                 * <pre>
                 *   库的 SdlBroadcastReceiver.onReceive(ACL_CONNECTED)
                 *     → wakeUpRouterService() 唤醒 Router
                 *     → 车机连上 Router → onSdlEnabled()
                 *     → 应用启动 SdlService
                 * </pre>
                 * 进程如果本来是死的、被这条广播拉起来，
                 * {@link com.xigaigai.syncglance.SyncGlanceApp#onCreate} 也会补一次探测
                 * （Application context，安全）。
                 */
                break;
            }

            case "android.bluetooth.adapter.action.STATE_CHANGED": {
                // 只在蓝牙打开时拉起，关闭时不做动作
                int state = intent.getIntExtra(
                        android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1);
                if (state == android.bluetooth.BluetoothAdapter.STATE_ON) {
                    // 只记日志：蓝牙刚打开时车机还没连，探测没有意义
                    Log2.i(TAG, "【启动来源】蓝牙已开启");
                }
                break;
            }

            case "android.bluetooth.device.action.ACL_DISCONNECTED": {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String name = deviceName(d);
                String addr = d == null ? null : d.getAddress();
                if (isSyncHeadUnit(context, name, addr)) {
                    Log2.i(TAG, "SYNC 车机已断开（" + name + "）");
                } else {
                    Log2.i(TAG, "蓝牙断开 " + (name.isEmpty() ? "未知" : name));
                }
                /*
                 * 断开时什么都不做。
                 *
                 * SdlService 自己会在 SdlManager.onDestroy 回调里收尾并 stopSelf
                 * （官方语义），不需要这里操心；也不要在断开时去「保活」，
                 * 那会和官方生命周期打架。
                 */
                break;
            }

            case "android.intent.action.MY_PACKAGE_REPLACED": {
                // 只监听自己的升级（不需要 data scheme，也不会被别的应用打扰）
                // 只记日志：进程会被这条广播拉起，SyncGlanceApp.onCreate 会补一次探测
                Log2.i(TAG, "【启动来源】本应用安装/升级完成");
                break;
            }

            default:
                break;
        }
    }

    /** 安全获取蓝牙设备名（可能因权限或未缓存返回 null） */
    private String deviceName(BluetoothDevice d) {
        if (d == null) return "";
        try {
            String n = d.getName();
            return n == null ? "" : n;
        } catch (SecurityException e) {
            // 没有 BLUETOOTH_CONNECT 权限
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 目标车机的名字关键字。
     *
     * 实车上报的设备名是「SYNC 」（末尾带空格），用 contains 判断即可覆盖
     * SYNC / SYNC 3 / Ford SYNC 这类写法。
     *
     * ★ 2026-11 收紧：以前名字含 FORD 也算，但 FORD 只会带来误判
     *   （某些福特配件也叫 FORD）。现在只认 SYNC。
     */
    private static final String HEAD_UNIT_KEYWORD = "SYNC";

    /**
     * 曾经这里有一份「测试设备白名单」，硬编码了开发机的 Windows 机器名，
     * 用于在电脑上跑台架测试时也能被 ACL_CONNECTED 拉起。
     *
     * <b>已移除</b>：那是本机信息，不该出现在代码仓库里。
     * 台架测试不受影响 —— 手动打开一次 App，{@code MainActivity} 与
     * {@code SyncGlanceApp} 都会调 {@code queryForConnectedService} 探测，
     * 完全不依赖蓝牙广播。
     */

    /**
     * 判断是不是要响应的设备（只认真车机）。
     *
     * 判断顺序：
     *   1. 名字含 "SYNC" → 车机，记入 MAC 白名单
     *   2. 名字读不到（权限/未缓存）→ 查 MAC 白名单
     *   3. 其余一律忽略
     *
     * ★ MAC 白名单是「集合」，不限数量：凡是成功识别过的设备都记下来，
     *   这样以后名字读不到时还能靠 MAC 认出来，且可以同时记住多台设备
     *   （真车机 + 以后换的车）。
     *
     * ★ 名字读得到时仍然以名字为准，不查 MAC ——
     *   名字变了说明换了设备，此时信名字比信 MAC 可靠。
     *
     * ★ 名字读不到时不再无条件放行 —— 之前那样会让任何蓝牙耳机都触发重建。
     */
    private boolean isSyncHeadUnit(Context ctx, String name, String addr) {

        // ① 名字读得到 → 先按名字判断
        if (name != null && !name.trim().isEmpty()) {
            String trimmed = name.trim();
            String upper = trimmed.toUpperCase(java.util.Locale.US);

            // 1a. 真车机
            if (upper.contains(HEAD_UNIT_KEYWORD)) {
                rememberDevice(ctx, addr);
                return true;
            }

            // 1b. 名字对不上 → 忽略（哪怕 MAC 在白名单里，
            //     名字对了才可信；名字变了说明换设备了）
            return false;
        }

        // ② 名字读不到 → 查 MAC 白名单
        if (isKnownDevice(ctx, addr)) {
            Log2.i(TAG, "设备名读不到，但 MAC 在白名单内: " + addr);
            return true;
        }

        // ③ 既没名字也不在白名单 → 不启动
        Log2.i(TAG, "设备名读不到且 MAC 不在白名单，保守忽略");
        return false;
    }

    // ---------------- MAC 白名单 ----------------

    private static final String PREF = "boot";
    /** 曾经成功识别过的设备 MAC 集合（不限数量） */
    private static final String KEY_KNOWN_MACS = "known_macs";
    /** 旧版本单值存储的 key，仅用于一次性迁移 */
    private static final String KEY_LEGACY_MAC = "head_unit_mac";

    /**
     * 把一个设备记进白名单。
     *
     * 不管它是真车机还是测试设备，只要识别成功就记下来 ——
     * 这样以后设备名读不到时（权限问题、系统未缓存等），还能靠 MAC 认出来。
     */
    private void rememberDevice(Context ctx, String addr) {
        if (addr == null || addr.isEmpty()) return;
        try {
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);

            // getStringSet 返回的集合不可直接修改，必须先复制
            java.util.Set<String> set = new java.util.HashSet<>(
                    sp.getStringSet(KEY_KNOWN_MACS, new java.util.HashSet<>()));

            // 顺带迁移旧版本的单值记录
            String legacy = sp.getString(KEY_LEGACY_MAC, null);
            if (legacy != null && !legacy.isEmpty()) set.add(legacy);

            if (set.add(addr)) {
                sp.edit()
                  .putStringSet(KEY_KNOWN_MACS, set)
                  .remove(KEY_LEGACY_MAC)
                  .apply();
                Log2.i(TAG, "记入 MAC 白名单: " + addr + "（共 " + set.size() + " 个）");
            }
        } catch (Exception e) {
            Log2.w(TAG, "写白名单失败: " + e.getMessage());
        }
    }

    /** MAC 是否在白名单内 */
    private boolean isKnownDevice(Context ctx, String addr) {
        if (addr == null || addr.isEmpty()) return false;
        try {
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            java.util.Set<String> set = sp.getStringSet(KEY_KNOWN_MACS, null);
            if (set != null && set.contains(addr)) return true;

            // 兼容旧版本留下的单值
            String legacy = sp.getString(KEY_LEGACY_MAC, null);
            return addr.equals(legacy);
        } catch (Exception e) {
            return false;
        }
    }

    /** 调试用：把当前白名单打出来 */
    private void dumpKnownDevices(Context ctx) {
        try {
            java.util.Set<String> set = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getStringSet(KEY_KNOWN_MACS, null);
            Log2.i(TAG, "MAC 白名单: " + (set == null || set.isEmpty() ? "（空）" : set.toString()));
        } catch (Exception ignored) { }
    }

    /*
     * ★ 这里曾经有一个 poke() 方法（调 queryForConnectedService），2026-11-21 删掉了。
     *
     *   它看着很合理 —— 官方 API，车机连着就被叫起来，没连就什么都不发生。
     *   但它在这个类里**必然闪退**：
     *
     *     BroadcastReceiver 的 context 是 ReceiverRestrictedContext
     *     → queryForConnectedService 把它一路传到
     *       RouterServiceValidator.createTrustedListRequest
     *       → SdlRouterStatusProvider.checkIsConnected
     *       → bindService
     *     → ReceiverCallNotAllowedException → 进程崩溃
     *
     *   只有「Router 正在跑、ServiceFinder 找得到」时才会走到那一步，
     *   所以表现为「首次上车没事、重新上车就闪退」
     *   （实车 18:31:45 / 18:33:12 各一次）。
     *
     *   同样这个 API 在 Application / Activity 的 context 里是安全的 ——
     *   差别只在 context 的包装类型，不在 API 本身。
     *   所以探测一律放在 SyncGlanceApp.onCreate 和 MainActivity.onCreate 里做。
     *
     *   完整堆栈与分析见 docs/连接可靠性排查.md。
     */
}
