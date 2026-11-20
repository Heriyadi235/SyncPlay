package com.xigaigai.syncglance.lyric;

import com.xigaigai.syncglance.util.Log2;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 网易云音乐歌词源（主源）。
 *
 * 分两步，<b>先精确、后搜索</b>：
 *
 * <ol>
 *   <li><b>MEDIA_ID 直取</b>（命中率最高）：
 *       网易云播放时 {@code METADATA_KEY_MEDIA_ID} 就是它的 song id，
 *       拿 {@code /api/song/detail?ids=[id]} 反查曲目信息，
 *       与通知里的歌名/歌手/时长对得上才取歌词 —— 对得上就是 100% 正确。</li>
 *   <li><b>搜索兜底</b>：{@code /api/search/get/web?s=歌名} → 候选 → 打分挑一条。</li>
 * </ol>
 *
 * <h3>为什么打分要重写（真实踩坑）</h3>
 *
 * 《艳舞台+烈焰红唇(Live)》是一首 <b>串烧</b>（MediaMetadata 的 TITLE 就是这么写的）。
 * 旧版打分里"标题包含"只值 +40、歌手 +60、时长差 84 秒只扣 40，
 * 于是 227 秒的录音室版《烈焰红唇》以 score=60 被选中，
 * 车机上整首歌都在显示另一首曲子的词。教训有两条：
 *
 * <ul>
 *   <li><b>串烧必须整串覆盖</b>：{@code A+B} 命中其中一段，是<b>另一首曲目</b>，不是"近似命中"。</li>
 *   <li><b>软扣分不否决，等于没有</b>：只要还有一项加分，错误候选照样能夺冠。
 *       现在改成硬门槛（不通过直接淘汰该候选），不再算加权总分。</li>
 * </ul>
 *
 * 另外端点都是非公开接口，随时可能变，所以 LyricManager 里还有 lrclib 兜底。
 */
final class NeteaseLyric {

    private static final String TAG = "Lyric";
    private static final String API_SEARCH =
            "https://music.163.com/api/search/get/web?s=%s&type=1&limit=8&offset=0";
    private static final String API_LYRIC =
            "https://music.163.com/api/song/lyric?id=%s&lv=1&kv=1&tv=-1";
    private static final String API_DETAIL =
            "https://music.163.com/api/song/detail?ids=[%s]";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String REFERER = "https://music.163.com/";

    // 档位常量对单测可见（同一包内）
    static final int T_NONE = 0;
    static final int T_CONTAINS = 2;
    static final int T_MAIN = 3;
    static final int T_FULL = 4;

    // ---------------- 歌手匹配档位 ----------------
    private static final int A_MISMATCH = 0;
    private static final int A_LOOSE = 1;
    private static final int A_EXACT = 2;

    /**
     * 标题只是「包含」关系时，允许的最大时长差。
     * 超过就认为是另一首录音（录音室 vs 现场、串烧 vs 单曲）。
     */
    private static final long MAX_DUR_DIFF_WEAK_MS = 60_000L;

    /** MEDIA_ID 直取时，允许的时长差（detail 接口给的是精确毫秒） */
    private static final long MAX_DUR_DIFF_BY_ID_MS = 5_000L;

    /** 候选曲目 */
    private static final class Cand {
        long id;
        String name;
        String artist;
        long durationMs;
        /** 与通知里的时长差；未知为 -1 */
        long durDiffMs = -1;
        int titleTier = T_NONE;
        int artistTier = A_MISMATCH;
    }

    private NeteaseLyric() { }

    /** 最近一次 fetch 是否因为网络层失败（而非单纯没找到） */
    private static volatile boolean sNetworkError = false;

    /** 上一次 fetch 是否属于网络失败。false 表示"确实没有这首歌的歌词" */
    static boolean lastWasNetworkError() { return sNetworkError; }

