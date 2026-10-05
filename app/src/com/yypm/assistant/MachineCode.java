package com.yypm.assistant;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import java.security.MessageDigest;

/**
 * ★ v5.4：机器码计算（独立类）。
 *
 * 为什么从 License 里拆出来：
 *   机器码**免费版也要算**（心跳上报要用），而 License.java 属于付费模块。
 *   本类只做「设备标识 → 哈希」这一件事，不含任何授权逻辑，可安全出现在公开源码里。
 *
 * 算法：
 *   machineCode = SHA256(androidId | serial | model) 前 32 位小写 hex
 *
 * ⚠️ 坑：Android 8+ 起 ANDROID_ID 按**应用签名密钥作用域**隔离 ——
 *    shell 里 `settings get secure android_id` 拿到的和 App 内拿到的是两个值。
 *    所以机器码只能在 App 内算，不能拿 shell 值代算。
 *    换签名密钥 = 换机器码；本应用 keystore 固定，升级不影响。
 */
public final class MachineCode {

    private MachineCode() {}

    /** 设备型号。 */
    public static String model() {
        try {
            String m = Build.MODEL;
            return m == null ? "" : m;
        } catch (Throwable t) { return ""; }
    }

    /**
     * 设备序列号。
     * Android 10+ 的 Build.getSerial() 需要 READ_PHONE_STATE（本应用未申请），
     * 取不到时用空串 —— 不改变算法结构，保证同设备每次算出来一样。
     */
    public static String serial() {
        String s = null;
        try { s = Build.getSerial(); } catch (Throwable ignored) {}
        if (s == null || s.isEmpty()) {
            try { s = Build.SERIAL; } catch (Throwable ignored) {}
        }
        if (s == null || s.isEmpty() || "unknown".equalsIgnoreCase(s)
                || "UNKNOWN".equals(s)) return "";
        return s;
    }

    /** 机器码：SHA256(androidId|serial|model) 前 32 位小写 hex。 */
    public static String of(Context c) {
        String aid = "";
        try {
            aid = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {}
        if (aid == null) aid = "";
        String raw = aid + "|" + serial() + "|" + model();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(raw.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                String h = Integer.toHexString(d[i] & 0xFF);
                if (h.length() == 1) sb.append('0');
                sb.append(h);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
