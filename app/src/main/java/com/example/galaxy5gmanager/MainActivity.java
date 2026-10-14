package com.example.galaxy5gmanager;

import android.app.Activity;
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
    private TextView statusText, logText;
    private Button permissionButton, overlayButton, statusButton, probeButton, applyCoreButton, applyFullButton, cancelButton;
    private String reportLast = "";
    private boolean active;
    private static final int PERMISSION_CODE = 4501;

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
                            .replaceAll("[^A-Z0-9 /&()\\-]", "").trim();
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
                    }
                    statusButton.setEnabled(!busy);
                    probeButton.setEnabled(!busy);
                    applyCoreButton.setEnabled(!busy);
                    applyFullButton.setEnabled(!busy);
                    cancelButton.setEnabled(busy);
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
        Shizuku.addBinderReceivedListenerSticky(onBinder);
        Shizuku.addBinderDeadListener(onDead);
        Shizuku.addRequestPermissionResultListener(onPermission);
        handler.post(refresh);
        refreshConnection();
    }

    @Override protected void onDestroy() {
        active = false;
        handler.removeCallbacks(refresh);
        // Deliberately do not stop the keep-alive service: ServiceMode may
        // remain on top while this Activity gets destroyed by configuration.
        Shizuku.removeBinderReceivedListener(onBinder);
        Shizuku.removeBinderDeadListener(onDead);
        Shizuku.removeRequestPermissionResultListener(onPermission);
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
                .processNameSuffix("galaxy5g").daemon(false).version(2).tag("galaxy5g-verified");
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
    }

    private void buildUi() {
        ScrollView s = new ScrollView(this); s.setFillViewport(true);
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(22), dp(26), dp(22), dp(24)); c.setBackgroundColor(Color.rgb(250, 251, 253));
        s.addView(c); setContentView(s);
        label(c, "Galaxy 5G 管理器", 26, true);
        label(c, "美版 S23 Ultra / S25 Ultra · SIM1 · 无 Root", 13, false);
        statusText = label(c, "正在检查 Shizuku…", 15, true);
        permissionButton = action(c, "连接 / 授权 Shizuku", v -> requestPermission());
        overlayButton = action(c, "开启悬浮进度提示（推荐）", v -> {
            if (Settings.canDrawOverlays(this))
                new AlertDialog.Builder(this).setMessage("悬浮提示已授权，执行菜单探测时会自动显示。")
                    .setPositiveButton("知道了", null).show();
            else requestOverlayPermission();
        });
        label(c, "第一步：检测和探测", 18, true);
        statusButton = action(c, "查看设备与固件状态（只读）", v -> runAction("status", "core", ""));
        probeButton = action(c, "探测六条菜单路径（不提交设置）", v -> runAction("probe", "full", ""));
        label(c, "第二步：恢复设置", 18, true);
        applyCoreButton = action(c, "恢复基础 5G（五项）", v -> confirmation(false));
        applyFullButton = action(c, "完整恢复（含毫米波，六项）", v -> confirmation(true));
        cancelButton = action(c, "中止当前操作", v -> new AlertDialog.Builder(this)
            .setTitle("确认中止？")
            .setMessage("已提交的基带菜单操作不会自动撤销。")
            .setNegativeButton("继续运行", null)
            .setPositiveButton("中止", (d, w) -> { try { if (remote != null) remote.cancel(); } catch (RemoteException e) { showError(e); } }).show());
        label(c, "执行记录", 18, true);
        logText = label(c, "尚无操作记录。", 13, false);
        logText.setTextIsSelectable(true);
        label(c, "使用条件：屏幕解锁、亮屏、三星原生拨号器工程码可用。\n操作后请手动重启并验证移动网络。没有 Root 的 Shizuku 重启后通常需要重新启动。\n本工具不自动刷机、不写 NV 原始地址，也不保证运营商一定提供 5G 服务。", 13, false);
        updateButtons(false);
    }
}