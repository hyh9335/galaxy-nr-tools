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
        remote = null; bindRequested = false; statusText.setText("Shizuku 服务已断开，请重新启动 Shizuku。");
        updateButtons(false);
    });
    private final Shizuku.OnRequestPermissionResultListener onPermission = (code, result) -> {
        if (code == PERMISSION_CODE) runOnUiThread(() -> {
            if (result == PackageManager.PERMISSION_GRANTED) bindService();
            else statusText.setText("未授予 Shizuku 权限。可返回 Shizuku 管理授权后重试。");
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
                        statusText.setText("不安全的执行身份 UID=" + uid + "，禁止使用。");
                        remote = null; updateButtons(false); return;
                    }
                    remote.setPageReader(pageReader);
                    statusText.setText("Shizuku 已连接 · UID=" + uid + " · " + Build.MODEL + " · 本地 OCR 诊断版");
                    updateButtons(true);
                } catch (Exception e) { showError(e); }
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            remote = null; bindRequested = false;
            runOnUiThread(() -> { statusText.setText("权限服务已断开。"); updateButtons(false); });
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
                        logText.setText(report.isEmpty() ? "尚无操作记录。" : report);
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
                statusText.setText("Shizuku 尚未启动。请先在 Shizuku 中启动无线调试服务。");
                updateButtons(false); return;
            }
            if (Shizuku.isPreV11()) {
                statusText.setText("Shizuku 版本过旧，至少需要 v11。"); return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bindService();
            else statusText.setText("Shizuku 已运行，尚未授权本应用。点击授权。 ");
        } catch (Exception e) { statusText.setText("Shizuku 连接不可用：" + e.getMessage()); }
    }

    private void requestPermission() {
        try {
            if (!Shizuku.pingBinder()) { refreshConnection(); return; }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bindService();
            else if (Shizuku.shouldShowRequestPermissionRationale())
                new AlertDialog.Builder(this).setMessage("权限已拒绝，请到 Shizuku 的授权应用列表中允许。")
                    .setPositiveButton("知道了", null).show();
            else Shizuku.requestPermission(PERMISSION_CODE);
        } catch (Exception e) { showError(e); }
    }

    private void bindService() {
        if (remote != null || bindRequested) return;
        try {
            bindRequested = true;
            statusText.setText("连接 Shizuku UserService 中…");
            Shizuku.UserServiceArgs a = new Shizuku.UserServiceArgs(
                    new ComponentName(getPackageName(), PrivilegedService.class.getName()))
                .processNameSuffix("galaxy5g").daemon(false).version(4).tag("galaxy5g-verified");
            Shizuku.bindUserService(a, connection);
        } catch (Exception e) { bindRequested = false; showError(e); }
    }

    private void runAction(String mode, String profile, String confirmation) {
        if (!"status".equals(mode) && !Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                .setTitle("开启悬浮进度提示")
                .setMessage("需要允许在其他应用上层显示，才能在 ServiceMode 中看到每一步的进度。悬浮条不接收触摸，不会挡住菜单操作。授权后请再点击探测。")
                .setPositiveButton("前往授权", (d, w) -> requestOverlayPermission())
                .setNegativeButton("不显示悬浮条，仍继续", (d, w) -> runActionInternal(mode, profile, confirmation))
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
            ? "悬浮进度提示 · 已授权" : "开启悬浮进度提示（推荐）");
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
                    statusText.setText("前台保活服务启动失败：" + e.getMessage());
                    return;
                }
            }
            boolean ok = remote.startRun(script, mode, profile, confirmation);
            if (ok) {
                rebootPromptShown = false;
                rebootButton.setEnabled(false);
                rebootButton.setVisibility(View.GONE);
            }
            if (!ok) { stopKeepAlive(); statusText.setText("无法启动：正在运行、身份不符或脚本完整性校验失败。"); }
            else { logText.setText("正在启动… 请保持屏幕解锁并让 ServiceMode 在前台。\n完成后返回这里查看记录。"); reportLast = ""; }
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
                diagnosticText.setText("系统网络显示回调不可用，仍可检测基础数据类型。" );
            }
        }
        renderNetworkDiagnostics();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PHONE_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                diagnoseNetwork();
            else diagnosticText.setText("未授权电话状态，仅影响只读诊断，不影响菜单探测。" );
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
            if (when > 0) diagnosticText.append("\n此固件此前观测过 5G 相关状态（历史记录，不代表现在连接或全部解锁）。");
        }
    }

    private void showRebootDialog() {
        new AlertDialog.Builder(this)
            .setTitle("菜单恢复流程已完成，建议重启")
            .setMessage("本次实际恢复流程已全部结束，但不代表已经验证基带 NV 和 5G 网络。重启会断开当前连接，且无 Root 的 Shizuku 通常需要重新启动。现在重启吗？")
            .setNegativeButton("稍后重启", null)
            .setPositiveButton("立即重启", (d, w) -> rebootNow())
            .show();
    }

    private void rebootNow() {
        if (remote == null) { statusText.setText("Shizuku 已断开，请手动重启。"); return; }
        try {
            if (!remote.canReboot()) { statusText.setText("仅在实际恢复流程全部成功后才允许重启。"); return; }
            boolean sent = remote.rebootDevice();
            statusText.setText(sent ? "已发送系统重启请求，等待设备重启…" : "重启请求失败，请查看执行日志或手动重启。");
            if (sent) rebootButton.setEnabled(false);
        } catch (Exception e) { showError(e); }
    }

    private void confirmation(boolean full) {
        String profile = full ? "full" : "core";
        String confirmation = full ? "FULL" : "APPLY";
        String description = full
            ? "将执行六项菜单设置，包括毫米波 ALL ENABLE 和 SA 白名单操作。"
            : "将执行五项菜单设置，不操作 Sub-6/mmWave 的 ALL ENABLE。";
        new AlertDialog.Builder(this)
            .setTitle("确认修改无线基带工程设置？")
            .setMessage(description + "\n\n请先完成菜单探测；仅限美版 S23U/S25U 的 SIM1。操作可能导致短时断网，且无法自动恢复到之前的设置。\n\n确定继续吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认执行", (dialog, which) -> runAction("apply", profile, confirmation)).show();
    }

    private void showError(Exception e) {
        statusText.setText("错误：" + e.getClass().getSimpleName() + "：" + e.getMessage());
    }

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
    private static final int BG = Color.rgb(9, 15, 28);
    private static final int SURFACE = Color.rgb(21, 32, 52);
    private static final int PANEL = Color.rgb(28, 42, 65);
    private static final int WHITE = Color.rgb(240, 246, 255);
    private static final int MUTED = Color.rgb(160, 175, 198);
    private static final int BLUE = Color.rgb(38, 119, 246);
    private static final int TEAL = Color.rgb(17, 135, 112);
    private static final int PURPLE = Color.rgb(103, 65, 207);

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
        panel.setBackground(rounded(SURFACE, 18, Color.rgb(39, 55, 82)));
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
        b.setText(title); b.setTextSize(13); b.setTextColor(WHITE);
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
        b.setText(title); b.setTextSize(13); b.setTextColor(WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false); b.setBackground(rounded(bgColor, 13, 0));
        b.setOnClickListener(cb);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(68), 1f);
        params.setMargins(dp(3),dp(3),dp(3),dp(3));
        row.addView(b, params);
        return b;
    }

    private void buildUi() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
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
        heading(brand,"Galaxy 5G Manager",22,true,WHITE);
        heading(brand,"S23U / S25U 美版 · Android 17",12,false,MUTED);
        space(content,13);

        LinearLayout connection = section(content);
        heading(connection,"◆  SHIZUKU 与设备状态",12,true,Color.rgb(109,184,252));
        space(connection,8);
        statusText = heading(connection,"正在连接 Shizuku…",14,true,WHITE);
        space(connection,10);
        permissionButton = button(connection,"连接 / 授权 Shizuku",PANEL, v -> requestPermission());
        overlayButton = button(connection,"悬浮进度提示 · 点击设置",PANEL, v -> {
            if (Settings.canDrawOverlays(this))
                new AlertDialog.Builder(this).setMessage("悬浮条已授权。运行菜单探测时会显示实时进度，不挡住操作。")
                    .setPositiveButton("知道了",null).show();
            else requestOverlayPermission();
        });

        LinearLayout diagnostics = section(content);
        heading(diagnostics,"01   5G 状态 · 只读检测",16,true,WHITE);
        space(diagnostics,7);
        heading(diagnostics,"当前 4G 不代表 5G 被锁；工程白名单不能仅凭信号判断。",12,false,MUTED);
        space(diagnostics,10);
        button(diagnostics,"检查 5G 网络与能力",TEAL, v -> diagnoseNetwork());
        diagnosticText = heading(diagnostics,"尚未检测。",12,false,MUTED);
        diagnosticText.setTextIsSelectable(true);

        LinearLayout automations = section(content);
        heading(automations,"02   ServiceMode · 自动化",16,true,WHITE);
        space(automations,7);
        heading(automations,"先探测，再按需恢复。OCR 在手机本地运行。",12,false,MUTED);
        space(automations,11);
        probeButton = button(automations,"探测六条菜单路径 · 不修改设置",BLUE, v -> runAction("probe","full",""));
        LinearLayout options = horizontal(automations);
        statusButton = tile(options,"设备 / 固件状态",PANEL, v -> runAction("status","core",""));
        Button openLogs = tile(options,"查看执行记录",PANEL, v -> {
            logsExpanded = !logsExpanded;
            logPanel.setVisibility(logsExpanded ? View.VISIBLE : View.GONE);
        });
        space(automations,8);
        heading(automations,"恢复设置 · 涉及基带参数，请谨慎",12,true,Color.rgb(255,198,104));
        space(automations,6);
        LinearLayout restores = horizontal(automations);
        applyCoreButton = tile(restores,"恢复基础 5G",PURPLE, v -> confirmation(false));
        applyFullButton = tile(restores,"完整恢复 · 含 mmWave",BLUE, v -> confirmation(true));
        cancelButton = button(automations,"中止当前操作",Color.rgb(112,49,63),v -> new AlertDialog.Builder(this)
            .setTitle("确认中止？")
            .setMessage("已经提交的设置无法自动撤销。")
            .setNegativeButton("继续",null)
            .setPositiveButton("中止",(d,w)->{try {if(remote!=null)remote.cancel();}catch(RemoteException e){showError(e);}}).show());
        rebootButton = button(automations,"恢复全部完成 · 立即重启手机",TEAL,v -> showRebootDialog());
        rebootButton.setVisibility(View.GONE);

        logPanel = section(content);
        heading(logPanel,"执行记录",16,true,WHITE);
        space(logPanel,7);
        heading(logPanel,"失败时会自动展开。长按文字可以复制。",12,false,MUTED);
        space(logPanel,8);
        logText = heading(logPanel,"尚无操作记录。",12,false,WHITE);
        logText.setTextIsSelectable(true);
        logPanel.setVisibility(View.GONE);
        space(content,3);
        heading(content,"所有图像识别都在设备本地完成。仅修改已明确确认的工程菜单项；失败时安全停止。",12,false,MUTED);
        updateButtons(false);
    }
}
