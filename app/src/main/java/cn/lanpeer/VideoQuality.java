package cn.lanpeer;

/** User selected encoder ceilings; WebRTC can adapt below these when the LAN is congested. */
public enum VideoQuality {
    SMOOTH("流畅", 1280, 45, 2_000_000),
    BALANCED("均衡", 1600, 30, 3_500_000),
    CLEAR("清晰", 1920, 30, 6_000_000);

    public final String title;
    public final int maxEdge;
    public final int fps;
    public final int bitrate;

    VideoQuality(String title, int maxEdge, int fps, int bitrate) {
        this.title = title;
        this.maxEdge = maxEdge;
        this.fps = fps;
        this.bitrate = bitrate;
    }

    public static VideoQuality parse(String value) {
        try { return valueOf(value); }
        catch (Exception ignored) { return BALANCED; }
    }
}