    /**
     * 按 MEDIA_ID（优先）+ 歌名 + 歌手 + 时长找歌词。
     *
     * @param mediaId 媒体会话的 METADATA_KEY_MEDIA_ID，可为 null
     * @return LRC 文本；找不到返回 null
     */
    static String fetch(String mediaId, String title, String artist, long durationMs) {
        sNetworkError = false;
        boolean hasTitle = title != null && !title.trim().isEmpty();
        if (!hasTitle && isBlank(mediaId)) return null;

        // ① 精确路径：MEDIA_ID 直取。只有校验通过才用，避免别的播放器的 id 撞号。
        String lrc = fetchByMediaId(mediaId, title, artist, durationMs);
        if (lrc != null) return lrc;
        if (sNetworkError) return null;   // 网络挂了就别再打第二次

        if (!hasTitle) return null;

        // ② 搜索路径。★ 用完整原始歌名搜索，不做剥括号。
        //
        //   教训：唐朝乐队的「天堂」把括号部分剥掉后，搜出来是另一个歌手的
        //   《天堂》，时长又接近，打分选了错的。括号里往往正是区分度最高的信息
        //   （译名 / 出处 / 版本），剥掉等于自废武功。
        //   剥括号只在 lrclib 精确匹配那一级使用（那边是精确匹配，剥了才有意义）。
        String query = title.trim();
        try {
            String searchJson = httpGet(String.format(API_SEARCH, enc(query)));
            if (searchJson == null) return null;

            Cand best = pickBest(searchJson, title, artist, durationMs);
            if (best == null) {
                Log2.i(TAG, "网易云无合适候选（标题/歌手/时长都没通过校验）: " + query);
                return null;
            }
            Log2.i(TAG, "网易云选中 id=" + best.id + " 《" + best.name + "》 " + best.artist
                    + " 标题=" + titleTierName(best.titleTier)
                    + " 歌手=" + artistTierName(best.artistTier)
                    + (best.durDiffMs >= 0 ? " 时长差=" + best.durDiffMs / 1000 + "s" : " 时长差=未知"));
            return lyricOf(String.valueOf(best.id));
        } catch (Exception e) {
            Log2.w(TAG, "网易云查询失败: " + e.getMessage());
            return null;
        }
    }

    // ---------------- ① MEDIA_ID 直取 ----------------

    /**
     * 用 mediaId 当 song id 直接取歌词。
     *
     * <b>必须反查校验</b>：只有网易云播放器给的 mediaId 才是 song id，
     * 别的播放器可能是专辑内序号、数据库主键等等，撞号了就会查到毫不相干的歌。
     * 校验三件套：标题档位 ≥ 同主标题、时长差 ≤ 5 秒、歌手不是完全不认识。
     *
     * @return 校验通过且取到歌词才返回文本，否则 null（调用方继续走搜索）
     */
    private static String fetchByMediaId(String mediaId, String title,
                                        String artist, long durationMs) {
        if (isBlank(mediaId)) return null;
        String id = mediaId.trim();
        // 非纯数字一定不是网易云 song id
        if (!id.matches("\\d{1,20}")) return null;

        String detailJson;
        try {
            detailJson = httpGet(String.format(API_DETAIL, id));
        } catch (Exception e) {
            Log2.w(TAG, "网易云 MEDIA_ID 反查失败: " + e.getMessage());
            return null;
        }
        if (detailJson == null) return null;

        try {
            JSONObject song = firstSong(new JSONObject(detailJson));
            if (song == null) return null;

            String name = song.optString("name", "");
            String art = joinArtists(song);
            long dur = song.optLong("duration", 0);

            if (!plausible(title, artist, durationMs, name, art, dur)) {
                Log2.i(TAG, "MEDIA_ID=" + id + " 反查不到同一首（返回《" + name + "》"
                        + art + (dur > 0 ? " " + dur / 1000 + "s" : "") + "），改走搜索");
                return null;
            }

            Log2.i(TAG, "网易云按 MEDIA_ID 直取: id=" + id + " 《" + name + "》 " + art
                    + (dur > 0 ? " " + dur / 1000 + "s" : ""));
            String lrc = lyricOf(id);
            if (lrc == null) Log2.i(TAG, "该曲目无歌词: id=" + id);
            return lrc;
        } catch (Exception e) {
            Log2.w(TAG, "MEDIA_ID 反查结果解析失败: " + e.getMessage());
            return null;
        }
    }

    /** 反查回来的曲目，是不是通知里那首 */
    private static boolean plausible(String title, String artist, long durationMs,
                                    String cName, String cArtist, long cDur) {
        // 通知里没有歌名（极少数播放器只给 mediaId）时，只要时长对得上就认
        boolean hasTitle = title != null && !title.trim().isEmpty();
        if (hasTitle) {
            int t = titleTier(title, cName);
            if (t < T_MAIN) return false;
            // 整串相同的时候，歌手字段各家写法差异大，不强制
            if (t < T_FULL && artistTier(artist, cArtist) == A_MISMATCH) return false;
        }
        if (durationMs > 0 && cDur > 0
                && Math.abs(cDur - durationMs) > MAX_DUR_DIFF_BY_ID_MS) {
            return false;
        }
        return true;
    }

