package com.example.galaxy5gmanager;

import android.app.Activity;
import android.Manifest;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.ImageView;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.TextView;
import rikka.shizuku.Shizuku;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.net.Uri;
import android.graphics.Point;
import android.content.Context;
import android.graphics.Rect;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.android.gms.tasks.Tasks;
import java.util.Locale;
import java.util.concurrent.TimeUnit;


/** Chinese UI. The actual automation is run by the Shizuku UserService. */
public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private IGalaxyAutomation remote;
    private boolean bindRequested;
    private TextView statusText, logText, diagnosticText;
    private LinearLayout logPanel;
    private boolean logsExpanded = false;
    private TelephonyManager telephony;
    private int lastOverrideType = -1;
    private TelephonyCallback diagnosticListener;
    private Button permissionButton, overlayButton, statusButton, probeButton, applyCoreButton, applyFullButton, cancelButton, rebootButton;
    private boolean screenResumed;
    private boolean rebootPromptShown;
    private String reportLast = "";
    private boolean active;
    private static final int PERMISSION_CODE = 4501;
    private static final int PHONE_PERMISSION_CODE = 4502;

    private final Shizuku.OnBinderReceivedListener onBinder = () -> runOnUiThread(this::refreshConnection);
    private final Shizuku.OnBinderDeadListener onDead = () -> runOnUiThread(() -> {
        remote = null; bindRequested = false; statusText.setText(tr("Shizuku disconnected. Start Shizuku again.", "Shizuku 服务已断开，请重新启动 Shizuku。"));
        updateButtons(false);
    });
    private final Shizuku.OnRequestPermissionResultListener onPermission = (code, result) -> {
        if (code == PERMISSION_CODE) runOnUiThread(() -> {
            if (result == PackageManager.PERMISSION_GRANTED) bindService();
            else statusText.setText(tr("Shizuku permission denied. Grant access in Shizuku.", "未授予 Shizuku 权限。可返回 Shizuku 管理授权后重试。"));
        });
    };
    // Kept in the regular app (not the Shizuku process). A temporary foreground
    // service keeps this app eligible to perform OCR while ServiceMode is on top.
    private static volatile TextRecognizer cachedRecognizer;
    private static synchronized TextRecognizer getRecognizer() {
        if (cachedRecognizer == null)
            cachedRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        return cachedRecognizer;
    }
    private boolean keepAliveForRun;
    private void stopKeepAlive() {
        if (keepAliveForRun) {
            keepAliveForRun = false;
            AutomationKeepAliveService.finishIfIdle();
        }
    }
    private final IGalaxyPageReader pageReader = new IGalaxyPageReader.Stub() {
        @Override public void onProgress(String line) {
            AutomationKeepAliveService.publishLine(line);
        }
        @Override public String recognize(ParcelFileDescriptor screenshot, int screenWidth, int screenHeight) {
            if (screenshot == null) return "ERROR: 截图文件描述符为空";
            Bitmap image = null;
            try {
                BitmapFactory.Options info = new BitmapFactory.Options();
                info.inJustDecodeBounds = true;
                BitmapFactory.decodeFileDescriptor(screenshot.getFileDescriptor(), null, info);
                if (info.outWidth < 200 || info.outHeight < 200 ||
                    info.outWidth > 6000 || info.outHeight > 9000)
                    return "ERROR: 截图尺寸无效 " + info.outWidth + "x" + info.outHeight;
                BitmapFactory.Options opt = new BitmapFactory.Options();
                // Limit allocations, but retain sufficient resolution for menu OCR.
                opt.inSampleSize = ((long)info.outWidth * info.outHeight > 3800000) ? 2 : 1;
                image = BitmapFactory.decodeFileDescriptor(screenshot.getFileDescriptor(), null, opt);
                if (image == null) return "ERROR: 截图解码失败";
                final int width = image.getWidth(), height = image.getHeight();
                float fx = (float)info.outWidth / width;
                float fy = (float)info.outHeight / height;
                StringBuilder rows = new StringBuilder();
                Text found = Tasks.await(getRecognizer().process(InputImage.fromBitmap(image, 0)),
                    13, TimeUnit.SECONDS);
                int rowCount = 0;
                for (Text.TextBlock block : found.getTextBlocks()) {
                    for (Text.Line line : block.getLines()) {
                        Rect box = line.getBoundingBox();
                        if (box == null || box.isEmpty()) continue;
                        String label = line.getText().toUpperCase(Locale.ROOT).trim()
                            .replaceAll("^(?:\\[|\\()?[0-9]+(?:\\]|\\)|\\.|:)?\\s*", "")
                            .replaceAll("\\s+", " ")
                            .replaceAll("[^A-Z0-9 /&()*\\-]", "").trim();
                        if (label.isEmpty() || label.length() > 140) continue;
                        int x = Math.min(info.outWidth - 1, Math.max(0, Math.round(box.centerX() * fx)));
                        int y = Math.min(info.outHeight - 1, Math.max(0, Math.round(box.centerY() * fy)));
                        rows.append(label).append('\t').append(x).append('\t').append(y).append('\n');
                        if (++rowCount >= 80) break;
                    }
                    if (rowCount >= 80) break;
                }
                return rows.length() > 0 ? rows.toString() : "ERROR: OCR 无文字结果（截图可能是空白或受保护画面）";
            } catch (OutOfMemoryError e) {
                return "ERROR: OCR 内存不足，已停止，请勿重复尝试";
            } catch (Exception e) {
                return "ERROR: OCR " + e.getClass().getSimpleName() + " " + e.getMessage();
            } finally {
                if (image != null) image.recycle();
                try { screenshot.close(); } catch (Exception ignored) { }
            }
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            remote = IGalaxyAutomation.Stub.asInterface(binder);
            runOnUiThread(() -> {
                try {
                    int uid = remote.shellUid();
                    if (uid != 2000 && uid != 0) {
                        statusText.setText(tr("Unsafe execution UID=", "不安全的执行身份 UID=") + uid + tr(". Refusing automation.", "，禁止使用。"));
                        remote = null; updateButtons(false); return;
                    }
                    remote.setPageReader(pageReader);
                    statusText.setText(tr("Shizuku connected · UID=", "Shizuku 已连接 · UID=") + uid + " · " + Build.MODEL + tr(" · Offline OCR", " · 本地 OCR 诊断版"));
                    updateButtons(true);
                } catch (Exception e) { showError(e); }
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            remote = null; bindRequested = false;
            runOnUiThread(() -> { statusText.setText(tr("Permission service disconnected.", "权限服务已断开。")); updateButtons(false); });
        }
    };
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (remote != null) {
                try {
                    boolean busy = remote.isRunning();
                    String report = remote.getReport();
                    if (!reportLast.equals(report)) {
                        reportLast = report;
                        logText.setText(report.isEmpty() ? tr("No execution log yet.", "尚无操作记录。") : report);
                        if (report.contains("流程异常终止") || report.contains("停止：")) {
                            if (logPanel != null) logPanel.setVisibility(View.VISIBLE);
                            logsExpanded = true;
                        }
                    }
                    statusButton.setEnabled(!busy);
                    probeButton.setEnabled(!busy);
                    applyCoreButton.setEnabled(!busy);
                    applyFullButton.setEnabled(!busy);
                    cancelButton.setEnabled(busy);
                    boolean canReboot = !busy && remote.canReboot();
                    rebootButton.setEnabled(canReboot);
                    rebootButton.setVisibility(canReboot ? View.VISIBLE : View.GONE);
                    if (canReboot && !rebootPromptShown && screenResumed) {
                        rebootPromptShown = true;
                        showRebootDialog();
                    }
                    if (!busy) stopKeepAlive();
                } catch (Exception e) { showError(e); }
            }
            handler.postDelayed(this, 1100);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        active = true;
        telephony = getSystemService(TelephonyManager.class);
        Shizuku.addBinderReceivedListenerSticky(onBinder);
        Shizuku.addBinderDeadListener(onDead);
        Shizuku.addRequestPermissionResultListener(onPermission);
        handler.post(refresh);
        refreshConnection();
    }

    @Override protected void onPause() {
        screenResumed = false;
        super.onPause();
    }

    @Override protected void onDestroy() {
        active = false;
        handler.removeCallbacks(refresh);
        // Deliberately do not stop the keep-alive service: ServiceMode may
        // remain on top while this Activity gets destroyed by configuration.
        Shizuku.removeBinderReceivedListener(onBinder);
        Shizuku.removeBinderDeadListener(onDead);
        Shizuku.removeRequestPermissionResultListener(onPermission);
        if (diagnosticListener != null && telephony != null) {
            try { telephony.unregisterTelephonyCallback(diagnosticListener); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    private void refreshConnection() {
        try {
            if (!Shizuku.pingBinder()) {
                statusText.setText(tr("Shizuku is not running. Start it through wireless debugging first.", "Shizuku 尚未启动。请先在 Shizuku 中启动无线调试服务。"));
                updateButtons(false); return;
            }
            if (Shizuku.isPreV11()) {
                statusText.setText(tr("Shizuku is outdated. Version 11+ is required.", "Shizuku 版本过旧，至少需要 v11。")); return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bindService();
            else statusText.setText(tr("Shizuku is running but this app is not authorized.", "Shizuku 已运行，尚未授权本应用。点击授权。 "));
        } catch (Exception e) { statusText.setText(tr("Shizuku unavailable: ", "Shizuku 连接不可用：") + e.getMessage()); }
    }

    private void requestPermission() {
        try {
            if (!Shizuku.pingBinder()) { refreshConnection(); return; }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bindService();
            else if (Shizuku.shouldShowRequestPermissionRationale())
                new AlertDialog.Builder(this).setMessage(tr("Permission denied. Allow this app in Shizuku settings.", "权限已拒绝，请到 Shizuku 的授权应用列表中允许。"))
                    .setPositiveButton(tr("OK", "知道了"), null).show();
            else Shizuku.requestPermission(PERMISSION_CODE);
        } catch (Exception e) { showError(e); }
    }

    private void bindService() {
        if (remote != null || bindRequested) return;
        try {
            bindRequested = true;
            statusText.setText(tr("Connecting to Shizuku UserService…", "连接 Shizuku UserService 中…"));
            Shizuku.UserServiceArgs a = new Shizuku.UserServiceArgs(
                    new ComponentName(getPackageName(), PrivilegedService.class.getName()))
                .processNameSuffix("galaxy5g").daemon(false).version(4).tag("galaxy5g-verified");
            Shizuku.bindUserService(a, connection);
        } catch (Exception e) { bindRequested = false; showError(e); }
    }

    private void runAction(String mode, String profile, String confirmation) {
        if (!"status".equals(mode) && !Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                .setTitle(tr("Enable floating progress", "开启悬浮进度提示"))
                .setMessage(tr("Allow display over other apps to see progress on ServiceMode. The overlay does not intercept taps.", "需要允许在其他应用上层显示，才能在 ServiceMode 中看到每一步的进度。悬浮条不接收触摸，不会挡住菜单操作。授权后请再点击探测。"))
                .setPositiveButton(tr("Open settings", "前往授权"), (d, w) -> requestOverlayPermission())
                .setNegativeButton(tr("Continue without overlay", "不显示悬浮条，仍继续"), (d, w) -> runActionInternal(mode, profile, confirmation))
                .show();
            return;
        }
        runActionInternal(mode, profile, confirmation);
    }

    private void requestOverlayPermission() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (RuntimeException e) { showError(e); }
    }

    @Override protected void onResume() {
        super.onResume();
        screenResumed = true;
        if (overlayButton != null) overlayButton.setText(Settings.canDrawOverlays(this)
            ? tr("Floating progress · Enabled", "悬浮进度提示 · 已授权") : tr("Enable floating progress (recommended)", "开启悬浮进度提示（推荐）"));
    }

    private void runActionInternal(String mode, String profile, String confirmation) {
        if (remote == null) { requestPermission(); return; }
        try {
            if (remote.isRunning()) return;
            String script = readAsset();
            remote.setPageReader(pageReader);
            if (!"status".equals(mode)) {
                try {
                    startForegroundService(new Intent(this, AutomationKeepAliveService.class));
                    keepAliveForRun = true;
                } catch (RuntimeException e) {
                    statusText.setText(tr("Foreground service could not start: ", "前台保活服务启动失败：") + e.getMessage());
                    return;
                }
            }
            boolean ok = remote.startRun(script, mode, profile, confirmation);
            if (ok) {
                rebootPromptShown = false;
                rebootButton.setEnabled(false);
                rebootButton.setVisibility(View.GONE);
            }
            if (!ok) { stopKeepAlive(); statusText.setText(tr("Cannot start: already running, unauthorized, or script integrity check failed.", "无法启动：正在运行、身份不符或脚本完整性校验失败。")); }
            else { logText.setText(tr("Starting… Keep the phone unlocked and ServiceMode visible.\nResults will open here automatically when possible.", "正在启动… 请保持屏幕解锁并让 ServiceMode 在前台。\n完成后返回这里查看记录。")); reportLast = ""; }
        } catch (Exception e) { stopKeepAlive(); showError(e); }
    }

    private String readAsset() throws Exception {
        try (InputStream in = getAssets().open("galaxy5g.sh"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static final class DisplayMonitor extends TelephonyCallback
        implements TelephonyCallback.DisplayInfoListener {
        private final MainActivity parent;
        DisplayMonitor(MainActivity parent) { this.parent = parent; }
        @Override public void onDisplayInfoChanged(TelephonyDisplayInfo info) {
            parent.runOnUiThread(() -> {
                parent.lastOverrideType = info.getOverrideNetworkType();
                parent.renderNetworkDiagnostics();
            });
        }
    }

    private void diagnoseNetwork() {
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, PHONE_PERMISSION_CODE);
            return;
        }
        if (diagnosticListener == null && telephony != null) {
            try {
                diagnosticListener = new DisplayMonitor(this);
                telephony.registerTelephonyCallback(getMainExecutor(), diagnosticListener);
            } catch (RuntimeException ex) {
                diagnosticText.setText(tr("Network callback unavailable. Basic radio data is still available.", "系统网络显示回调不可用，仍可检测基础数据类型。") );
            }
        }
        renderNetworkDiagnostics();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PHONE_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                diagnoseNetwork();
            else diagnosticText.setText(tr("Phone-state permission denied. Only read-only diagnostics are affected.", "未授权电话状态，仅影响只读诊断，不影响菜单探测。") );
        }
    }

    private void renderNetworkDiagnostics() {
        if (diagnosticText == null) return;
        NetworkDiagnostics.Result result = NetworkDiagnostics.inspect(this, lastOverrideType);
        diagnosticText.setText(result.text);
        if (result.has5gEvidence) {
            getPreferences(MODE_PRIVATE).edit().putString("evidence_build", Build.FINGERPRINT)
                .putLong("evidence_time", System.currentTimeMillis()).apply();
        } else if (Build.FINGERPRINT.equals(
                getPreferences(MODE_PRIVATE).getString("evidence_build", ""))) {
            long when = getPreferences(MODE_PRIVATE).getLong("evidence_time", 0);
            if (when > 0) diagnosticText.append(tr("\n5G was observed on this firmware before (history, not a current connection or full unlock).", "\n此固件此前观测过 5G 相关状态（历史记录，不代表现在连接或全部解锁）。"));
        }
    }

    private void showRebootDialog() {
        new AlertDialog.Builder(this)
            .setTitle(tr("Radio menu operations complete · Restart recommended", "菜单恢复流程已完成，建议重启"))
            .setMessage(tr("All requested menu actions finished, but modem NV and network capability remain unverified. Restart disconnects your phone; Shizuku without root must usually be restarted too. Restart now?", "本次实际恢复流程已全部结束，但不代表已经验证基带 NV 和 5G 网络。重启会断开当前连接，且无 Root 的 Shizuku 通常需要重新启动。现在重启吗？"))
            .setNegativeButton(tr("Later", "稍后重启"), null)
            .setPositiveButton(tr("Restart now", "立即重启"), (d, w) -> rebootNow())
            .show();
    }

    private void rebootNow() {
        if (remote == null) { statusText.setText(tr("Shizuku disconnected; restart manually.", "Shizuku 已断开，请手动重启。")); return; }
        try {
            if (!remote.canReboot()) { statusText.setText(tr("Restart is available only after a complete restore.", "仅在实际恢复流程全部成功后才允许重启。")); return; }
            boolean sent = remote.rebootDevice();
            statusText.setText(sent ? tr("Restart requested. Waiting for the device…", "已发送系统重启请求，等待设备重启…") : tr("Restart request failed. See log or restart manually.", "重启请求失败，请查看执行日志或手动重启。"));
            if (sent) rebootButton.setEnabled(false);
        } catch (Exception e) { showError(e); }
    }

    private void confirmation(boolean full) {
        String profile = full ? "full" : "core";
        String confirmation = full ? "FULL" : "APPLY";
        String description = full
            ? tr("Run all six actions, including mmWave ALL ENABLE and removing the SA permit list.", "将执行六项菜单设置，包括毫米波 ALL ENABLE 和 SA 白名单操作。")
            : tr("Run five actions, excluding Sub6/mmWave ALL ENABLE.", "将执行五项菜单设置，不操作 Sub-6/mmWave 的 ALL ENABLE。");
        new AlertDialog.Builder(this)
            .setTitle(tr("Modify radio engineering settings?", "确认修改无线基带工程设置？"))
            .setMessage(description + tr("\n\nProbe the menu paths first. Only listed US Galaxy S23+ U/U1 devices, SIM1. Experimental outside tested S25 Ultra; network connectivity may be lost. No automatic rollback.\n\nProceed?", "\n\n请先完成菜单探测；仅限已列出的美版 Galaxy S23 及更新 U/U1 型号的 SIM1（其他机型未真机验证）。操作可能导致短时断网，且无法自动恢复到之前的设置。\n\n确定继续吗？"))
            .setNegativeButton(tr("Cancel", "取消"), null)
            .setPositiveButton(tr("Confirm restore", "确认执行"), (dialog, which) -> runAction("apply", profile, confirmation)).show();
    }

    private void showError(Exception e) {
        statusText.setText(tr("Error: ", "错误：") + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    private String tr(String en, String zh) { return UiText.t(this, en, zh); }

    private int dp(int n) { return (int)(getResources().getDisplayMetrics().density * n + 0.5f); }

    private TextView label(LinearLayout parent, String text, int size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextColor(Color.rgb(36, 43, 57)); t.setTextSize(size);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, dp(6), 0, dp(8)); parent.addView(t); return t;
    }
    private Button action(LinearLayout parent, String name, View.OnClickListener cb) {
        Button b = new Button(this); b.setText(name); b.setAllCaps(false); b.setOnClickListener(cb);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54)); p.bottomMargin = dp(8);
        parent.addView(b, p); return b;
    }
    private void updateButtons(boolean connected) {
        statusButton.setEnabled(connected); probeButton.setEnabled(connected);
        applyCoreButton.setEnabled(connected); applyFullButton.setEnabled(connected);
        cancelButton.setEnabled(false);
        rebootButton.setEnabled(false);
        rebootButton.setVisibility(View.GONE);
    }

    // Compact dark cards, clear primary/secondary actions and no giant scroll of
    // default Material buttons. All controls are native Android widgets.
    private int BG, SURFACE, PANEL, WHITE, MUTED, BLUE, TEAL, PURPLE, ON_ACCENT, OUTLINE;
    private boolean darkTheme;
    private void loadThemeColors() {
        darkTheme = (getResources().getConfiguration().uiMode &
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES;
        BG = getColor(R.color.app_background);
        SURFACE = getColor(R.color.app_surface);
        PANEL = getColor(R.color.app_panel);
        WHITE = getColor(R.color.app_text);
        MUTED = getColor(R.color.app_muted);
        OUTLINE = getColor(R.color.app_outline);
        BLUE = darkTheme ? Color.rgb(43, 98, 190) : Color.rgb(30, 99, 218);
        ON_ACCENT = Color.WHITE;
        TEAL = darkTheme ? Color.rgb(22, 113, 101) : Color.rgb(18, 111, 93);
        PURPLE = darkTheme ? Color.rgb(104, 73, 181) : Color.rgb(103, 73, 181);
    }

    private GradientDrawable rounded(int color, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (strokeColor != 0) drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }
    private TextView heading(LinearLayout parent, String value, int sp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(sp); view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        parent.addView(view);
        return view;
    }
    private LinearLayout section(LinearLayout container) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(rounded(SURFACE, 18, OUTLINE));
        panel.setPadding(dp(16), dp(15), dp(16), dp(15));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(13);
        container.addView(panel, params);
        return panel;
    }
    private void space(LinearLayout p, int size) {
        View v = new View(this);
        p.addView(v, new LinearLayout.LayoutParams(1, dp(size)));
    }
    private Button button(LinearLayout parent, String title, int background, View.OnClickListener cb) {
        Button b = new Button(this);
        b.setText(title); b.setTextSize(13); b.setTextColor(background == PANEL ? WHITE : ON_ACCENT);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false);
        b.setElevation(dp(1));
        b.setPadding(dp(6), 0, dp(6), 0);
        b.setBackground(rounded(background, 13, 0));
        b.setOnClickListener(cb);
        LinearLayout.LayoutParams param = new LinearLayout.LayoutParams(-1, dp(49));
        param.bottomMargin = dp(7);
        parent.addView(b,param);
        return b;
    }
    private LinearLayout horizontal(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(row, new LinearLayout.LayoutParams(-1,-2));
        return row;
    }
    private Button tile(LinearLayout row, String title, int bgColor, View.OnClickListener cb) {
        Button b = new Button(this);
        b.setText(title); b.setTextSize(13); b.setTextColor(bgColor == PANEL ? WHITE : ON_ACCENT);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false); b.setBackground(rounded(bgColor, 13, 0));
        b.setOnClickListener(cb);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(68), 1f);
        params.setMargins(dp(3),dp(3),dp(3),dp(3));
        row.addView(b, params);
        return b;
    }

    private void buildUi() {
        loadThemeColors();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(darkTheme ? 0 :
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(24), dp(18), dp(30));
        scroll.addView(content); setContentView(scroll);

        LinearLayout top = horizontal(content);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(com.example.galaxy5gmanager.R.drawable.ic_launcher);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(dp(65), dp(65));
        imageParams.rightMargin = dp(12);
        top.addView(icon, imageParams);
        LinearLayout brand = new LinearLayout(this);brand.setOrientation(LinearLayout.VERTICAL);
        top.addView(brand, new LinearLayout.LayoutParams(0,-2,1f));
        heading(brand,tr("Galaxy NR Tools", "Galaxy NR 工具"),22,true,WHITE);
        heading(brand,Build.MODEL + " · Android " + Build.VERSION.RELEASE +
            tr(" · US Galaxy S23+", " · 美版 Galaxy S23 及更新机型"),12,false,MUTED);
        space(content,13);

        LinearLayout connection = section(content);
        heading(connection,tr("◆  Shizuku & device", "◆  SHIZUKU 与设备状态"),12,true,BLUE);
        space(connection,8);
        statusText = heading(connection,tr("Connecting to Shizuku…", "正在连接 Shizuku…"),14,true,WHITE);
        space(connection,10);
        permissionButton = button(connection,tr("Connect / Authorize Shizuku", "连接 / 授权 Shizuku"),PANEL, v -> requestPermission());
        overlayButton = button(connection,tr("Floating progress · Configure", "悬浮进度提示 · 点击设置"),PANEL, v -> {
            if (Settings.canDrawOverlays(this))
                new AlertDialog.Builder(this).setMessage(tr("Floating progress is enabled. It does not block menu taps.", "悬浮条已授权。运行菜单探测时会显示实时进度，不挡住操作。"))
                    .setPositiveButton(tr("OK", "知道了"),null).show();
            else requestOverlayPermission();
        });

        LinearLayout diagnostics = section(content);
        heading(diagnostics,tr("01   5G status · Read-only", "01   5G 状态 · 只读检测"),16,true,WHITE);
        space(diagnostics,7);
        heading(diagnostics,tr("LTE connection does not imply 5G is disabled. Menu switches require separate verification.", "当前 4G 不代表 5G 被锁；工程白名单不能仅凭信号判断。"),12,false,MUTED);
        space(diagnostics,10);
        button(diagnostics,tr("Check 5G network evidence", "检查 5G 网络与能力"),TEAL, v -> diagnoseNetwork());
        diagnosticText = heading(diagnostics,tr("Not checked yet.", "尚未检测。"),12,false,MUTED);
        diagnosticText.setTextIsSelectable(true);

        LinearLayout automations = section(content);
        heading(automations,tr("02   ServiceMode · Automation", "02   ServiceMode · 自动化"),16,true,WHITE);
        space(automations,7);
        heading(automations,tr("Probe first, then restore if needed. OCR runs offline on the device.", "先探测，再按需恢复。OCR 在手机本地运行。"),12,false,MUTED);
        space(automations,11);
        probeButton = button(automations,tr("Probe six menu paths · No changes", "探测六条菜单路径 · 不修改设置"),BLUE, v -> runAction("probe","full",""));
        LinearLayout options = horizontal(automations);
        statusButton = tile(options,tr("Device / firmware", "设备 / 固件状态"),PANEL, v -> runAction("status","core",""));
        Button openLogs = tile(options,tr("Execution log", "查看执行记录"),PANEL, v -> {
            logsExpanded = !logsExpanded;
            logPanel.setVisibility(logsExpanded ? View.VISIBLE : View.GONE);
        });
        space(automations,8);
        heading(automations,tr("Restore radio configuration · Use caution", "恢复设置 · 涉及基带参数，请谨慎"),12,true,MUTED);
        space(automations,6);
        LinearLayout restores = horizontal(automations);
        applyCoreButton = tile(restores,tr("Restore core 5G", "恢复基础 5G"),PURPLE, v -> confirmation(false));
        applyFullButton = tile(restores,tr("Full restore · incl. mmWave", "完整恢复 · 含 mmWave"),BLUE, v -> confirmation(true));
        cancelButton = button(automations,tr("Cancel current task", "中止当前操作"),Color.rgb(159, 54, 72),v -> new AlertDialog.Builder(this)
            .setTitle(tr("Cancel the current task?", "确认中止？"))
            .setMessage(tr("Settings already applied cannot be undone automatically.", "已经提交的设置无法自动撤销。"))
            .setNegativeButton(tr("Continue", "继续"),null)
            .setPositiveButton(tr("Stop", "中止"),(d,w)->{try {if(remote!=null)remote.cancel();}catch(RemoteException e){showError(e);}}).show());
        rebootButton = button(automations,tr("Restore complete · Restart phone", "恢复全部完成 · 立即重启手机"),TEAL,v -> showRebootDialog());
        rebootButton.setVisibility(View.GONE);

        logPanel = section(content);
        heading(logPanel,tr("Execution log", "执行记录"),16,true,WHITE);
        space(logPanel,7);
        heading(logPanel,tr("Opens automatically on failure. Long-press to copy.", "失败时会自动展开。长按文字可以复制。"),12,false,MUTED);
        space(logPanel,8);
        logText = heading(logPanel,tr("No execution log yet.", "尚无操作记录。"),12,false,WHITE);
        logText.setTextIsSelectable(true);
        logPanel.setVisibility(View.GONE);
        space(content,3);
        heading(content,tr("All OCR stays on your device. Unknown menu states stop safely.", "所有图像识别都在设备本地完成。仅修改已明确确认的工程菜单项；失败时安全停止。"),12,false,MUTED);
        updateButtons(false);
    }
}
