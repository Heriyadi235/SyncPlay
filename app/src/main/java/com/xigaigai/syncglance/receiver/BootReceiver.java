package com.xigaigai.syncglance.receiver;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.xigaigai.syncglance.service.NotificationCatcher;
import com.xigaigai.syncglance.service.SdlService;
import com.xigaigai.syncglance.util.Log2;

/**
 * 蓝牙/开机事件 → 拉起 SDL 服务。
 *
 * AppLink 的生命周期缺陷：车机无法唤醒手机上的 app，
 * 所以必须靠这些系统事件把服务重新拉起来。
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
                Log2.i(TAG, "【启动来源】系统开机");
                startSdl(context, false);
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
                // ★ 2026-11 起不再 forceReset。
                //
                //   加入 SdlReceiver + SyncRouterService 之后，连接由 SDL 官方链路负责：
                //   ACL_CONNECTED → SdlReceiver 唤起 RouterService 监听 RFCOMM
                //                → 车机连上 → onSdlEnabled → 拉起 SdlService。
                //
                //   此刻车机还没连 SDL，强制 dispose 旧 proxy 只会在 RouterService
                //   还没起来的时候抢跑建新 proxy，反而更容易失败。
                //   这里只保证进程活着，是否需要重建交给 SdlService.needRebuild() 判断。
                Log2.i(TAG, "★ 已连接，启动服务（连接交给 SDL 官方链路）");
                startSdl(context, false);
                break;
            }

            case "android.bluetooth.adapter.action.STATE_CHANGED": {
                // 只在蓝牙打开时拉起，关闭时不做动作
                int state = intent.getIntExtra(
                        android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1);
                if (state == android.bluetooth.BluetoothAdapter.STATE_ON) {
                    Log2.i(TAG, "【启动来源】蓝牙已开启");
                    startSdl(context, false);
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
                // 不断服务，等 ACL_CONNECTED 来触发重建
                break;
            }

            case "android.intent.action.MY_PACKAGE_REPLACED": {
                // 只监听自己的升级（不需要 data scheme，也不会被别的应用打扰）
                Log2.i(TAG, "【启动来源】本应用安装/升级完成");
                startSdl(context, true);
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
     * 台架测试不受影响 —— 手动打开一次 App，{@code MainActivity} 的 onCreate
     * 会自己 {@code startSdlService()}，完全不依赖蓝牙广播。
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

    private void startSdl(Context ctx, boolean forceReset) {
        try {
            Intent i = new Intent(ctx, SdlService.class);
            if (forceReset) i.putExtra(SdlService.EXTRA_FORCE_RESET, true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            Log2.i(TAG, "已拉起 SdlService" + (forceReset ? "（强制重建）" : ""));
        } catch (Exception e) {
            Log2.e(TAG, "拉起服务失败", e);
        }
    }
}
