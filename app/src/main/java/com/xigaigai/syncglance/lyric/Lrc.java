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
     * 在「半角宽度 n」附近切开，返回前半段。
     *
     * 宽度口径与 {@link #widthOf} 一致：汉字 2、ASCII 1。
     *
     * 两条规则：
     *   ① 优先从空格处断开 —— 罗马音（`shi wa se na`）和德语歌词
     *      从词中间切会很难读，所以在目标位置前后 3 个字符内找最近的空格
     *   ② 找不到空格才硬切
     *
     * ★ 这是折行的<b>唯一实现</b>：实车渲染（StateRenderer）与
     *   sync 缓存预生成（SyncLyric）都调这里，避免两套折行逻辑漂移。
     */
    public static String cutAtWidth(String s, int n) {
        if (s == null || s.isEmpty()) return "";
        if (n <= 0) return "";

        // 先按半角宽度找到切点下标
        int w = 0;
        int target = -1;
        for (int i = 0; i < s.length(); i++) {
            int cw = isWide(s.charAt(i)) ? 2 : 1;
            if (w + cw > n) { target = i; break; }
            w += cw;
        }
        if (target < 0) return s;              // n 超过整串宽度，不用切
        if (target == 0) target = 1;           // 至少切一个字符，避免死循环

        // 在 target 前后找最近的空格（窗口 ±3）
        int best = -1;
        for (int d = 0; d <= 3; d++) {
            int right = target + d;
            if (right < s.length() && s.charAt(right) == ' ') { best = right; break; }
            int left = target - d;
            if (left > 0 && s.charAt(left) == ' ') { best = left; break; }
        }
        if (best > 0 && best < s.length()) {
            return s.substring(0, best);
        }
        return s.substring(0, target);
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

        long span = Math.max(1, end - start);
        int slices = (totalW + capacity - 1) / capacity;
        long sliceMs = span / slices;

        int which = sliceMs <= 0 ? slices - 1
                : (int) Math.min(slices - 1, (positionMs - start) / sliceMs);

        // 跳过前 which 屏的宽度，再取一屏
        String rest = text;
        for (int k = 0; k < which; k++) {
            String head = takeWidth(rest, capacity);
            if (head.length() >= rest.length()) { rest = ""; break; }
            rest = rest.substring(head.length());
        }
        res[0] = takeWidth(rest, capacity).trim();
        return res;
    }
}
