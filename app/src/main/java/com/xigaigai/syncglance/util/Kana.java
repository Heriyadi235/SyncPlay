package com.xigaigai.syncglance.util;

import java.util.HashMap;
import java.util.Map;

/**
 * 假名转罗马音。
 *
 * 背景：车机没有日文字库，直接发假名会显示成方块。
 * 这里把平假名/片假名转成对应的罗马音（Hepburn 风格）。
 *
 * 处理顺序很重要：
 *   1. 先处理拗音（きゃ → kya），否则会变成 kiya
 *   2. 再处理促音（っ → 双写下一个辅音）
 *   3. 最后处理普通假名
 */
public final class Kana {

    /** 拗音（两字符假名 → 罗马音） */
    private static final Map<String, String> YOON = new HashMap<>();
    /** 单假名 → 罗马音 */
    private static final Map<Character, String> SINGLE = new HashMap<>();

    static {
        // ---- 拗音 ----
        String[][] yoon = {
                {"きゃ","kya"},{"きゅ","kyu"},{"きょ","kyo"},
                {"しゃ","sha"},{"しゅ","shu"},{"しょ","sho"},
                {"ちゃ","cha"},{"ちゅ","chu"},{"ちょ","cho"},
                {"にゃ","nya"},{"にゅ","nyu"},{"にょ","nyo"},
                {"ひゃ","hya"},{"ひゅ","hyu"},{"ひょ","hyo"},
                {"みゃ","mya"},{"みゅ","myu"},{"みょ","myo"},
                {"りゃ","rya"},{"りゅ","ryu"},{"りょ","ryo"},
                {"ぎゃ","gya"},{"ぎゅ","gyu"},{"ぎょ","gyo"},
                {"じゃ","ja"},{"じゅ","ju"},{"じょ","jo"},
                {"びゃ","bya"},{"びゅ","byu"},{"びょ","byo"},
                {"ぴゃ","pya"},{"ぴゅ","pyu"},{"ぴょ","pyo"},
                // 片假名
                {"キャ","kya"},{"キュ","kyu"},{"キョ","kyo"},
                {"シャ","sha"},{"シュ","shu"},{"ショ","sho"},
                {"チャ","cha"},{"チュ","chu"},{"チョ","cho"},
                {"ニャ","nya"},{"ニュ","nyu"},{"ニョ","nyo"},
                {"ヒャ","hya"},{"ヒュ","hyu"},{"ヒョ","hyo"},
                {"ミャ","mya"},{"ミュ","myu"},{"ミョ","myo"},
                {"リャ","rya"},{"リュ","ryu"},{"リョ","ryo"},
                {"ギャ","gya"},{"ギュ","gyu"},{"ギョ","gyo"},
                {"ジャ","ja"},{"ジュ","ju"},{"ジョ","jo"},
                {"ビャ","bya"},{"ビュ","byu"},{"ビョ","byo"},
                {"ピャ","pya"},{"ピュ","pyu"},{"ピョ","pyo"},
        };
        for (String[] p : yoon) YOON.put(p[0], p[1]);

        // ---- 平假名 ----
        String[][] hira = {
                {"あ","a"},{"い","i"},{"う","u"},{"え","e"},{"お","o"},
                {"か","ka"},{"き","ki"},{"く","ku"},{"け","ke"},{"こ","ko"},
                {"さ","sa"},{"し","shi"},{"す","su"},{"せ","se"},{"そ","so"},
                {"た","ta"},{"ち","chi"},{"つ","tsu"},{"て","te"},{"と","to"},
                {"な","na"},{"に","ni"},{"ぬ","nu"},{"ね","ne"},{"の","no"},
                {"は","ha"},{"ひ","hi"},{"ふ","fu"},{"へ","he"},{"ほ","ho"},
                {"ま","ma"},{"み","mi"},{"む","mu"},{"め","me"},{"も","mo"},
                {"や","ya"},{"ゆ","yu"},{"よ","yo"},
                {"ら","ra"},{"り","ri"},{"る","ru"},{"れ","re"},{"ろ","ro"},
                {"わ","wa"},{"を","wo"},{"ん","n"},
                {"が","ga"},{"ぎ","gi"},{"ぐ","gu"},{"げ","ge"},{"ご","go"},
                {"ざ","za"},{"じ","ji"},{"ず","zu"},{"ぜ","ze"},{"ぞ","zo"},
                {"だ","da"},{"ぢ","ji"},{"づ","zu"},{"で","de"},{"ど","do"},
                {"ば","ba"},{"び","bi"},{"ぶ","bu"},{"べ","be"},{"ぼ","bo"},
                {"ぱ","pa"},{"ぴ","pi"},{"ぷ","pu"},{"ぺ","pe"},{"ぽ","po"},
                {"ぁ","a"},{"ぃ","i"},{"ぅ","u"},{"ぇ","e"},{"ぉ","o"},
                {"ゃ","ya"},{"ゅ","yu"},{"ょ","yo"},
                {"ー","-"},
        };
        for (String[] p : hira) SINGLE.put(p[0].charAt(0), p[1]);

        // ---- 片假名 ----
        String[][] kata = {
                {"ア","a"},{"イ","i"},{"ウ","u"},{"エ","e"},{"オ","o"},
                {"カ","ka"},{"キ","ki"},{"ク","ku"},{"ケ","ke"},{"コ","ko"},
                {"サ","sa"},{"シ","shi"},{"ス","su"},{"セ","se"},{"ソ","so"},
                {"タ","ta"},{"チ","chi"},{"ツ","tsu"},{"テ","te"},{"ト","to"},
                {"ナ","na"},{"ニ","ni"},{"ヌ","nu"},{"ネ","ne"},{"ノ","no"},
                {"ハ","ha"},{"ヒ","hi"},{"フ","fu"},{"ヘ","he"},{"ホ","ho"},
                {"マ","ma"},{"ミ","mi"},{"ム","mu"},{"メ","me"},{"モ","mo"},
                {"ヤ","ya"},{"ユ","yu"},{"ヨ","yo"},
                {"ラ","ra"},{"リ","ri"},{"ル","ru"},{"レ","re"},{"ロ","ro"},
                {"ワ","wa"},{"ヲ","wo"},{"ン","n"},
                {"ガ","ga"},{"ギ","gi"},{"グ","gu"},{"ゲ","ge"},{"ゴ","go"},
                {"ザ","za"},{"ジ","ji"},{"ズ","zu"},{"ゼ","ze"},{"ゾ","zo"},
                {"ダ","da"},{"ヂ","ji"},{"ヅ","zu"},{"デ","de"},{"ド","do"},
                {"バ","ba"},{"ビ","bi"},{"ブ","bu"},{"ベ","be"},{"ボ","bo"},
                {"パ","pa"},{"ピ","pi"},{"プ","pu"},{"ペ","pe"},{"ポ","po"},
                {"ァ","a"},{"ィ","i"},{"ゥ","u"},{"ェ","e"},{"ォ","o"},
                {"ャ","ya"},{"ュ","yu"},{"ョ","yo"},
                {"ヴ","vu"},{"ヷ","va"},{"ヸ","vi"},{"ヹ","ve"},{"ヺ","vo"},
        };
        for (String[] p : kata) SINGLE.put(p[0].charAt(0), p[1]);
    }

