package com.xigaigai.syncglance.lyric;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.xigaigai.syncglance.util.Kana;

import org.junit.Test;

/**
 * 假名 → 罗马音的词级转换验收用例。
 *
 * ★ 期望值就是 docs/design.md「显示文本转写规范」里的规则；
 *   改规则必须同步改这里，否则实车显示会悄悄变样。
 */
public class RomajiTest {

    // ---------------- 用户给的样例 ----------------

    @Test
    public void 独立助词前后加空格_送假名不加() {
        // 私 は 学生 です → 私 wa 学生 desu
        assertEquals("私 wa 学生 desu", Romaji.convert("私 は 学生 です"));
        // 送假名紧跟汉字
        assertEquals("新shii", Romaji.convert("新しい"));
        assertEquals("君 no 名 wa", Romaji.convert("君の名は"));
    }

    @Test
    public void 纯假名按词切分() {
        // 旧实现会得到 "ki mi ga i re ba"
        assertEquals("kimi ga ireba", Romaji.convert("きみがいれば"));
    }

    @Test
    public void 片假名词段整体大写() {
        assertEquals("KARAOKE", Romaji.convert("カラオケ"));
        assertEquals("KONPYUUTAA", Romaji.convert("コンピューター"));
    }

    // ---------------- 平文式细节 ----------------

    @Test
    public void 促音与拗音() {
        assertEquals("issho", Kana.romajiOfKana("いっしょ"));
        assertEquals("kotchi", Kana.romajiOfKana("こっち"));
        assertEquals("kippu", Kana.romajiOfKana("きっぷ"));
        assertEquals("kekka", Kana.romajiOfKana("けっか"));
    }

    @Test
    public void 拨音撇号() {
        assertEquals("shin'you", Kana.romajiOfKana("しんよう"));
        assertEquals("kon'ya", Kana.romajiOfKana("こんや"));
        assertEquals("annai", Kana.romajiOfKana("あんない"));
    }

    @Test
    public void 长音重复前一元音而不是macron() {
        assertEquals("raamen", Kana.romajiOfKana("ラーメン"));
        assertEquals("koohii", Kana.romajiOfKana("コーヒー"));
    }

    // ---------------- 不该动的文本 ----------------

    @Test
    public void 非日文原样返回() {
        assertEquals("晴天", Romaji.convert("晴天"));
        assertEquals("hello world", Romaji.convert("hello world"));
        assertEquals("", Romaji.convert(""));
        assertEquals(null, Romaji.convert(null));
        assertTrue(Romaji.convert("君の名は").length() > 0);
    }
}
