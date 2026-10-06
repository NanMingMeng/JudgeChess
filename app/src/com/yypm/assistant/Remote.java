package com.yypm.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ★ v5.4：远程配置 + 心跳上报。
 * ★ v8.3：新增「更新提示」。
 *
 * 为什么单独一个类、不并进 License：
 *   这两件事**免费版也需要** —— 免费版要显示运营联系方式，也要上报活跃。
 *   而 License.java 属于付费模块，所以必须分开。
 *
 * 内容边界：本类只访问公开接口（config / report / version），不含任何授权判断逻辑，
 *          因此可以安全地出现在免费版公开源码里。
 *
 * 隐私：上报只带机器码（不可逆哈希）+ 版本 + 机型 + 授权状态，不带卡密、不带设备标识原文。
 */
public final class Remote {

    // 服务端接口前缀。
    // 域名拆开拼接：公开仓库里不想被脚本直接扒到完整接口地址。
    // 注意这不是安全手段 —— 真正的边界在服务端（这几个接口本就无需鉴权，
    // 只做「读配置」「记一次匿名心跳」「读版本号」三件事，没有任何敏感数据）。
    private static final String BASE =
            "https://" + "yongyan" + "peiming" + ".cn" + "/kernel/api/tz";
    private static final String PREFS = "judge_remote";
    private static final String P_CFG_AT = "cfg_at";
    private static final String P_REP_AT = "rep_at";
    /** ★ v8.3：上次弹出更新提示的日期（yyyy-MM-dd），用于「一天只提示一次」。 */
    private static final String P_UPD_DAY = "upd_day";

    /** 配置同步间隔：6 小时。 */
    private static final long CFG_INTERVAL_MS = 6L * 60 * 60 * 1000;
    /** 心跳间隔：6 小时（服务端按机器码聚合，重复上报只更新时间）。 */
    private static final long REP_INTERVAL_MS = 6L * 60 * 60 * 1000;

    /** 服务端没给地址时的兜底下载页。 */
    private static final String FALLBACK_PAGE = "https://yongyanpeiming.cn/judge/";

    /** 启动后延迟多久弹更新提示 —— 让主界面先画出来，别一进 App 就糊脸。 */
    private static final long UPD_DELAY_MS = 1500L;

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
                    // ★ v8.3：带上 versionCode。服务端据此看「谁卡在哪个版本」，
                    //   也是以后按版本做强制更新阈值的依据。
                    req.put("versionCode", verCode(c));
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

    // ============================================================ ★ v8.3 更新提示

    /**
     * 检查有没有新版本，有就弹原生对话框。**不修改任何现有界面**，只是叠一个对话框。
     *
     * 规则（用户 2026-10-06 定）：
     *   · 频率：一天最多提示一次（本地记日期，当天弹过就不再弹）
     *   · 强制：只有服务端 tz_app.json 里 force=true 才强制（无「稍后」）
     *   · 形态：原生 AlertDialog
     *   · 去更新：跳浏览器打开 page 指定的页面（官网下载页 / 网盘转存页）
     *   · 比较：用 versionCode 整数（versionName 字符串比会踩 "8.10" < "8.2" 的坑）
     *
     * 全部失败路径都是静默的 —— 更新提示绝不能影响下棋。
     */
    public static void maybeCheckUpdate(final Activity act) {
        if (act == null) return;
        // 今天已经弹过（且不是强制）→ 直接跳过，连网络都不发
        try {
            if (todayKey().equals(prefs(act).getString(P_UPD_DAY, ""))) return;
        } catch (Throwable ignored) {}

        new Thread(new Runnable() {
            public void run() {
                try {
                    String body = get(BASE + "/version");
                    JSONObject o = new JSONObject(body);
                    if (!o.optBoolean("enabled", true)) return;      // 总开关关了
                    int latest = o.optInt("versionCode", 0);
                    int cur = verCode(act);
                    if (latest <= 0 || latest <= cur) return;        // 已是最新 / 服务端没配

                    final String name = o.optString("version", "");
                    final String notes = o.optString("notes", "");
                    String page = o.optString("page", "").trim();
                    if (page.isEmpty()) page = o.optString("url", "").trim();
                    if (page.isEmpty()) page = FALLBACK_PAGE;
                    final String fPage = page;
                    final boolean force = o.optBoolean("force", false);

                    // 非强制才记「今天弹过了」；强制的话每次启动都要拦
                    if (!force) {
                        prefs(act).edit().putString(P_UPD_DAY, todayKey()).apply();
                    }

                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        public void run() { showUpdateDialog(act, name, notes, fPage, force); }
                    }, UPD_DELAY_MS);
                } catch (Throwable ignored) {
                    // 断网 / 解析失败：静默，下次启动再试
                }
            }
        }).start();
    }

    /** 弹原生更新对话框。 */
    private static void showUpdateDialog(final Activity act, String latestName,
                                         String notes, final String page, boolean force) {
        try {
            if (act.isFinishing()) return;
            StringBuilder sb = new StringBuilder();
            sb.append("当前版本　v").append(verName(act)).append('\n');
            sb.append("最新版本　v").append(latestName).append('\n');
            String n = (notes == null) ? "" : notes.trim();
            if (!n.isEmpty()) {
                sb.append("\n本次更新\n");
                for (String raw : n.split("\\n")) {
                    String t = raw.trim();
                    if (t.isEmpty()) continue;
                    if (t.startsWith("·") || t.startsWith("-") || t.startsWith("*")) {
                        t = t.substring(1).trim();
                    }
                    sb.append("· ").append(t).append('\n');
                }
            }
            if (!force) sb.append("\n提示每天最多出现一次，不会反复打扰。");

            AlertDialog.Builder b = new AlertDialog.Builder(act)
                    .setTitle(force ? "需要更新后才能继续使用" : "发现新版本")
                    .setMessage(sb.toString().trim())
                    .setPositiveButton("去更新", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { openPage(act, page); }
                    });
            if (force) {
                b.setCancelable(false);     // 返回键 / 点外部都关不掉
            } else {
                b.setNegativeButton("稍后", null);
            }
            b.show();
        } catch (Throwable ignored) {}
    }

    /** 跳浏览器打开下载页。 */
    private static void openPage(Activity act, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Throwable t) {
            try {
                Toast.makeText(act, "打不开链接：" + url, Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
        }
    }

    private static String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private static String verName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable t) { return ""; }
    }

    /** ★ v8.3：versionCode（整数，单调递增，用来比大小）。 */
    private static int verCode(Context c) {
        try {
            android.content.pm.PackageInfo pi =
                    c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return (int) pi.getLongVersionCode();
            return pi.versionCode;
        } catch (Throwable t) { return 0; }
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
