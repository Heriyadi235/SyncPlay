package com.xigaigai.syncglance.activity;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.xigaigai.syncglance.R;
import com.xigaigai.syncglance.core.SyncPlayCore;
import com.xigaigai.syncglance.display.StateRenderer;
import com.xigaigai.syncglance.display.SyncLifecycle;
import com.xigaigai.syncglance.media.NeteaseControl;
import com.xigaigai.syncglance.nav.AmapLinkClient;
import com.xigaigai.syncglance.service.KeyMap;
import com.xigaigai.syncglance.service.NotificationCatcher;
import com.xigaigai.syncglance.service.SdlService;
import com.xigaigai.syncglance.util.Log2;
import com.smartdevicelink.proxy.rpc.enums.ButtonName;

/**
 * SyncPlay 主界面。
 *
 * 布局（两列）：
 *   状态
 *   车机     [权限检查] [重置连接]
 *   音乐     [播放网易云] [暂停/继续]
 *   导航     [高德地图] [回家]
 *            [去公司]   [软键开关]
 *   计时器   [正计时]   [倒计时]
 *            [暂停计时] [清零]
 *   其他     [导航模式] [键位设置]
 *
 * 日志和车机预览在 LogActivity 里，从左边缘右滑进入。
 */
public class MainActivity extends AppCompatActivity {

    private TextView stateView;
    private Button softBtn;

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log2.init(this);

        stateView = findViewById(R.id.tvState);
        softBtn = findViewById(R.id.btnSoftButton);

        requestRuntimePermissions();

        // ---- 音乐 ----
        findViewById(R.id.btnPlayNetease).setOnClickListener(v -> {
            NeteaseControl.startPlayback(this);
            toast("已唤起网易云");
        });
        findViewById(R.id.btnPlayPause).setOnClickListener(v -> {
            NeteaseControl.toggle(this);
            toast("切换播放状态");
        });

        // ---- 导航 ----
        findViewById(R.id.btnAmap).setOnClickListener(v -> launchAmapAndConnect());
        findViewById(R.id.btnGoHome).setOnClickListener(v -> navigateTo("home", "回家"));
        findViewById(R.id.btnGoWork).setOnClickListener(v -> navigateTo("work", "去公司"));

        // ---- 计时器 ----
        findViewById(R.id.btnTimerCountUp).setOnClickListener(v -> {
            timer(0, 0, 0, "COUNTUP"); toast("正计时开始");
        });
        findViewById(R.id.btnTimerCountDown).setOnClickListener(v -> showCountdownDialog());
        findViewById(R.id.btnTimerPause).setOnClickListener(v -> {
            timer(0, 0, 0, "PAUSE"); toast("计时暂停");
        });
        findViewById(R.id.btnTimerClear).setOnClickListener(v -> {
            timer(0, 0, 0, "CLEAR"); toast("计时清零");
        });

        // ---- 其他 ----
        /*
         * 软键测试：在 3 ~ 8 之间循环。
         *
         *   ★ 下限是 3 —— 格1 播放/暂停、格2 占位 -、格3 计时 都是正常功能，
         *     测试键只能追加在后面，不能把它们挤掉。
         *     （以前下限是 1，调到 1 个时计时键就消失了。）
         *   ★ 上限是 8 —— 车机最多认 8 个（AGENTS.md 6.4）。
         */
        findViewById(R.id.btnSoftButton).setOnClickListener(v -> {
            // ★ 走 SyncPlayCore 而不是 SdlService 实例：软键数量是本地状态，
            //   服务按官方语义「断开就 stopSelf」，平时不存在，走 getInstance() 会一直
            //   提示「服务未启动」，等于这个按钮失效。
            StateRenderer r = SyncPlayCore.get().renderer();
            if (r == null) { toast("渲染器未就绪"); return; }
            int n = r.getSoftKeyCount() + 1;
            if (n > StateRenderer.SOFT_KEY_MAX) n = StateRenderer.SOFT_KEY_DEFAULT;
            r.setSoftKeyCount(n);
            updateSoftButtonLabel(n);
            if (n == StateRenderer.SOFT_KEY_DEFAULT) {
                toast("软键回到 3 个（播放 · - · 计时）");
            } else {
                toast("软键 " + n + " 个：测试键追加在计时后面，看车机怎么翻页");
            }
        });
        findViewById(R.id.btnNavMode).setOnClickListener(v -> toggleNavMode());
        findViewById(R.id.btnKeyMap).setOnClickListener(v -> showKeyMapDialog());
        findViewById(R.id.btnPermCheck).setOnClickListener(v -> checkAllPermissions());
        findViewById(R.id.btnReset).setOnClickListener(v -> {
            SdlService s = SdlService.getInstance();
            if (s != null) { s.resetProxy(); toast("已重置连接"); }
            else toast("服务未启动");
        });
        findViewById(R.id.btnOpenLog).setOnClickListener(v -> openLogScreen());

