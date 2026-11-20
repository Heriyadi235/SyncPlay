package com.xigaigai.syncglance.lyric;

import android.os.Handler;
import android.os.Looper;

import com.xigaigai.syncglance.model.NowPlaying;
import com.xigaigai.syncglance.util.Log2;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 歌词管理。
 *
 * 降级链：
 *   ① 内存缓存
 *   ② 本地磁盘缓存（.lrc）
 *   ③ 网易云音乐（主源，两步：搜索定位 songId → 拉 lrc）
 *   ④ lrclib 三级降级：原名 → 剥括号 → /api/search 模糊
 *   ⑤ 都没有 → 空（写入负缓存，避免反复重查）
 *
 * 关于「歌名（中文名）」：
 *   网易云常把曲名做成带括号的形式，直接拿去精确匹配会失败。
 *   统一用 {@link TitleNorm} 剥括号 + 规范化歌手，缓存 key 也用规范名，
 *   这样「晴天」和「晴天（原唱 周杰伦）」落到同一份缓存。
 *
 * lrclib 要点：
 *   - 免费无 API key，duration 必须匹配 ±2 秒，否则 404
 *   - 必须设置 User-Agent 标识客户端
 */
public class LyricManager {

    private static final String TAG = "Lyric";
    private static final String API_GET = "https://lrclib.net/api/get";
    private static final String API_SEARCH = "https://lrclib.net/api/search";
    private static final String USER_AGENT = "SyncPlay v1.0 (Ford SYNC AppLink head unit app)";

    /** 负缓存有效期：期间同一首歌不再发起网络查询 */
    private static final long NEG_TTL_MS = 6 * 60 * 60 * 1000L;
    /** 负缓存文件后缀 */
    private static final String NEG_SUFFIX = ".none";

    public interface Callback {
        void onLyrics(NowPlaying song, List<Lrc.Line> lines);
    }

    private final File cacheDir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private List<Lrc.Line> current;
    private String currentKey;
    /**
     * 请求序号。每换一次歌自增，回调前比对：不一致就说明结果已经过期
     * （典型场景：A→B→A 快速切歌，A 的旧网络结果迟到，绝不能覆盖当前显示）。
     */
    private volatile int loadSeq;

    public LyricManager(File cacheDir) {
        this.cacheDir = cacheDir;
        if (!cacheDir.exists()) cacheDir.mkdirs();
    }

    /** 立即拿到当前缓存歌词（可能为 null） */
    public List<Lrc.Line> getCurrent() { return current; }

    /**
     * 清空磁盘缓存（原始 .lrc、成品 .sync.lrc、负缓存 .none）并丢弃内存缓存。
     *
     * 用途：歌词匹配错了 / 版本判断错了 / 负缓存把一首其实有歌词的歌锁死 6 小时，
     * 都可以用这个按钮强制重查，不用去手动清 App 数据。
     *
     * @return 删掉的文件个数
     */
    public int clearCache() {
        // 请求序号自增：正在飞行的网络结果回来后会被判定过期，不会又写回缓存
        loadSeq++;
        current = null;
        currentKey = null;

        File[] files = cacheDir == null ? null : cacheDir.listFiles();
        int removed = 0;
        if (files != null) {
            for (File f : files) {
                if (f != null && f.isFile() && f.delete()) removed++;
            }
        }
        Log2.i(TAG, "歌词缓存已清空，删除 " + removed + " 个文件");
        return removed;
    }

