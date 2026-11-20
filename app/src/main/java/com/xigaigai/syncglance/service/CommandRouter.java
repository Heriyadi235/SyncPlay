package com.xigaigai.syncglance.service;

import com.smartdevicelink.proxy.rpc.enums.ButtonPressMode;
import com.smartdevicelink.proxy.rpc.enums.ButtonName;
import com.xigaigai.syncglance.util.Log2;

/**
 * 菜单与按键的路由。
 *
 * 菜单 ID 规划（cmdID 全局唯一）：
 *   1xxx  音乐
 *   2xxx  导航
 *   3xxx  计时
 *   9xx   父菜单本身
 */
public final class CommandRouter {

    private static final String TAG = "Router";

    // ---- 父菜单 ----
    public static final int MENU_MUSIC = 901;
    public static final int MENU_NAV = 902;
    public static final int MENU_TIMER = 903;

    // ---- 音乐 ----
    public static final int CMD_PREV = 1001;
    public static final int CMD_NEXT = 1002;
    public static final int CMD_PLAY_PAUSE = 1003;
    public static final int CMD_SHOW_LYRIC = 1004;
    public static final int CMD_HIDE_LYRIC = 1005;

    // ---- 导航 ----
    public static final int CMD_NAV_ON = 2001;
    public static final int CMD_NAV_OFF = 2002;

    // ---- 软键 ----
    public static final int CMD_SOFT_PLAY_PAUSE = 9001;
    /** 软键 ID，必须和 Show.softButtons 里设的一致 */
    public static final int SOFT_BTN_PLAY_PAUSE = 9001;

    // ---- 计时 ----
    public static final int CMD_TIMER_UP = 3001;
    public static final int CMD_TIMER_DOWN = 3002;
    public static final int CMD_TIMER_PAUSE = 3003;
    public static final int CMD_TIMER_RESUME = 3004;
    public static final int CMD_TIMER_CLEAR = 3005;

    private CommandRouter() { }

    /** 处理菜单命令 */
    public static void handle(SdlService svc, int cmdId) {
        switch (cmdId) {
            case CMD_PREV:
                Log2.i(TAG, "音乐：上一首");
                com.xigaigai.syncglance.media.NeteaseControl.previous(svc);
                break;
            case CMD_NEXT:
                Log2.i(TAG, "音乐：下一首");
                com.xigaigai.syncglance.media.NeteaseControl.next(svc);
                break;
            case CMD_PLAY_PAUSE:
                Log2.i(TAG, "音乐：播放暂停");
                com.xigaigai.syncglance.media.NeteaseControl.toggle(svc);
                break;
            case CMD_SHOW_LYRIC:
                Log2.i(TAG, "音乐：显示歌词");
                // ★ 必须重新 load：「隐藏歌词」把 renderer 的歌词置空了，
                //   只 markDirty 的话永远显示「无歌词信息」。
                svc.reloadLyrics();
                break;
            case CMD_HIDE_LYRIC:
                Log2.i(TAG, "音乐：隐藏歌词");
                svc.getRenderer().setLyrics(null);
                break;

            case CMD_NAV_ON:
                svc.setManualNavMode(true);
                break;
            case CMD_NAV_OFF:
                svc.setManualNavMode(false);
                break;

            case CMD_TIMER_UP:
                svc.doTimer(0, 0, 0, "COUNTUP");
                break;
            case CMD_TIMER_DOWN:
                svc.doTimer(0, 5, 0, "COUNTDOWN");
                break;
            case CMD_TIMER_PAUSE:
                svc.doTimer(0, 0, 0, "PAUSE");
                break;
            case CMD_TIMER_RESUME:
                svc.doTimer(0, 0, 0, "RESUME");
                break;
            case CMD_TIMER_CLEAR:
                svc.doTimer(0, 0, 0, "CLEAR");
                break;

            case CMD_SOFT_PLAY_PAUSE:
                Log2.i(TAG, "软键：播放/暂停");
                com.xigaigai.syncglance.media.NeteaseControl.toggle(svc);
                break;

            default:
                Log2.w(TAG, "未知 cmdID=" + cmdId);
        }
    }

