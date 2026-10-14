package com.example.galaxy5gmanager;

import android.os.RemoteException;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import android.os.ParcelFileDescriptor;
import java.util.concurrent.TimeUnit;


/**
 * Runs in Shizuku's UserService process (ADB shell UID 2000 or root UID 0).
 * This is NOT an exported Android Service and is not a generic command executor.
 * The script's exact digest and argv are checked before execution.
 */
public final class PrivilegedService extends IGalaxyAutomation.Stub {
    private static final String SCRIPT_SHA256 = "d243eca8b70fa73d5ea5f592633b424ae5f8e933017ad5c7d84a69ee653cfe1c";
    private final StringBuilder report = new StringBuilder();
    private volatile boolean running;
    private volatile Process child;
    private volatile boolean cancelled;
    private volatile boolean rebootEligible;
    private volatile IGalaxyPageReader pageReader;
    private Thread worker;

    public PrivilegedService() { }

    @Override public void setPageReader(IGalaxyPageReader reader) { this.pageReader = reader; }

    @Override public int shellUid() { return android.os.Process.myUid(); }

    @Override public synchronized boolean startRun(String script, String mode, String profile, String confirmation) {
        if (running) return false;
        if (shellUid() != 2000 && shellUid() != 0) {
            append("拒绝执行：Shizuku 未提供 shell/root 身份。");
            return false;
        }
        if (script == null || script.length() > 48000 || !SCRIPT_SHA256.equals(sha256(script))) {
            append("拒绝执行：内置导航脚本完整性检查失败。");
            return false;
        }
        if (!Arrays.asList("status", "inspect", "probe", "apply").contains(mode) ||
            !Arrays.asList("core", "full").contains(profile)) return false;
        if ("apply".equals(mode)) {
            String wanted = "full".equals(profile) ? "FULL" : "APPLY";
            if (!wanted.equals(confirmation)) return false;
        } else if (confirmation == null || !confirmation.isEmpty()) {
            return false;
        }
        report.setLength(0);
        append("执行身份 UID=" + shellUid() + "，模式=" + mode + "/" + profile);
        cancelled = false;
        rebootEligible = false;
        running = true;
        worker = new Thread(() -> runJob(script, mode, profile, confirmation), "galaxy-5g-worker");
        worker.start();
        return true;
    }

