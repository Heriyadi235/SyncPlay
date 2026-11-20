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

/**
 * 网易云音乐歌词源（主源）。
 *
 * 两步走：
 *   ① /api/search/get/web?s=歌名&type=1   → 候选曲目（含 id / name / artists / duration）
 *   ② /api/song/lyric?id=xxx&lv=1&kv=1&tv=-1 → lrc.lyric（带 [mm:ss.xxx] 时间轴）
 *
 * 实测两个端点都无需登录、无需签名，只要带 Referer 即可。
 * 这是非公开接口，随时可能变，所以 LyricManager 里有 lrclib 兜底。
 */
final class NeteaseLyric {

    private static final String TAG = "Lyric";
    private static final String API_SEARCH =
            "https://music.163.com/api/search/get/web?s=%s&type=1&limit=8&offset=0";
    private static final String API_LYRIC =
            "https://music.163.com/api/song/lyric?id=%s&lv=1&kv=1&tv=-1";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String REFERER = "https://music.163.com/";

    /** 候选曲目 */
    private static final class Cand {
        long id;
        String name;
        String artist;
        long durationMs;
        int score = 0;
    }

    private NeteaseLyric() { }

    /** 最近一次 fetch 是否因为网络层失败（而非单纯没找到） */
    private static volatile boolean sNetworkError = false;

    /** 上一次 fetch 是否属于网络失败。false 表示"确实没有这首歌的歌词" */
    static boolean lastWasNetworkError() { return sNetworkError; }

    /**
     * 按歌名+歌手+时长找歌词。
     * @return LRC 文本；找不到返回 null
     */
    static String fetch(String title, String artist, long durationMs) {
        sNetworkError = false;
        if (title == null || title.trim().isEmpty()) return null;

        // ★ 用完整原始歌名搜索，不做剥括号。
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
                Log2.i(TAG, "网易云无合适候选: " + query);
                return null;
            }
            Log2.i(TAG, "网易云选中 id=" + best.id + " 《" + best.name
                    + "》 " + best.artist + " score=" + best.score);

            String lyricJson = httpGet(String.format(API_LYRIC, best.id));
            if (lyricJson == null) return null;

            String lrc = extractLrc(lyricJson);
            if (lrc == null || lrc.trim().isEmpty()) {
                Log2.i(TAG, "网易云该曲无歌词: id=" + best.id);
                return null;
            }
            return lrc;
        } catch (Exception e) {
            Log2.w(TAG, "网易云查询失败: " + e.getMessage());
            return null;
        }
    }

    // ---------------- 候选筛选 ----------------

    private static Cand pickBest(String json, String wantTitle,
                                 String wantArtist, long durationMs) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject result = root.optJSONObject("result");
            if (result == null) return null;
            JSONArray songs = result.optJSONArray("songs");
            if (songs == null || songs.length() == 0) return null;

            String wantFull = wantTitle.trim().toLowerCase();
            String wantMain = TitleNorm.stripBrackets(wantTitle).toLowerCase();
            String wantArt = TitleNorm.primaryArtist(wantArtist).toLowerCase();
            long wantSec = durationMs > 0 ? Math.round(durationMs / 1000.0) : -1;

            Cand best = null;
            for (int i = 0; i < songs.length(); i++) {
                JSONObject s = songs.optJSONObject(i);
                if (s == null) continue;

                Cand c = new Cand();
                c.id = s.optLong("id");
                c.name = s.optString("name", "");
                c.durationMs = s.optLong("duration", 0);

                StringBuilder art = new StringBuilder();
                JSONArray artists = s.optJSONArray("artists");
                if (artists != null) {
                    for (int k = 0; k < artists.length(); k++) {
                        JSONObject a = artists.optJSONObject(k);
                        if (a == null) continue;
                        if (art.length() > 0) art.append('/');
                        art.append(a.optString("name", ""));
                    }
                }
                c.artist = art.toString();

                String cFull = c.name.trim().toLowerCase();
                String cMain = TitleNorm.stripBrackets(c.name).toLowerCase();

                if (cFull.equals(wantFull)) c.score += 140;          // 整串完全一样，最可信
                else if (cMain.equals(wantMain)) c.score += 100;     // 主标题一样
                else if (cMain.contains(wantMain) || wantMain.contains(cMain)) c.score += 40;
                else if (!cFull.contains(wantFull)) c.score -= 60;   // 完全不沾边，重罚

                if (!wantArt.isEmpty()) {
                    String cArt = TitleNorm.primaryArtist(c.artist).toLowerCase();
                    if (cArt.equals(wantArt)) c.score += 60;
                    else if (c.artist.toLowerCase().contains(wantArt)
                            || wantArt.contains(cArt)) c.score += 25;
                    else c.score -= 50;                              // ★ 歌手对不上要扣分
                }

                // 时长接近加分：排除 Live / 翻唱最有效的一招
                if (wantSec > 0 && c.durationMs > 0) {
                    long diff = Math.abs(Math.round(c.durationMs / 1000.0) - wantSec);
                    if (diff <= 2) c.score += 50;
                    else if (diff <= 5) c.score += 25;
                    else if (diff > 30) c.score -= 40;
                }

                if (c.score > 0 && (best == null || c.score > best.score)) best = c;
            }
            return best;
        } catch (Exception e) {
            Log2.w(TAG, "网易云搜索结果解析失败: " + e.getMessage());
            return null;
        }
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
