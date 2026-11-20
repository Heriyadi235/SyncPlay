package com.xigaigai.syncglance.service;

import android.content.Context;
import android.content.SharedPreferences;

import com.smartdevicelink.proxy.rpc.enums.ButtonName;

import java.util.HashMap;
import java.util.Map;

/**
 * 按键 → 功能 的映射，存在 SharedPreferences 里可改。
 *
 * 默认：SEEKLEFT=上一首，SEEKRIGHT=下一首
 */
public class KeyMap {

    private static final String PREF = "keymap";
    private static final String KEY_PREFIX = "btn_";

    private final SharedPreferences sp;

    private KeyMap(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static KeyMap instance;

    public static synchronized KeyMap get(Context ctx) {
        if (instance == null) instance = new KeyMap(ctx);
        return instance;
    }

    /** 这个按键绑的功能（cmdID），0 表示未绑定 */
    public int commandFor(ButtonName btn) {
        if (btn == null) return 0;
        String def = String.valueOf(defaultFor(btn));
        String v = sp.getString(KEY_PREFIX + btn.name(), def);
        try { return Integer.parseInt(v); } catch (Exception e) { return 0; }
    }

    /** 改绑：同一个功能解绑其他键，保证一键一功能 */
    public void bind(ButtonName btn, int cmdId) {
        if (btn == null) return;
        // 先解绑其他键上的同一个功能
        Map<String, ?> all = sp.getAll();
        SharedPreferences.Editor ed = sp.edit();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            if (e.getValue() instanceof String
                    && String.valueOf(cmdId).equals(e.getValue())) {
                ed.remove(e.getKey());
            }
        }
        ed.putString(KEY_PREFIX + btn.name(), String.valueOf(cmdId));
        ed.apply();
    }

    public void unbind(ButtonName btn) {
        if (btn == null) return;
        sp.edit().remove(KEY_PREFIX + btn.name()).apply();
    }

    /** 查某个功能绑在哪个键上 */
    public ButtonName findKeyFor(int cmdId, ButtonName[] candidates) {
        String target = String.valueOf(cmdId);
        for (ButtonName b : candidates) {
            if (target.equals(sp.getString(KEY_PREFIX + b.name(), null))) return b;
        }
        return null;
    }

    public Map<String, Integer> dump() {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            try {
                out.put(e.getKey().replace(KEY_PREFIX, ""),
                        Integer.parseInt(String.valueOf(e.getValue())));
            } catch (Exception ignored) { }
        }
        return out;
    }

    public void resetAll() {
        sp.edit().clear().apply();
    }

    private static int defaultFor(ButtonName btn) {
        switch (btn) {
            case SEEKLEFT: return CommandRouter.CMD_PREV;
            case SEEKRIGHT: return CommandRouter.CMD_NEXT;
            case PLAY_PAUSE: return CommandRouter.CMD_PLAY_PAUSE;
            default: return 0;
        }
    }

    /**
     * 要订阅的所有按键。
     *
     * 注意：
     *   - 不要有重复项（重复订阅会返回 IGNORED）
     *   - 实测这台车机不支持 TUNEUP / TUNEDOWN（返回 UNSUPPORTED_RESOURCE）
     *     但保留在列表里也没关系 —— subscribeAllKeys() 会按车机能力过滤
     */
    public static final ButtonName[] ALL_SUBSCRIBE = {
            ButtonName.OK,
            ButtonName.PLAY_PAUSE,
            ButtonName.SEEKLEFT,
            ButtonName.SEEKRIGHT,
            ButtonName.PRESET_0,
            ButtonName.PRESET_1,
            ButtonName.PRESET_2,
            ButtonName.PRESET_3,
            ButtonName.PRESET_4,
            ButtonName.PRESET_5,
            ButtonName.PRESET_6,
            ButtonName.PRESET_7,
            ButtonName.PRESET_8,
            ButtonName.PRESET_9,
    };

    /** 可绑定的功能清单（给设置界面用） */
    public static final int[] BINDABLE = {
            CommandRouter.CMD_PREV,
            CommandRouter.CMD_NEXT,
            CommandRouter.CMD_PLAY_PAUSE,
            CommandRouter.CMD_NAV_ON,
            CommandRouter.CMD_NAV_OFF,
            CommandRouter.CMD_TIMER_UP,
            CommandRouter.CMD_TIMER_DOWN,
            CommandRouter.CMD_TIMER_PAUSE,
            CommandRouter.CMD_TIMER_RESUME,
            CommandRouter.CMD_TIMER_CLEAR,
    };

    public static String labelOf(int cmdId) {
        switch (cmdId) {
            case CommandRouter.CMD_PREV: return "上一首";
            case CommandRouter.CMD_NEXT: return "下一首";
            case CommandRouter.CMD_PLAY_PAUSE: return "播放/暂停";
            case CommandRouter.CMD_NAV_ON: return "开启导航模式";
            case CommandRouter.CMD_NAV_OFF: return "关闭导航模式";
            case CommandRouter.CMD_TIMER_UP: return "开始正计时";
            case CommandRouter.CMD_TIMER_DOWN: return "开始倒计时";
            case CommandRouter.CMD_TIMER_PAUSE: return "计时暂停";
            case CommandRouter.CMD_TIMER_RESUME: return "计时继续";
            case CommandRouter.CMD_TIMER_CLEAR: return "计时清零";
            default: return "未绑定";
        }
    }
}