    private void runJob(String script, String mode, String profile, String confirmation) {
        File scriptFile = null;
        File replyFile = null;
        Process p = null;
        try {
            scriptFile = File.createTempFile("galaxy5g-", ".sh", new File("/data/local/tmp"));
            try (FileOutputStream out = new FileOutputStream(scriptFile)) {
                out.write(script.getBytes(StandardCharsets.UTF_8));
            }
            // Cancellation may arrive before the subprocess exists.
            if (cancelled) {
                append("执行开始前已中止；没有启动脚本。");
                return;
            }
            replyFile = File.createTempFile("galaxy5g-ocr-", ".tsv", new File("/data/local/tmp"));
            if (!replyFile.delete()) throw new java.io.IOException("OCR 临时文件无法初始化");
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", scriptFile.getAbsolutePath(),
                mode, profile, confirmation);
            pb.environment().put("G5G_OCR_REPLY", replyFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            p = pb.start();
            child = p;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if ("@G5G_OCR_REQUEST@".equals(line)) {
                        performOcr(replyFile);
                    } else {
                        append(line);
                    }
                }
            }
            int code = p.waitFor();
            rebootEligible = code == 0 && !cancelled && "apply".equals(mode);
            if (rebootEligible) append("恢复菜单操作全部完成；建议重启。返回应用可选择立即重启或稍后重启。");
            append(cancelled ? "已中断。某些步骤可能已经提交；请检查再决定是否重试。" :
                (code == 0 ? "流程结束（仅代表脚本完成；并非基带 NV 验证）。" :
                    "流程异常终止，退出码=" + code + "。请检查前面的错误，不要盲目重试。"));
        } catch (Exception e) {
            append("执行异常：" + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            child = null;
            if (p != null) p.destroy();
            if (scriptFile != null && scriptFile.exists()) scriptFile.delete();
            if (replyFile != null && replyFile.exists()) replyFile.delete();
            running = false;
        }
    }

    // Screenshot is taken only after the script verifies ServiceMode foreground.
    // The screenshot is sent over a private Binder callback to the app's bundled
    // offline OCR model. Never persist the image or upload any contents.
    private void performOcr(File replyFile) {
        File shot = null;
        String result;
        try {
            if (cancelled) throw new java.io.IOException("已取消");
            IGalaxyPageReader callback = pageReader;
            if (callback == null || !callback.asBinder().isBinderAlive())
                throw new java.io.IOException("离线 OCR 回调未连接，请重新打开管理器");
            shot = File.createTempFile("g5g-shot-", ".png", new File("/data/local/tmp"));
            Process grab = new ProcessBuilder("/system/bin/screencap", "-p", shot.getAbsolutePath()).start();
            if (!grab.waitFor(8, TimeUnit.SECONDS)) {
                grab.destroyForcibly();
                throw new java.io.IOException("screencap 超时");
            }
            if (grab.exitValue() != 0) throw new java.io.IOException("screencap 返回 " + grab.exitValue());
            // The app process performs all bitmap allocation and recognition. Passing
            // a PFD avoids Binder byte-array payloads and cuts memory copies.
            if (shot.length() < 1200 || shot.length() > 16000000L)
                throw new java.io.IOException("截图文件大小异常：" + shot.length());
            append("UIAutomator 不可用：使用文件描述符发送截图供普通应用离线 OCR");
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(shot,
                     ParcelFileDescriptor.MODE_READ_ONLY)) {
                result = callback.recognize(fd, 0, 0);
            }
            if (result == null || result.isEmpty()) result = "ERROR: 没有识别到菜单文字";
            if (result.length() > 16000) result = "ERROR: 识别数据长度异常";
        } catch (Exception e) {
            result = "ERROR: " + e.getClass().getSimpleName() + " " + e.getMessage();
            append("离线 OCR 请求失败：" + e.getMessage());
        } finally {
            if (shot != null) shot.delete();
        }
        // Only the fixed bridge file is writable, not arbitrary caller paths.
        try {
            File temp = new File(replyFile.getAbsolutePath() + ".part");
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(result.getBytes(StandardCharsets.UTF_8));
            }
            if (!temp.renameTo(replyFile)) throw new java.io.IOException("OCR 回复文件重命名失败");
        } catch (Exception e) { append("OCR 通道写入失败：" + e.getMessage()); }
    }

    private synchronized void append(String line) {
        report.append(line).append('\n');
        if (report.length() > 50000) report.delete(0, report.length() - 40000);
        // Report in parallel to the app's floating progress display. Never
        // fail the navigation if the ordinary app process is interrupted.
        IGalaxyPageReader callback = pageReader;
        if (callback != null) {
            try { callback.onProgress(line); }
            catch (Exception ignored) { }
        }
    }

    @Override public boolean isRunning() { return running; }

    @Override public boolean canReboot() { return rebootEligible && !running && !cancelled; }

    // Allowed only after a complete, successful apply. The app must show a
    // separate confirmation dialog before invoking this fixed command.
    @Override public synchronized boolean rebootDevice() {
        if (!canReboot() || (shellUid() != 2000 && shellUid() != 0)) return false;
        try {
            new ProcessBuilder("/system/bin/svc", "power", "reboot").start();
            rebootEligible = false;
            append("已发送系统重启请求；如果设备没有重启，请手动重启。");
            return true;
        } catch (Exception e) {
            append("无法发送重启指令：" + e.getMessage());
            return false;
        }
    }

    @Override public synchronized String getReport() { return report.toString(); }

    @Override public void cancel() {
        cancelled = true;
        append("用户要求中止。不会自动撤销已执行的工程菜单设置。");
        Process p = child;
        if (p != null) p.destroy();
    }

    @Override public void destroy() {
        cancel();
        System.exit(0);
    }

    private static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(hash.length * 2);
            for (byte value : hash) b.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
            return b.toString();
        } catch (Exception e) { return ""; }
    }
}
