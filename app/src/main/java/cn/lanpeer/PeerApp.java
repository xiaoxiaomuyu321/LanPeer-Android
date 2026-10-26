package cn.lanpeer;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

public final class PeerApp extends Application {
    public final Handler main = new Handler(Looper.getMainLooper());
    public Session session;
    public Runnable listener;
    public String status = "两台设备连接同一个 Wi-Fi，或一台开启热点、另一台加入。";

    public void publish(String message) {
        main.post(() -> {
            status = message;
            android.util.Log.i("LanPeer", message);
            if (ShareService.instance != null) ShareService.instance.updateNotification(message);
            if (listener != null) listener.run();
        });
    }

    public void disconnect() {
        Session old = session;
        session = null;
        if (old != null) old.close();
        publish("已断开。双方可重新选择控制方向。");
    }
}
