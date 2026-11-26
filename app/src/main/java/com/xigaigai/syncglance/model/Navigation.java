package com.xigaigai.syncglance.model;

/**
 * 导航状态。
 *
 * 数据来源：导航 app 的通知（高德/百度）。
 * 阶段 1 只做抓取器，把通知原样打进日志；
 * 解析规则等拿到真实格式后再写，见 {@link NavigationParser}。
 */
public class Navigation {

    /** 转向指令，如「前方300米右转」 */
    public String instruction;
    /** 路名，如「进入建国路」 */
    public String road;
    /** 剩余距离文本，如「剩余12.4公里」 */
    public String remainDistance;
    /** 剩余距离（米），用于算 ETA */
    public long remainDistanceMeters = -1;
    /** 剩余时间（秒） */
    public long remainSeconds = -1;
    /** 预计到达时间戳（毫秒），解析不出就用 now + remainSeconds 推算 */
    public long etaMillis = -1;
    /** 最后更新时间 */
    public long lastUpdateMillis;

    /** 有可显示的内容 */
    public boolean hasData() {
        return notEmpty(instruction) || notEmpty(road) || notEmpty(remainDistance);
    }

    /** 清空所有导航数据 */
    public void clear() {
        instruction = null;
        road = null;
        remainDistance = null;
        remainDistanceMeters = -1;
        remainSeconds = -1;
        etaMillis = -1;
        lastUpdateMillis = 0;
        cachedEtaText = null;
        cachedEtaAt = 0;
    }

    /**
     * 数据多久没更新就"可能停了"（10 秒）。
     *
     * <p>★ <b>高德是按需推送，不是恒定心跳。</b>它的 1 秒一次只是<b>最大刷新率</b>；
     * 车停着不动、或者长时间匀速直行时，剩余距离和转向都没变化，
     * 高德压根不会发数据。所以「多久没数据」<b>不能</b>单独用来判定导航结束
     * —— 否则停个红灯屏幕就闪回空闲，用户会以为导航自己退出了。</p>
     *
     * <p>这个阈值现在的含义是「该去探一次活了」的起点，真正的判定见
     * {@code StateRenderer.staleWatchdog}（探活 + 本值与 {@link #LONG_STALE_MS} 两段）。</p>
     */
    public static final long STALE_MS = 10_000;

    /**
     * 硬兜底：数据停这么久（180 秒 = 3 分钟），即使高德进程还活着也判定导航结束。
     *
     * <p>兜的是一种探活查不出来的情况：导航已经结束、但高德进程并没有退出，
     * binder 一直活着。只靠探活的话，屏幕会永远停在最后一条转向指令上。</p>
     *
     * <p>为什么是 3 分钟：够长，停车、堵车、长直路都不会被误判；
     * 又够短，真的结束导航后不会留下太久。</p>
     */
    public static final long LONG_STALE_MS = 180_000;

    /**
     * 数据是否还新鲜。
     *
     * <p>⚠️ 这里只做「时间」这一维的判断，<b>不知道</b>导航是不是真结束了
     * —— 因为高德停了推送很可能只是车没动。真正决定降级的逻辑在
     * {@code StateRenderer.staleWatchdog}：数据超 {@link #STALE_MS} 后先探活，
     * binder 已死立即降级，binder 活着则要等满 {@link #LONG_STALE_MS}。</p>
     *
     * <p>★ 这里以前还有一条 {@code if (manualMode && lastUpdateMillis == 0) return true;}
     * ——那个 {@code manualMode}（老「导航模式」按钮）只在时间戳恰好为 0 时才起作用，
     * 而真实数据流里 {@code onDecrypted} 每次都会写入时间戳，等于永远不生效，已删除。
     * 现在的「导航优先」是纯显示层开关，在 {@code StateRenderer} 里，不参与新鲜度判断。</p>
     */
    public boolean isFresh() {
        if (!hasData()) return false;
        long age = System.currentTimeMillis() - lastUpdateMillis;
        return lastUpdateMillis > 0 && age >= 0 && age < STALE_MS;
    }

    /** 数据已经过期多久（毫秒），没过 {@link #STALE_MS} 返回 -1 */
    public long staleAge() {
        if (!hasData() || lastUpdateMillis == 0) return -1;
        long age = System.currentTimeMillis() - lastUpdateMillis;
        return age >= STALE_MS ? age : -1;
    }

    /** 预计到达时间的显示文本 */
    public String etaText() {
        // ETA 每分钟才重算一次；实际到点时间变了才更新显示，避免刷屏
        long now = System.currentTimeMillis();
        if (cachedEtaText != null && (now - cachedEtaAt) < ETA_REFRESH_MS) {
            return cachedEtaText;
        }

        long t = etaMillis;
        if (t <= 0 && remainSeconds > 0) {
            t = now + remainSeconds * 1000L;
        }
        if (t <= 0) {
            cachedEtaText = null;
            return null;
        }
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
        cachedEtaText = "预计" + f.format(new java.util.Date(t)) + "到达";
        cachedEtaAt = now;
        return cachedEtaText;
    }

    /**
     * ETA 刷新间隔。
     *
     * 原来设 3 分钟，是为了「别让秒级的剩余时间冒来冒去」。
     * 但现在显示的是 hh:mm 时刻，秒级抖动影响不到它，
     * 真正需要注意的是「到点时间真的改了」——1 分钟足够灵敏。
     */
    private static final long ETA_REFRESH_MS = 60 * 1000L;
    private transient String cachedEtaText;
    private transient long cachedEtaAt = 0;

    /** 只返回时刻部分（"14:52"），给 track 行用 */
    public String etaClock() {
        String full = etaText();
        if (full == null) return null;
        int i = full.indexOf("预计");
        int j = full.indexOf("到达");
        if (i >= 0 && j > i) return full.substring(i + 2, j);
        return full;
    }

    /**
     * 直接算出 ETA 的 hh:mm 时刻字符串，不经缓存。
     *
     * 用于「导航中到点时间变了就更新车机时钟」这类需要马上反映的场合。
     */
    public String etaClockNow() {
        long t = etaMillis;
        if (t <= 0 && remainSeconds > 0) {
            t = System.currentTimeMillis() + remainSeconds * 1000L;
        }
        if (t <= 0) return null;
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(new java.util.Date(t));
    }

    private static boolean notEmpty(String s) { return s != null && !s.trim().isEmpty(); }

    @Override
    public String toString() {
        return "nav[" + instruction + " | " + road + " | " + remainDistance
                + " | eta=" + etaText() + "]";
    }
}
