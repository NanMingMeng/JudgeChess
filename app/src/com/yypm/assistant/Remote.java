package com.yypm.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * ★ v5.4：远程配置 + 心跳上报。
 *
 * 为什么单独一个类、不并进 License：
 *   这两件事**免费版也需要** —— 免费版要显示运营联系方式，也要上报活跃。
 *   而 License.java 在净化时整文件删除，所以必须分开。
 *
 * 内容边界：本类只访问两个公开接口（config / report），不含任何授权判断逻辑，
 *          因此可以安全地出现在免费版公开源码里。
 *
 * 隐私：上报只带机器码（不可逆哈希）+ 版本 + 机型 + 授权状态，不带卡密、不带设备标识原文。
 */
public final class Remote {

    // 服务端接口前缀。
    // 域名拆开拼接：公开仓库里不想被脚本直接扒到完整接口地址。
    // 注意这不是安全手段 —— 真正的边界在服务端（这两个接口本就无需鉴权，
    // 只做「读配置」和「记一次匿名心跳」两件事，没有任何敏感数据）。
    private static final String BASE =
            "https://" + "yongyan" + "peiming" + ".cn" + "/kernel/api/tz";
    private static final String PREFS = "judge_remote";
    private static final String P_CFG_AT = "cfg_at";
    private static final String P_REP_AT = "rep_at";

    /** 配置同步间隔：6 小时。 */
    private static final long CFG_INTERVAL_MS = 6L * 60 * 60 * 1000;
    /** 心跳间隔：6 小时（服务端按机器码聚合，重复上报只更新时间）。 */
    private static final long REP_INTERVAL_MS = 6L * 60 * 60 * 1000;

    private Remote() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 启动时调用：按需拉配置 + 上报心跳。两个都是异步，不阻塞界面。 */
    public static void startup(final Context c) {
        if (c == null) return;
        long now = System.currentTimeMillis();
        try {
            SharedPreferences p = prefs(c);
            if (now - p.getLong(P_CFG_AT, 0L) > CFG_INTERVAL_MS) syncConfig(c);
            if (now - p.getLong(P_REP_AT, 0L) > REP_INTERVAL_MS) report(c);
        } catch (Throwable ignored) {}
    }

    /**
     * 拉远程配置并覆盖本地。
     *
     * 优先级：服务端有值 → 覆盖；服务端为空 → 保留本地（用户手填的兜底值不会被空配置抹掉）。
     */
    public static void syncConfig(final Context c) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    String body = get(BASE + "/config");
                    JSONObject o = new JSONObject(body);
                    JSONObject d = o.optJSONObject("data");
                    if (d == null) return;
                    String contact = d.optString("contact", "").trim();
                    String qq = d.optString("qq", "").trim();
                    boolean changed = false;
                    if (!contact.isEmpty()
                            && !contact.equals(XqService.spStr(MainActivity.K_HOLO_CONTACT, ""))) {
                        XqService.spPutStr(MainActivity.K_HOLO_CONTACT, contact);
                        changed = true;
                    }
                    if (!qq.isEmpty()
                            && !qq.equals(XqService.spStr(MainActivity.K_HOLO_QQ, ""))) {
                        XqService.spPutStr(MainActivity.K_HOLO_QQ, qq);
                        changed = true;
                    }
                    if (changed) MainActivity.bumpHoloVer();   // 让主界面卡片重取数据
                    prefs(c).edit().putLong(P_CFG_AT, System.currentTimeMillis()).apply();
                } catch (Throwable ignored) {
                    // 断网 / 服务端不可用：静默跳过，下次启动再试
                }
            }
        }).start();
    }

    /** 上报一次心跳。 */
    public static void report(final Context c) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject req = new JSONObject();
                    req.put("machineCode", MachineCode.of(c));
                    req.put("version", verName(c));
                    req.put("model", MachineCode.model());
                    req.put("activated", ProModule.activated());
                    req.put("type", ProModule.typeCode());
                    post(BASE + "/report", req.toString());
                    prefs(c).edit().putLong(P_REP_AT, System.currentTimeMillis()).apply();
                } catch (Throwable ignored) {
                    // 上报失败无所谓，不打扰用户
                }
            }
        }).start();
    }

    private static String verName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable t) { return ""; }
    }

    // ============================================================ HTTP

    private static String get(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "JudgeChess/" + verName(XqService.appCtx));
            return read(conn);
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static String post(String url, String body) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("User-Agent", "JudgeChess/" + verName(XqService.appCtx));
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();
            return read(conn);
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static String read(HttpURLConnection conn) throws Exception {
        int sc = conn.getResponseCode();
        InputStream in = (sc >= 200 && sc < 300) ? conn.getInputStream() : conn.getErrorStream();
        if (in == null) throw new Exception("HTTP " + sc);
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String ln;
        while ((ln = br.readLine()) != null) sb.append(ln);
        br.close();
        return sb.toString();
    }
}
