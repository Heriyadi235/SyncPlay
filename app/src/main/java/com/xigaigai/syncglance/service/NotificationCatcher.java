package com.xigaigai.syncglance.service;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.xigaigai.syncglance.model.NowPlaying;
import com.xigaigai.syncglance.util.Log2;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 通知监听：整个 app 的音乐/导航数据入口。
 *
 * 两条职责：
 *   1. 【调试】把所有通知原样全量 dump 到日志——阶段 1 的核心任务，
 *      用来抓出高德/百度/网易云通知的真实格式，然后才写解析规则。
 *   2. 【生产】从中等取媒体会话，更新 NowPlaying。
 *
 * 只需一个权限：通知使用权（用户在系统设置里手动开）。
 * 不需要签名级的 MEDIA_CONTENT_CONTROL —— 通过通知里的
 * EXTRA_MEDIA_SESSION 拿 MediaSession.Token 就够了。
 */
public class NotificationCatcher extends NotificationListenerService {

    private static final String TAG = "Notif";

    /** 已知的音乐 app 包名（用于日志归类，不作为过滤条件） */
    private static final String[] KNOWN_MUSIC = {
            "com.netease.cloudmusic",           // 网易云音乐
            "com.tencent.qqmusic",              // QQ 音乐
            "com.kugou.android",                // 酷狗
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
    };

    /** 已知的导航 app 包名 */
    private static final String[] KNOWN_NAV = {
            "com.autonavi.minimap",             // 高德地图
            "com.baidu.BaiduMap",               // 百度地图
            "com.baidu.naviauto",               // 百度地图车机版
            "com.tencent.map",                  // 腾讯地图
            "com.google.android.apps.maps",
    };

    public interface MusicListener {
        void onNowPlaying(NowPlaying np);

        /** 音乐通知消失（播放器退出 / 媒体控件被划掉） */
        void onMusicGone();
    }

    private static NotificationCatcher instance;
    private static MusicListener musicListener;

    /** 最近一次抓到的播放状态（供外部判断是否真需要发播放键） */
    private static volatile boolean lastPlaying = false;
    private static volatile long lastPlayingUpdateAt = 0;
    private static volatile String lastTitle = null;

    public static NotificationCatcher getInstance() { return instance; }

    public static void setMusicListener(MusicListener l) { musicListener = l; }

