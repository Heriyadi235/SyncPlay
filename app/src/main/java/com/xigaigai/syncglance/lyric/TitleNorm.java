package com.xigaigai.syncglance.lyric;

/**
 * 歌名规范化工具 —— <b>仅用于「怎么去查」，不用于「这是哪首歌」</b>。
 *
 * 缓存身份一律用通知里拿到的原始完整标题（见 LyricManager.cacheKey）。
 * 这里做的事是：在向 lrclib / 网易云发查询时，构造几种更可能命中的写法。
 *
 * 括号内容分两类，必须区别对待：
 *
 *   A 类（可剥，歌词相同）：译名、出处注释
 *     「君は薔薇より美しい (你比玫瑰更美丽)」→ 君は薔薇より美しい
 *     「梦缘 (梅艳芳"夢伴"原曲)」           → 梦缘
 *
 *   B 类（不剥，歌词不同）：版本标签
 *     Live / 现场 / Remix / 伴奏 / Instrumental / 翻唱 / 片段 ...
 *     剥掉会把「夕阳之歌 (Live)」查成录音室版，宁可查不到也不要查错。
 */
public final class TitleNorm {

    /**
     * 版本标签关键词。命中任意一个，那一组括号就<b>原样保留</b>（歌词不同，不能剥）。
     *
     * 判据：括号里的内容是否描述了「这首录音和原版有什么不同」。
     *   Live / 现场 / 演唱会 / Remix / 伴奏 / 翻唱 / 片段 —— 是，保留
     *   译名 / 出处 / 原唱说明 / 女声版 / 弦乐版      —— 否，剥掉
     *
     * 注意「女声版」「弦乐版」虽然编曲不同，但这两个词在网易云多半出现在
     * 别人翻唱的曲名里，歌手字段已经能区分，所以按 A 类处理。
     */
    private static final String[] VERSION_TAGS = {
            // 现场
            "live", "\u73b0\u573a", "\u6f14\u5531\u4f1a", "\u6f14\u51fa", "unplugged",
            // 重新混音 / 编曲
            "remix", "\u6df7\u97f3", "\u91cd\u6df7", "\u52a0\u957f\u7248", "extended",
            // 伴奏 / 纯音乐
            "\u4f34\u594f", "instrumental", "\u7eaf\u97f3\u4e50", "karaoke", "off vocal", "off-vocal",
            // 翻唱 / 其他演绎
            "\u7ffb\u5531", "cover", "tribute",
            // 剪辑 / 片段
            "\u7247\u6bb5", "\u526a\u8f91", "\u8bd5\u542c", "\u9884\u544a",
            // 明确标注了不同录音的
            "acoustic", "\u4e0d\u63d2\u7535", "\u672a\u63d2\u7535", "demo"
    };

    private TitleNorm() { }

    /** 括号内容是否属于「版本标签」（B 类，不能剥） */
    static boolean isVersionTag(String inner) {
        if (inner == null) return false;
        String s = inner.toLowerCase();
        for (String t : VERSION_TAGS) {
            if (s.contains(t)) return true;
        }
        return false;
    }

    /**
     * 剥掉 <b>A 类</b>括号及其内容，保留 <b>B 类</b>版本标签。
     *
     * stripBrackets("晴天（原唱 周杰伦）")  → "晴天"
     * stripBrackets("夕阳之歌 (Live)")      → "夕阳之歌 (Live)"   ← 保留
     * stripBrackets("晴天 (女声版)")        → "晴天 (女声版)"     ← 保留（女声版不算纯译名）
     * stripBrackets("晴天 (女声版))")       → "晴天 (女声版)"     ← 修掉脏数据多余右括号
     */
    public static String stripBrackets(String title) {
        if (title == null) return "";
        String s = title.trim();

        for (int i = 0; i < 5; i++) {
            String next = removeNonVersionGroups(s);
            if (next.equals(s)) break;
            s = next;
        }

        // 清掉剥完后可能残留的悬空分隔符：「歌名 -」
        s = s.replaceAll("[\\-\u2014\u2013~\u00b7\u30fb]+\\s*$", "").trim();
        // 修掉脏数据里多余的孤立右括号（「晴天 (女声版))」→「晴天 (女声版)」）
        while (countUnmatched(s, ')' ) > 0) {
            int idx = s.lastIndexOf(')');
            if (idx < 0) break;
            s = (s.substring(0, idx) + s.substring(idx + 1)).trim();
        }
        s = s.replaceAll("\\s{2,}", " ").trim();

        return s.isEmpty() ? title.trim() : s;
    }

    /** 右括号比左括号多的个数 */
    private static int countUnmatched(String s, char close) {
        int open = 0, cl = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isOpen(c)) open++;
            else if (isClose(c)) cl++;
        }
        return cl - open;
    }

    /**
     * 扫描一遍，删掉「成对、不嵌套、且内容不是版本标签」的括号组。
     * 版本标签组原样保留；孤立括号也原样保留 —— 宁可留着，不要误删正文。
     */
    private static String removeNonVersionGroups(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (!isOpen(c)) {
                out.append(c);
                i++;
                continue;
            }
            char close = matchClose(c);
            int j = i + 1;
            boolean nested = false;
            while (j < n && s.charAt(j) != close) {
                if (isOpen(s.charAt(j)) || isClose(s.charAt(j))) { nested = true; break; }
                j++;
            }
            if (j < n && s.charAt(j) == close && !nested) {
                String inner = s.substring(i + 1, j);
                if (isVersionTag(inner)) {
                    // B 类：整组保留
                    out.append(s, i, j + 1);
                }
                // A 类：整组丢弃
                i = j + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString().replaceAll("\\s{2,}", " ").trim();
    }

    private static boolean isOpen(char c) {
        return c == '(' || c == '\uff08' || c == '[' || c == '\u3010' || c == '{';
    }

    private static boolean isClose(char c) {
        return c == ')' || c == '\uff09' || c == ']' || c == '\u3011' || c == '}';
    }

    private static char matchClose(char open) {
        switch (open) {
            case '\uff08': return '\uff09';
            case '[': return ']';
            case '\u3010': return '\u3011';
            case '{': return '}';
            default: return ')';
        }
    }

    /**
     * 剥歌手字段里的多余内容，例如「周杰伦/Jay Chou」只取第一位。
     */
    public static String primaryArtist(String artist) {
        if (artist == null) return "";
        String s = artist.trim();
        if (s.isEmpty()) return s;
        String[] parts = s.split("\\s*[/\u3001,\uff0c&;\uff1b]\\s*");
        for (String p : parts) {
            if (!p.trim().isEmpty()) return p.trim();
        }
        return s;
    }

    /**
     * 供 lrclib 搜索用的查询标题。
     * 只在「查询」时调用，绝不参与缓存身份判定。
     */
    public static String queryTitle(String title) {
        String s = stripBrackets(title == null ? "" : title);
        return s.isEmpty() ? (title == null ? "" : title.trim()) : s;
    }
}