    // ---------------- ② 搜索候选筛选 ----------------

    private static Cand pickBest(String json, String wantTitle,
                                 String wantArtist, long durationMs) {
        try {
            JSONObject result = new JSONObject(json).optJSONObject("result");
            if (result == null) return null;
            JSONArray songs = result.optJSONArray("songs");
            if (songs == null || songs.length() == 0) return null;

            Cand best = null;
            for (int i = 0; i < songs.length(); i++) {
                JSONObject s = songs.optJSONObject(i);
                if (s == null) continue;

                Cand c = new Cand();
                c.id = s.optLong("id");
                c.name = s.optString("name", "");
                c.durationMs = s.optLong("duration", 0);
                c.artist = joinArtists(s);

                // ① 标题档位：沾不上边、或串烧只覆盖一段 → 直接淘汰
                c.titleTier = titleTier(wantTitle, c.name);
                if (c.titleTier == T_NONE) continue;

                if (durationMs > 0 && c.durationMs > 0) {
                    c.durDiffMs = Math.abs(c.durationMs - durationMs);
                }

                // ② 时长硬门槛：标题只是"包含"关系、时长又差一大截 → 另一首录音
                if (c.titleTier == T_CONTAINS && c.durDiffMs > MAX_DUR_DIFF_WEAK_MS) continue;

                // ③ 歌手硬门槛：标题对上了但歌手完全是另一个人 → 另一首录音
                //    （整串完全相同的情况放宽，各家歌手字段写法差异太大）
                c.artistTier = artistTier(wantArtist, c.artist);
                if (c.artistTier == A_MISMATCH && c.titleTier < T_FULL) continue;

                if (best == null || betterThan(c, best)) best = c;
            }
            return best;
        } catch (Exception e) {
            Log2.w(TAG, "网易云搜索结果解析失败: " + e.getMessage());
            return null;
        }
    }

    /** 标题档位 → 歌手档位 → 时长差（越小越好） */
    private static boolean betterThan(Cand a, Cand b) {
        if (a.titleTier != b.titleTier) return a.titleTier > b.titleTier;
        if (a.artistTier != b.artistTier) return a.artistTier > b.artistTier;
        if (a.durDiffMs < 0) return false;           // 未知时长排最后
        if (b.durDiffMs < 0) return true;
        return a.durDiffMs < b.durDiffMs;
    }

    /**
     * 标题匹配档位。
     *
     * <p>关键的一条：<b>查询侧是多段式（串烧）时，候选必须覆盖所有段</b>。
     * 例：want = {@code 艳舞台+烈焰红唇(Live)}，候选 {@code 烈焰红唇} 只覆盖一段，
     * 它是另一首曲目，返回 {@link #T_NONE} 淘汰 —— 这正是本次误配的根因。</p>
     *
     * <p>包级可见是为了让单测能直接验证（见 {@code NeteaseLyricTest}）。</p>
     */
    static int titleTier(String want, String cand) {
        if (want == null || cand == null) return T_NONE;
        String wFull = want.trim().toLowerCase(Locale.US);
        String cFull = cand.trim().toLowerCase(Locale.US);
        if (wFull.isEmpty() || cFull.isEmpty()) return T_NONE;
        if (cFull.equals(wFull)) return T_FULL;

        String wMain = TitleNorm.stripBrackets(want).toLowerCase(Locale.US);
        String cMain = TitleNorm.stripBrackets(cand).toLowerCase(Locale.US);
        if (wMain.equals(cMain)) return T_MAIN;

        Set<String> wSegs = segments(wMain);
        if (wSegs.size() >= 2) {
            // 串烧：段集合必须全覆盖，否则就是其中一首单曲
            return segments(cMain).containsAll(wSegs) ? T_MAIN : T_NONE;
        }

        if (cMain.contains(wMain) || wMain.contains(cMain)) return T_CONTAINS;
        return T_NONE;
    }

