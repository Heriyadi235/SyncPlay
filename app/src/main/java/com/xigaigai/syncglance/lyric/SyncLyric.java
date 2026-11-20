package com.xigaigai.syncglance.lyric;

import com.xigaigai.syncglance.util.Log2;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 车机专用的「成品歌词」缓存。
 *
 * 为什么要有这一层：
 *   假名转罗马音要分词（kuromoji，首次 1~2 秒），再加上长句折行/翻页，
 *   每帧都算一遍太浪费；而一旦算好，<b>同一首歌永远不需要再算第二次</b>。
 *   所以这里把「转写 + 折行 + 翻页」全部离线做完，存成一份新的 LRC，
 *   显示时直接按时间取行即可。
 *
 * 文件：{@code <cacheDir>/<md5>.v2-14x2.sync.lrc}
 *
 * 格式：
 * <pre>
 * #syncglance sync-lyric v2; units=14; pages=2; romaji=hepburn
 * [00:12.340]私 wa 学生 desu␟
 * [00:16.100]君 no 名 wa␟Ki mi no na wa
 * </pre>
 *   - 每行 = <b>一屏</b>：最多两行，用 U+241F（␟）分隔（第二行可以为空，但分隔符一定在）。
 *     固定带分隔符是为了读取时能可靠区分「成品」和「原始 LRC」。
 *   - 首行是签名：规则/常量一变（版本、每行宽度、每屏行数），签名就对不上，
 *     文件自动作废重建，不需要写迁移代码。
 *   - 0.5 秒提前量（{@code StateRenderer.LYRIC_LEAD_MS}）<b>不</b>写进文件，
 *     它属于查询时的补偿，改它不用重建缓存。
 */
public final class SyncLyric {

    private static final String TAG = "Lyric";

    /** 一屏一行能放多少半角宽度（7 个汉字 = 14） */
    public static final int LINE_UNITS = 14;
    /** 一屏给歌词几行 */
    public static final int PAGES = 2;
    /**
     * 规则版本：转写规则、折行算法或分页时间分配一变就加一。
     * v2：折行改为「优先在空格处断」+ 每屏时长按字数比例。
     */
    private static final String VERSION = "v2";

    /**
     * 一屏内两行的分隔符。
     *
     * ★ 不能用 \u0001 这类控制字符：{@link Lrc#parse} 会对每行文本 {@code trim()}，
     *   而 trim() 会删掉 ≤ U+0020 的字符 —— 第二行为空时分隔符就没了，
     *   于是「成品」和「原始 LRC」再也分不出来。U+241F（␟ SYMBOL FOR UNIT SEPARATOR）
     *   码位远高于空白，永远不会被 trim，且肉眼可辨、不会出现在歌词里。
     */
    public static final char SEP = '\u241F';

    private SyncLyric() { }

    /** 文件头签名。规则/常量变化必须体现在这里，否则旧缓存会被误用。 */
    public static String signature() {
        return "#syncglance sync-lyric " + VERSION
                + "; units=" + LINE_UNITS
                + "; pages=" + PAGES
                + "; romaji=hepburn; caps=kana-seg";
    }

    public static File fileFor(File cacheDir, String key) {
        return new File(cacheDir, key + "." + VERSION + "-"
                + LINE_UNITS + "x" + PAGES + ".sync.lrc");
    }

    /**
     * 由原始 LRC 文本生成车机专用 LRC。
     *
     * 纯计算，不碰磁盘；调用方负责放到 IO 线程。
     *
     * @return 新 LRC 全文；原始歌词为空或全部转写失败时返回 null（调用方应降级）
     */
    public static String build(String lrcText) {
        List<Lrc.Line> src = Lrc.parse(lrcText);
        if (src.isEmpty()) return null;

        StringBuilder out = new StringBuilder(src.size() * 48);
        out.append(signature()).append('\n');

        final int capacity = LINE_UNITS * PAGES;
        int produced = 0;

        for (int i = 0; i < src.size(); i++) {
            Lrc.Line line = src.get(i);
            if (line.text == null || line.text.trim().isEmpty()) continue;

            // ★ 转写在这里做一次，之后显示层零算力
            String roma = Romaji.convert(line.text).trim();
            if (roma.isEmpty()) continue;

            int w = Lrc.widthOf(roma);
            if (w <= capacity) {
                appendPage(out, line.timeMs, roma);
                produced++;
                continue;
            }

            // 超过一屏：先找下一句的开始时刻，作为这一句的时间窗口右端
            long end = Long.MAX_VALUE;
            for (int j = i + 1; j < src.size(); j++) {
                if (src.get(j).text != null && !src.get(j).text.trim().isEmpty()) {
                    end = src.get(j).timeMs;
                    break;
                }
            }
            if (end == Long.MAX_VALUE) end = line.timeMs + 5000;

            // ★ 分页的唯一实现（含「每屏时长按字数比例」），与 Lrc.paged 共用
            for (Lrc.Page page : Lrc.planPages(roma, line.timeMs, end, capacity)) {
                appendPage(out, page.timeMs, page.text);
                produced++;
            }
        }

        return produced == 0 ? null : out.toString();
    }

    /** 写一屏：`[t]行1␁行2` */
    private static void appendPage(StringBuilder out, long timeMs, String text) {
        String[] two = splitTwo(text);
        out.append('[').append(fmtTime(timeMs)).append(']')
                .append(two[0]).append(SEP).append(two[1]).append('\n');
    }