    private Kana() { }

    /**
     * 转罗马音，并且**每个假名单元之间加空格**，方便阅读。
     *
     * 例：きみがいれば → ki mi ga i re ba
     *
     * 汉字、英文、数字原样保留，不加额外空格。
     */
    public static String romajiSpaced(String input) {
        if (input == null || input.isEmpty()) return input;
        if (!containsKana(input)) return input;

        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = input.length();

        while (i < n) {
            char c = input.charAt(i);
            boolean appendSep = false;

            // 促音
            if (c == 'っ' || c == 'ッ') {
                String next = nextRomaji(input, i + 1);
                if (!next.isEmpty() && !"aiueo".contains(String.valueOf(next.charAt(0)))) {
                    out.append(next.charAt(0));
                }
                i++;
                appendSep = true;
            }
            // 长音符
            else if (c == 'ー') {
                String prev = out.toString().trim();
                if (!prev.isEmpty()) {
                    char last = prev.charAt(prev.length() - 1);
                    if ("aiueo".indexOf(last) >= 0) out.append(last);
                }
                i++;
                appendSep = true;
            }
            // 拗音
            else if (i + 1 < n && YOON.containsKey(input.substring(i, i + 2))) {
                out.append(YOON.get(input.substring(i, i + 2)));
                i += 2;
                appendSep = true;
            }
            // 单假名
            else if (SINGLE.containsKey(c)) {
                out.append(SINGLE.get(c));
                i++;
                appendSep = true;
            }
            // 非假名：原样追加，不加分隔
            else {
                out.append(c);
                i++;
            }

            if (appendSep && i < n) {
                // 下一个还是假名才加空格，避免英文单词被切碎
                char nextC = input.charAt(i);
                if (isKana(nextC) || nextC == 'ー'
                        || (i + 1 < n && (isKana(input.charAt(i + 1))
                        || isKatakana(input.charAt(i))))) {
                    out.append(' ');
                }
            }
        }

        return out.toString().replaceAll(" {2,}", " ").trim();
    }