    /**
     * 为一首歌请求歌词。已有缓存则同步回调，否则后台网络查询。
     *
     * 缓存层次（详见 docs/development.md 4.5.4）：
     *   ① 内存 → ② 成品缓存（.sync.lrc，转写+折行+翻页都做完）
     *   → ②' 原始磁盘缓存（.lrc）→ ②.5 负缓存（.none）→ ③ 网络
     */
    public void load(NowPlaying song, Callback cb) {
        if (song == null || !song.hasData()) {
            current = null;
            currentKey = null;
            if (cb != null) cb.onLyrics(song, null);
            return;
        }

        final int seq = ++loadSeq;
        final String key = cacheKey(song);
        if (key.equals(currentKey) && current != null) {
            if (cb != null) cb.onLyrics(song, current);
            return;
        }

        final File rawFile = new File(cacheDir, key + ".lrc");
        final File syncFile = SyncLyric.fileFor(cacheDir, key);

        // ② 磁盘缓存（原始歌词）
        if (rawFile.exists()) {
            try {
                String lrc = readFile(rawFile);
                List<Lrc.Line> raw = Lrc.parse(lrc);
                if (!raw.isEmpty()) {
                    // ②' 成品歌词：转写、折行、翻页都已完成 → 直接用，零算力
                    List<Lrc.Line> ready = SyncLyric.read(syncFile);
                    if (ready != null) {
                        apply(song, key, ready, cb, "成品缓存", seq);
                        return;
                    }
                    // 没有成品：先把原始歌词显示出来（不卡住），再后台补生成
                    apply(song, key, raw, cb, "磁盘缓存", seq);
                    final String srcLrc = lrc;
                    io.execute(() -> buildSync(song, key, syncFile, srcLrc, cb, seq));
                    return;
                }
            } catch (Exception e) {
                Log2.w(TAG, "读缓存失败: " + e.getMessage());
            }
        }

        // ②.5 负缓存：最近查过且确实没有，短期内不再打网络
        File neg = new File(cacheDir, key + NEG_SUFFIX);
        if (neg.exists() && System.currentTimeMillis() - neg.lastModified() < NEG_TTL_MS) {
            Log2.i(TAG, "负缓存命中，跳过网络查询: " + song.title);
            current = null;
            currentKey = key;
            if (cb != null) cb.onLyrics(song, null);
            return;
        }

        // ③ 在线查询：网易云优先，lrclib 兜底
        io.execute(() -> {
            String lrc = null;
            String src = null;
            // 是否发生过网络层失败。网络失败 ≠ 确实没有歌词，
            // 前者绝不能写负缓存，否则一次断网会让这首歌"消失"六小时。
            boolean[] netError = {false};

            // ③-A 网易云音乐
            try {
                lrc = NeteaseLyric.fetch(song.title, song.artist, song.durationMs);
                if (lrc != null && !lrc.trim().isEmpty()) {
                    src = "网易云";
                } else {
                    lrc = null;
                    if (NeteaseLyric.lastWasNetworkError()) netError[0] = true;
                }
            } catch (Exception e) {
                Log2.w(TAG, "网易云异常: " + e.getMessage());
                netError[0] = true;
            }

            // ③-B lrclib 三级降级
            if (lrc == null) {
                lrc = fetchFromLrclibTiered(song, netError);
                if (lrc != null && !lrc.trim().isEmpty()) src = "lrclib";
                else lrc = null;
            }

            // 解析 + 落盘 + 预生成成品歌词（全部在 IO 线程）
            List<Lrc.Line> lines = null;
            if (lrc != null) {
                List<Lrc.Line> parsed = Lrc.parse(lrc);
                if (!parsed.isEmpty()) {
                    writeFile(rawFile, lrc);
                    //noinspection ResultOfMethodCallIgnored
                    neg.delete();

                    String built = null;
                    try {
                        built = SyncLyric.build(lrc);
                    } catch (Throwable t) {
                        Log2.w(TAG, "生成成品歌词失败: " + t);
                    }
                    if (built != null) {
                        SyncLyric.write(syncFile, built);
                        lines = Lrc.parse(built);
                        if (lines.isEmpty()) lines = parsed;
                        else src = src + "+成品";
                    } else {
                        lines = parsed;
                    }
                }
            }

            final List<Lrc.Line> result = lines;
            final String source = src;
            final boolean hadNetError = netError[0];
            // 负缓存也放到 IO 线程写，别占主线程
            if (result == null && !hadNetError) writeFile(neg, "1");

            main.post(() -> {
                if (seq != loadSeq) return;
                if (result != null && !result.isEmpty()) {
                    apply(song, key, result, cb, source, seq);
                } else {
                    if (hadNetError) {
                        // 网络问题：不写负缓存，下次切回来还能重试
                        Log2.i(TAG, "网络异常导致无歌词，不写负缓存: " + song.title);
                    } else {
                        Log2.i(TAG, "无歌词: " + song.title + " - " + song.artist);
                    }
                    current = null;
                    currentKey = key;
                    if (cb != null) cb.onLyrics(song, null);
                }
            });
        });
    }

