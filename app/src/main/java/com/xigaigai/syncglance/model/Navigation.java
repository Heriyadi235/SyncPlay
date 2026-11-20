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

    /** 手动开启的导航模式（通知解析成功时也会置 true） */
    public boolean manualMode;

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

    /** 数据多久没更新就算失效（高德正常每秒推一次，8 秒没更新基本就是停了） */
    public static final long STALE_MS = 8_000;

    /**
     * 数据是否还新鲜。
     *
     * 高德正常每秒推送一次，超过 15 秒没更新说明：
     *   - 高德闪退了
     *   - 导航结束了但没发 type 100
     *   - 手机休眠了
     * 这时候必须当作"没有导航数据"，否则车机会一直卡在最后一条指令上误导司机。
     *
     * 手动模式（用户点的）不受时效限制。
     */
    public boolean isFresh() {
        if (!hasData()) return false;
        if (manualMode && lastUpdateMillis == 0) return true;
        long age = System.currentTimeMillis() - lastUpdateMillis;
        return lastUpdateMillis > 0 && age >= 0 && age < STALE_MS;
    }

    /** 数据已经过期多久（毫秒），没过期返回 -1 */
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
