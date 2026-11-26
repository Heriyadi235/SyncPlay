package com.xigaigai.syncglance.display;

import android.os.Handler;
import android.os.Looper;

import com.smartdevicelink.proxy.RPCMessage;
import com.smartdevicelink.proxy.RPCRequest;
import com.xigaigai.syncglance.util.Log2;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 时钟指令的<b>唯一发送队列</b>。
 *
 * <h3>为什么需要它</h3>
 * 时钟格在车机那边只有「一块表」，所有 {@code SetMediaClockTimer} 都在操作同一个东西。
 * 而「把那一格定在某个读数」需要两条指令严格有序：
 * <pre>
 *   ① COUNTUP(startTime)   起一块表
 *   ② PAUSE                停住它
 * </pre>
 * 只靠 {@code sendSequentialRPCs} 保证不了全局顺序 —— 那是「一次调用内」有序。
 * 两次独立调用（比如导航 ETA 的两步法正在等响应，用户又按了「暂停」）仍会交错：
 * <pre>
 *   COUNTUP(ETA) → COUNTUP(timer) → PAUSE → PAUSE
 * </pre>
 * 第一条 PAUSE 停的是计时器的表，第二条被车机 {@code IGNORE} —— 定格读数就错了。
 *
 * <h3>规则</h3>
 * <ul>
 *   <li><b>单 in-flight</b>：同一时刻只有一批在发，上一批发完（或超时）才发下一批。</li>
 *   <li><b>新批次覆盖旧批次</b>：排队中的批次直接丢弃 —— 时钟状态是"最新即真理"，
 *       补发一条已经过时的 ETA 没有意义。已经开始发送的两步法不会被拆开（必须发完）。</li>
 *   <li><b>超时兜底</b>：车机不回响应时（最多 {@value #ACK_TIMEOUT_MS} ms）强行推进，
 *       否则队列会永久卡死、那一格再也不更新。</li>
 * </ul>
 */
public final class ClockDispatcher {

    private static final String TAG = "ClockQ";

    /** 等待车机响应的上限；超时就往下走 */
    private static final long ACK_TIMEOUT_MS = 1500;

    /** 发送通道（由 {@code SyncPlayCore} 接到 {@code SdlService} 上） */
    public interface Transport {
        void send(RPCMessage msg, Runnable onDone);
    }

    private static final class Batch {
        final List<RPCMessage> msgs;
        final String reason;

        Batch(List<RPCMessage> msgs, String reason) {
            this.msgs = msgs;
            this.reason = reason;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Transport transport;

    /**
     * 时钟指令自己的 correlationID 段。
     *
     * <p>和 {@code SdlService} / {@code SyncPlayCore} 的号段错开，看日志时能一眼认出
     * 是「时钟队列」发出去的那条。</p>
     */
    private final AtomicInteger corr = new AtomicInteger(5000);

    /** 排队中的批次（只可能在 inFlight 时存在） */
    private Batch pending;
    /** 是否有批次正在发 */
    private boolean inFlight;

    public ClockDispatcher(Transport transport) {
        this.transport = transport;
    }

    /**
     * 提交一批要按顺序发送的时钟指令。
     *
     * @param msgs   指令（1~2 条；2 条就是两步法）
     * @param reason 进日志用的人话，例如「计时器暂停定格 03:21」
     */
    public void submit(List<? extends RPCMessage> msgs, String reason) {
        if (msgs == null || msgs.isEmpty()) return;

        final List<RPCMessage> copy = new ArrayList<>(msgs.size());
        for (RPCMessage m : msgs) {
            if (m instanceof RPCRequest) {
                RPCRequest req = (RPCRequest) m;
                if (req.getCorrelationID() == null) {
                    req.setCorrelationID(corr.getAndIncrement());
                }
            }
            copy.add(m);
        }

        main.post(() -> {
            if (pending != null) {
                Log2.i(TAG, "覆盖排队中的旧批次: " + pending.reason);
            }
            pending = new Batch(copy, reason);
            if (!inFlight) startNext();
        });
    }

    /** 队列里还有东西没发完吗（诊断用） */
    public boolean isBusy() {
        return inFlight || pending != null;
    }

    // ---------------- 内部 ----------------

    private void startNext() {
        Batch b = pending;
        pending = null;
        if (b == null) {
            inFlight = false;
            return;
        }
        inFlight = true;
        if (b.msgs.size() > 1) {
            Log2.i(TAG, "时钟批次 " + b.msgs.size() + " 条（按序）: " + b.reason);
        } else {
            Log2.i(TAG, "时钟批次: " + b.reason);
        }
        sendOne(b, 0);
    }

    private void sendOne(Batch b, int idx) {
        if (idx >= b.msgs.size()) {
            inFlight = false;
            startNext();
            return;
        }

        final AtomicBoolean done = new AtomicBoolean(false);
        final Runnable[] cont = new Runnable[1];
        final Runnable[] timeout = new Runnable[1];

        cont[0] = () -> {
            if (!done.compareAndSet(false, true)) return;
            main.removeCallbacks(timeout[0]);
            sendOne(b, idx + 1);
        };
        timeout[0] = () -> {
            Log2.w(TAG, "等响应超时（" + ACK_TIMEOUT_MS + "ms），跳过: "
                    + b.msgs.get(idx).getFunctionName());
            main.post(cont[0]);
        };

        main.postDelayed(timeout[0], ACK_TIMEOUT_MS);
        transport.send(b.msgs.get(idx), () -> main.post(cont[0]));
    }
}
