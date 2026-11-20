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
import com.xigaigai.syncglance.display.CharProbe;
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

        requestBtPermission();

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
        // 软键测试：循环 1 → 2 → … → SOFT_KEY_MAX，用来探测车机真实能力。
        //   1 个 = 正常工作状态（只有播放/暂停）
        //   2 个以上 = 往后面几格塞「键2」「键3」… 看车机最多认几个、要不要翻页
        findViewById(R.id.btnSoftButton).setOnClickListener(v -> {
            SdlService s = SdlService.getInstance();
            if (s == null || s.getRenderer() == null) { toast("服务未启动"); return; }
            int n = s.getRenderer().getSoftKeyCount() + 1;
            if (n > StateRenderer.SOFT_KEY_MAX) n = 1;
            s.getRenderer().setSoftKeyCount(n);
            updateSoftButtonLabel(n);
            if (n == 1) {
                toast("软键恢复为 1 个（仅播放/暂停）");
            } else {
                toast("已发送 " + n + " 个软键，看车机画几个、要不要翻页");
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
        findViewById(R.id.btnCharTest).setOnClickListener(v -> showCharTestDialog());

        startSdlService();

        // 定时刷新状态
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshState();
                main.postDelayed(this, 2000);
            }
        }, 1000);
    }

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

        // 2. 通知读取
        boolean notifOk = NotificationCatcher.isEnabled(this);
        sb.append("通知读取：").append(notifOk ? "✓" : "✗").append('\n');

        // 3. 高德关联启动（只能提示）
        sb.append("高德关联启动：需在系统设置里手动开启\n");
        sb.append("电池优化白名单：建议开启\n");

        new AlertDialog.Builder(this)
                .setTitle("权限检查")
                .setMessage(sb.toString())
                .setPositiveButton("申请蓝牙", (d, w) -> requestBtPermission())
                .setNeutralButton("开通知权限", (d, w) -> openNotifSettings())
                .setNegativeButton("关闭", null)
                .show();
    }

    private void requestBtPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                }, 1001);
            }
        }
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
        SdlService s = SdlService.getInstance();
        if (s == null) { toast("服务未启动"); return; }
        boolean on = !s.isManualNavMode();
        s.setManualNavMode(on);
        toast("导航模式：" + (on ? "开" : "关"));
    }

    // ---------------- 计时器 ----------------

    private void timer(int h, int m, int s, String mode) {
        SdlService svc = SdlService.getInstance();
        if (svc == null) { toast("服务未启动"); return; }
        svc.doTimer(h, m, s, mode);
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

    /**
     * 字符测试：把 {@link CharProbe} 里的分组逐条发到车机。
     *
     * 用 {@code setSingleChoiceItems} 是为了点完<b>不关</b>对话框 ——
     * 人可以坐在车里连着点十几组，不用每试一组就回手机重开一次。
     *
     * 每组内容带两位编号，车机上看到「03」后面是个圆点，就说明第 3 个字符没字形。
     */
    private void showCharTestDialog() {
        SdlService s = SdlService.getInstance();
        if (s == null) { toast("服务未启动"); return; }
        if (!s.isHmiFull()) { toast("还没连上车机（需要 HMI_FULL）"); return; }

        String[] names = new String[CharProbe.GROUPS.length];
        for (int i = 0; i < names.length; i++) names[i] = CharProbe.GROUPS[i].name;

        new AlertDialog.Builder(this)
                .setTitle("字符测试（点一组发一次）")
                .setSingleChoiceItems(names, -1, (d, which) -> {
                    CharProbe.Group g = CharProbe.GROUPS[which];
                    s.sendScrollableTest(g.body);
                    toast("已发送：" + g.name);
                })
                .setPositiveButton("关闭", null)
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
            softBtn.setText(n <= 1 ? "软键测试：1 个" : "软键测试：" + n + " 个");
        }
    }

    private void refreshState() {
        SdlService s = SdlService.getInstance();
        StringBuilder sb = new StringBuilder();

        if (s == null) {
            sb.append("连接：服务未启动\n");
            sb.append("媒体：—\n");
            sb.append("导航：—\n");
        } else {
            SyncLifecycle lc = s.getLifecycle();
            sb.append("连接：").append(lc.describe()).append('\n');

            // 媒体播放状态
            sb.append("媒体：").append(mediaStatusText(s)).append('\n');

            // 高德连接状态
            AmapLinkClient amap = AmapLinkClient.get(this);
            sb.append("导航：").append(amap.displayStatus()).append('\n');
        }

        stateView.setText(sb.toString());

        if (s != null && s.getRenderer() != null) {
            updateSoftButtonLabel(s.getRenderer().getSoftKeyCount());
        }
    }

    /** 媒体状态文字：歌名 / 歌手 [播放|暂停] */
    private String mediaStatusText(SdlService s) {
        com.xigaigai.syncglance.model.NowPlaying np =
                s.getRenderer() == null ? null : s.getRenderer().getSong();
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

    private void startSdlService() {
        try {
            Intent i = new Intent(this, SdlService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        } catch (Exception e) {
            Log2.e("UI", "启动服务失败", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    /** 打开日志界面（滑入动画） */
    private void openLogScreen() {
        startActivity(new Intent(this, LogActivity.class));
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
