package com.example.galaxy5gmanager;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyCallback;

/** Public, read-only radio observation. Never interpret LTE as "5G disabled". */
public final class NetworkDiagnostics {
    private NetworkDiagnostics() {}

    public static final class Result {
        public final String text;
        public final boolean has5gEvidence;
        Result(String text, boolean evidence) { this.text = text; this.has5gEvidence = evidence; }
    }

    static boolean isObserved5g(int dataRat, int override) {
        return dataRat == TelephonyManager.NETWORK_TYPE_NR ||
            override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
            override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED;
    }

    public static String verdict(int dataRat, int override) {
        if (dataRat == TelephonyManager.NETWORK_TYPE_NR)
            return "结论：观测到当前使用 NR 数据承载，有 5G 连接证据。无需仅因 5G 图标问题重复设置工程菜单。";
        if (override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
            override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED)
            return "结论：系统报告 5G NSA/增强型 5G 显示状态，说明已有 5G 网络证据，但显示类型不等于实时 NR 驻留。";
        if (dataRat == TelephonyManager.NETWORK_TYPE_LTE)
            return "结论：当前显示 LTE。可能因为信号、覆盖、运营商策略或 NSA 的 LTE 锚点；不能据此判断 5G 被锁定。";
        return "结论：当前蜂窝数据网络类型不足以判断 5G 是否可用，不能自动建议修改工程设置。";
    }

    public static Result inspect(Context context, int override) {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            return new Result("未授权读取电话状态。仅在手机本地读取，不会上传，也不会读取 IMEI/IMSI。", false);
        try {
            TelephonyManager tm = context.getSystemService(TelephonyManager.class);
            if (tm == null) return new Result("无蜂窝网络管理器；无法检测。", false);
            int dataRat = tm.getDataNetworkType();
            String label = TelephonyManager.getNetworkTypeName(dataRat);
            String display = override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ? "系统 5G NSA 显示" :
                override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED ? "系统增强型 5G 显示（不等于一定在毫米波）" :
                override < 0 ? "尚未收到网络显示回调" : "没有 5G 显示标记";
            boolean evidence = isObserved5g(dataRat, override);
            String text = "当前默认移动数据网络：" + label + "\n" +
                "系统显示状态：" + display + "\n" +
                "移动数据开关：" + (tm.isDataEnabled() ? "开启" : "关闭") + "\n" +
                verdict(dataRat, override) + "\n\n" +
                "能力检测边界：无法通过公开 API 证明 NR PRUNE、GCF、SA permit list 或 mmWave ALL ENABLE 已解除。" +
                "Shizuku shell 也不等于具备 READ_PRIVILEGED_PHONE_STATE。" +
                "即使已经观察到 5G，也不证明每个工程菜单开关都已开启。";
            return new Result(text, evidence);
        } catch (SecurityException | UnsupportedOperationException e) {
            return new Result("网络状态权限或设备接口不支持：" + e.getClass().getSimpleName(), false);
        } catch (RuntimeException e) {
            return new Result("读取失败：" + e.getClass().getSimpleName() + "，不能据此判断 5G 被禁用。", false);
        }
    }
}