    /**
     * 主动刷新一次播放状态。
     *
     * 通知只在内容变化时才会发，播放中的歌曲位置不会每秒推送，
     * 所以需要这个方法来按需拉取最新的 positionMs。
     *
     * @return 最新状态；拿不到返回 null
     */
    public NowPlaying pollNowPlaying() {
        try {
            StatusBarNotification[] list = getActiveNotifications();
            if (list == null) return null;
            for (StatusBarNotification sbn : list) {
                if (!matches(sbn.getPackageName(), KNOWN_MUSIC)) continue;
                Notification n = sbn.getNotification();
                if (n == null || n.extras == null) continue;

                Object obj = n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
                if (!(obj instanceof android.media.session.MediaSession.Token)) continue;

                MediaController c = new MediaController(this,
                        (android.media.session.MediaSession.Token) obj);
                MediaMetadata md = c.getMetadata();
                if (md == null) continue;

                NowPlaying np = new NowPlaying();
                np.title = str(md, MediaMetadata.METADATA_KEY_TITLE);
                np.artist = str(md, MediaMetadata.METADATA_KEY_ARTIST);
                np.durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
                np.sourcePackage = sbn.getPackageName();

                PlaybackState ps = c.getPlaybackState();
                if (ps != null) {
                    // 用"最后更新时间 + 流逝时间"推算更准
                    long base = ps.getPosition();
                    long since = android.os.SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime();
                    if (ps.getState() == PlaybackState.STATE_PLAYING && since > 0) {
                        base += since;
                    }
                    np.positionMs = Math.max(0, base);
                    np.playing = ps.getState() == PlaybackState.STATE_PLAYING;
                }
                if (np.hasData()) return np;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * 当前是否正在播放。
     * 只在最近 30 秒内有过状态更新时才可信，否则返回 false（宁可多发一次）。
     */
    public static boolean isCurrentlyPlaying() {
        if (System.currentTimeMillis() - lastPlayingUpdateAt > 30_000) return false;
        return lastPlaying;
    }

    /** 最近一首歌的名字，用于日志 */
    public static String getLastTitle() { return lastTitle; }

    /** 通知权限是否已授予 */
    public static boolean isEnabled(Context ctx) {
        String flat = android.provider.Settings.Secure.getString(
                ctx.getContentResolver(), "enabled_notification_listeners");
        if (flat == null) return false;
        String me = new ComponentName(ctx, NotificationCatcher.class).flattenToString();
        String meShort = new ComponentName(ctx, NotificationCatcher.class).flattenToShortString();
        return flat.contains(me) || flat.contains(meShort);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log2.init(this);
        Log2.i(TAG, "通知监听服务已启动");
        // 把已有的通知也 dump 一遍
        dumpActive("启动时已有通知");
    }

    @Override
    public void onDestroy() {
        instance = null;
        Log2.i(TAG, "通知监听服务已停止");
        super.onDestroy();
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log2.i(TAG, "✓ 已连上通知服务（权限已授予）");
        dumpActive("连接后已有通知");
    }

    @Override
    public void onListenerDisconnected() {
        Log2.w(TAG, "通知服务断开，尝试重连");
        try { requestRebind(new ComponentName(this, NotificationCatcher.class)); }
        catch (Exception ignored) { }
        super.onListenerDisconnected();
    }

    // ---------------- 通知到达 ----------------

    /** 上次每个包的通知内容指纹，用来去重 */
    private final java.util.Map<String, String> lastFingerprint = new java.util.HashMap<>();

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        String pkg = sbn.getPackageName();
        boolean isMusic = matches(pkg, KNOWN_MUSIC);
        boolean isNav = matches(pkg, KNOWN_NAV);

        if (isMusic || isNav) {
            logOneLine(sbn, isNav ? "导航" : "音乐");
        }

        // ★ 高德发通知 = 它启动了，触发一次 AmapLink 连接尝试
        if ("com.autonavi.minimap".equals(pkg)) {
            try {
                com.xigaigai.syncglance.nav.AmapLinkClient.get(this).wakeUp();
            } catch (Exception e) {
                Log2.w(TAG, "触发 AmapLink 失败: " + e.getMessage());
            }
        }

        // 从通知里取媒体会话
        tryExtractMedia(sbn);
    }

    /**
     * 一行式通知日志。
     * 内容没变就不打——网易云/高德每隔几秒刷一次，去重能省掉大量重复。
     */
    private void logOneLine(StatusBarNotification sbn, String kind) {
        String pkg = sbn.getPackageName();
        Notification n = sbn.getNotification();
        if (n == null) return;

        Bundle ex = n.extras;
        String title = ex == null ? "" : str(ex.getCharSequence(Notification.EXTRA_TITLE));
        String text = ex == null ? "" : str(ex.getCharSequence(Notification.EXTRA_TEXT));
        String sub = ex == null ? "" : str(ex.getCharSequence(Notification.EXTRA_SUB_TEXT));

        String fp = title + "|" + text + "|" + sub;
        if (fp.equals(lastFingerprint.get(pkg))) return;   // 没变，不打
        lastFingerprint.put(pkg, fp);

        StringBuilder sb = new StringBuilder();
        sb.append("[").append(kind).append("] ");
        if (!title.isEmpty()) sb.append(title);
        if (!text.isEmpty()) {
            if (sb.charAt(sb.length() - 1) != ' ') sb.append(" / ");
            sb.append(text);
        }
        if (!sub.isEmpty()) sb.append(" | ").append(sub);

        Log2.i(TAG, sb.toString());
    }

    private static String str(CharSequence cs) {
        if (cs == null) return "";
        String s = cs.toString().trim();
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (matches(pkg, KNOWN_NAV) || matches(pkg, KNOWN_MUSIC)) {
            boolean music = matches(pkg, KNOWN_MUSIC);
            Log2.i(TAG, "[" + (music ? "音乐" : "导航") + "] 通知移除");
            lastFingerprint.remove(pkg);

            // 音乐通知没了 = 播放器真的退出了（或用户划掉了媒体控件），
            // 此时应尽快退回导航/欢迎页，而不是一直挂着最后一首歌。
            if (music && musicListener != null) {
                musicListener.onMusicGone();
            }
        }
    }

    // ---------------- 全量 dump（阶段 1 核心）----------------

    private void dumpActive(String why) {
        StatusBarNotification[] list;
        try { list = getActiveNotifications(); }
        catch (Exception e) { return; }
        if (list == null || list.length == 0) {
            Log2.i(TAG, why + "：没有");
            return;
        }
        int known = 0;
        for (StatusBarNotification sbn : list) {
            String pkg = sbn.getPackageName();
            if (matches(pkg, KNOWN_MUSIC) || matches(pkg, KNOWN_NAV)) known++;
        }
        if (known == 0) {
            Log2.i(TAG, why + "：" + list.length + " 条，无音乐/导航通知");
            return;
        }
        for (StatusBarNotification sbn : list) {
            String pkg = sbn.getPackageName();
            if (matches(pkg, KNOWN_MUSIC) || matches(pkg, KNOWN_NAV)) {
                logOneLine(sbn, matches(pkg, KNOWN_NAV) ? "导航" : "音乐");
            }
        }
    }

    /**
     * 把一个 extras 值描述成可读文本。
     * Bundle 递归展开 —— 高德可能把导航数据塞在自定义 Bundle 里。
     */
    private static String describe(Object v) {
        if (v == null) return "null";

        if (v instanceof CharSequence) {
            String s = v.toString();
            return s.length() > 300 ? s.substring(0, 300) + "…" : s;
        }
        if (v instanceof Bundle) {
            Bundle b = (Bundle) v;
            StringBuilder sb = new StringBuilder("{");
            Set<String> ks = b.keySet();
            if (ks.isEmpty()) return "{}（空 Bundle）";
            for (String k : ks) {
                Object sub;
                try { sub = b.get(k); } catch (Exception e) { sub = "?"; }
                sb.append(k).append("=").append(describe(sub)).append("; ");
            }
            return sb.append("}").toString();
        }
        if (v instanceof Object[]) {
            Object[] arr = (Object[]) v;
            StringBuilder sb = new StringBuilder("[");
            for (Object o : arr) sb.append(describe(o)).append(" | ");
            return sb.append("]").toString();
        }
        if (v instanceof int[]) {
            return java.util.Arrays.toString((int[]) v);
        }
        if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            return "byte[" + b.length + "]";
        }
        if (v instanceof android.graphics.Bitmap) {
            android.graphics.Bitmap bm = (android.graphics.Bitmap) v;
            return "Bitmap " + bm.getWidth() + "x" + bm.getHeight();
        }
        if (v instanceof android.app.PendingIntent) {
            return "PendingIntent";
        }
        if (v instanceof android.widget.RemoteViews) {
            return "RemoteViews（自定义布局，内容读不到）";
        }
        String s = String.valueOf(v);
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    // ---------------- 媒体会话 ----------------

    /**
     * 从通知里拿 MediaSession.Token，再取 MediaController 读元数据。
     * 这是不需要 MEDIA_CONTENT_CONTROL 权限的正确姿势。
     */
    private void tryExtractMedia(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;

        Object obj;
        try {
            obj = n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
        } catch (Exception e) {
            return;
        }
        if (!(obj instanceof android.media.session.MediaSession.Token)) return;

        android.media.session.MediaSession.Token token =
                (android.media.session.MediaSession.Token) obj;

        try {
            MediaSessionManager mgr =
                    (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            if (mgr == null) return;

            // API 24 只有 getActiveSessions(ComponentName)；
            // 我们本身就是启用的通知监听器，可以按自己的组件名查。
            ComponentName cn = new ComponentName(this, NotificationCatcher.class);
            List<MediaController> controllers;
            try {
                controllers = mgr.getActiveSessions(cn);
            } catch (Exception e) {
                controllers = new ArrayList<>();
            }

            MediaController target = null;
            for (MediaController c : controllers) {
                if (token.equals(c.getSessionToken())) { target = c; break; }
            }
            if (target == null) {
                target = new MediaController(this, token);
            }

            MediaMetadata md = target.getMetadata();
            if (md == null) return;

            NowPlaying np = new NowPlaying();
            np.title = str(md, MediaMetadata.METADATA_KEY_TITLE);
            np.artist = str(md, MediaMetadata.METADATA_KEY_ARTIST);
            np.album = str(md, MediaMetadata.METADATA_KEY_ALBUM);
            np.durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            np.sourcePackage = sbn.getPackageName();

            PlaybackState ps = target.getPlaybackState();
            if (ps != null) {
                np.positionMs = ps.getPosition();
                int st = ps.getState();
                np.playing = st == PlaybackState.STATE_PLAYING;
            }

            if (!np.hasData()) return;

            // 缓存播放状态，供 NeteaseControl 判断是否需要发播放键
            lastPlaying = np.playing;
            lastPlayingUpdateAt = System.currentTimeMillis();
            lastTitle = np.title;

            Log2.i(TAG, "媒体会话: " + np);
            MusicListener l = musicListener;
            if (l != null) l.onNowPlaying(np);

            // 首次拿到时，把 metadata 的所有 key 也 dump 一次（找隐藏的歌词字段）
            dumpMetadataKeys(md, n.extras);

        } catch (Exception e) {
            Log2.w(TAG, "读媒体会话失败: " + e.getMessage());
        }
    }

    private boolean dumpedMetadataOnce = false;

    /** metadata 里有没有藏歌词？各家做法不统一，把已知 key 都列出来看 */
    private void dumpMetadataKeys(MediaMetadata md, Bundle notifExtras) {
        if (dumpedMetadataOnce) return;
        dumpedMetadataOnce = true;

        String[] keys = {
                MediaMetadata.METADATA_KEY_TITLE,
                MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
                MediaMetadata.METADATA_KEY_ART_URI,
                MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_TRACK_NUMBER,
                MediaMetadata.METADATA_KEY_NUM_TRACKS,
                MediaMetadata.METADATA_KEY_GENRE,
                MediaMetadata.METADATA_KEY_MEDIA_ID,
                MediaMetadata.METADATA_KEY_MEDIA_URI,
        };

        StringBuilder sb = new StringBuilder("MediaMetadata 已知字段:\n");
        for (String k : keys) {
            CharSequence cs = md.getText(k);
            if (cs != null && cs.length() > 0) {
                sb.append("  ").append(k).append(" = ").append(cs).append('\n');
            }
        }
        sb.append("  DURATION = ").append(md.getLong(MediaMetadata.METADATA_KEY_DURATION)).append('\n');
        Log2.i(TAG, sb.toString());

        // 在 extras 里找像歌词的东西
        try {
            if (notifExtras != null) {
                for (String k : notifExtras.keySet()) {
                    String lk = k.toLowerCase(java.util.Locale.US);
                    if (lk.contains("lyric") || lk.contains("lrc")) {
                        Log2.i(TAG, "★ 发现疑似歌词字段: " + k + " = "
                                + notifExtras.get(k));
                    }
                }
            }
        } catch (Exception ignored) { }
    }

    private static String str(MediaMetadata md, String key) {
        CharSequence cs = md.getText(key);
        return cs == null ? null : cs.toString();
    }

    private static boolean matches(String pkg, String[] list) {
        if (pkg == null) return false;
        for (String p : list) if (p.equals(pkg)) return true;
        return false;
    }
}