        /*
         * ★ 不在 Activity 里建连接，只做官方探测（2026-11-21 实车修正）。
         *
         *   以前这里是 startSdlService() —— 直接 startForegroundService
         *   → SdlService.onCreate → SdlManager.start()。两个问题：
         *     1. 打开 App 时车机往往还没连 Router，SdlManager 会在 5~11ms 内自毁；
         *     2. 车机真连着的时候，onSdlEnabled 那条官方链路本来就会把服务叫起来，
         *        这次启动纯属多余。
         *
         *   官方 hello_sdl 的 Activity 里也只调 queryForConnectedService：
         *   车机真连着 → 库回调 onSdlEnabled → 启动服务；没连 → 什么都不发生。
         *
         *   与 SyncGlanceApp.onCreate 的那次探测互补：那次跑得极早，
         *   蓝牙/库可能还没就绪，这里是界面真正可见时的第二次确认。
         */
        if (!com.xigaigai.syncglance.receiver.SdlReceiver.pokeIfBluetoothConnected(this)) {
            Log2.w("UI", "探测已连接车机失败（不影响后续 ACL_CONNECTED 链路）");
        }

        /*
         * 状态刷新的心跳改到 onResume 里启动（见那里的注释）。
         * ★ 以前是在这里 postDelayed 自续期的匿名 Runnable，而且 onPause/onDestroy
         *   都没有 removeCallbacks —— 离开主界面后它仍每 2 秒跑一次（改 TextView），
         *   Activity 销毁后 Handler 还持有它，顺带泄漏。
         */
    }

    /** 主界面状态刷新间隔 */
    private static final long STATE_TICK_MS = 2000;

    private final Runnable stateTicker = new Runnable() {
        @Override
        public void run() {
            refreshState();
            main.postDelayed(this, STATE_TICK_MS);
        }
    };

    // ---------------- 权限 ----------------

    /** 一键检查并申请所有必要权限 */
    private void checkAllPermissions() {
        StringBuilder sb = new StringBuilder();

        // 1. 蓝牙
        boolean btOk = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            btOk = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        sb.append("蓝牙权限：").append(btOk ? "✓" : "✗").append('\n');

        // 2. 通知显示（targetSdk 33+ 前台服务通知必需）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean postOk = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            sb.append("通知显示：").append(postOk ? "✓" : "✗（前台服务通知不会显示）").append('\n');
        }

        // 3. 通知读取（读歌名/歌词用，和上面那个不是一回事）
        boolean notifOk = NotificationCatcher.isEnabled(this);
        sb.append("通知读取：").append(notifOk ? "✓" : "✗").append('\n');

        // 4. 高德关联启动（只能提示）
        sb.append("高德关联启动：需在系统设置里手动开启\n");
        sb.append("电池优化白名单：建议开启\n");

        new AlertDialog.Builder(this)
                .setTitle("权限检查")
                .setMessage(sb.toString())
                .setPositiveButton("申请权限", (d, w) -> requestRuntimePermissions())
                .setNeutralButton("开通知读取", (d, w) -> openNotifSettings())
                .setNegativeButton("关闭", null)
                .show();
    }

    /**
     * 申请运行时权限。
     *
     * 蓝牙（Android 12+ 拆分的那两个）和 **通知**（Android 13+）一起申请：
     * targetSdk 提到 35 后，前台服务的常驻通知依赖 POST_NOTIFICATIONS，
     * 不申请的话状态栏什么都不显示，服务被杀的概率也更高。
     */
    private void requestRuntimePermissions() {
        java.util.List<String> need = new java.util.ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (need.isEmpty()) {
            toast("权限都已授予");
            return;
        }
        requestPermissions(need.toArray(new String[0]), 1001);
    }

    private void openNotifSettings() {
        try {
            toast("请在列表里找到 SyncPlay 并打开");
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        } catch (Exception e) {
            toast("无法打开设置");
        }
    }

    // ---------------- 导航 ----------------

    /** 拉起高德，几秒后尝试连接 AmapLink */
    private void launchAmapAndConnect() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.autonavi.minimap");
            if (i == null) { toast("高德未安装"); return; }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);

            // ★ 已经连上就别再排一次重连，省掉 5 秒空等
            AmapLinkClient amap = AmapLinkClient.get(this);
            if (amap.isRegistered()) {
                Log2.i("UI", "拉起高德（AmapLink 已连接，不再重连）");
                toast("已打开高德");
                return;
            }

            toast("已打开高德，正在连接…");
            Log2.i("UI", "手动拉起高德，5 秒后连接 AmapLink");
            main.postDelayed(amap::wakeUp, 5000);
        } catch (Exception e) {
            toast("打开高德失败");
        }
    }

    /**
     * 用 amapuri 协议让高德直接开始导航。
     *
     * @param type "home" 或 "work"（高德内置的常用地址）
     */
    private void navigateTo(String type, String label) {
        // 高德 URI：amapuri://route/plan/?sourceApplication=xxx&keyword=家
        try {
            String uri = "amapuri://route/plan/?sourceApplication=SyncPlay&keyword="
                    + ("home".equals(type) ? "家" : "公司");
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse(uri));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            toast("正在导航" + label + "…");
            Log2.i("UI", "导航到" + label + ": " + uri);
            main.postDelayed(() -> AmapLinkClient.get(this).wakeUp(), 6000);
        } catch (Exception e) {
            toast("启动高德导航失败，请确认装了高德");
        }
    }

    private void toggleNavMode() {
        // 导航模式是本地开关（SyncPlayCore 里本来就有），不必依赖 SdlService 实例
        boolean on = !SyncPlayCore.get().isManualNavMode();
        SyncPlayCore.get().setManualNavMode(on);
        toast("导航模式：" + (on ? "开" : "关"));
    }

    // ---------------- 计时器 ----------------

    private void timer(int h, int m, int s, String mode) {
        /*
         * ★ 直接走 SyncPlayCore，不再依赖 SdlService 实例。
         *
         *   计时器是本地状态（车机拿到指令后自己走表），没连车机也该能设、
         *   屏幕预览也该看得到。而 SdlService 现在是「断开就 stopSelf」
         *   （官方语义），平时根本不存在 —— 以前走 getInstance() 就会
         *   直接 return "服务未启动"，连预览都不动。
         *
         *   RPC 发不发由 SyncPlayCore 判断：没连接就先记着，连上后自动补发。
         */
        SyncPlayCore.get().doTimer(h, m, s, mode);
    }

    /**
     * 倒计时时长选择：分 / 秒 两个滚轮。
     *
     * 上限锁死 59:59 —— 车机的时钟格是 mm:ss，没有小时位，
     * 超过 59 分钟的分值会被车机判定为 INVALID_DATA 直接拒掉
     * （实测 minutes=99 → INVALID_DATA）。所以这里不做小时，
     * 分钟也只给到 59。
     */
    private void showCountdownDialog() {
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        root.setGravity(android.view.Gravity.CENTER);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        android.widget.NumberPicker minPicker = new android.widget.NumberPicker(this);
        minPicker.setMinValue(0);
        minPicker.setMaxValue(59);
        minPicker.setValue(5);
        minPicker.setFormatter(v -> String.format(java.util.Locale.US, "%02d", v));

        android.widget.NumberPicker secPicker = new android.widget.NumberPicker(this);
        secPicker.setMinValue(0);
        secPicker.setMaxValue(59);
        secPicker.setValue(0);
        secPicker.setFormatter(v -> String.format(java.util.Locale.US, "%02d", v));

        TextView mLab = new TextView(this);
        mLab.setText(" 分 ");
        mLab.setTextSize(16);
        TextView sLabel = new TextView(this);
        sLabel.setText(" 秒 ");
        sLabel.setTextSize(16);

        root.addView(minPicker);
        root.addView(mLab);
        root.addView(secPicker);
        root.addView(sLabel);

        new AlertDialog.Builder(this)
                .setTitle("倒计时时长")
                .setView(root)
                .setPositiveButton("开始", (d, w) -> {
                    int m = minPicker.getValue();
                    int s = secPicker.getValue();
                    if (m == 0 && s == 0) { toast("时长不能为 0"); return; }
                    timer(0, m, s, "COUNTDOWN");
                    toast(String.format(java.util.Locale.US, "倒计时 %02d:%02d", m, s));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 键位 ----------------
    private void showKeyMapDialog() {
        final Context ctx = this;
        String[] labels = new String[KeyMap.BINDABLE.length];
        for (int i = 0; i < KeyMap.BINDABLE.length; i++) {
            int cmd = KeyMap.BINDABLE[i];
            ButtonName bound = KeyMap.get(ctx).findKeyFor(cmd, KeyMap.ALL_SUBSCRIBE);
            labels[i] = KeyMap.labelOf(cmd) + "  →  " + (bound == null ? "未绑定" : bound.name());
        }
        new AlertDialog.Builder(this)
                .setTitle("键位设置")
                .setItems(labels, (d, which) -> pickButtonFor(KeyMap.BINDABLE[which]))
                .setNegativeButton("重置默认", (d, w) -> {
                    KeyMap.get(ctx).resetAll();
                    toast("已重置");
                })
                .setNeutralButton("关闭", null)
                .show();
    }

    private void pickButtonFor(int cmdId) {
        final Context ctx = this;
        final String[] names = new String[KeyMap.ALL_SUBSCRIBE.length];
        for (int i = 0; i < KeyMap.ALL_SUBSCRIBE.length; i++) {
            names[i] = KeyMap.ALL_SUBSCRIBE[i].name();
        }
        new AlertDialog.Builder(this)
                .setTitle("把「" + KeyMap.labelOf(cmdId) + "」绑到哪个键？")
                .setItems(names, (d, which) -> {
                    KeyMap.get(ctx).bind(KeyMap.ALL_SUBSCRIBE[which], cmdId);
                    toast("已绑定 " + names[which]);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 状态 ----------------

    private void updateSoftButtonLabel(int n) {
        if (softBtn != null) {
            softBtn.setText("软键测试：" + n + " 个");
        }
    }

    /**
     * 刷新状态栏。
     *
     * ★ 状态不再依赖 SdlService 是否存活：连接状态、媒体、导航分别来自
     *   SyncPlayCore.lifecycle() / renderer / AmapLinkClient，
     *   服务退出（没连车机时 SDL 5.x 会这样）也照样能看到手机侧状态。
     */
    private void refreshState() {
        SyncPlayCore core = SyncPlayCore.get();
        StringBuilder sb = new StringBuilder();

        sb.append("连接：").append(core.lifecycle().describe());
        // SDL 服务是「连接 + 发送」的薄层，没连车机时它会自己退出，这里如实标出来
        sb.append(SdlService.getInstance() == null ? "（服务未运行）" : "").append('\n');

        sb.append("媒体：").append(mediaStatusText()).append('\n');
        sb.append("导航：").append(AmapLinkClient.get(this).displayStatus()).append('\n');

        stateView.setText(sb.toString());

        updateSoftButtonLabel(core.renderer().getSoftKeyCount());
    }

    /** 媒体状态文字：歌名 / 歌手 [播放|暂停]（数据来自 SyncPlayCore） */
    private String mediaStatusText() {
        com.xigaigai.syncglance.model.NowPlaying np = SyncPlayCore.get().song();
        if (np == null || !np.hasData()) return "—";

        StringBuilder sb = new StringBuilder();
        if (np.title != null && !np.title.isEmpty()) sb.append(np.title);
        if (np.artist != null && !np.artist.isEmpty()) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(np.artist);
        }
        sb.append(np.playing ? "  [播放]" : "  [暂停]");
        return sb.toString();
    }

    // ---------------- 服务 ----------------

    /*
     * ★ 这里曾经有一个 startSdlService()（直接 startForegroundService）。
     *
     *   2026-11-21 按「连接生命周期完全对齐 SDL 5.x 官方」的要求删掉了：
     *   应用只在 SdlReceiver.onSdlEnabled（车机已连通 Router）时启动 SdlService，
     *   其他地方一律只 queryForConnectedService 探测。
     *
     *   要不要恢复它，先看 docs/连接可靠性排查.md 里的实车证据：
     *   从 Activity / 广播直接建 SdlManager 这条路，实测 5~11ms 就自毁。
     */

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
        // 只在页面可见时刷新状态；onPause 会停掉
        main.removeCallbacks(stateTicker);
        main.postDelayed(stateTicker, STATE_TICK_MS);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(stateTicker);
        super.onPause();
    }

    /** 打开日志界面（滑入动画） */
    private void openLogScreen() {
        startActivity(new Intent(this, LogActivity.class));
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