    /**
     * 处理软键按压。
     *
     * 车机回调形如：
     *   OnButtonPress { buttonName: CUSTOM_BUTTON, buttonPressMode: SHORT,
     *                   customButtonID: &lt;我们在 Show.softButtons 里设的 ID&gt; }
     *
     * 注意它<b>不走</b> OnCommand，也不在物理键表里，所以必须单独接。
     */
    public static void handleSoftButton(SdlService svc, Integer id) {
        if (id == null) {
            Log2.w(TAG, "软键：回调未带 customButtonID");
            return;
        }
        Log2.i(TAG, "软键按压 id=" + id);
        switch (id) {
            case SOFT_BTN_PLAY_PAUSE:
                handle(svc, CMD_SOFT_PLAY_PAUSE);
                break;
            default:
                Log2.w(TAG, "软键 id=" + id + " 未绑定");
        }
    }

    // ---------------- 同键别名去重 ----------------

    /**
     * 本车 <b>OK 与 PLAY_PAUSE 是同一个物理键</b>，按一次会收到两条通知：
     *
     * <pre>
     * OnButtonPress { OK,         SHORT }
     * OnButtonPress { PLAY_PAUSE, SHORT }
     * </pre>
     *
     * 只处理其中一条即可，否则：
     *   · 两个键绑同一功能 → 执行两次，切换类动作互相抵消（播放→暂停→播放）
     *   · 两个键绑不同功能 → 一次按键触发两个动作
     *
     * ★ 注意<b>不能</b>按 cmdID 做通用去抖 —— 那会把「用户快速连按同一个键」
     *   也一起吃掉（快速切两首歌，第二下失效）。这里只针对这一对别名合并。
     */

    /** 别名合并的时间窗口。车机两条通知几乎同时到，200ms 足够 */
    private static final long ALIAS_WINDOW_MS = 200;

    /** 上一次<b>真正执行</b>的按键名 */
    private static ButtonName lastHandled = null;
    private static long lastHandledAt = 0;

    /** 这两个键名在硬件上是不是同一个键（且确实是两个不同的名字） */
    private static boolean isSamePhysicalKey(ButtonName a, ButtonName b) {
        if (a == null || b == null || a == b) return false;
        boolean aInPair = (a == ButtonName.OK || a == ButtonName.PLAY_PAUSE);
        boolean bInPair = (b == ButtonName.OK || b == ButtonName.PLAY_PAUSE);
        return aInPair && bInPair;
    }

    /** 处理物理按键 */
    public static void handleButton(SdlService svc, ButtonName btn, ButtonPressMode mode) {
        if (mode != ButtonPressMode.SHORT) return;   // 只响应短按

        KeyMap km = KeyMap.get(svc);
        int cmd = km.commandFor(btn);
        if (cmd == 0) {
            Log2.i(TAG, "按键 " + btn + " 未绑定");
            // 未绑定的不参与别名判定：否则先按 OK（未绑定）、后按 PLAY_PAUSE
            // 时状态会对不上
            return;
        }

        // ★ 同一物理键的第二条通知直接丢弃
        long now = System.currentTimeMillis();
        if (isSamePhysicalKey(btn, lastHandled) && now - lastHandledAt < ALIAS_WINDOW_MS) {
            Log2.i(TAG, "按键 " + btn + " 与 " + lastHandled
                    + " 是同一物理键（" + (now - lastHandledAt) + "ms 内），忽略");
            return;
        }

        lastHandled = btn;
        lastHandledAt = now;

        Log2.i(TAG, "按键 " + btn + " → cmdID=" + cmd);
        handle(svc, cmd);
    }
}