    /**
     * 后台把原始 LRC 转成车机专用成品歌词，并写盘。
     * 只在「原始歌词已显示、但成品还没生成」时走这条路径，不阻塞首屏。
     */
    private void buildSync(NowPlaying song, String key, File syncFile,
                           String lrc, Callback cb, int seq) {
        try {
            String built = SyncLyric.build(lrc);
            if (built == null) return;
            List<Lrc.Line> lines = Lrc.parse(built);
            if (lines.isEmpty()) return;
            SyncLyric.write(syncFile, built);
            Log2.i(TAG, "成品歌词就绪: " + song.title + " 共 " + lines.size() + " 屏");
            main.post(() -> apply(song, key, lines, cb, "成品缓存", seq));
        } catch (Throwable t) {
            Log2.w(TAG, "生成成品歌词失败: " + t);
        }
    }

    // ---------------- lrclib 三级降级 ----------------

    /**
     * 级别 1：原名精确匹配（与原行为一致）
     * 级别 2：剥括号后精确匹配
     * 级别 3：/api/search 模糊搜索，按 trackName 相似度挑一条
     */
    private String fetchFromLrclibTiered(NowPlaying song, boolean[] netError) {
        String title = song.title == null ? "" : song.title;
        String artist = song.artist == null ? "" : song.artist;
        String main = TitleNorm.stripBrackets(title);
        String art1 = TitleNorm.primaryArtist(artist);

        // 级别 1：原名
        String r = lrclibGet(title, art1, song.durationMs, netError);
        if (r != null) {
            Log2.i(TAG, "lrclib 命中(原名): " + title);
            return r;
        }

        // 级别 2：剥括号（和原名不同才试，省一次请求）
        if (!main.equalsIgnoreCase(title.trim())) {
            r = lrclibGet(main, art1, song.durationMs, netError);
            if (r != null) {
                Log2.i(TAG, "lrclib 命中(剥括号): " + main);
                return r;
            }
        }

        // 级别 3：模糊搜索
        r = lrclibSearch(main.isEmpty() ? title : main, art1, song.durationMs, netError);
        if (r != null) {
            Log2.i(TAG, "lrclib 命中(搜索): " + title);
            return r;
        }

        return null;
    }

    /** lrclib 精确匹配，duration 由服务端 ±2s 校验 */
    private String lrclibGet(String title, String artist, long durationMs, boolean[] netError) {
        try {
            StringBuilder url = new StringBuilder(API_GET)
                    .append("?track_name=").append(enc(title))
                    .append("&artist_name=").append(enc(artist == null ? "" : artist));
            if (durationMs > 0) {
                long sec = Math.round(durationMs / 1000.0);
                if (sec >= 1 && sec <= 3600) {
                    url.append("&duration=").append(sec);
                }
            }
            String body = httpGetJson(url.toString());
            if (body == null) {
                if (lastHttpWasNetworkError) netError[0] = true;
                return null;
            }
            return extractSyncedLyrics(body);
        } catch (Exception e) {
            Log2.w(TAG, "lrclib get 失败: " + e.getMessage());
            netError[0] = true;
            return null;
        }
    }

