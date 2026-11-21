package com.xigaigai.syncglance.display;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.xigaigai.syncglance.R;

/**
 * 车机屏幕预览器 —— <b>只负责「把一帧画出来」</b>。
 *
 * <h3>边界</h3>
 * 它不知道歌名从哪来、歌词怎么切、导航数据怎么解析，也不认识
 * NowPlaying / Navigation / 歌词模型，更不做任何文本转写。
 * 它只接收 {@link ScreenFrame}：里面全是**算好的字符串** + 一条计时器**指令**。
 *
 * <h3>为什么计时器是指令而不是读数</h3>
 * 车机的 {@code SetMediaClockTimer} 是「App 下指令、车机自己走表」。
 * 所以这里也维护一台"车机计时器"：拿到 {@link ClockInstruction} 后
 * 按 {@code now - issuedAtMs} 自己推算秒数。
 * 它与「Show 节流（1 秒）」「歌词选句（时间轴）」是三条互不相干的时间线 ——
 * 预览里的秒数会照常走，即使 App 这会儿根本没在刷新屏幕。
 *
 * <h3>抽库</h3>
 * 本类 + {@link ScreenFrame} + {@link ClockInstruction} + 布局
 * 就是一个零依赖的预览器包；别的 AppLink 应用可以用 {@link #renderRaw} 直接接入。
 */
public class ScreenPreviewView {

    /** 一行能放的半角宽度（= 7 汉字 = 14 个英文字母），与 StateRenderer 一致 */
    private static final int LINE_UNITS = 14;

    /**
     * mediaTrack 那一格比正文窄得多：实车实测只能放 <b>8 个字符（4 个汉字）</b>。
     * 超出会把汉字切成半个，所以这里主动截断。
     */
    private static final int TRACK_UNITS = 8;

    /** 空软键格子的占位文本 */
    private static final String EMPTY_KEY = Glyphs.blankKey();

    /** 只用来推进「车机计时器」那一格；与 Show 的 1 秒节流无关 */
    private static final long CLOCK_TICK_MS = 400;

    private final TextView tvMode;
    private final TextView tvTitle1, tvClock1, tvS1L1, tvS1L2, tvS1L3, tvS1Timer;
    private final TextView tvTitle2, tvClock2, tvS2L1, tvS2L2, tvS2L3, tvS2Timer;
    private final TextView tvS1A, tvS1B, tvS1C, tvS1D;
    private final TextView tvS2A, tvS2B, tvS2C, tvS2D;

    private final Handler main = new Handler(Looper.getMainLooper());

    /** 当前这一帧 */
    private ScreenFrame frame = new ScreenFrame();

    /** 上一次画出的计时器秒数，用来判断要不要重画 */
    private long lastClockSecond = Long.MIN_VALUE;

    private final Runnable clockTicker = new Runnable() {
        @Override
        public void run() {
            try {
                clockTick();
            } catch (Exception ignored) { }
            main.postDelayed(this, CLOCK_TICK_MS);
        }
    };

    public ScreenPreviewView(View root) {
        tvMode = root.findViewById(R.id.tvPreviewMode);

        tvTitle1 = root.findViewById(R.id.tvTitle1);
        tvClock1 = root.findViewById(R.id.tvClock1);
        tvS1L1 = root.findViewById(R.id.tvS1L1);
        tvS1L2 = root.findViewById(R.id.tvS1L2);
        tvS1L3 = root.findViewById(R.id.tvS1L3);
        tvS1Timer = root.findViewById(R.id.tvS1Timer);
        tvS1A = root.findViewById(R.id.tvS1A);
        tvS1B = root.findViewById(R.id.tvS1B);
        tvS1C = root.findViewById(R.id.tvS1C);
        tvS1D = root.findViewById(R.id.tvS1D);

        tvTitle2 = root.findViewById(R.id.tvTitle2);
        tvClock2 = root.findViewById(R.id.tvClock2);
        tvS2L1 = root.findViewById(R.id.tvS2L1);
        tvS2L2 = root.findViewById(R.id.tvS2L2);
        tvS2L3 = root.findViewById(R.id.tvS2L3);
        tvS2Timer = root.findViewById(R.id.tvS2Timer);
        tvS2A = root.findViewById(R.id.tvS2A);
        tvS2B = root.findViewById(R.id.tvS2B);
        tvS2C = root.findViewById(R.id.tvS2C);
        tvS2D = root.findViewById(R.id.tvS2D);

        main.postDelayed(clockTicker, CLOCK_TICK_MS);
    }

    // ---------------- 对外 API ----------------

    /**
     * 显示一帧。
     *
     * 预览器不该被"多久刷新一次"绑架：调用方什么时候算出新帧就什么时候调，
     * 计时器那一格由本类自己按指令推进。
     */
    public void render(ScreenFrame f) {
        if (f == null) return;
        this.frame = f;
        lastClockSecond = Long.MIN_VALUE;   // 强制重画时钟格
        draw();
        // 保证计时器推进没被停掉（例如页面回到前台后第一次 render）
        main.removeCallbacks(clockTicker);
        main.postDelayed(clockTicker, CLOCK_TICK_MS);
    }

