package com.xigaigai.syncglance.lyric;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 成品歌词（sync 缓存）的格式与折行约束。
 *
 * 核心不变量：
 *   1. 每一屏必带 U+0001 分隔符（否则显示层会误判成原始 LRC）
 *   2. 每行宽度 ≤ LINE_UNITS（14 半角），否则车机会截成省略号
 *   3. 成品行里不应再有假名（已经全部转写过）
 */
public class SyncLyricTest {

    private static final String SAMPLE =
            "[00:01.000]私 は 学生 です\n"
          + "[00:05.500]きみがいれば\n"
          + "[00:09.000]カラオケ\n";

    @Test
    public void 折行永远不超过一行宽度且不丢字() {
        // ★ splitTwo 的契约是「一屏」：输入宽度必须 ≤ LINE_UNITS*PAGES（=28）。
        //   超过一屏的句子由 SyncLyric.build / Lrc.paged 先切片再进来。
        String[] samples = {
                "this is a very long line",        // 24
                "私 wa 学生 desu 学生 desu",        // 25
                "kimi ga ireba kimi ga",           // 23
                "短",
                "exactly fourteen",                // 16
        };
        for (String s : samples) {
            assertTrue("用例本身超一屏: " + s, Lrc.widthOf(s) <= SyncLyric.LINE_UNITS * SyncLyric.PAGES);
            String[] two = SyncLyric.splitTwo(s);
            assertTrue("第一行超宽: " + two[0], Lrc.widthOf(two[0]) <= SyncLyric.LINE_UNITS);
            assertTrue("第二行超宽: " + two[1], Lrc.widthOf(two[1]) <= SyncLyric.LINE_UNITS);
            // 不丢字
            assertEquals("丢字: " + s + " → " + two[0] + " / " + two[1],
                    s.replace(" ", ""), (two[0] + two[1]).replace(" ", ""));
        }
    }

    @Test
    public void 单行不折() {
        String[] two = SyncLyric.splitTwo("私 wa");
        assertEquals("私 wa", two[0]);
        assertEquals("", two[1]);
    }

    @Test
    public void 生成的成品行格式正确() {
        String built = SyncLyric.build(SAMPLE);
        assertNotNull("成品歌词不应为空", built);
        assertTrue(built.startsWith(SyncLyric.signature()));

        List<Lrc.Line> lines = Lrc.parse(built);
        assertFalse(lines.isEmpty());
        assertTrue(SyncLyric.isPrebuilt(lines));

        for (Lrc.Line l : lines) {
            String[] p = l.text.split(String.valueOf(SyncLyric.SEP), -1);
            assertEquals("每屏必须是两行", 2, p.length);
            assertTrue("第一行超宽: " + p[0], Lrc.widthOf(p[0]) <= SyncLyric.LINE_UNITS);
            assertTrue("第二行超宽: " + p[1], Lrc.widthOf(p[1]) <= SyncLyric.LINE_UNITS);
            assertFalse("残留假名: " + p[0], p[0].matches(".*[\\u3040-\\u30ff].*"));
            assertFalse("残留假名: " + p[1], p[1].matches(".*[\\u3040-\\u30ff].*"));
        }
    }

    @Test
    public void 按时间取屏() {
        String built = SyncLyric.build(SAMPLE);
        List<Lrc.Line> lines = Lrc.parse(built);
        assertNotNull(lines);

        String[] first = SyncLyric.pageAt(lines, 1500);
        assertNotNull(first);
        assertFalse(first[0].isEmpty());

        // 第一句之前显示第一屏，不能空白
        String[] before = SyncLyric.pageAt(lines, 0);
        assertNotNull(before);
        assertEquals(first[0], before[0]);
    }

    @Test
    public void 长句会翻页成多屏() {
        String lrc = "[00:00.000]"
                + "きみがいれば きみがいれば きみがいれば きみがいれば\n"
                + "[00:20.000]次の句\n";
        String built = SyncLyric.build(lrc);
        assertNotNull(built);
        List<Lrc.Line> lines = Lrc.parse(built);
        // 一句话一屏放不下（>28 半角）→ 必须切出多屏
        assertTrue("长句应翻页，实际只有 " + lines.size() + " 屏", lines.size() >= 2);
        // 屏幕时间必须递增
        for (int i = 1; i < lines.size(); i++) {
            assertTrue(lines.get(i).timeMs >= lines.get(i - 1).timeMs);
        }
    }

    @Test
    public void 签名不符视为无效() {
        // 伪造一份签名不对的文件内容：read() 走文件，这里直接验证签名常量可比较
        assertTrue(SyncLyric.signature().startsWith("#syncglance sync-lyric v2"));
        // 空列表不是成品
        assertFalse(SyncLyric.isPrebuilt(null));
        assertNull(SyncLyric.pageAt(null, 0));
    }

    @Test
    public void 折行优先在空格处断_不切断单词() {
        // 旧实现：cutAtWidth 在 ±3 内找不到空格就硬切 → "NO KONPY" / "UUTAA NO"
        String[] two = SyncLyric.splitTwo("NO KONPYUUTAA NO");
        assertEquals("NO KONPYUUTAA", two[0]);
        assertEquals("NO", two[1]);

        // 英语长句：两行都必须落在词边界
        String[] en = SyncLyric.splitTwo("this is a very long line");
        assertEquals("this is a", en[0]);
        assertEquals("very long line", en[1]);
    }

    @Test
    public void 每屏时长按字数比例分配() {
        // 宽 35 → 两屏；两屏宽度不同，第二屏开始时间应按宽度占比
        String text = "君 no 名 wa 君 no 名 wa 君 no 名 wa";
        List<Lrc.Page> pages = Lrc.planPages(text, 0, 30000, 28);
        assertEquals(2, pages.size());

        int w0 = Lrc.widthOf(pages.get(0).text);
        int w1 = Lrc.widthOf(pages.get(1).text);
        assertTrue(w0 > 0 && w1 > 0);
        long expect = Math.round(30000.0 * w0 / (w0 + w1));

        assertEquals(0, pages.get(0).timeMs);
        assertTrue("第二屏起始 " + pages.get(1).timeMs + " 应约等于 " + expect,
                Math.abs(pages.get(1).timeMs - expect) <= 2);
        assertTrue(pages.get(1).timeMs < 30000);
    }

    @Test
    public void 一屏放得下时不参与分页() {
        List<Lrc.Page> pages = Lrc.planPages("君 no 名 wa", 1000, 9000, 28);
        assertEquals(1, pages.size());
        assertEquals(1000, pages.get(0).timeMs);
    }

    @Test
    public void 成品缓存读写往返与签名校验() throws Exception {
        java.io.File tmp = java.io.File.createTempFile("synclyric", ".lrc");
        try {
            String built = SyncLyric.build(SAMPLE);
            assertNotNull(built);

            SyncLyric.write(tmp, built);
            List<Lrc.Line> back = SyncLyric.read(tmp);
            assertNotNull("签名正确时应能读回", back);
            assertEquals(Lrc.parse(built).size(), back.size());
            assertTrue(SyncLyric.isPrebuilt(back));

            // 签名被改 → 整份作废，必须返回 null（触发重建）
            SyncLyric.write(tmp, "#syncglance sync-lyric v0; units=99\n"
                    + "[00:01.000]x" + SyncLyric.SEP + "\n");
            assertNull("签名不符必须作废", SyncLyric.read(tmp));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
