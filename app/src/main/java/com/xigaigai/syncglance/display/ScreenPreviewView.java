package com.xigaigai.syncglance.display;

import android.view.View;
import android.widget.TextView;

import com.xigaigai.syncglance.R;
import com.xigaigai.syncglance.lyric.Lrc;
import com.xigaigai.syncglance.model.Navigation;
import com.xigaigai.syncglance.model.NowPlaying;
import com.xigaigai.syncglance.util.Kana;

import java.util.List;
import java.util.Locale;

/**
 * 车机屏幕预览 —— 两屏并列。
 *
 * 取值逻辑和 StateRenderer.doShow() 完全一致，保证预览与实车行为对齐：
 *   - 计时器在跑 → track 显示计时器时间（本地模拟走表）
 *   - 导航页 → track 显示预计到达时间
 *   - 歌曲页 → track 显示播放进度
 */
public class ScreenPreviewView {

    /**
     * 一行能放的半角宽度单位。
     *
     * 车机一行 7 个汉字 = 14 半角；字母占半格，所以 14 个字母也刚好一行。
     * 与 {@code StateRenderer.LYRIC_LINE_UNITS} 保持一致。
     */
    private static final int LINE_UNITS = 14;

    private final TextView tvMode;
    private final TextView tvTitle1, tvClock1, tvS1L1, tvS1L2, tvS1L3, tvS1Timer;
    private final TextView tvTitle2, tvClock2, tvS2L1, tvS2L2, tvS2L3, tvS2Timer;
    private final TextView tvS1A, tvS1B, tvS1C, tvS1D;
    private final TextView tvS2A, tvS2B, tvS2C, tvS2D;

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
    }

    /** 空软键格子的占位文本 */
    private static final String EMPTY_KEY = Glyphs.blankKey();

    /** 刷新两屏内容 */
    public void update(StateRenderer.State state,
                       NowPlaying song,
                       List<Lrc.Line> lyrics,
                       Navigation nav,
                       boolean timerRunning,
                       boolean connected,
                       long timerStartMillis,
                       int timerH, int timerM, int timerS,
                       boolean timerCountdown,
                       String[] softKeys,
                       StateRenderer.TrackKind trackKind,
                       boolean timerActive) {

        String nowTime = nowTime();
        String title = connected ? "SyncPlay" : "SyncPlay (未连接)";

        tvTitle1.setText(title);
        tvTitle2.setText(title);
        tvClock1.setText(nowTime);
        tvClock2.setText(nowTime);
        tvMode.setText("模式：" + stateLabel(state));

        // ---- 计时器时间（本地模拟走表）----
        String timerText = "";
        if (timerRunning && timerStartMillis > 0) {
            long elapsed = (System.currentTimeMillis() - timerStartMillis) / 1000;
            if (elapsed < 0) elapsed = 0;
            long shown;
            if (timerCountdown) {
                long total = timerH * 3600L + timerM * 60L + timerS;
                shown = Math.max(0, total - elapsed);
            } else {
                shown = elapsed;
            }
            timerText = fmtClock(shown);
        }

        // ---- 两屏的三行 ----
        String p1l1, p1l2, p1l3, p2l1, p2l2, p2l3;
        // 第 3 行：左边 mediaTrack 文字
        String track1, track2;
        // 第 3 行：右边计时器文字
        String clockText = "";

        switch (state) {
            case A_EMPTY:
                p1l1 = greeting();
                p1l2 = "西改改";
                p1l3 = "";
                p2l1 = ""; p2l2 = ""; p2l3 = "";
                track1 = "";
                track2 = "";
                // 空闲模式也显示计时器（如果启用了）
                clockText = timerRunning ? timerText : "";
                break;

            case B_NAV_ONLY:
                p1l1 = nz(nav == null ? null : nav.instruction);
                p1l2 = nz(nav == null ? null : nav.road);
                p1l3 = "";
                p2l1 = nz(nav == null ? null : nav.etaText());
                p2l2 = nz(nav == null ? null : nav.remainDistance);
                p2l3 = "";
                // mediaTrack：剩余距离
                track1 = nz(nav == null ? null : nav.remainDistance);
                track2 = track1;
                // 右侧：ETA 时刻
                clockText = timerRunning ? timerText : navTime(nav);
                break;

            case C_MUSIC_ONLY: {
                p1l1 = nz(song == null ? null : song.title);
                p1l2 = nz(song == null ? null : song.artist);
                p1l3 = "";

                // 第2屏：两行全给歌词；没歌词时第一行提示
                //   ★ 直接复用 StateRenderer 的实现，保证预览与实车完全一致
                if (song != null && lyrics != null && !lyrics.isEmpty()) {
                    String[] fl = StateRenderer.computeLyricLines(lyrics, song.positionMs);
                    p2l1 = fl[0];
                    p2l2 = fl[1];
                } else {
                    p2l1 = "无歌词信息";
                    p2l2 = "";
                }
                p2l3 = "";
                // mediaTrack：只显示歌名
                track1 = nz(song == null ? null : song.title);
                track2 = track1;
                // 右侧：播放进度
                clockText = timerRunning ? timerText : playTime(song);
                break;
            }

            case D_BOTH:
            default: {
                p1l1 = nz(nav == null ? null : nav.instruction);
                p1l2 = nz(nav == null ? null : nav.road);
                p1l3 = "";

                // 第2屏：两行全给歌词；没歌词时第一行提示
                //   ★ 直接复用 StateRenderer 的实现，保证预览与实车完全一致
                if (song != null && lyrics != null && !lyrics.isEmpty()) {
                    String[] fl = StateRenderer.computeLyricLines(lyrics, song.positionMs);
                    p2l1 = fl[0];
                    p2l2 = fl[1];
                } else {
                    p2l1 = "无歌词信息";
                    p2l2 = "";
                }
                p2l3 = "";

                // mediaTrack：始终显示导航剩余距离
                track1 = nz(nav == null ? null : nav.remainDistance);
                track2 = track1;
                // 右侧：ETA 时刻
                clockText = timerRunning ? timerText : navTime(nav);
                break;
            }
        }

        // ★ track 前加状态指示符（跟着内容类型走；track 为空时不加）
        track1 = previewPrefix(trackKind, track1, timerRunning, timerActive, nav, song) + track1;
        track2 = previewPrefix(trackKind, track2, timerRunning, timerActive, nav, song) + track2;

        // 第 1 屏
        tvS1L1.setText(truncate(Kana.romajiSpaced(p1l1)));
        tvS1L2.setText(truncate(Kana.romajiSpaced(p1l2)));
        // 第 3 行左侧 = mediaTrack
        tvS1L3.setText(truncate(Kana.romajiSpaced(track1)));
        // 第 3 行右侧 = 计时器 / ETA
        tvS1Timer.setText(clockText);

        // 第 2 屏
        tvS2L1.setText(truncate(Kana.romajiSpaced(p2l1)));
        tvS2L2.setText(truncate(Kana.romajiSpaced(p2l2)));
        tvS2L3.setText(truncate(Kana.romajiSpaced(track2)));
        tvS2Timer.setText(clockText);

        // ---- 软键区 ----
        //   真实布局：底栏 4 格。
        //   · 我们发的软键（Show.softButtons）从左往右占位
        //   · 第 4 格固定是车机自带的「更多…」，点进去是 AddCommand/AddSubMenu 的菜单页
        //   · 我们的软键占满/超过 3 个时，「更多…」会被挤掉（待实车验证）
        renderSoftKeys(softKeys);
    }

    /**
     * 画底栏软键。
     *
     * @param keys 我们下发的软键文本列表（第 1 个是播放/暂停，后面是测试键）
     */
    private void renderSoftKeys(String[] keys) {
        final int used = 0xFFFFFFFF;      // 有内容的软键：白色
        final int empty = 0xFF5A7A9A;     // 空格子：暗蓝，表示不可点
        final int moreColor = 0xFFFFFFFF; // 「更多…」：车机自带

        final TextView[] s1 = {tvS1A, tvS1B, tvS1C, tvS1D};
        final TextView[] s2 = {tvS2A, tvS2B, tvS2C, tvS2D};

        int n = keys == null ? 0 : keys.length;

        for (int i = 0; i < 4; i++) {
            String text;
            int color;
            if (i < n) {
                text = keys[i];
                color = used;
            } else if (i == 3 && n < 4) {
                // 第 4 格：我们没用满时，车机补一个「更多…」
                text = "更多…";
                color = moreColor;
            } else if (n >= 4) {
                // 我们占了 4 格及以上：第 4 格被我们的软键占用，
                // 「更多…」是否还在，需要实车验证 —— 预览里按"被挤掉"画
                text = EMPTY_KEY;
                color = empty;
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
     * track 状态指示符，逻辑与 StateRenderer.statusPrefix 对应。
     *
     * ★ 跟着「track 里装的是什么」判断，不跟 ABCD 状态判断 ——
     *   这样以后加新布局也不用改这里。
     */
    private static String previewPrefix(StateRenderer.TrackKind kind,
                                        String trackText,
                                        boolean timerRunning, boolean timerActive,
                                        com.xigaigai.syncglance.model.Navigation nav,
                                        com.xigaigai.syncglance.model.NowPlaying song) {
        // 计时器最优先：哪怕 track 为空也要显示沙漏（U+29D7 ⧗）
        if (timerActive) return Glyphs.clock();

        // track 为空 → 不加内容类指示符
        if (trackText == null || trackText.isEmpty()) return "";

        if (kind == StateRenderer.TrackKind.NAV) {
            String ins = nav == null || nav.instruction == null ? "" : nav.instruction;
            boolean left = ins.contains("\u5de6");
            boolean right = ins.contains("\u53f3");
            boolean rear = ins.contains("\u540e")
                    || ins.contains("\u6389\u5934") || ins.contains("\u8c03\u5934");
            if (rear && left) return Glyphs.arrow("\u2199");
            if (rear && right) return Glyphs.arrow("\u2198");
            if (rear) return Glyphs.arrow("\u2193");
            if (left && ins.contains("\u524d")) return Glyphs.arrow("\u2196");
            if (right && ins.contains("\u524d")) return Glyphs.arrow("\u2197");
            if (left) return Glyphs.arrow("\u2190");
            if (right) return Glyphs.arrow("\u2192");
            return Glyphs.arrow("\u2191");
        }
        if (kind == StateRenderer.TrackKind.SONG) {
            boolean playing = song != null && song.playing;
            return Glyphs.play(playing);
        }
        return "";                                // NONE
    }

    /** 按时间段返回问候语 */
    private static String greeting() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (hour >= 5 && hour < 9) return "早上好";
        if (hour >= 9 && hour < 12) return "上午好";
        if (hour >= 12 && hour < 18) return "下午好";
        return "晚上好";
    }

    /** "歌名 - 歌手" 格式 */
    private static String songLabel(NowPlaying song) {
        if (song == null || !song.hasData()) return "";
        StringBuilder sb = new StringBuilder();
        if (song.title != null && !song.title.trim().isEmpty()) sb.append(song.title);
        if (song.artist != null && !song.artist.trim().isEmpty()) {
            if (sb.length() > 0) sb.append(" - ");
            sb.append(song.artist);
        }
        return sb.toString();
    }

    /** 导航页的 track：预计到达时间（每 3 分钟才变） */
    private static String navTime(Navigation nav) {
        if (nav == null) return "";
        String clock = nav.etaClock();
        return clock == null ? "" : clock;
    }

    /**
     * 按一行的宽度截断，超出加 "..."。
     *
     * 宽度口径与实车一致：汉字 2、ASCII 1，一行上限 {@link #LINE_UNITS}。
     */
    private static String truncate(String s) {
        if (s == null || s.isEmpty()) return "";
        int width = 0;
        int i;
        for (i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int w = isWideChar(c) ? 2 : 1;
            if (width + w > LINE_UNITS) break;
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

    private static String stateLabel(StateRenderer.State st) {
        switch (st) {
            case A_EMPTY: return "空闲";
            case B_NAV_ONLY: return "仅导航";
            case C_MUSIC_ONLY: return "仅歌曲";
            case D_BOTH: return "导航 + 歌曲";
            default: return "未知";
        }
    }

    private static String playTime(NowPlaying song) {
        if (song == null || !song.hasData()) return "";
        long pos = song.positionMs;
        if (pos <= 0) return "";
        return fmtClock(pos / 1000);
    }

    /** 秒数 → mm:ss 或 h:mm:ss */
    private static String fmtClock(long totalSec) {
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private static String nowTime() {
        return new java.text.SimpleDateFormat("HH:mm", Locale.US)
                .format(new java.util.Date());
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
