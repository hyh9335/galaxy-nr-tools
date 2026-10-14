package com.example.galaxy5gmanager;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Foreground life support only while the user has started a ServiceMode session. */
public final class AutomationKeepAliveService extends Service {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stop = this::stopSelf;
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("g5g-progress", "5G 菜单探测",
            NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, "g5g-progress")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Galaxy 5G 正在探测 ServiceMode")
            .setContentText("只在用户主动运行期间进行本地离线菜单识别")
            .setContentIntent(pi).setOngoing(true).build();
        startForeground(221, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        handler.removeCallbacks(stop);
        handler.postDelayed(stop, 10 * 60 * 1000L);
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() { handler.removeCallbacks(stop); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
