package com.xigaigai.syncglance.lyric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC 歌词解析。
 *
 * 支持：
 *   [mm:ss.xx] 文本
 *   [mm:ss.xxx] 文本
 *   [mm:ss] 文本
 *   一行多个时间标签：[00:10.00][00:40.00] 副歌
 */
public final class Lrc {

    private static final Pattern TIME_TAG =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    public static class Line {
        public final long timeMs;
        public final String text;

        Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }

        @Override
        public String toString() { return "[" + timeMs + "]" + text; }
    }

    private Lrc() { }

    /**
     * 解析 LRC 文本。
     * @return 按时间升序排列的行；解析不出来返回空列表
     */
    public static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<>();
        if (lrc == null || lrc.trim().isEmpty()) return out;

        for (String rawLine : lrc.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            Matcher m = TIME_TAG.matcher(line);
            List<Long> times = new ArrayList<>();
            int lastEnd = 0;

            while (m.find()) {
                try {
                    int min = Integer.parseInt(m.group(1));
                    int sec = Integer.parseInt(m.group(2));
                    String frac = m.group(3);
                    long ms = 0;
                    if (frac != null) {
                        // 两位=百分秒，三位=毫秒
                        ms = frac.length() == 3
                                ? Long.parseLong(frac)
                                : Long.parseLong(frac) * 10L;
                    }
                    times.add(min * 60_000L + sec * 1000L + ms);
                    lastEnd = m.end();
                } catch (NumberFormatException ignored) { }
            }

            if (times.isEmpty()) continue;

            String text = line.substring(lastEnd).trim();
            // 去掉常见的元数据标签行
            if (text.startsWith("[") || text.contains("by:") || text.contains("ti:")
                    || text.contains("ar:") || text.contains("al:")) {
                continue;
            }
            for (long t : times) {
                out.add(new Line(t, text));
            }
        }

        Collections.sort(out, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        return out;
    }

    /**
     * 按播放位置找「当前句」和「下一句」。
     * @return 长度 2 的数组，[0]=当前句（可能是空串），[1]=下一句
     */
    public static String[] currentAndNext(List<Line> lines, long positionMs) {
        String[] res = new String[]{"", ""};
        if (lines == null || lines.isEmpty()) return res;

        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs <= positionMs) idx = i;
            else break;
        }
        if (idx < 0) {
            // 还没到第一句
            res[0] = "";
            res[1] = lines.get(0).text;
            return res;
        }

        res[0] = lines.get(idx).text;
        // 跳过空行找下一句有内容的
        for (int i = idx + 1; i < lines.size(); i++) {
            if (!lines.get(i).text.isEmpty()) {
                res[1] = lines.get(i).text;
                break;
            }
        }
        return res;
    }

    /**
     * 文本的显示宽度，单位是<b>半角字符</b>。
     *
     * 汉字 / 假名 / 全角标点 算 2，ASCII 字母数字算 1。
     * 这样「7 个汉字」和「14 个英文字母」是同一个宽度，
     * 车机上才能都刚好占满一行。
     */
    public static int widthOf(String s) {
        if (s == null) return 0;
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += isWide(s.charAt(i)) ? 2 : 1;
        }
        return w;
    }

    private static boolean isWide(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // 汉字
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x3040 && c <= 0x30FF)  // 假名
                || (c >= 0xFF00 && c <= 0xFF60); // 全角标点
    }

    /** 单个字符的显示宽度（半角单位）。折行/切屏的计算共用这一口径。 */
    public static int widthOfChar(char c) {
        return isWide(c) ? 2 : 1;
    }

    /** 取前 n 个半角宽度单位的字符（不会把汉字切一半）。包内可见，供 SyncLyric 复用。 */
    static String takeWidth(String s, int n) {
        if (s == null) return "";
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cw = isWide(s.charAt(i)) ? 2 : 1;
            // 字符是代理对（emoji 等）时跳过两个 char
            int step = Character.isHighSurrogate(s.charAt(i))
                    && i + 1 < s.length() ? 2 : 1;
            if (w + cw > n) return s.substring(0, i);
            w += cw;
            i += step;
        }
        return s;
    }

    /**
     * 长句自动翻页。
     *
     * 一句歌词放不下一整屏时（比如 40 个字），按时间把它切成若干段，
     * 每段在各自的时间窗口内显示，看起来像翻页。
     *
     * ★ 宽度单位统一用<b>半角</b>：汉字 2、ASCII 1。
     *   早期版本按 String.length() 算，对纯英文/德语歌词会把
     *   「14 个字母」当成「14 个字」而误判成放不下。
     *
     * @param lines      整首歌词
     * @param positionMs 当前播放位置
     * @param perLine    一行能放的半角宽度（本车实测 7 汉字 → 14）
     * @param pages      一屏有几行给歌词（一般 2）
     * @return 长度 2 的数组，[0]=当前应显示的内容，[1]=""（不再预读下一句）
     */
    public static String[] paged(List<Line> lines, long positionMs,
                                 int perLine, int pages) {
        String[] res = new String[]{"", ""};
        if (lines == null || lines.isEmpty()) return res;

        int capacity = Math.max(1, perLine * pages);   // 一屏半角宽度总额

        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs <= positionMs) idx = i;
            else break;
        }
        if (idx < 0) {
            // 还没到第一句：显示第一句的开头
            return new String[]{takeWidth(lines.get(0).text, capacity).trim(), ""};
        }

        Line cur = lines.get(idx);
        String text = cur.text;
        if (text == null || text.isEmpty()) {
            // 空行：往前找最后一句有内容的，避免整屏空白
            for (int i = idx - 1; i >= 0; i--) {
                if (!lines.get(i).text.isEmpty()) {
                    return new String[]{takeWidth(lines.get(i).text, capacity).trim(), ""};
                }
            }
            return res;
        }

        int totalW = widthOf(text);

        // 一屏放得下就不翻页
        if (totalW <= capacity) {
            res[0] = text;
            return res;
        }

        // 放不下 → 按时间切片
        long start = cur.timeMs;
        long end = Long.MAX_VALUE;
        for (int i = idx + 1; i < lines.size(); i++) {
            if (!lines.get(i).text.isEmpty()) {
                end = lines.get(i).timeMs;
                break;
            }
        }
        if (end == Long.MAX_VALUE) end = start + 5000;   // 最后一句没有下文，给 5 秒

        List<Page> plan = planPages(text, start, end, capacity);
        int which = 0;
        for (int k = 0; k < plan.size(); k++) {
            if (plan.get(k).timeMs <= positionMs) which = k;
            else break;
        }
        res[0] = plan.get(which).text;
        return res;
    }

    // ---------------- 分页 ----------------

    /**
     * 一屏歌词：从哪个时刻开始显示、这一屏显示什么。
     */
    public static final class Page {
        public final long timeMs;
        public final String text;

        public Page(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }

        @Override
        public String toString() {
            return "[" + timeMs + "]" + text;
        }
    }

    /**
     * 把一句放不下一屏的歌词切成若干屏，并按<b>每屏字数（半角宽度）比例</b>分配时间。
     *
     * 为什么按宽度比例而不是平均分：一屏字多，唱的时间理应更长；
     * 平均分会让「只剩两三个字」的最后一屏也占掉同样长的时间。
     * 代价是极窄的一屏可能不足 1 秒，而车机最快 1 秒才刷一次
     * （已知取舍，见 docs/design.md §2.5）。
     *
     * ★ 这是<b>分页的唯一实现</b>：实车降级路径（{@link #paged}）与
     *   sync 成品预生成（{@code SyncLyric.build}）共用，保证两处观感一致。
     *
     * @param capacity 一屏能放的半角宽度（perLine × pages）
     */
    public static List<Page> planPages(String text, long startMs, long endMs, int capacity) {
        List<Page> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        int cap = Math.max(1, capacity);

        if (widthOf(text) <= cap) {
            out.add(new Page(startMs, text));
            return out;
        }

        // 按宽度切片
        List<String> pieces = new ArrayList<>();
        String rest = text;
        while (!rest.isEmpty()) {
            String raw = takeWidth(rest, cap);
            if (raw.isEmpty()) raw = rest.substring(0, 1);   // 防御：至少前进一个字符
            rest = rest.substring(raw.length());
            String piece = raw.trim();
            if (!piece.isEmpty()) pieces.add(piece);
        }
        if (pieces.isEmpty()) {
            out.add(new Page(startMs, text));
            return out;
        }

        // 每屏时长 ∝ 该屏宽度
        int sum = 0;
        for (String p : pieces) sum += widthOf(p);
        if (sum <= 0) sum = 1;

        long span = Math.max(1L, endMs - startMs);
        double elapsed = 0;
        for (String p : pieces) {
            out.add(new Page(startMs + (long) elapsed, p));
            elapsed += (double) span * widthOf(p) / sum;
        }
        return out;
    }
}