    /**
     * 把歌名切成"段"，用于识别串烧。
     *
     * 分隔符取串烧/合作最常见的写法：{@code + & 、 , / |} 以及 with / medley / 串烧 / 组曲。
     * 每段再剥一次 A 类括号，这样 {@code 烈焰红唇(Live)} 与 {@code 烈焰红唇} 算同一段。
     */
    private static Set<String> segments(String mainTitle) {
        Set<String> out = new LinkedHashSet<>();
        if (mainTitle == null || mainTitle.isEmpty()) return out;
        String s = mainTitle.toLowerCase(Locale.US)
                .replaceAll("\\bwith\\b", "+")
                .replaceAll("\\bmedley\\b", "+")
                .replace("\u4e32\u70e7", "+")   // 串烧
                .replace("\u7ec4\u66f2", "+")   // 组曲
                .replace("\u8054\u5531", "+");  // 联唱
        for (String p : s.split("[+\uff0b&\uff06\u3001,\uff0c/\\/\uff0f|\\|\uff5c]")) {
            // ★ 这里不能用 TitleNorm.stripBrackets —— 它<b>故意保留</b>版本标签，
            //   于是「烈焰红唇(Live)」与「烈焰红唇」会被当成两段。
            //   比较"是不是同一段"时，括号里的内容一律忽略。
            String seg = bare(p);
            if (!seg.isEmpty()) out.add(seg);
        }
        return out;
    }

    /**
     * 去掉所有成对括号及其内容（不区分是不是版本标签），只用于"段"的比对。
     * 孤立括号原样保留，宁可留着也不误删正文。
     */
    private static String bare(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            char close = closeOf(c);
            if (close == 0) {
                out.append(c);
                i++;
                continue;
            }
            int j = i + 1;
            boolean nested = false;
            while (j < n && s.charAt(j) != close) {
                if (closeOf(s.charAt(j)) != 0) { nested = true; break; }
                j++;
            }
            if (j < n && s.charAt(j) == close && !nested) {
                i = j + 1;                       // 整组丢弃
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString().replaceAll("\\s{2,}", " ").trim();
    }

    /** 左括号 → 对应右括号；不是左括号返回 0 */
    private static char closeOf(char c) {
        switch (c) {
            case '(': return ')';
            case '\uff08': return '\uff09';
            case '[': return ']';
            case '\u3010': return '\u3011';
            case '{': return '}';
            default: return 0;
        }
    }

    private static int artistTier(String wantArtist, String candArtist) {
        String w = TitleNorm.primaryArtist(wantArtist).toLowerCase(Locale.US);
        if (w.isEmpty()) return A_LOOSE;             // 通知里没给歌手，这项不参与判定
        String c = TitleNorm.primaryArtist(candArtist).toLowerCase(Locale.US);
        if (c.isEmpty()) return A_LOOSE;
        if (w.equals(c)) return A_EXACT;
        if (c.contains(w) || w.contains(c)) return A_LOOSE;
        return A_MISMATCH;
    }

    private static String titleTierName(int t) {
        switch (t) {
            case T_FULL: return "整串相同";
            case T_MAIN: return "主标题相同";
            case T_CONTAINS: return "包含";
            default: return "无关";
        }
    }

    private static String artistTierName(int a) {
        switch (a) {
            case A_EXACT: return "一致";
            case A_LOOSE: return "近似/未知";
            default: return "不符";
        }
    }

    // ---------------- JSON 小工具 ----------------

    private static JSONObject firstSong(JSONObject root) {
        JSONArray songs = root.optJSONArray("songs");
        if (songs == null || songs.length() == 0) return null;
        return songs.optJSONObject(0);
    }

    private static String joinArtists(JSONObject song) {
        StringBuilder art = new StringBuilder();
        JSONArray artists = song.optJSONArray("artists");
        if (artists == null) return "";
        for (int k = 0; k < artists.length(); k++) {
            JSONObject a = artists.optJSONObject(k);
            if (a == null) continue;
            if (art.length() > 0) art.append('/');
            art.append(a.optString("name", ""));
        }
        return art.toString();
    }

    /** 取某首歌的 LRC 文本 */
    private static String lyricOf(String id) {
        try {
            String lyricJson = httpGet(String.format(API_LYRIC, id));
            if (lyricJson == null) return null;
            return extractLrc(lyricJson);
        } catch (Exception e) {
            Log2.w(TAG, "网易云取歌词失败: " + e.getMessage());
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ---------------- HTTP ----------------

    private static String httpGet(String url) throws Exception {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Referer", REFERER);
            c.setRequestProperty("Accept", "*/*");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);

            int code = c.getResponseCode();
            if (code != 200) {
                Log2.w(TAG, "网易云 HTTP " + code + " " + url);
                // HTTP 层面拿到响应 = 网络是通的，不算网络错误
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            // 连接/读取异常 = 网络层失败
            sNetworkError = true;
            throw e;
        }
    }

    private static String extractLrc(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject lrc = root.optJSONObject("lrc");
            if (lrc == null) return null;
            String s = lrc.optString("lyric", "");
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