    /**
     * 把一屏文本折成最多两行，<b>保证每行 ≤ LINE_UNITS</b>、且不丢字。
     *
     * 三条路径，<b>优先空格</b>：
     *   ① 「对半、但只在空格处断」—— 观感最均衡，且不会切断单词
     *   ② 对半处附近没有空格（中点落在一个长单词里）→ 尽量填满第一行，
     *      仍然只在空格处断，让第二行尽量完整
     *   ③ 整屏一个空格都没有（纯中文 / 一个超长单词）→ 按宽度硬切
     *
     * 历史：原来 ① 直接用 {@code Lrc.cutAtWidth}（±3 窗口内找不到空格就硬切），
     * 于是 `NO KONPYUUTAA NO` 会被折成 `NO KONPY` + `UUTAA NO`（词中被切断）。
     */
    public static String[] splitTwo(String text) {
        if (text == null) return new String[]{"", ""};
        if (Lrc.widthOf(text) <= LINE_UNITS) return new String[]{text, ""};
        int w = Lrc.widthOf(text);

        // ① 对半，但必须落在空格上
        String a = headAtSpace(text, w / 2);
        if (a != null) {
            String b = text.substring(a.length()).trim();
            if (!b.isEmpty() && Lrc.widthOf(a) <= LINE_UNITS && Lrc.widthOf(b) <= LINE_UNITS) {
                return new String[]{a.trim(), b};
            }
        }

        // ② 尽量填满第一行（空格处断）
        int acc = 0;
        int cut = -1;
        for (int i = 0; i < text.length(); i++) {
            int cw = Lrc.widthOfChar(text.charAt(i));
            if (acc + cw > LINE_UNITS) break;
            acc += cw;
            if (text.charAt(i) == ' ') cut = i;
        }
        if (cut > 0) {
            String a2 = text.substring(0, cut).trim();
            String b2 = text.substring(cut + 1).trim();
            if (!b2.isEmpty() && Lrc.widthOf(a2) <= LINE_UNITS && Lrc.widthOf(b2) <= LINE_UNITS) {
                return new String[]{a2, b2};
            }
        }

        // ③ 没有任何可用空格 → 按宽度硬切（宁可断词，也不能溢出被车机截成省略号）
        String a3 = Lrc.takeWidth(text, LINE_UNITS).trim();
        String b3 = text.substring(Math.min(a3.length(), text.length())).trim();
        return new String[]{a3, b3};
    }

    /**
     * 在宽度 {@code target} 处切开，但**只在附近有空格时才切**。
     *
     * @return 前半段（不含分隔空格）；附近 3 个字符内没有空格时返回 null，
     *         由调用方改用别的策略（不硬切）
     */
    private static String headAtSpace(String s, int target) {
        if (target <= 0 || target >= s.length()) return null;
        for (int d = 0; d <= 3; d++) {
            int i = target - d;
            if (i > 0 && i < s.length() && s.charAt(i) == ' ') {
                return s.substring(0, i);
            }
        }
        return null;
    }

    // ---------------- 读写 ----------------

    /**
     * 读取成品缓存：签名不符 / 文件损坏 / 没有任何一屏 → 返回 null（调用方重建）。
     */
    public static List<Lrc.Line> read(File f) {
        if (f == null || !f.isFile()) return null;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8));
            String head = r.readLine();
            if (head == null || !head.equals(signature())) return null;

            StringBuilder body = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) body.append(line).append('\n');

            List<Lrc.Line> lines = Lrc.parse(body.toString());
            if (lines.isEmpty()) return null;

            // 必须至少有一屏带分隔符，否则不是我们生成的格式
            for (Lrc.Line l : lines) {
                if (l.text != null && l.text.indexOf(SEP) >= 0) return lines;
            }
            return null;
        } catch (Exception e) {
            Log2.w(TAG, "读成品歌词失败: " + e.getMessage());
            return null;
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) { }
        }
    }

    /** 原子性无所谓：写坏了下次读校验不过会自动重建 */
    public static void write(File f, String content) {
        if (f == null || content == null) return;
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(content);
        } catch (Exception e) {
            Log2.w(TAG, "写成品歌词失败: " + e.getMessage());
        }
    }

    /** 从成品缓存里按时间取这一屏（返回两行，不含分隔符） */
    public static String[] pageAt(List<Lrc.Line> lines, long positionMs) {
        if (lines == null || lines.isEmpty()) return null;
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs <= positionMs) idx = i;
            else break;
        }
        Lrc.Line line = idx < 0 ? lines.get(0) : lines.get(idx);
        String text = line.text == null ? "" : line.text;
        int sep = text.indexOf(SEP);
        if (sep < 0) return null;
        return new String[]{text.substring(0, sep), text.substring(sep + 1)};
    }

    /** 是否是一份「成品」行（含屏分隔符） */
    public static boolean isPrebuilt(List<Lrc.Line> lines) {
        if (lines == null || lines.isEmpty()) return false;
        for (Lrc.Line l : lines) {
            if (l.text != null && l.text.indexOf(SEP) >= 0) return true;
        }
        return false;
    }

    private static String fmtTime(long ms) {
        long totalSec = ms / 1000;
        long m = totalSec / 60;
        long s = totalSec % 60;
        long frac = ms % 1000;
        return String.format(java.util.Locale.US, "%02d:%02d.%03d", m, s, frac);
    }

    /** 仅供调试：把成品缓存转成人能看的样子 */
    public static List<String> describe(List<Lrc.Line> lines) {
        List<String> out = new ArrayList<>();
        if (lines == null) return out;
        for (Lrc.Line l : lines) {
            String[] p = l.text == null ? new String[]{"", ""} : l.text.split(String.valueOf(SEP), -1);
            out.add(l.timeMs + "ms | " + (p.length > 0 ? p[0] : "")
                    + (p.length > 1 && !p[1].isEmpty() ? " / " + p[1] : ""));
        }
        return out;
    }
}
