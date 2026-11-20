package com.xigaigai.syncglance.display;

/**
 * 车机能渲染的「装饰字符」总开关。
 *
 * 2026-11-20 实车确认：SYNC 1.1 / MFD4 的点阵字库**不认识任何一个**我们用的指示符 ——
 * ⧗ (U+29D7)、▶ (U+25B6)、⏸ (U+23F8)、八方向箭头 (U+2190~U+2199)
 * 在车机上全部渲染成一个「·」。
 *
 * 所以先用开关整体关掉。等「字符测试」把车机真正认识的字符试出来，再按需打开。
 *
 * 关掉时全部退回到汉字或空格兜底，保证任何情况下都不会在屏幕上出现圆点或方框。
 */
public final class Glyphs {

    /**
     * 总开关。
     *
     * 找到车机能显示的字符后，把这里改 true，再去试哪些具体字符要保留。
     */
    public static final boolean ENABLED = false;

    private Glyphs() {}

    /** 计时器激活时挂在 mediaTrack 前面的前缀 */
    public static String clock() {
        return ENABLED ? "\u29d7" : "";              // ⧗
    }

    /** 播放/暂停前缀（mediaTrack 前面那个小符号） */
    public static String play(boolean playing) {
        if (!ENABLED) return "";
        return playing ? "\u25b6" : "\u23f8";        // ▶ / ⏸
    }

    /**
     * 软键第 1 格的按钮文字。
     *
     * 这一格必须有内容，否则按钮会变成空白 —— 用户根本不知道该点哪。
     * 所以关掉符号时退回汉字「播放 / 暂停」。
     */
    public static String playKey(boolean playing) {
        if (ENABLED) return playing ? "\u23f8" : "\u25b6";
        return playing ? "暂停" : "播放";
    }

    /** 方位箭头：关掉时返回空串，让调用方自己决定要不要留空格 */
    public static String arrow(String ch) {
        return ENABLED ? ch : "";
    }

    /** 空软键占位符。em dash 在车机上也是圆点，退回空格 */
    public static String blankKey() {
        return ENABLED ? "\u2014" : " ";
    }
}
