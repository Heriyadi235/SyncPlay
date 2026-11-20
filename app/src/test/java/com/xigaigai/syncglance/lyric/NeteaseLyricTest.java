package com.xigaigai.syncglance.lyric;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 网易云候选的标题匹配档位。
 *
 * 这套判定的由来是一次真实误配：
 * 车机上放《艳舞台+烈焰红唇(Live)》（串烧，311 秒），
 * 旧版打分却选中了 227 秒的录音室版《烈焰红唇》——
 * 理由仅仅是"我们的标题包含了候选的标题"给了 +40 分。
 *
 * 现在的铁律：<b>查询侧是多段式串烧时，候选必须覆盖所有段</b>，
 * 只覆盖其中一段的是另一首曲目，一律淘汰。
 */
public class NeteaseLyricTest {

    @Test
    public void 串烧只命中其中一段必须淘汰() {
        // 真实 case：艳舞台+烈焰红唇，网易云搜出来只有单曲《烈焰红唇》
        assertEquals(NeteaseLyric.T_NONE,
                NeteaseLyric.titleTier("艳舞台+烈焰红唇(Live)", "烈焰红唇"));
        assertEquals(NeteaseLyric.T_NONE,
                NeteaseLyric.titleTier("艳舞台+烈焰红唇(Live)", "烈焰红唇 (Live)"));
    }

    @Test
    public void 串烧覆盖全部段才算同一首() {
        // 同一串烧的另一种写法（缺 Live 标注）：段集合一致 → 主标题相同
        assertEquals(NeteaseLyric.T_MAIN,
                NeteaseLyric.titleTier("艳舞台+烈焰红唇(Live)", "艳舞台+烈焰红唇"));
        // 完全一样 → 整串相同
        assertEquals(NeteaseLyric.T_FULL,
                NeteaseLyric.titleTier("艳舞台+烈焰红唇(Live)", "艳舞台+烈焰红唇(Live)"));
    }

    @Test
    public void 其他分隔符也算串烧() {
        assertEquals(NeteaseLyric.T_NONE,
                NeteaseLyric.titleTier("A、B", "A"));
        assertEquals(NeteaseLyric.T_MAIN,
                NeteaseLyric.titleTier("A、B", "B、A"));
    }

    @Test
    public void 普通歌名照旧允许包含关系() {
        // 通知里歌名不带版本标注、候选带 Live —— 这是同一首歌，弱匹配但可接受
        assertEquals(NeteaseLyric.T_CONTAINS,
                NeteaseLyric.titleTier("千千阙歌", "千千阙歌 (Live)"));
        // 剥掉 A 类括号后相同
        assertEquals(NeteaseLyric.T_MAIN,
                NeteaseLyric.titleTier("梦缘 (梅艳芳\"夢伴\"原曲)", "梦缘"));
        // 完全不沾边
        assertEquals(NeteaseLyric.T_NONE,
                NeteaseLyric.titleTier("千千阙歌", "夕阳之歌"));
    }
}
