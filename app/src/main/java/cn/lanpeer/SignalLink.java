package cn.lanpeer;

import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.server.DefaultSSLWebSocketServerFactory;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLParameters;

/** All application callbacks and state transitions are serialized on the Android main thread. */
public final class SignalLink {
    public interface Listener {
        void ready();
        void opened();
        void message(JSONObject message);
        void closed(String reason);
    }
    private final PeerApp app;
    private final Listener listener;
    private WebSocketServer server;
    private WebSocketClient client;
    private WebSocket peer;
    private volatile boolean stopped;
    private int failures;

    public SignalLink(PeerApp app, Listener listener) { this.app = app; this.listener = listener; }

    public void host(TlsIdentity identity, String token, String pin) {
        server = new WebSocketServer(new InetSocketAddress("0.0.0.0", 8765), 1) {
            @Override public void onStart() { app.main.post(() -> { if (!stopped) listener.ready(); }); }
            @Override public void onOpen(WebSocket socket, ClientHandshake handshake) {
                app.main.post(() -> {
                    if (stopped || !LanAddress.isPrivateV4(socket.getRemoteSocketAddress().getAddress().getHostAddress())
                            || getConnections().size() > 4) { socket.close(1008, "LAN only"); return; }
                    app.main.postDelayed(() -> {
                        if (socket.isOpen() && peer != socket) socket.close(1008, "Authentication timeout");
                    }, 10000);
                });
            }
            @Override public void onMessage(WebSocket socket, String text) {
                app.main.post(() -> {
                    if (stopped) return;
                    if (text.length() > 65536) { socket.close(1009); return; }
                    try {
                        JSONObject message = new JSONObject(text);
                        if (peer == socket) {
                            listener.message(message);
                        } else if (peer != null) {
                            socket.close(1008, "Device busy");
                        } else if ("auth".equals(message.optString("type")) && message.optInt("version") == 1
                                && (constantEquals(token, message.optString("token")) || constantEquals(pin, message.optString("token")))) {
                            peer = socket;
                            listener.opened();
                        } else {
                            socket.close(1008, "Invalid credentials");
                            if (++failures >= 5) listener.closed("连接码错误次数过多，共享已停止，请重新开启");
                        }
                    } catch (Exception e) { socket.close(1008, "Invalid message"); }
                });
            }
            @Override public void onMessage(WebSocket socket, ByteBuffer bytes) { socket.close(1003); }
            @Override public void onClose(WebSocket socket, int code, String reason, boolean remote) {
                app.main.post(() -> {
                    if (!stopped && peer == socket) listener.closed("对方已断开，请重新开启共享");
                });
            }
            @Override public void onError(WebSocket socket, Exception error) {
                android.util.Log.e("LanPeer", "Signaling server error", error);
                app.main.post(() -> { if (!stopped && socket == null) listener.closed("本地服务启动失败：" + error.getClass().getSimpleName()); });
            }
        };
        server.setReuseAddr(true);
        server.setConnectionLostTimeout(15);
        server.setWebSocketFactory(new DefaultSSLWebSocketServerFactory(identity.server) {
            @Override public java.nio.channels.ByteChannel wrapChannel(java.nio.channels.SocketChannel socket,
                    java.nio.channels.SelectionKey key) throws java.io.IOException {
                javax.net.ssl.SSLEngine engine = sslcontext.createSSLEngine();
                engine.setUseClientMode(false);
                engine.setEnabledProtocols(new String[]{"TLSv1.2"});
                engine.setEnabledCipherSuites(new String[]{"TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"});
                try { return new org.java_websocket.SSLSocketChannel2(socket, engine, exec, key) {
                    @Override public int read(ByteBuffer buffer) throws java.io.IOException {
                        try { return super.read(buffer); }
                        catch (java.io.IOException e) { android.util.Log.e("LanPeer", "TLS server read failed", e); throw e; }
                    }
                    @Override public int write(ByteBuffer buffer) throws java.io.IOException {
                        try { return super.write(buffer); }
                        catch (java.io.IOException e) { android.util.Log.e("LanPeer", "TLS server write failed", e); throw e; }
                    }
                    @Override public int readMore(ByteBuffer buffer) throws javax.net.ssl.SSLException {
                        try { return super.readMore(buffer); }
                        catch (javax.net.ssl.SSLException e) { android.util.Log.e("LanPeer", "TLS server unwrap failed", e); throw e; }
                    }
                }; } catch (java.io.IOException | RuntimeException e) {
                    android.util.Log.e("LanPeer", "TLS server channel initialization failed", e);
                    throw e;
                }
            }
        });
        server.start();
    }

    public void connect(Pairing pairing) throws Exception {
        client = new WebSocketClient(URI.create("wss://" + pairing.host + ":" + pairing.port + "/"),
                new org.java_websocket.drafts.Draft_6455(), null, 10000) {
            @Override protected void onSetSSLParameters(SSLParameters parameters) {
                // Identity is checked against the out-of-band QR/manual certificate fingerprint.
                parameters.setEndpointIdentificationAlgorithm(null);
            }
            @Override public void onOpen(ServerHandshake handshake) {
                app.main.post(() -> {
                    if (stopped) return;
                    peer = this;
                    sendJson(Session.json("auth", "version", 1, "token", pairing.token));
                    listener.opened();
                });
            }
            @Override public void onMessage(String text) {
                app.main.post(() -> {
                    if (stopped) return;
                    if (text.length() > 65536) { close(1009); return; }
                    try { listener.message(new JSONObject(text)); }
                    catch (Exception e) { close(1008, "Invalid message"); }
                });
            }
            @Override public void onClose(int code, String reason, boolean remote) {
                app.main.post(() -> { if (!stopped) listener.closed("连接已断开，请检查网络、连接码或对方是否拒绝"); });
            }
            @Override public void onError(Exception error) {
                android.util.Log.e("LanPeer", "Signaling client error", error);
                app.main.post(() -> { if (!stopped) listener.closed(error instanceof java.net.SocketTimeoutException
                        || error instanceof java.net.NoRouteToHostException
                        ? "无法访问对方设备：同一 Wi-Fi 可能开启了设备隔离，可改用手机热点或允许设备互访"
                        : "连接失败，请核对同一局域网、地址与设备校验码"); });
            }
        };
        client.setSocketFactory(TlsIdentity.pinnedClient(pairing.fingerprint).getSocketFactory());
        client.setConnectionLostTimeout(15);
        client.connect();
    }

    public String remoteAddress() {
        return peer == null || peer.getRemoteSocketAddress() == null ? "未知设备" : peer.getRemoteSocketAddress().getAddress().getHostAddress();
    }

    public void sendJson(JSONObject message) {
        if (!stopped && peer != null && peer.isOpen()) peer.send(message.toString());
    }

    public void close() {
        stopped = true;
        if (peer != null) peer.close();
        if (client != null) client.close();
        WebSocketServer old = server;
        if (old != null) new Thread(() -> {
            try { old.stop(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "signal-stop").start();
    }

    private static boolean constantEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8), b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
