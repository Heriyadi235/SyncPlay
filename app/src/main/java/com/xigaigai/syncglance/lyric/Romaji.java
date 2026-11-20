package com.xigaigai.syncglance.lyric;

import com.atilika.kuromoji.ipadic.Token;
import com.atilika.kuromoji.ipadic.Tokenizer;
import com.xigaigai.syncglance.util.Kana;
import com.xigaigai.syncglance.util.Log2;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 假名 → 罗马音（<b>词级</b>转换，平文式 / Hepburn）。
 *
 * 背景：车机没有日文字库，假名会显示成方块，必须转罗马音。
 * 早期实现（{@link Kana#romajiLegacy}）是「每个假名单元之间加一个空格」，
 * 结果 `きみがいれば` 变成 `ki mi ga i re ba` —— 又长又难读，
 * 一行 14 半角根本放不下（见 docs/development.md 3.5 与踩坑记录）。
 *
 * 现在改成<b>按词切分</b>：
 *
 *   私 は 学生 です   → 私 wa 学生 desu
 *   新しい           → 新shii        （送假名紧跟汉字，不加空格）
 *   きみがいれば      → kimi ga ireba （纯假名也能切词）
 *   カラオケ          → KARAOKE      （片假名 → 大写）
 *
 * 规则（与 docs/design.md「显示文本转写规范」一致）：
 *   R1 汉字 / ASCII / 数字 / 标点原样保留
 *   R2 平假名 → 小写；片假名 → 大写（按<b>词段</b>整体判定，不逐字符）
 *   R3 助词按平文式读音：は→wa、へ→e、を→o
 *   R4 长音 ー 重复前一元音（不用 macron，车机字库不认 ā/ō）
 *   R5 促音 っ：双写，sh→ssh / ch→tch / ts→tts（こっち→kotchi）
 *   R6 拨音 ん：后接元音 / y 时写 n'（しんよう→shin'you）
 *   R7 词间 1 个空格；送假名与前面的汉字之间不加空格
 *
 * 词边界由 kuromoji（IPADIC）给出。分词器不可用（老设备 OOM、词典资源缺失）时
 * 自动降级到 {@link Kana#romajiLegacy}，保证旋律不中断。
 */
public final class Romaji {

    private static final String TAG = "Romaji";

    /** 纯假名文本的结果缓存：歌词每 4 秒重算一次，避免重复分词 */
    private static final Map<String, String> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 512;
                }
            });

    private static volatile Tokenizer sTokenizer;
    private static volatile boolean sInitFailed;

    private Romaji() { }

    // ---------------- 对外入口 ----------------

    /**
     * 把一段文本里的假名转成罗马音。
     *
     * <b>不含假名的文本原样返回</b>（中文、英文、德语歌词不会被分词加空格），
     * 因此可以安全地对所有发往车机的字符串调用。
     */
    public static String convert(String input) {
        if (input == null || input.isEmpty()) return input;
        if (!Kana.containsKana(input)) return input;

        String hit = CACHE.get(input);
        if (hit != null) return hit;

        String out = null;
        try {
            out = convertByTokens(input);
        } catch (Throwable t) {
            // 分词器内部异常（词典损坏 / OOM）不能连累整个显示链路
            Log2.w(TAG, "分词转换失败，降级逐字: " + t);
        }
        if (out == null || out.trim().isEmpty()) {
            out = Kana.romajiLegacy(input);
        }
        CACHE.put(input, out);
        return out;
    }

    /** 分词器是否已就绪（日志 / 测试用） */
    public static boolean isTokenizerReady() {
        return sTokenizer != null;
    }

    // ---------------- 分词器 ----------------

    /**
     * 惰性初始化。词典是 jar 内的 IPADIC 资源（12.7 MiB），
     * 首次 build() 约 1~2 秒、内存峰值较高，所以只在真正遇到日文歌词时才做，
     * 并且记住失败状态，避免每首歌都重试一次。
     */
    private static Tokenizer tokenizer() {
        Tokenizer tk = sTokenizer;
        if (tk != null) return tk;
        if (sInitFailed) return null;

        synchronized (Romaji.class) {
            if (sTokenizer != null) return sTokenizer;
            if (sInitFailed) return null;
            try {
                long t0 = System.currentTimeMillis();
                sTokenizer = new Tokenizer();
                Log2.i(TAG, "kuromoji 就绪，耗时 " + (System.currentTimeMillis() - t0) + "ms");
            } catch (Throwable e) {
                sInitFailed = true;
                Log2.w(TAG, "kuromoji 初始化失败，本进程内降级为逐字转换: " + e);
            }
            return sTokenizer;
        }
    }

    // ---------------- 词级转换 ----------------

    private static String convertByTokens(String input) {
        Tokenizer tk = tokenizer();
        if (tk == null) return null;

        List<Token> tokens;
        synchronized (tk) {                 // kuromoji 的 Tokenizer 不是线程安全的
            tokens = tk.tokenize(input);
        }
        if (tokens == null || tokens.isEmpty()) return null;

        StringBuilder out = new StringBuilder(input.length() + 16);
        String prevSurface = null;
        boolean prevWasPunct = false;

        for (Token t : tokens) {
            String surface = t.getSurface();
            if (surface == null || surface.isEmpty()) continue;

            String pos1 = safe(t.getPartOfSpeechLevel1());
            String pos2 = safe(t.getPartOfSpeechLevel2());
            String piece = pieceOf(t, surface, pos1);
            if (piece.isEmpty()) continue;

            if (out.length() > 0 && needSpace(surface, pos1, pos2, prevSurface, prevWasPunct)) {
                if (out.charAt(out.length() - 1) != ' ') out.append(' ');
            }
            out.append(piece);

            prevSurface = surface;
            prevWasPunct = isPunct(surface, pos1);
        }
        return out.toString().replaceAll(" {2,}", " ").trim();
    }

    /**
     * 当前 token 与前一个 token 之间是否要空格。
     *
     * 三条规则，按优先级：
     *   ① 标点紧贴前文，标点后空一格
     *   ② <b>接续助词</b>（ば / て / ても / ながら / から（因为）…）粘在前一个词尾：
     *      `いれ` + `ば` → `ireba`，而不是 `ire ba`。
     *      这是分词器自己给的词性（助詞,接続助詞），不是我们猜的。
     *   ③ <b>送假名</b>：纯假名 token 紧跟在汉字后面、且词性不是助词/助动词/接续词/感动词/记号
     *      （`新` + `しい`、`食べ` + `た`）→ 不空格。
     */
    private static boolean needSpace(String surface, String pos1, String pos2,
                                     String prevSurface, boolean prevWasPunct) {
        if (isPunct(surface, pos1)) return false;          // 标点紧贴前文
        if (prevWasPunct) return true;                     // 标点后空一格
        if (prevSurface == null) return false;
        if (isPureKana(surface) && isConjunctiveParticle(pos1, pos2)) return false;
        if (isPureKana(surface) && endsWithKanji(prevSurface) && !isStandalonePos(pos1)) {
            return false;                                  // 送假名
        }
        return true;
    }

    /**
     * 接续助词 / 接续助动词：日语里紧贴活用词尾，不该空格。
     * 格助词（は・が・を…）不在此列，仍然空格。
     */
    private static boolean isConjunctiveParticle(String pos1, String pos2) {
        boolean particle = pos1.contains("助詞") || pos1.contains("助動詞");
        return particle && pos2.contains("接続");
    }

    /** 这些词性即使跟在汉字后也要空格（独立词） */
    private static boolean isStandalonePos(String pos1) {
        if (pos1 == null) return true;
        return pos1.contains("助詞") || pos1.contains("助動詞")
                || pos1.contains("接続詞") || pos1.contains("感動詞")
                || pos1.contains("記号");
    }

    private static String pieceOf(Token t, String surface, String pos1) {
        if (isPureKana(surface)) {
            // R3 助词的平文式读音（分词器把「は」标成助詞时才能确定）
            if (pos1 != null && pos1.contains("助詞")) {
                if (surface.equals("は")) return casing(surface, "wa");
                if (surface.equals("へ")) return casing(surface, "e");
                if (surface.equals("を")) return casing(surface, "o");
            }
            // 读音优先（は→ワ、を→ヲ 这类在读音字段里已经规范化），
            // 但拼写规则仍由 Kana 的假名表给出。
            String pron = t.getPronunciation();
            String src = (pron == null || pron.isEmpty() || "*".equals(pron)) ? surface : pron;
            String roma = Kana.romajiOfKana(src);
            if (roma.isEmpty()) return surface;
            return casing(surface, roma);
        }
        // 汉字 / ASCII / 混合词：汉字原样，假名段转音（送假名在词内自然不空格）
        return convertMixed(surface);
    }

    /** 混合词：汉字与 ASCII 原样保留，连续假名段整体转音 */
    private static String convertMixed(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Kana.isKana(c) || c == 'ー') {
                run.append(c);
            } else {
                flushKanaRun(out, run);
                out.append(c);
            }
        }
        flushKanaRun(out, run);
        return out.toString();
    }

    private static void flushKanaRun(StringBuilder out, StringBuilder run) {
        if (run.length() == 0) return;
        String src = run.toString();
        String roma = Kana.romajiOfKana(src);
        out.append(casing(src, roma));
        run.setLength(0);
    }

    /** R2：片假名占多数 → 大写；平假名占多数 → 小写 */
    private static String casing(String surface, String roma) {
        int kata = 0;
        int hira = 0;
        for (int i = 0; i < surface.length(); i++) {
            char c = surface.charAt(i);
            if (Kana.isKatakana(c)) kata++;
            else if (c >= 0x3041 && c <= 0x309F) hira++;
        }
        return kata > hira ? roma.toUpperCase(Locale.ROOT) : roma;
    }

    // ---------------- 字符工具 ----------------

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static boolean isPureKana(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Kana.isKana(c) && c != 'ー') return false;
        }
        return true;
    }

    private static boolean endsWithKanji(String s) {
        if (s == null || s.isEmpty()) return false;
        char c = s.charAt(s.length() - 1);
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }

    private static boolean isPunct(String surface, String pos1) {
        if (pos1 != null && pos1.contains("記号")) return true;
        if (surface == null || surface.isEmpty()) return false;
        char c = surface.charAt(0);
        return "、。，．！？!?.,・…‥〜～「」『』（）()【】〈〉《》".indexOf(c) >= 0;
    }
}
