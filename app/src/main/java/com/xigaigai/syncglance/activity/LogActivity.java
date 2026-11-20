package com.xigaigai.syncglance.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.xigaigai.syncglance.R;
import com.xigaigai.syncglance.core.SyncCore;
import com.xigaigai.syncglance.display.ScreenPreviewView;
import com.xigaigai.syncglance.display.StateRenderer;
import com.xigaigai.syncglance.nav.AmapLinkClient;
import com.xigaigai.syncglance.service.NotificationCatcher;
import com.xigaigai.syncglance.service.SdlService;
import com.xigaigai.syncglance.util.Log2;

/**
 * 通信日志 + 车机屏幕预览。
 *
 * 从主界面左边缘右滑进入。
 *
 * 上半部分：日志（可复制、分享、切换原始 JSON、清空）
 * 下半部分：车机屏幕的字符预览
 */
public class LogActivity extends AppCompatActivity {

    private TextView logView;
    private ScrollView logScroll;
    private ScreenPreviewView previewCtrl;
    private Button rawBtn;
    private Button pauseBtn;

    private boolean autoRefresh = true;
    private int lastLength = -1;

    private final Handler main = new Handler();
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (autoRefresh) {
                refreshLog();
                refreshPreview();
            }
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("信息");
        }

        logView = findViewById(R.id.logText);
        logScroll = findViewById(R.id.logScroll2);
        previewCtrl = new ScreenPreviewView(findViewById(R.id.previewInclude));
        rawBtn = findViewById(R.id.btnRawToggle);
        pauseBtn = findViewById(R.id.btnPauseScroll);

        logView.setTextIsSelectable(true);
        updateRawButton();

        findViewById(R.id.btnCopy).setOnClickListener(v -> copyAll());
        findViewById(R.id.btnShare).setOnClickListener(v -> shareAll());
        findViewById(R.id.btnClear).setOnClickListener(v -> {
            Log2.clear();
            logView.setText("");
            lastLength = -1;
            toast("已清空");
        });
        findViewById(R.id.btnRawToggle).setOnClickListener(v -> {
            Log2.setRawEnabled(!Log2.isRawEnabled());
            updateRawButton();
        });
        pauseBtn.setOnClickListener(v -> {
            autoRefresh = !autoRefresh;
            pauseBtn.setText(autoRefresh ? "暂停刷新" : "继续刷新");
            if (autoRefresh) { refreshLog(); refreshPreview(); }
        });

        // 清空歌词缓存：歌词匹配错了 / 负缓存把有歌词的歌锁死，都靠它强制重查
        findViewById(R.id.btnClearLyricCache).setOnClickListener(v -> confirmClearLyricCache());

        refreshLog();
        refreshPreview();
    }

    /** 清空歌词缓存（二次确认，避免误触把几十首歌的缓存删了） */
    private void confirmClearLyricCache() {
        new AlertDialog.Builder(this)
                .setTitle("清空歌词缓存")
                .setMessage("会删除已缓存的歌词（含「无歌词」负缓存），并重新查询当前这首歌。\n不影响设置和其它数据。")
                .setPositiveButton("清空", (d, w) -> {
                    int removed = SyncCore.get().clearLyricCache();
                    toast(removed > 0 ? "已清空 " + removed + " 个缓存文件，正在重查歌词"
                                      : "缓存本来就是空的，已重新查询");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateRawButton() {
        rawBtn.setText("JSON:" + (Log2.isRawEnabled() ? "开" : "关"));
    }

    private void refreshLog() {
        String full = Log2.getFullLog();
        if (full.isEmpty()) full = Log2.getUiLog();
        if (full.length() == lastLength) return;
        lastLength = full.length();
        logView.setText(full);
        if (logScroll != null) {
            logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        }
    }

    /**
     * 刷新车机屏幕预览。
     *
     * 预览器只吃「一帧」——由 StateRenderer 算出「如果现在发会发什么」。
     * 它不依赖 SdlService 是否存活，也不关心歌词怎么切、数据从哪来；
     * 计时器那一格由预览器自己按 ClockInstruction 走表。
     */
    private void refreshPreview() {
        if (previewCtrl == null) return;
        previewCtrl.render(SyncCore.get().renderer().getLastFrame());
    }

    private String currentText() {
        String full = Log2.getFullLog();
        return full.isEmpty() ? Log2.getUiLog() : full;
    }

    private void copyAll() {
        String text = currentText();
        if (text.trim().isEmpty()) { toast("日志是空的"); return; }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("SyncPlay log", text));
            toast("已复制 " + text.length() + " 字符");
        }
    }

    private void shareAll() {
        String text = currentText();
        if (text.trim().isEmpty()) { toast("日志是空的"); return; }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "SyncPlay 日志");
        i.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, "分享日志"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        autoRefresh = true;
        lastLength = -1;
        /*
         * ★ 信息页打开 = 用户正在看车机屏幕预览。
         *
         *   不是 HMI_FULL 的时候，导航包只在"有人看"的时候才解码 —— AES-GCM 是
         *   CPU 大头，后台一直解密纯属浪费电。两页都不满足时仍然读 fd 并关闭，
         *   只记心跳，不解密。（原来那个「始终解析导航包」开关就是干这个的，现已去掉）
         */
        AmapLinkClient.setPreviewVisible(true);
        previewCtrl.render(SyncCore.get().renderer().getLastFrame());
        main.post(ticker);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(ticker);
        AmapLinkClient.setPreviewVisible(false);
        super.onPause();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
