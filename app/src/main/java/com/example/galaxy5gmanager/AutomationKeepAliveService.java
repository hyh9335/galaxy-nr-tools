package com.example.galaxy5gmanager;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * A short-lived foreground service for the user's explicit ServiceMode session.
 * The optional overlay is a tiny, passive progress pill: it never receives
 * focus or touches, and does not change the underlying ServiceMode content.
 */
public final class AutomationKeepAliveService extends Service {
    private static volatile AutomationKeepAliveService active;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stop = this::stopSelf;
    private WindowManager wm;
    private TextView pill;
    private boolean terminal;
    private int totalSteps = 6;
    private String lastError = "";

    /** Called from the already-authorized Shizuku UserService OCR callback. */
    static void publishLine(String line) {
        AutomationKeepAliveService s = active;
        if (s != null && line != null && line.length() < 8000) {
            s.handler.post(() -> { if (s == active) s.handleProgress(line); });
        }
    }

    /** Fall back if the Shizuku callback disconnects before a terminal line. */
    static void finishIfIdle() {
        AutomationKeepAliveService s = active;
        if (s != null) s.handler.postDelayed(() -> {
            // Leave room for a queued final onProgress Binder callback.
            if (s == active && !s.terminal)
                s.finish(tr("Task finished; open the app to view results", "执行已结束，请回应用查看记录"), false);
        }, 1000L);
    }

    private String tr(String en, String zh) { return UiText.t(this, en, zh); }

    private int dp(int n) {
        return (int) (getResources().getDisplayMetrics().density * n + 0.5f);
    }

    private void display(String message, int shade) {
        if (pill == null) return;
        pill.setText(message.length() > 60 ? message.substring(0, 58) + "…" : message);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(shade);
        bg.setCornerRadius(dp(24));
        pill.setBackground(bg);
    }

    private void beginOverlay() {
        if (!Settings.canDrawOverlays(this) || pill != null) return;
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            pill = new TextView(this);
            pill.setTextSize(12);
            pill.setTextColor(Color.WHITE);
            pill.setMaxLines(2);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(14), dp(8), dp(14), dp(8));
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                    WindowManager.LayoutParams.FLAG_SECURE,
                android.graphics.PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.y = dp(42);
            // Overlay is untouchable; alpha <=0.8 also avoids Android's
            // untrusted-touch suppression in the small overlapping region.
            params.alpha = 0.78f;
            wm.addView(pill, params);
            display(tr("NR · Preparing menu probe…", "NR · 正在准备探测…"), Color.rgb(36, 53, 77));
        } catch (RuntimeException e) {
            pill = null; // Overlay is optional; the full trace remains in-app.
        }
    }

    private void handleProgress(String line) {
        if (terminal) return;
        if (line.startsWith("[Galaxy5G] 停止：")) {
            lastError = line.substring("[Galaxy5G] 停止：".length());
            display("✕ " + lastError, Color.rgb(157, 40, 52));
        } else if (line.contains("流程异常终止") || line.startsWith("执行异常：") || line.startsWith("已中断。")) {
            finish("✕ " + (lastError.isEmpty() ? tr("Stopped · see execution log", "已停止，请查看执行日志") : lastError), true);
        } else if (line.contains("探测结束：") || line.contains("所有指定菜单操作已提交")) {
            finish(line.contains("探测结束：") ? tr("✓ Probe complete (no changes)", "✓ 菜单探测完成（未修改设置）") :
                tr("✓ Menu actions submitted · verify network", "✓ 菜单操作已提交（仍需验证网络）"), false);
        } else if (line.startsWith("[Galaxy5G] [") && line.contains("] ")) {
            int pos = line.indexOf("] ", 12);
            if (pos > 12) {
                String step = line.substring(12, pos);
                if (step.length() <= 2 && step.matches("[1-6]"))
                    display(tr("● Step ", "● 第 ") + step + "/" + totalSteps + tr(" · ", " 项 · ") + line.substring(pos + 2), Color.rgb(36, 53, 77));
            }
        } else if (line.contains("点击 [")) {
            int first = line.indexOf("点击 [") + 4;
            int last = line.indexOf(']', first);
            if (last > first)
                display(tr("● Opening ", "● 正在打开 ") + line.substring(first, last), Color.rgb(36, 53, 77));
        } else if (line.contains("可访问：")) {
            display(tr("✓ Menu path verified", "✓ 本项菜单路径探测通过"), Color.rgb(29, 100, 88));
        } else if (line.contains("已点击 [")) {
            display(tr("✓ Action submitted (NV unverified)", "✓ 本项操作已提交（未验证 NV）"), Color.rgb(29, 100, 88));
        }
    }

    private void finish(String text, boolean failed) {
        terminal = true;
        display(text, failed ? Color.rgb(157, 40, 52) : Color.rgb(29, 100, 88));
        handler.removeCallbacks(stop);
        handler.postDelayed(stop, 10000L);
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("g5g-progress", getString(R.string.notification_name),
            NotificationManager.IMPORTANCE_MIN));
        active = this;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, "g5g-progress")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(tr("Galaxy NR Tools · ServiceMode session", "Galaxy NR 工具 · 工程菜单运行中"))
            .setContentText(tr("Offline OCR started by you", "正在执行用户启动的离线 OCR"))
            .setContentIntent(pi).setOngoing(true).build();
        startForeground(221, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        terminal = false;
        lastError = "";
        beginOverlay();
        handler.removeCallbacks(stop);
        handler.postDelayed(stop, 10 * 60 * 1000L);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(stop);
        if (active == this) active = null;
        if (pill != null && wm != null) {
            try { wm.removeViewImmediate(pill); } catch (Exception ignored) { }
            pill = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
