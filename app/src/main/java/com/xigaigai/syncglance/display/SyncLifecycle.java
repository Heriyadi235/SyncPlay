package com.xigaigai.syncglance.display;

import com.smartdevicelink.proxy.rpc.enums.AudioStreamingState;
import com.smartdevicelink.proxy.rpc.enums.HMILevel;
import com.smartdevicelink.proxy.rpc.enums.SystemContext;

/**
 * SYNC 连接生命周期状态机。
 *
 * 依据《AppLink Android Developers Guide ver1.6.0》第 3.3.1 / 3.4 节：
 *
 * ┌─────────────────────────────────────────────────────────┐
 * │  ① SYNC 查询可用 app  →  OnHMIStatus(NONE)               │
 * │  ② 用户在车机选中 app  →  OnHMIStatus(FULL, firstRun)    │
 * │  ③ 别的 app 抢到前台   →  OnHMIStatus(LIMITED/BACKGROUND)│
 * │  ④ 用户退出 app        →  OnHMIStatus(NONE)              │
 * │  ⑤ 连接断开            →  OnProxyClosed                  │
 * └─────────────────────────────────────────────────────────┘
 *
 * 关键规则（文档明确要求，代码必须遵守）：
 *   1. **只有 HMI_FULL 才能发 RPC** —— 其他等级发出去会抛
 *      `SdlException: There is no valid connection to SDL`
 *   2. **BACKGROUND / NONE 时，FULL/LIMITED 期间发起的操作会被车机自动终止**
 *      （进行中的 interaction、Speak 等）
 *   3. **OnProxyClosed 后，车机上所有 menu command / choice set /
 *      button subscription 都被删掉了，重连必须重建**
 *   4. `firstRun=true` 表示这是本次连接的第一次进入，需要做完整初始化
 */
public class SyncLifecycle {

    /**
     * 我们的状态，比 HMILevel 多两个：
     *   DISCONNECTED —— proxy 还没建或已 dispose
     *   CONNECTING   —— proxy 建好了，但车机还没选我们
     */
    public enum State {
        /** proxy 未建立 / 已释放 */
        DISCONNECTED,
        /** proxy 已建立，等车机选中（还没收到任何 OnHMIStatus） */
        CONNECTING,
        /** NONE —— 注册了但什么都不能做 */
        NONE,
        /** BACKGROUND —— 失焦，不能交互 */
        BACKGROUND,
        /** LIMITED —— 部分可用（仅导航型车机） */
        LIMITED,
        /** FULL —— 完全可用，唯一能发 RPC 的等级 */
        FULL
    }

    private volatile State state = State.DISCONNECTED;
    private volatile boolean firstRun = false;
    private volatile SystemContext context = SystemContext.SYSCTXT_MAIN;
    private volatile AudioStreamingState audio = AudioStreamingState.NOT_AUDIBLE;
    /** 本次连接是否已经做过初始化（菜单、订阅） */
    private volatile boolean initializedForThisConnection = false;

    public State getState() { return state; }
    public boolean isFirstRun() { return firstRun; }
    public SystemContext getContext() { return context; }
    public AudioStreamingState getAudioState() { return audio; }

    /** ★ 唯一能发 RPC 的状态 */
    public boolean canSendRpc() { return state == State.FULL; }

    /** 能显示内容（FULL / LIMITED 车机都显示） */
    public boolean canDisplay() {
        return state == State.FULL || state == State.LIMITED;
    }

    /** 用户正在菜单里操作 —— 此时不应打断 */
    public boolean inMenuOrVr() {
        return context == SystemContext.SYSCTXT_MENU
                || context == SystemContext.SYSCTXT_VRSESSION;
    }

    /** 是否已建立连接（含未 FULL） */
    public boolean isConnected() {
        return state != State.DISCONNECTED;
    }

    // ---------------- 状态迁移 ----------------

    /** proxy 刚建好 */
    public void onProxyCreated() {
        state = State.CONNECTING;
        firstRun = false;
        context = SystemContext.SYSCTXT_MAIN;
        initializedForThisConnection = false;
    }

    /** 连接断开 / dispose */
    public void onProxyClosed() {
        state = State.DISCONNECTED;
        firstRun = false;
        context = SystemContext.SYSCTXT_MAIN;
        initializedForThisConnection = false;
    }

    /**
     * 收到 OnHMIStatus。
     * @return 迁移前的状态，方便调用方判断"是不是刚变成 FULL"
     */
    public State onHmiStatus(HMILevel level, Boolean firstRunFlag,
                             SystemContext ctx, AudioStreamingState audioState) {
        State prev = state;

        if (level != null) state = mapLevel(level);
        if (firstRunFlag != null) firstRun = firstRunFlag.booleanValue();
        if (ctx != null) context = ctx;
        if (audioState != null) audio = audioState;

        return prev;
    }

    /** 是不是刚刚从非 FULL 变成 FULL */
    public boolean justBecameFull(State prev) {
        return prev != State.FULL && state == State.FULL;
    }

    /** 是不是刚失焦（FULL → 其他） */
    public boolean justLostFocus(State prev) {
        return prev == State.FULL && state != State.FULL;
    }

    public boolean isInitialized() { return initializedForThisConnection; }
    public void markInitialized() { initializedForThisConnection = true; }

    private static State mapLevel(HMILevel l) {
        if (l == null) return State.NONE;
        switch (l) {
            case HMI_FULL: return State.FULL;
            case HMI_LIMITED: return State.LIMITED;
            case HMI_BACKGROUND: return State.BACKGROUND;
            case HMI_NONE:
            default: return State.NONE;
        }
    }

    /** 给界面显示用的中文描述 */
    public String describe() {
        switch (state) {
            case DISCONNECTED: return "未连接";
            case CONNECTING: return "已建立，等待车机选中";
            case NONE: return "已连接(NONE)";
            case BACKGROUND: return "已连接(BACKGROUND)";
            case LIMITED: return "已连接(LIMITED)";
            case FULL: return "已连接(FULL)";
            default: return "?";
        }
    }
}
