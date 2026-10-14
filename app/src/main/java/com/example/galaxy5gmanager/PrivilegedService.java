package com.example.galaxy5gmanager;

import android.os.RemoteException;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Runs in Shizuku's UserService process (ADB shell UID 2000 or root UID 0).
 * This is NOT an exported Android Service and is not a generic command executor.
 * The script's exact digest and argv are checked before execution.
 */
public final class PrivilegedService extends IGalaxyAutomation.Stub {
    private static final String SCRIPT_SHA256 = "b5d81f5937b589137394dd59dc617b30678c1688fb2ea6491ecf29be640c83bd";
    private final StringBuilder report = new StringBuilder();
    private volatile boolean running;
    private volatile Process child;
    private volatile boolean cancelled;
    private Thread worker;

    public PrivilegedService() { }

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
        running = true;
        worker = new Thread(() -> runJob(script, mode, profile, confirmation), "galaxy-5g-worker");
        worker.start();
        return true;
    }

    private void runJob(String script, String mode, String profile, String confirmation) {
        File scriptFile = null;
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
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", scriptFile.getAbsolutePath(),
                mode, profile, confirmation);
            pb.redirectErrorStream(true);
            p = pb.start();
            child = p;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    append(line);
                }
            }
            int code = p.waitFor();
            append(cancelled ? "已中断。某些步骤可能已经提交；请检查再决定是否重试。" :
                (code == 0 ? "流程结束（仅代表脚本完成；并非基带 NV 验证）。" :
                    "流程异常终止，退出码=" + code + "。请检查前面的错误，不要盲目重试。"));
        } catch (Exception e) {
            append("执行异常：" + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            child = null;
            if (p != null) p.destroy();
            if (scriptFile != null && scriptFile.exists()) scriptFile.delete();
            running = false;
        }
    }

    private synchronized void append(String line) {
        report.append(line).append('\n');
        if (report.length() > 50000) report.delete(0, report.length() - 40000);
    }

    @Override public boolean isRunning() { return running; }
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