    private static boolean isKatakana(char c) {
        return c >= 0x30A1 && c <= 0x30FF;
    }

    /**
     * 把字符串里的假名转成罗马音。
     * 汉字、英文、数字原样保留。
     */
    public static String toRomaji(String input) {
        if (input == null || input.isEmpty()) return input;
        if (!containsKana(input)) return input;    // 没有假名，原样返回

        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = input.length();

        while (i < n) {
            char c = input.charAt(i);

            // 促音：っ / ッ → 双写下一个辅音
            if (c == 'っ' || c == 'ッ') {
                String next = nextRomaji(input, i + 1);
                if (!next.isEmpty() && !"aiueo".contains(String.valueOf(next.charAt(0)))) {
                    out.append(next.charAt(0));
                }
                i++;
                continue;
            }

            // 长音符：ー → 重复前一个元音
            if (c == 'ー') {
                String prev = out.toString();
                if (!prev.isEmpty()) {
                    char last = prev.charAt(prev.length() - 1);
                    if ("aiueo".indexOf(last) >= 0) out.append(last);
                }
                i++;
                continue;
            }

            // 拗音（两字符）
            if (i + 1 < n) {
                String pair = input.substring(i, i + 2);
                String y = YOON.get(pair);
                if (y != null) {
                    out.append(y);
                    i += 2;
                    continue;
                }
            }

            // 单假名
            String s = SINGLE.get(c);
            if (s != null) {
                out.append(s);
                i++;
                continue;
            }

            // 其他字符原样
            out.append(c);
            i++;
        }

        return out.toString();
    }

    /** 判断字符串里是否含假名 */
    public static boolean containsKana(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            if (isKana(s.charAt(i))) return true;
        }
        return false;
    }

    public static boolean isKana(char c) {
        return (c >= 0x3041 && c <= 0x309F)     // 平假名
                || (c >= 0x30A1 && c <= 0x30FF);  // 片假名
    }

    /** 预览下一个假名的罗马音（促音用） */
    private static String nextRomaji(String s, int index) {
        if (index >= s.length()) return "";
        if (index + 1 < s.length()) {
            String pair = s.substring(index, index + 2);
            String y = YOON.get(pair);
            if (y != null) return y;
        }
        String one = SINGLE.get(s.charAt(index));
        return one == null ? "" : one;
    }
}
