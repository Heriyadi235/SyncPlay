package com.xigaigai.syncglance.model;

/**
 * 当前播放的歌曲状态。
 * 由 {@link com.xigaigai.syncglance.service.MediaWatcher} 维护。
 */
public class NowPlaying {

    public String title;
    public String artist;
    public String album;
    public long durationMs;
    /** 播放位置，用于歌词同步 */
    public long positionMs;
    /** 是否正在播放 */
    public boolean playing;
    /** 数据来源的 app 包名 */
    public String sourcePackage;

    public boolean hasData() {
        return notEmpty(title) || notEmpty(artist);
    }

    public boolean sameSong(NowPlaying other) {
        if (other == null) return false;
        return eq(title, other.title) && eq(artist, other.artist);
    }

    public void copyFrom(NowPlaying o) {
        if (o == null) return;
        title = o.title;
        artist = o.artist;
        album = o.album;
        durationMs = o.durationMs;
        positionMs = o.positionMs;
        playing = o.playing;
        sourcePackage = o.sourcePackage;
    }

    private static boolean notEmpty(String s) { return s != null && !s.trim().isEmpty(); }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    @Override
    public String toString() {
        return (title == null ? "?" : title) + " / " + (artist == null ? "?" : artist)
                + " [" + (playing ? "播放" : "暂停") + "] " + durationMs / 1000 + "s";
    }
}