    /**
     * 给「别的 AppLink 应用」用的入口：不想构造 {@link ScreenFrame} 时直接喂字符串。
     *
     * @param softKeys 底栏我们的软键（第 4 格「更多…」由车机自带，不用传）
     * @param clock    计时器指令，可为 null
     */
    public void renderRaw(String f1, String f2, String f3, String f4,
                          String mediaTrack, ClockInstruction clock,
                          String[] softKeys, boolean connected) {
        ScreenFrame f = new ScreenFrame();
        f.f1 = nz(f1);
        f.f2 = nz(f2);
        f.f3 = nz(f3);
        f.f4 = nz(f4);
        f.mediaTrack = nz(mediaTrack);
        f.clock = clock == null ? ClockInstruction.none() : clock;
        f.softKeys = softKeys == null ? new String[0] : softKeys;
        f.connected = connected;
        f.title = connected ? "SyncPlay" : "SyncPlay (未连接)";
        f.stateLabel = "";
        render(f);
    }

    /** 停止内部计时器（Activity 销毁时调用，避免泄漏） */
    public void release() {
        main.removeCallbacks(clockTicker);
    }

    // ---------------- 绘制 ----------------

    /** 计时器那一格的推进：只有秒数变了才重画 */
    private void clockTick() {
        ClockInstruction c = frame.clock;
        long sec = c == null ? -1 : c.valueSeconds();
        if (sec != lastClockSecond) draw();
    }

    private void draw() {
        ScreenFrame f = frame;
        String nowTime = nowTime();

        tvTitle1.setText(f.title);
        tvTitle2.setText(f.title);
        tvClock1.setText(nowTime);
        tvClock2.setText(nowTime);
        tvMode.setText(f.stateLabel.isEmpty() ? "模式：—" : "模式：" + f.stateLabel);

        String clockText = f.clock == null ? "" : f.clock.displayText();

        // 第 1 屏
        tvS1L1.setText(truncateUnits(f.f1, LINE_UNITS));
        tvS1L2.setText(truncateUnits(f.f2, LINE_UNITS));
        tvS1L3.setText(truncateUnits(f.mediaTrack, TRACK_UNITS));
        tvS1Timer.setText(clockText);

        // 第 2 屏
        tvS2L1.setText(truncateUnits(f.f3, LINE_UNITS));
        tvS2L2.setText(truncateUnits(f.f4, LINE_UNITS));
        tvS2L3.setText(truncateUnits(f.mediaTrack, TRACK_UNITS));
        tvS2Timer.setText(clockText);

        renderSoftKeys(f.softKeys);

        ClockInstruction c = f.clock;
        lastClockSecond = c == null ? -1 : c.valueSeconds();
    }

    /**
     * 画底栏软键。
     *
     * 真实布局：底栏 4 格。
     *   · 我们发的软键（Show.softButtons）从左往右占位
     *   · 第 4 格固定是车机自带的「更多…」
     *   · 我们的软键占满 4 格时，「更多…」会被挤掉（按实车见到的样子画）
     *   · {@link ScreenFrame#highlightIndex} 指向的那格画成高亮（车机选中态）
     */
    private void renderSoftKeys(String[] keys) {
        final int used = 0xFFFFFFFF;      // 有内容的软键：白色
        final int empty = 0xFF5A7A9A;     // 空格子：暗蓝
        final int moreColor = 0xFFFFFFFF; // 「更多…」：车机自带
        final int highlight = 0xFFFFD24A; // 高亮（选中）态：仪表盘那种琥珀色

        final TextView[] s1 = {tvS1A, tvS1B, tvS1C, tvS1D};
        final TextView[] s2 = {tvS2A, tvS2B, tvS2C, tvS2D};

        int n = keys == null ? 0 : keys.length;
        int hl = frame == null ? -1 : frame.highlightIndex;

        for (int i = 0; i < 4; i++) {
            String text;
            int color;
            if (i < n) {
                text = keys[i];
                color = (i == hl) ? highlight : used;
            } else if (i == 3 && n < 4) {
                text = "更多…";
                color = moreColor;
            } else {
                text = EMPTY_KEY;
                color = empty;
            }
            s1[i].setText(text);
            s1[i].setTextColor(color);
            s2[i].setText(text);
            s2[i].setTextColor(color);
        }
    }

    // ---------------- 工具 ----------------

    /**
     * 按宽度截断，超出加 "..."。
     * 宽度口径与实车一致：汉字 2、ASCII 1。
     *
     * @param units 这一行能放多少个半角单位（正文 14，mediaTrack 只有 8）
     */
    private static String truncateUnits(String s, int units) {
        if (s == null || s.isEmpty()) return "";
        int width = 0;
        int i;
        for (i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int w = isWideChar(c) ? 2 : 1;
            if (width + w > units) break;
            width += w;
        }
        if (i >= s.length()) return s;
        return s.substring(0, i) + "...";
    }

    private static boolean isWideChar(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x3040 && c <= 0x30FF)
                || (c >= 0xFF00 && c <= 0xFF60);
    }

    private static String nowTime() {
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(new java.util.Date());
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