    /**
     * lrclib 模糊搜索。
     *
     * 返回数组，每项含 trackName / artistName / duration / syncedLyrics。
     * 这里挑时长最接近且 syncedLyrics 非空的一条，避免选到 Live 版。
     */
    private String lrclibSearch(String title, String artist, long durationMs, boolean[] netError) {
        try {
            StringBuilder url = new StringBuilder(API_SEARCH)
                    .append("?q=").append(enc(title));
            if (artist != null && !artist.isEmpty()) {
                url.append("+").append(enc(artist));
            }
            String body = httpGetJson(url.toString());
            if (body == null) {
                if (lastHttpWasNetworkError) netError[0] = true;
                return null;
            }

            org.json.JSONArray arr = new org.json.JSONArray(body);
            if (arr.length() == 0) return null;

            long wantSec = durationMs > 0 ? Math.round(durationMs / 1000.0) : -1;
            String bestLrc = null;
            long bestDiff = Long.MAX_VALUE;

            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String sl = o.optString("syncedLyrics", "");
                if (sl.isEmpty() || "null".equals(sl)) continue;

                long dur = Math.round(o.optDouble("duration", 0));
                long diff = wantSec > 0 && dur > 0 ? Math.abs(dur - wantSec) : 0;

                if (bestLrc == null || diff < bestDiff) {
                    bestLrc = sl;
                    bestDiff = diff;
                }
                // 差 2 秒以内直接收工
                if (wantSec > 0 && diff <= 2) break;
            }
            if (bestLrc != null && wantSec > 0 && bestDiff > 30) {
                Log2.w(TAG, "lrclib 搜索结果时长偏差 " + bestDiff + "s，可能是错误版本");
            }
            return bestLrc;
        } catch (Exception e) {
            Log2.w(TAG, "lrclib search 失败: " + e.getMessage());
            return null;
        }
    }

    /** 通用 JSON GET，404/429 都返回 null */
    private String httpGetJson(String url) {
        lastHttpWasNetworkError = false;
        try {
            Log2.i(TAG, "查询 " + url);
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);

            int code = c.getResponseCode();
            if (code == 429) {
                Log2.w(TAG, "lrclib 限流，Retry-After=" + c.getHeaderField("Retry-After"));
                // ★ 限流是「这次没问出结果」，不是「确实没有歌词」。
                //   不置位会被当成 404 一样写 6 小时负缓存，一次限流就让这首歌消失半天。
                lastHttpWasNetworkError = true;
                return null;
            }
            if (code == 404) {
                return null;
            }
            if (code != 200) {
                Log2.w(TAG, "lrclib HTTP " + code);
                return null;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            Log2.w(TAG, "HTTP 失败: " + e.getMessage());
            // 连接层异常（断网、DNS、超时）—— 与"确实没有"要区分开
            lastHttpWasNetworkError = true;
            return null;
        }
    }

    /** 最近一次 httpGetJson 是否因为网络层失败（而非 404/429） */
    private volatile boolean lastHttpWasNetworkError = false;

    /**
     * 落地歌词并回调。
     *
     * ★ 必须先校验请求序号：IO 线程是串行的，快速切歌时旧请求的结果会晚于新请求到达，
     *   不校验就会出现「放着 B、屏幕显示 A 的歌词」，而且会一直错到下次切歌。
     */
    private void apply(NowPlaying song, String key, List<Lrc.Line> lines,
                       Callback cb, String src, int seq) {
        if (seq != loadSeq) {
            Log2.i(TAG, "丢弃过期歌词(" + src + "): " + song.title);
            return;
        }
        current = lines;
        currentKey = key;
        Log2.i(TAG, "歌词就绪(" + src + "): " + song.title + " 共 " + lines.size() + " 句");
        if (cb != null) cb.onLyrics(song, lines);
    }

    // ---------------- lrclib 辅助 ----------------

    /** 从 JSON 里抠出 syncedLyrics 字段（不引 JSON 库，简单解析） */
    private static String extractSyncedLyrics(String json) {
        String key = "\"syncedLyrics\":";
        int i = json.indexOf(key);
        if (i < 0) return null;
        int p = i + key.length();
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length()) return null;
        if (json.startsWith("null", p)) return null;
        if (json.charAt(p) != '"') return null;

        StringBuilder sb = new StringBuilder();
        p++;
        while (p < json.length()) {
            char ch = json.charAt(p);
            if (ch == '\\') {
                if (p + 1 >= json.length()) break;
                char n = json.charAt(++p);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case 'u':
                        if (p + 4 < json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(
                                        json.substring(p + 1, p + 5), 16));
                                p += 4;
                            } catch (NumberFormatException ignored) { }
                        }
                        break;
                    default: sb.append(n);
                }
                p++;
            } else if (ch == '"') {
                break;
            } else {
                sb.append(ch);
                p++;
            }
        }
        return sb.toString();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------- 缓存 ----------------

    private static String cacheKey(NowPlaying song) {
        // ★ 用「通知里来的原始完整歌名」做 key，不做任何剥括号。
        //
        //   原因：剥括号后「夕阳之歌」和「夕阳之歌 (Live)」会撞成同一个 key，
        //   于是 Live 歌词被当成录音室版缓存下来，之后再也查不对。
        //   歌名的身份就交给原始字符串，查询时怎么变换是另一回事。
        //
        //   时长不参与 key —— 很多播放器不给 duration（=0），
        //   那时同名不同版本会全撞在一起。时长只在命中后做校验。
        String raw = (song.title == null ? "" : song.title).trim() + "|"
                + (song.artist == null ? "" : song.artist).trim();
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(raw.hashCode());
        }
    }

    private static String readFile(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        return sb.toString();
    }

    private static void writeFile(File f, String content) {
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(content);
        } catch (Exception e) {
            Log2.w(TAG, "写缓存失败: " + e.getMessage());
        }
    }
}
