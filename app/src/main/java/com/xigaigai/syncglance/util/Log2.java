package com.xigaigai.syncglance.util;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * 全局日志中枢。
 *
 * 特点：
 *   1. 同时保留两份——界面用的（秒级时间戳）和导出用的（毫秒时间戳，含原始 JSON）
 *   2. 通过广播推给 Activity，UI 和后台服务解耦
 *   3. 有行数上限，防止内存爆掉
 *   4. 支持"原始 JSON"开关，打开后把收发完整包都记下来
 *
 * 用法：Log2.i("TAG", "message")
 *       Log2.raw("→", json)
 */
public final class Log2 {

    public static final String ACTION_LOG = "com.xigaigai.syncglance.LOG";

    /** 界面日志最多保留多少行 */
    private static final int MAX_UI_LINES = 800;
    /** 完日志最多保留多少行 */
    private static final int MAX_FULL_LINES = 6000;

    private static final SimpleDateFormat TS_FULL =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat TS_UI =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    private static final Deque<String> uiLines = new ArrayDeque<>();
    private static final StringBuilder fullLog = new StringBuilder();

    private static Context appContext;
    private static volatile boolean rawEnabled = false;

    private Log2() { }

    /** 在 Application 或首个 Service 里初始化一次 */
    public static void init(Context ctx) {
        if (appContext == null) appContext = ctx.getApplicationContext();
    }

    public static void setRawEnabled(boolean on) {
        rawEnabled = on;
        i("Config", "原始JSON记录：" + (on ? "开" : "关"));
    }

    public static boolean isRawEnabled() { return rawEnabled; }

    // ---------------- 正常日志 ----------------

    public static void i(String tag, String msg) {
        Log.i("SyncGlance", "[" + tag + "] " + msg);
        emit(tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w("SyncGlance", "[" + tag + "] " + msg);
        emit(tag, "⚠ " + msg);
    }

    public static void e(String tag, String msg) {
        Log.e("SyncGlance", "[" + tag + "] " + msg);
        emit(tag, "✗ " + msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        Log.e("SyncGlance", "[" + tag + "] " + msg, t);
        emit(tag, "✗ " + msg + " : " + (t == null ? "" : t.getMessage()));
    }

    private static void emit(String tag, String msg) {
        final String ui = TS_UI.format(new Date()) + "  " + msg;
        final String full = TS_FULL.format(new Date()) + "  [" + tag + "] " + msg;

        synchronized (Log2.class) {
            uiLines.addLast(ui);
            while (uiLines.size() > MAX_UI_LINES) uiLines.removeFirst();

            fullLog.append(full).append('\n');
            if (fullLog.length() > MAX_FULL_LINES * 90) {
                // 砍掉前 1/4
                int cut = fullLog.length() / 4;
                fullLog.delete(0, cut);
            }
        }
        broadcast(ui);
    }

    // ---------------- 原始 JSON ----------------

    /**
     * 记录原始收发内容。只有开关打开时才输出。
     * @param direction "→" 发出去 / "←" 收进来
     */
    public static void raw(String direction, String json) {
        if (!rawEnabled || json == null) return;
        String one = json.replace("\r", "").replace("\n", " ");

        synchronized (Log2.class) {
            fullLog.append(TS_FULL.format(new Date()))
                   .append("  ").append(direction).append(' ')
                   .append(one).append('\n');
        }
        String preview = one.length() <= 260 ? one : one.substring(0, 260) + "…";
        broadcast(TS_UI.format(new Date()) + "    " + direction + " " + preview);
    }

    /**
     * 直接把 RPCMessage 序列化后记录，异常在这里统一吃掉。
     * 这样每个回调只要一行 `Log2.rawMsg("←", r)` 就够了。
     */
    public static void rawMsg(String direction, Object rpcMessage) {
        if (!rawEnabled || rpcMessage == null) return;
        if (rpcMessage instanceof com.smartdevicelink.proxy.RPCMessage) {
            try {
                org.json.JSONObject o =
                        ((com.smartdevicelink.proxy.RPCMessage) rpcMessage).serializeJSON();
                raw(direction, o == null ? null : o.toString());
            } catch (Exception e) {
                raw(direction, "（序列化失败: " + e.getMessage() + "）");
            }
        } else {
            raw(direction, String.valueOf(rpcMessage));
        }
    }

    private static void broadcast(String line) {
        Context ctx = appContext;
        if (ctx == null) return;
        try {
            Intent i = new Intent(ACTION_LOG);
            i.setPackage(ctx.getPackageName());
            i.putExtra("msg", line);
            ctx.sendBroadcast(i);
        } catch (Exception ignored) { }
    }

    // ---------------- 导出 ----------------

    /** 完整日志（毫秒时间戳 + 原始 JSON），用于长按复制/分享 */
    public static String getFullLog() {
        synchronized (Log2.class) {
            return fullLog.toString();
        }
    }

    /** 界面用的日志（没初始化广播时也能拿到） */
    public static String getUiLog() {
        StringBuilder sb = new StringBuilder();
        synchronized (Log2.class) {
            for (String l : uiLines) sb.append(l).append('\n');
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (Log2.class) {
            uiLines.clear();
            fullLog.setLength(0);
        }
    }

    /**
     * 导出统计——告诉用户这份日志有多完整。
     */
    public static String stats() {
        int uiCount, fullLen;
        synchronized (Log2.class) {
            uiCount = uiLines.size();
            fullLen = fullLog.length();
        }
        return "界面 " + uiCount + " 行 / 完整日志 " + fullLen + " 字符"
                + "，原始JSON " + (rawEnabled ? "开" : "关");
    }
}
