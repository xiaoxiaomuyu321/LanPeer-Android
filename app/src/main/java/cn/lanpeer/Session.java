package cn.lanpeer;

import android.content.Intent;
import org.json.JSONObject;
import java.security.SecureRandom;
import java.util.List;

public final class Session implements SignalLink.Listener {
    public final PeerApp app;
    public final boolean host;
    public final SignalLink link;
    public RtcEngine rtc;
    public TlsIdentity identity;
    public String pin;
    public String token;
    public List<String> addresses;
    public boolean pending;
    public boolean approved;
    public boolean closed;
    public Pairing pairing;
    private Intent capturePermission;

    public Session(PeerApp app, boolean host) {
        this.app = app; this.host = host;
        link = new SignalLink(app, this);
    }

    public void startHost(Intent permission) {
        capturePermission = permission;
        app.publish("正在准备局域网共享…");
        new Thread(() -> {
            try {
                TlsIdentity tls = new TlsIdentity();
                List<String> ips = LanAddress.local();
                SecureRandom random = new SecureRandom();
                byte[] bytes = new byte[16]; random.nextBytes(bytes);
                String secret = TlsIdentity.hex(bytes);
                String code = String.format(java.util.Locale.ROOT, "%08d", random.nextInt(100000000));
                app.main.post(() -> {
                    if (closed) return;
                    if (ips.isEmpty()) { fail("没有找到局域网地址，请先开启热点或连接 Wi-Fi"); return; }
                    identity = tls; addresses = ips; token = secret; pin = code;
                    link.host(identity, token, pin);
                });
            } catch (Exception e) {
                android.util.Log.e("LanPeer", "Host initialization failed", e);
                app.main.post(() -> fail("无法启动安全共享：" + e.getClass().getSimpleName()));
            }
        }, "prepare-host").start();
    }

    public void startClient(Pairing pairing) {
        this.pairing = pairing;
        app.publish("正在连接 " + pairing.host + "…");
        try { link.connect(pairing); }
        catch (Exception e) { fail("无法连接：" + e.getClass().getSimpleName()); }
        app.main.postDelayed(() -> { if (!closed && !approved) fail("连接超时，请检查网络并在被控设备确认请求"); }, 45000);
    }

    @Override public void ready() { app.publish("等待连接。另一台设备可扫码，或输入地址、连接码与校验码。"); }
    @Override public void opened() {
        if (host) {
            pending = true;
            app.publish("设备 " + link.remoteAddress() + " 请求控制，请在本机确认。");
            app.main.postDelayed(() -> { if (!closed && pending) fail("连接请求已超时，共享已停止"); }, 30000);
        } else app.publish("设备校验通过，请在对方屏幕上确认连接。");
    }

    public void approve() {
        if (!host || !pending || closed) return;
        pending = false; approved = true;
        try {
            rtc = new RtcEngine(this);
            rtc.startCapture(capturePermission);
            capturePermission = null;
            link.sendJson(json("approved"));
            app.publish("已允许连接，正在建立视频通道…");
            app.main.postDelayed(() -> {
                if (!closed && rtc != null && !rtcConnected()) fail("视频连接超时，请重新连接");
            }, 25000);
        } catch (Exception e) {
            android.util.Log.e("LanPeer", "Screen capture failed", e);
            fail("屏幕采集启动失败：" + e.getClass().getSimpleName() + "，请重新授权");
        }
    }

    private boolean rtcConnected() { return rtc != null && rtc.isConnected(); }

    @Override public void message(JSONObject message) {
        if (closed) return;
        try {
            String type = message.optString("type");
            if ("approved".equals(type) && !host && !approved) {
                approved = true;
                rtc = new RtcEngine(this);
                rtc.offer();
                app.publish("对方已允许，正在建立视频通道…");
                app.main.postDelayed(() -> { if (!closed && !rtcConnected()) fail("视频连接超时，请重新连接"); }, 25000);
            } else if (approved && rtc != null && ("sdp".equals(type) || "ice".equals(type))) rtc.receive(message);
        } catch (Exception e) { fail("通信数据无效，连接已停止"); }
    }

    @Override public void closed(String reason) { fail(reason); }
    public void fail(String reason) {
        if (closed) return;
        if (app.session == this) app.disconnect(); else close();
        app.publish(reason);
    }

    public void close() {
        if (closed) return;
        closed = true; approved = false; pending = false;
        ControlService.cancelSession(this);
        capturePermission = null;
        // Tell the UI to release its renderer before disposing the shared EGL context.
        if (app.listener != null) app.listener.run();
        link.close();
        if (rtc != null) rtc.close();
        if (host) app.stopService(new Intent(app, ShareService.class));
    }

    public static JSONObject json(String type, Object... fields) {
        JSONObject object = new JSONObject();
        try {
            object.put("type", type);
            for (int i = 0; i < fields.length; i += 2) object.put((String) fields[i], fields[i + 1]);
        } catch (Exception e) { throw new IllegalArgumentException(e); }
        return object;
    }
}
