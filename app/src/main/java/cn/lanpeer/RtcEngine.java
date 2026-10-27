package cn.lanpeer;

import android.content.Intent;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjection;
import android.view.WindowManager;
import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RtcEngine implements PeerConnection.Observer, DisplayManager.DisplayListener {
    private final Session session;
    private final PeerApp app;
    public final EglBase egl = EglBase.create();
    private final PeerConnectionFactory factory;
    private final PeerConnection peer;
    private final List<IceCandidate> pendingIce = new ArrayList<>();
    private VideoSource source;
    private RtpSender videoSender;
    private VideoTrack localTrack;
    private VideoTrack remoteTrack;
    private ScreenCapturerAndroid capturer;
    private SurfaceTextureHelper texture;
    private DataChannel channel;
    private SurfaceViewRenderer renderer;
    private boolean remoteDescriptionSet;
    private boolean disposed;
    private int captureWidth, captureHeight;
    private VideoQuality quality = VideoQuality.BALANCED;
    private long lastStatsAt, lastDecoded, lastBytes;
    private long lastControlRejectAt;
    public volatile String liveStats = "等待视频统计…";
    public volatile int videoWidth, videoHeight;

    private final VideoSink sink = frame -> {
        videoWidth = frame.getRotatedWidth();
        videoHeight = frame.getRotatedHeight();
        synchronized (RtcEngine.this) { if (renderer != null) renderer.onFrame(frame); }
    };

    public RtcEngine(Session session) {
        this.session = session;
        app = session.app;
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions());
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext())).createPeerConnectionFactory();
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Collections.emptyList());
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
        peer = factory.createPeerConnection(config, this);
        if (peer == null) throw new IllegalStateException("无法创建视频连接");
        if (!session.host) {
            attachChannel(peer.createDataChannel("control-v1", new DataChannel.Init()));
            peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY));
        }
    }

    public void startCapture(Intent permission) {
        texture = SurfaceTextureHelper.create("screen-capture", egl.getEglBaseContext());
        source = factory.createVideoSource(true);
        capturer = new ScreenCapturerAndroid(permission, new MediaProjection.Callback() {
            @Override public void onStop() { app.main.post(() -> session.fail("系统已停止屏幕共享")); }
        });
        capturer.initialize(texture, app, source.getCapturerObserver());
        int[] size = captureSize();
        captureWidth = size[0]; captureHeight = size[1];
        capturer.startCapture(captureWidth, captureHeight, quality.fps);
        localTrack = factory.createVideoTrack("screen", source);
        videoSender = peer.addTrack(localTrack, Collections.singletonList("screen"));
        applyQuality();
        ((DisplayManager) app.getSystemService(android.content.Context.DISPLAY_SERVICE)).registerDisplayListener(this, app.main);
    }

    @SuppressWarnings("deprecation")
    private int[] captureSize() {
        Point p = new Point();
        ((WindowManager) app.getSystemService(android.content.Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(p);
        float scale = Math.min(1f, (float) quality.maxEdge / Math.max(p.x, p.y));
        return new int[]{Math.max(2, Math.round(p.x * scale) / 2 * 2), Math.max(2, Math.round(p.y * scale) / 2 * 2)};
    }

    @Override public void onDisplayChanged(int id) {
        if (disposed || capturer == null) return;
        int[] size = captureSize();
        if (size[0] != captureWidth || size[1] != captureHeight) {
            captureWidth = size[0]; captureHeight = size[1];
            capturer.changeCaptureFormat(captureWidth, captureHeight, quality.fps);
        }
    }

    public void requestQuality(VideoQuality preset) {
        if (session.host || disposed) return;
        send(Session.json("quality", "preset", preset.name()));
    }

    private void changeQuality(VideoQuality preset) {
        if (!session.host || !session.approved || disposed || capturer == null) return;
        quality = preset;
        int[] size = captureSize();
        captureWidth = size[0]; captureHeight = size[1];
        capturer.changeCaptureFormat(captureWidth, captureHeight, quality.fps);
        applyQuality();
        android.util.Log.i("LanPeer", "Video preset " + quality.name() + " " + captureWidth + "x" + captureHeight);
    }

    private void applyQuality() {
        if (videoSender == null) return;
        RtpParameters params = videoSender.getParameters();
        params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE;
        for (RtpParameters.Encoding encoding : params.encodings) {
            encoding.maxBitrateBps = quality.bitrate;
            encoding.maxFramerate = quality.fps;
        }
        if (!videoSender.setParameters(params)) android.util.Log.w("LanPeer", "Encoder rejected video preset");
    }

    public void pollStats() {
        if (disposed || session.host) return;
        peer.getStats(report -> {
            long now = System.currentTimeMillis();
            long decoded = -1, bytes = -1;
            int width = 0, height = 0;
            double rttMs = -1;
            for (RTCStats stat : report.getStatsMap().values()) {
                Map<String, Object> m = stat.getMembers();
                if ("inbound-rtp".equals(stat.getType()) && "video".equals(m.get("kind"))) {
                    decoded = number(m.get("framesDecoded"));
                    bytes = number(m.get("bytesReceived"));
                    width = (int) number(m.get("frameWidth"));
                    height = (int) number(m.get("frameHeight"));
                } else if ("candidate-pair".equals(stat.getType()) && Boolean.TRUE.equals(m.get("nominated"))) {
                    Object value = m.get("currentRoundTripTime");
                    if (value instanceof Number) rttMs = ((Number) value).doubleValue() * 1000;
                }
            }
            if (decoded < 0) return;
            long elapsed = now - lastStatsAt;
            String value = width + "×" + height;
            if (elapsed > 0 && lastStatsAt > 0 && decoded >= lastDecoded && bytes >= lastBytes) {
                double fps = (decoded - lastDecoded) * 1000.0 / elapsed;
                double mbps = (bytes - lastBytes) * 8.0 / elapsed / 1000.0;
                value = String.format(Locale.ROOT, "%s · %.0f fps · %.1f Mbps", value, fps, mbps);
            }
            if (rttMs >= 0) value += String.format(Locale.ROOT, " · RTT %.0f ms", rttMs);
            liveStats = value;
            lastStatsAt = now; lastDecoded = decoded; lastBytes = bytes;
        });
    }

    private static long number(Object value) { return value instanceof Number ? ((Number) value).longValue() : -1; }
    @Override public void onDisplayAdded(int id) { }
    @Override public void onDisplayRemoved(int id) { }

    public void offer() {
        peer.createOffer(new SdpAdapter() {
            @Override public void onCreateSuccess(SessionDescription description) { app.main.post(() -> setLocal(description)); }
        }, new MediaConstraints());
    }

    private void setLocal(SessionDescription description) {
        if (disposed) return;
        peer.setLocalDescription(new SdpAdapter() {
            @Override public void onSetSuccess() {
                app.main.post(() -> {
                    if (!disposed) session.link.sendJson(Session.json("sdp", "sdpType", description.type.canonicalForm(), "sdp", description.description));
                });
            }
        }, description);
    }

    public void receive(JSONObject message) throws Exception {
        if (disposed) return;
        if ("ice".equals(message.optString("type"))) {
            IceCandidate candidate = new IceCandidate(message.optString("mid"), message.getInt("line"), message.getString("candidate"));
            if (remoteDescriptionSet) peer.addIceCandidate(candidate);
            else if (pendingIce.size() < 128) pendingIce.add(candidate);
        } else if ("sdp".equals(message.optString("type"))) {
            String type = message.getString("sdpType");
            if (!(session.host ? "offer" : "answer").equals(type) || remoteDescriptionSet) return;
            SessionDescription description = new SessionDescription(SessionDescription.Type.fromCanonicalForm(type), message.getString("sdp"));
            peer.setRemoteDescription(new SdpAdapter() {
                @Override public void onSetSuccess() {
                    app.main.post(() -> {
                        if (disposed) return;
                        remoteDescriptionSet = true;
                        for (IceCandidate candidate : pendingIce) peer.addIceCandidate(candidate);
                        pendingIce.clear();
                        if (session.host) peer.createAnswer(new SdpAdapter() {
                            @Override public void onCreateSuccess(SessionDescription answer) { app.main.post(() -> setLocal(answer)); }
                        }, new MediaConstraints());
                    });
                }
            }, description);
        }
    }

    private void attachChannel(DataChannel value) {
        if (disposed || channel != null) { value.dispose(); return; }
        channel = value;
        channel.registerObserver(new DataChannel.Observer() {
            @Override public void onBufferedAmountChange(long previousAmount) { }
            @Override public void onStateChange() {
                app.main.post(() -> {
                    if (!disposed && channel.state() == DataChannel.State.OPEN) {
                        if (!session.host && session.pairing != null) new KnownDevices(app).remember(session.pairing);
                        app.publish(session.host ? "对方已连接，正在共享屏幕；可在通知中随时停止。" : "已连接，可直接触摸画面控制对方。");
                        if (session.host) sendControlStatus(ControlService.available(), false);
                    }
                });
            }
            @Override public void onMessage(DataChannel.Buffer buffer) {
                if (buffer.binary || buffer.data.remaining() > 16384) return;
                byte[] data = new byte[buffer.data.remaining()];
                buffer.data.get(data);
                app.main.post(() -> {
                    if (disposed || session.closed) return;
                    try {
                        JSONObject message = new JSONObject(new String(data, StandardCharsets.UTF_8));
                        if (session.host && "quality".equals(message.optString("type"))) {
                            changeQuality(VideoQuality.parse(message.optString("preset")));
                        } else if (session.host) {
                            boolean accepted = ControlService.execute(app, session, message);
                            if (!"stroke".equals(message.optString("type")))
                                android.util.Log.i("LanPeer", "Control " + message.optString("type") + ": " + accepted);
                            if (!accepted && System.currentTimeMillis() - lastControlRejectAt > 1200) {
                                lastControlRejectAt = System.currentTimeMillis();
                                sendControlStatus(ControlService.available(), true);
                            }
                        } else if ("controlStatus".equals(message.optString("type"))) {
                            if (!message.optBoolean("enabled"))
                                app.publish("已连接画面；被控端触控服务未运行，请在被控端设置页开启。");
                            else if (message.optBoolean("rejected"))
                                app.publish("本次触控未执行；请重试，持续失败时重启被控端触控服务。");
                            else app.publish("已连接，远程触控服务就绪。");
                        }
                    } catch (Exception ignored) { }
                });
            }
        });
    }

    public boolean send(JSONObject message) {
        return !disposed && channel != null && channel.state() == DataChannel.State.OPEN && channel.bufferedAmount() < 65536
                && channel.send(new DataChannel.Buffer(ByteBuffer.wrap(message.toString().getBytes(StandardCharsets.UTF_8)), false));
    }

    public void sendControlStatus(boolean enabled, boolean rejected) {
        if (session.host) send(Session.json("controlStatus", "enabled", enabled, "rejected", rejected));
    }

    public synchronized void attachRenderer(SurfaceViewRenderer value) { renderer = value; }

    public boolean isConnected() {
        return !disposed && peer.connectionState() == PeerConnection.PeerConnectionState.CONNECTED;
    }

    @Override public void onIceCandidate(IceCandidate candidate) {
        app.main.post(() -> {
            if (!disposed) session.link.sendJson(Session.json("ice", "mid", candidate.sdpMid, "line", candidate.sdpMLineIndex, "candidate", candidate.sdp));
        });
    }
    @Override public void onDataChannel(DataChannel value) {
        app.main.post(() -> { if (session.host) attachChannel(value); else value.dispose(); });
    }
    @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
        app.main.post(() -> {
            if (disposed || session.host || !(receiver.track() instanceof VideoTrack)) return;
            if (remoteTrack != null) remoteTrack.removeSink(sink);
            remoteTrack = (VideoTrack) receiver.track();
            remoteTrack.addSink(sink);
        });
    }
    @Override public void onConnectionChange(PeerConnection.PeerConnectionState state) {
        app.main.post(() -> {
            if (disposed) return;
            if (state == PeerConnection.PeerConnectionState.FAILED) session.fail("视频连接失败，请确认热点未隔离设备，然后重新连接");
            if (state == PeerConnection.PeerConnectionState.DISCONNECTED) {
                app.publish("连接中断，正在等待局域网恢复…");
                app.main.postDelayed(() -> {
                    if (!disposed && peer.connectionState() == PeerConnection.PeerConnectionState.DISCONNECTED) session.fail("网络中断，共享已停止");
                }, 10000);
            }
            if (state == PeerConnection.PeerConnectionState.CONNECTED) app.publish(session.host ? "屏幕共享已连接，可随时停止。" : "画面已连接，正在准备触控通道。");
        });
    }
    @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
    @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) { }
    @Override public void onIceConnectionReceivingChange(boolean receiving) { }
    @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) { }
    @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
    @Override public void onAddStream(MediaStream stream) { }
    @Override public void onRemoveStream(MediaStream stream) { }
    @Override public void onRenegotiationNeeded() { }

    private class SdpAdapter implements SdpObserver {
        public void onCreateSuccess(SessionDescription description) { }
        public void onSetSuccess() { }
        public void onCreateFailure(String error) { app.main.post(() -> { if (!disposed) session.fail("视频协商失败：" + error); }); }
        public void onSetFailure(String error) { app.main.post(() -> { if (!disposed) session.fail("视频协商失败：" + error); }); }
    }

    public void close() {
        if (disposed) return;
        disposed = true;
        attachRenderer(null);
        ((DisplayManager) app.getSystemService(android.content.Context.DISPLAY_SERVICE)).unregisterDisplayListener(this);
        if (remoteTrack != null) remoteTrack.removeSink(sink);
        if (channel != null) { channel.unregisterObserver(); channel.close(); channel.dispose(); }
        if (capturer != null) { capturer.stopCapture(); capturer.dispose(); }
        peer.close(); peer.dispose();
        if (localTrack != null) localTrack.dispose();
        if (source != null) source.dispose();
        if (texture != null) texture.dispose();
        factory.dispose();
        // Renderer EGL contexts are released by the Activity before this shared context.
        egl.release();
    }
}
