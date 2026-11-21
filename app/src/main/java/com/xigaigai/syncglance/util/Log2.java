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
    /**
     * 原始 JSON 默认开启。
     *
     * 排查车机问题时，"到底发了什么/收到了什么"比什么都重要，
     * 而车机上的行为往往无法复现，事后开关就晚了。
     */
    private static volatile boolean rawEnabled = true;

    // ---------------- 落盘 ----------------

    /*
     * 为什么要落盘：
     *   内存日志一进程死就没了，而连接类问题（开机后 Router 起得慢、重连时
     *   SdlService 没起来）恰恰发生在**进程重启前后** —— 最需要的证据刚好被切断。
     *
     * 为什么不是每条都写文件：
     *   刚做完功耗优化，不能又搞出每秒几次 IO。
     *   所以是「内存缓冲 + 攒够 8KB 或过了 5 秒才落一次」。
     */

    /** 待落盘的缓冲 */
    private static final StringBuilder pending = new StringBuilder();
    /** 攒多久落一次 */
    private static final long FLUSH_INTERVAL_MS = 5_000;
    /** 攒多少字节落一次 */
    private static final int FLUSH_BYTES = 8 * 1024;
    /** 文件上限，超了砍掉前半段 */
    private static final long MAX_FILE_BYTES = 200 * 1024;

    private static java.io.File logFile;
    private static long lastFlushAt = 0;

    private Log2() { }

    /** 在 Application 或首个 Service 里初始化一次 */
    public static void init(Context ctx) {
        if (appContext == null) appContext = ctx.getApplicationContext();
        if (logFile == null) {
            try {
                // app 专属外部目录：免权限、用户能用文件管理器拿到、不会被系统随意清
                java.io.File dir = appContext.getExternalFilesDir(null);
                if (dir == null) dir = appContext.getFilesDir();
                if (dir != null) logFile = new java.io.File(dir, "syncplay.log");
            } catch (Exception ignored) { }
        }
    }

    /** 落盘文件的绝对路径（信息页可以显示给用户，方便取文件） */
    public static String logFilePath() {
        return logFile == null ? "" : logFile.getAbsolutePath();
    }

    /** 把 buffer 里的内容追加到文件（攒够才落） */
    private static void appendToFile(String line) {
        if (logFile == null) return;

        String batch;
        synchronized (Log2.class) {
            pending.append(line).append('\n');
            long now = System.currentTimeMillis();
            if (pending.length() < FLUSH_BYTES && now - lastFlushAt < FLUSH_INTERVAL_MS) return;
            // 到点或攒够了：把这批取走，锁内不做 IO
            batch = pending.toString();
            pending.setLength(0);
            lastFlushAt = now;
        }
        writeBatch(batch);
    }

    /** 立刻把缓冲写下去（进程收尾时用） */
    public static void flushNow() {
        String batch;
        synchronized (Log2.class) {
            if (pending.length() == 0) return;
            batch = pending.toString();
            pending.setLength(0);
            lastFlushAt = System.currentTimeMillis();
        }
        writeBatch(batch);
    }

    private static void writeBatch(String batch) {
        if (logFile == null || batch == null || batch.isEmpty()) return;
        try {
            if (logFile.length() + batch.length() > MAX_FILE_BYTES) trimFile();
            try (java.io.FileOutputStream out =
                         new java.io.FileOutputStream(logFile, true)) {
                out.write(batch.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }

    /** 文件超限时砍掉前半段，避免无限增长 */
    private static void trimFile() {
        java.io.FileInputStream in = null;
        try {
            long len = logFile.length();
            if (len <= 0) return;
            long skip = len / 2;
            in = new java.io.FileInputStream(logFile);
            long skipped = 0;
            while (skipped < skip) {
                long s = in.skip(skip - skipped);
                if (s <= 0) break;
                skipped += s;
            }
            byte[] tail = new byte[(int) (len - skipped)];
            int read = 0;
            while (read < tail.length) {
                int n = in.read(tail, read, tail.length - read);
                if (n <= 0) break;
                read += n;
            }
            try (java.io.FileOutputStream out =
                         new java.io.FileOutputStream(logFile, false)) {
                out.write(tail, 0, read);
            }
        } catch (Exception ignored) {
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }
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
        /*
         * ★ 异常必须连堆栈一起落盘（2026-11-21 修）。
         *
         *   以前这里只写 t.getMessage()，结果 18:33 那次
         *   ReceiverCallNotAllowedException 在落盘日志里只剩一行
         *   「Error receiving broadcast ... : null」——
         *   getMessage() 为 null，堆栈全丢，只能靠 logcat 的 crash 缓冲救回来。
         *   而车里根本没有 adb。所以这里把堆栈也写进文件。
         */
        String trace = stackTraceOf(t);
        emit(tag, "✗ " + msg + " : " + (t == null ? "" : t.getMessage())
                + (trace.isEmpty() ? "" : "\n" + trace));
    }

    /** 取异常堆栈文本（最多 40 行，防止一次崩溃把 200KB 的日志文件撑爆） */
    private static String stackTraceOf(Throwable t) {
        if (t == null) return "";
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            t.printStackTrace(pw);
            pw.flush();
            String[] lines = sw.toString().replace("\r", "").split("\n");
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (String line : lines) {
                if (n >= MAX_TRACE_LINES) {
                    sb.append("    ... 省略 ").append(lines.length - MAX_TRACE_LINES).append(" 行");
                    break;
                }
                if (n > 0) sb.append('\n');
                sb.append("    ").append(line);
                n++;
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static final int MAX_TRACE_LINES = 40;

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
        // ★ 以前这里会 ctx.sendBroadcast(ACTION_LOG)，但全项目没有任何接收者
        //   （信息页是直接读内存的），等于每条日志白发一次跨进程 IPC。
        //   Show 每秒一次 + 各种日志，白发的广播非常可观 —— 已移除。
        // 落盘：攒批写，不是每条都 IO
        appendToFile(full.trim());
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
        // 只进内存环形缓冲；不再发无人接收的广播（见 emit 的注释）
        synchronized (Log2.class) {
            uiLines.addLast(TS_UI.format(new Date()) + "    " + direction + " " + preview);
            while (uiLines.size() > MAX_UI_LINES) uiLines.removeFirst();
        }
        // 原始 JSON 也落盘（排查时最有用的一类）
        appendToFile(TS_FULL.format(new Date()) + "  " + direction + " " + one);
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
            pending.setLength(0);
        }
        // 文件也一起清掉，否则"清空"只是清了半个
        try {
            if (logFile != null && logFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                logFile.delete();
            }
        } catch (Exception ignored) { }
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
        String fileInfo = "";
        try {
            if (logFile != null && logFile.exists()) {
                fileInfo = "，落盘 " + (logFile.length() / 1024) + "KB";
            } else {
                fileInfo = "，落盘未启用";
            }
        } catch (Exception ignored) { }
        return "界面 " + uiCount + " 行 / 完整日志 " + fullLen + " 字符"
                + fileInfo
                + "，原始JSON " + (rawEnabled ? "开" : "关");
    }
}
