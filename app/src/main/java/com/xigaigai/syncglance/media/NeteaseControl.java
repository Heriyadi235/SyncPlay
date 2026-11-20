package com.xigaigai.syncglance.media;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import com.xigaigai.syncglance.util.Log2;

/**
 * 网易云音乐控制。
 *
 * 两条路：
 *   ① 唤起 app 播放 —— 直接发 Intent 到网易云主页，或者发媒体按键
 *   ② 发媒体按键 —— 走 AudioManager.dispatchMediaKeyEvent，
 *      任何注册了 MediaSession 的播放器都会收到
 *
 * 方案 ② 更可靠：不需要知道网易云的 Activity 名，也不怕它改版。
 */
public final class NeteaseControl {

    private static final String TAG = "Netease";
    public static final String PKG = "com.netease.cloudmusic";

    private NeteaseControl() { }

    /**
     * 开始播放。
     *
     * 关键：**已经在播放就不发播放键**。
     * 很多手机连上车载蓝牙时会自动按一次播放，重复发会导致播放/暂停来回切。
     *
     * 策略：
     *   1. 先查当前是否在播放 → 在播就什么都不做
     *   2. 没在播才启动 app + 发播放键
     */
    public static void startPlayback(Context ctx) {
        if (com.xigaigai.syncglance.service.NotificationCatcher.isCurrentlyPlaying()) {
            Log2.i(TAG, "已在播放中（" + 
                    com.xigaigai.syncglance.service.NotificationCatcher.getLastTitle()
                    + "），不重复发送播放键");
            return;
        }

        Log2.i(TAG, "当前未播放，先尝试只发媒体键");

        // ★ 优先只发媒体键，绝对不把网易云界面拉到前台。
        //
        //   历史行为：以前这里会 launchApp()，因为网易云没在前台时不响应按键。
        //   但那样每次点「播放网易云」都会跳出网易云主界面，在车里非常碍事 ——
        //   用户要的是"手机在兜里也能放歌"，不是"打开网易云"。
        //
        //   新策略：
        //     1. 先发 PLAY 键（网易云的 MediaSession 即使后台也常驻，多半能响应）
        //     2. 1.5 秒后仍没在播，才退回启动界面（此时是必要条件，不是打扰）
        sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (com.xigaigai.syncglance.service.NotificationCatcher.isCurrentlyPlaying()) {
                Log2.i(TAG, "媒体键已生效，未拉起界面");
                return;
            }
            Log2.i(TAG, "媒体键无效，改为启动网易云界面");
            if (launchApp(ctx)) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (!com.xigaigai.syncglance.service.NotificationCatcher.isCurrentlyPlaying()) {
                        sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY);
                    }
                }, 1200);
            }
        }, 1500);
    }

    /** 只发播放键（已经检查过状态） */
    public static void resume(Context ctx) {
        if (com.xigaigai.syncglance.service.NotificationCatcher.isCurrentlyPlaying()) {
            Log2.i(TAG, "已在播放，跳过");
            return;
        }
        sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY);
    }

    /** 只发暂停键 */
    public static void pause(Context ctx) {
        sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE);
    }

    /** 播放/暂停切换 */
    public static void toggle(Context ctx) {
        sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
    }

    public static void next(Context ctx) {
        sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    public static void previous(Context ctx) {
        sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    // ---------------- 内部 ----------------

    /** 启动网易云主界面 */
    private static boolean launchApp(Context ctx) {
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(PKG);
            if (i == null) {
                Log2.w(TAG, "找不到网易云的启动入口");
                return false;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            Log2.i(TAG, "已启动网易云");
            return true;
        } catch (Exception e) {
            Log2.e(TAG, "启动网易云失败", e);
            return false;
        }
    }

    /**
     * 发一个媒体按键事件。
     *
     * 用 AudioManager.dispatchMediaKeyEvent —— 这是系统级 API，
     * 所有注册了 MediaSession 的播放器（包括网易云）都会响应。
     */
    private static void sendMediaKey(Context ctx, int keyCode) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                Log2.w(TAG, "AudioManager 不可用");
                return;
            }
            long now = System.currentTimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
            KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0);
            am.dispatchMediaKeyEvent(down);
            am.dispatchMediaKeyEvent(up);
            Log2.i(TAG, "→ 媒体按键 " + KeyEvent.keyCodeToString(keyCode));
        } catch (Exception e) {
            Log2.e(TAG, "发送媒体按键失败", e);
        }
    }
}
