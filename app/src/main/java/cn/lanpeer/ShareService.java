package cn.lanpeer;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public final class ShareService extends Service {
    static ShareService instance;
    private static final String CHANNEL = "screen-sharing";
    private Session owned;
    private PowerManager.WakeLock screenWakeLock;

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "局域网屏幕共享", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        PeerApp app = (PeerApp) getApplication();
        if (intent == null || "stop".equals(intent.getAction())) {
            if (owned != null && app.session == owned) app.disconnect();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (owned != null) return START_NOT_STICKY;
        Intent permission = intent.getParcelableExtra("permission");
        if (permission == null) { stopSelf(); return START_NOT_STICKY; }
        try {
            Notification notification = notification("共享准备中，点击返回应用");
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(1, notification);
            app.disconnect();
            owned = new Session(app, true);
            app.session = owned;
            owned.startHost(permission);
            keepScreenAwake(owned);
        } catch (Exception e) {
            app.publish("共享服务启动失败，请重新授权屏幕共享");
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ShareService.class).setAction("stop"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_peer)
                .setContentTitle("局域互控 · 共享已开启").setContentText(text)
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止共享", stop).build()).build();
    }

    void updateNotification(String text) { getSystemService(NotificationManager.class).notify(1, notification(text)); }
    @SuppressWarnings("deprecation")
    void keepScreenAwake(Session session) {
        if (owned != session || screenWakeLock != null) return;
        // The activity flag stops working when the controller opens another app on this device.
        // Keep the host screen lit while sharing is active, including before approval.
        PowerManager power = getSystemService(PowerManager.class);
        if (power == null) return;
        screenWakeLock = power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "LanPeer:activeShareScreen");
        screenWakeLock.setReferenceCounted(false);
        screenWakeLock.acquire();
    }

    private void releaseScreenWakeLock() {
        if (screenWakeLock == null) return;
        if (screenWakeLock.isHeld()) screenWakeLock.release();
        screenWakeLock = null;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        releaseScreenWakeLock();
        instance = null;
        PeerApp app = (PeerApp) getApplication();
        if (owned != null && app.session == owned) app.disconnect();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
