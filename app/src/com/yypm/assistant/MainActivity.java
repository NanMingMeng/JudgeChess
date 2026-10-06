package com.yypm.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.InputStream;

/**
 * 主界面 = 权限总控台 + 实时看盘。
 *
 * 设计原则（按用户要求）：
 *   · 所有权限一进来就摊开显示，绿=已就绪 / 红=缺失，一眼看清
 *   · 缺什么就点对应按钮修，或者直接点顶部「一键全部修复」
 *   · 前台期间每秒自检一次；从别处回到本页也会立刻自检
 *   · 无障碍已开、只缺屏幕捕获时，自动弹授权 —— 不让用户自己找按钮
 *
 * 一个必须说清的例外：
 *   屏幕捕获（MediaProjection）是系统安全项，**每次重启手机后必须重新授权**，
 *   任何 App 都无法绕过。本页能做的是「实时发现 + 一键/自动补救」，
 *   而不是伪造永久授权。其它权限（无障碍、通知、电池优化）一旦给了就长期有效。
 */
public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 4001;
    private static final int REQ_NOTIF = 4002;

    /** 权限行：0 无障碍 1 屏幕捕获 2 通知 3 电池优化 */
    private static final int N_PERM = 4;
    private static final String[] PERM_NAME = {
            "① 无障碍服务", "② 屏幕捕获", "③ 通知权限", "④ 后台运行（电池优化白名单）"};
    private static final String[] PERM_DESC = {
            "识别 + 自动落子的核心权限，必须开",
            "截屏识别棋盘；重启手机后需重新授权",
            "让助手在后台提醒你「权限掉了」",
            "防止系统把助手杀掉，强烈建议开"};

    private TextView tvState, tvFen, tvMove, tvStep, tvBanner, tvVer;
    /** v4.42：用户截屏回调（API 34+）。必须留着引用，否则会被回收掉。 */
    private android.app.Activity.ScreenCaptureCallback shotCb;
    /** v4.28：主界面显示「引擎名 / 本机算力」。 */
    private TextView tvEngine, tvCompute;
    private BoardView boardView;
    private final TextView[] permState = new TextView[N_PERM];
    private final Button[] permBtn = new Button[N_PERM];
    /** v4.3：「权限已获取，开始游戏」按钮 —— 点了才播开场动画。 */
    private Button bStart;
    /** 权限行左侧状态点（绿/黄/红）。 */
    private final View[] permDot = new View[N_PERM];

    /** v4.81 MELLO 文字方案：0=玻璃衬底 1=深色文字 2=毛玻璃 */
    private int holoTextMode;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean polling = false;
    private boolean autoFlow = false;
    private boolean askingCapture = false;
    private long lastAutoAsk = 0L;
    /** 上次已知的「屏幕捕获是否在跑」，用于发现「掉权限」这个事件。 */
    private boolean lastCapOn = false;
    private boolean capDropNotified = false;
    /** 「一键全部修复」进行中：等权限补齐后自动拉起悬浮窗。 */
    private boolean pendingBringUp = false;
    /** ★ 播报队列（v4.1）：本页额头那条轨道，一次只显示一张。 */
    private FrameLayout cardHost;
    private final java.util.ArrayDeque<String[]> cardQ = new java.util.ArrayDeque<>();
    private View cardView;
    private boolean cardBusy;
    /** 本次「一键修复」已经把整份缺项当预告排过队了 → 各步不再重复播。 */
    private boolean planAnnounced = false;
    /** 播报卡停留时长。 */
    private static final long CARD_HOLD_MS = 1700L;
    /** 跳系统页前给本页播报卡留的亮相时间。 */
    private static final long LEAD_IN_MS = 900L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v4.7：给 XqService 一个应用级 Context —— 无障碍还没开时，
        // 设置页/主界面也能读写个性化配置（服务实例为 null 时靠它兜底）。
        try { XqService.appCtx = getApplicationContext(); } catch (Throwable ignored) {}
        // ★ v5.4：远程配置（联系方式等）+ 心跳上报。免费版也保留 —— 两个都是公开接口。
        try { Remote.startup(this); } catch (Throwable ignored) {}
        // ★ v8.3：更新提示。一天最多一次；不改动任何现有界面，只叠一个原生对话框。
        try { Remote.maybeCheckUpdate(this); } catch (Throwable ignored) {}

        final String vn = versionName();

        holoTextMode = XqService.spIntOf(this, "holo_text_mode", 0);
        final boolean holo = UiTheme.current(this) == 5;
        ScrollView sc = holo ? new PassthroughScrollView(this) : new ScrollView(this);
        sc.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(11), dp(20), dp(11), dp(14));
        sc.addView(root);

        // ---------- 标题行：图标 + 名称 + 版本（版本可点，看更新说明）----------
        LinearLayout hd = new LinearLayout(this);
        hd.setOrientation(LinearLayout.HORIZONTAL);
        hd.setGravity(Gravity.CENTER_VERTICAL);
        holoPanel(hd, 8, 6, 8, 6);
        root.addView(hd);

        TextView ico = new TextView(this);
        ico.setText("象");
        ico.setTextSize(17);
        ico.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        ico.setGravity(Gravity.CENTER);
        ico.setTextColor(0xFF0B1014);
        ico.setBackgroundResource(R.drawable.hintcard_icon_bg);
        hd.addView(ico, new LinearLayout.LayoutParams(dp(30), dp(30)));

        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(8);
        hd.addView(tcol, tlp);

        TextView title = new TextView(this);
        title.setText("审判者　v" + vn);
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(inkMain());
        tcol.addView(title);

        tvVer = new TextView(this);
        tvVer.setTextSize(9);
        tvVer.setTextColor(holoTextMode == 1 ? 0xFF3A5A8C : 0xFF5AB0FF);
        tvVer.setText("v" + vn + "　（点这里看本版更新说明）");
        tvVer.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showUpdateNotes(vn); }
        });
        tcol.addView(tvVer);

        // ---------- v4.28 引擎与算力 ----------
        // 用户想知道「我这台机器跑得动吗」，就得把引擎名和实际采用的参数摊出来。
        // 数字全部来自真实探测（见 XqService.probeDevice / runBenchmarkOnce），不是写死的。
        LinearLayout engBox = new LinearLayout(this);
        engBox.setOrientation(LinearLayout.VERTICAL);
        engBox.setBackgroundResource(R.drawable.bg_ctrl_card);
        engBox.setPadding(dp(10), dp(8), dp(10), dp(8));

        LinearLayout eR1 = new LinearLayout(this);
        eR1.setOrientation(LinearLayout.HORIZONTAL);
        eR1.setGravity(Gravity.CENTER_VERTICAL);
        TextView eK1 = new TextView(this);
        eK1.setText("引擎");
        eK1.setTextSize(10);
        eK1.setTextColor(0xFF7E8B98);
        eR1.addView(eK1, new LinearLayout.LayoutParams(dp(34),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        tvEngine = new TextView(this);
        tvEngine.setTextSize(11);
        tvEngine.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvEngine.setTextColor(0xFF7ED321);
        eR1.addView(tvEngine, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        engBox.addView(eR1);

        LinearLayout eR2 = new LinearLayout(this);
        eR2.setOrientation(LinearLayout.HORIZONTAL);
        eR2.setGravity(Gravity.CENTER_VERTICAL);
        TextView eK2 = new TextView(this);
        eK2.setText("算力");
        eK2.setTextSize(10);
        eK2.setTextColor(0xFF7E8B98);
        eR2.addView(eK2, new LinearLayout.LayoutParams(dp(34),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        tvCompute = new TextView(this);
        tvCompute.setTextSize(10);
        tvCompute.setTextColor(0xFFC6D2DC);
        tvCompute.setLineSpacing(dp(2), 1f);
        eR2.addView(tvCompute, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        engBox.addView(eR2);

        root.addView(engBox, lpOf(LinearLayout.LayoutParams.WRAP_CONTENT, dp(9)));

        // ---------- 顶部总状态横幅 ----------
        tvBanner = new TextView(this);
        tvBanner.setTextSize(12);
        tvBanner.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvBanner.setPadding(dp(11), dp(9), dp(11), dp(9));
        root.addView(tvBanner, lpOf(LinearLayout.LayoutParams.WRAP_CONTENT, dp(9)));

        // ---------- 一键全部修复 ----------
        Button bAll = makePrimaryBtn("一键获取权限");
        bAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startFlow(); }
        });
        root.addView(bAll, lpOf(dp(36), dp(9)));

        // ---------- 权限已获取，开始游戏（v4.3 开场流程入口）----------
        // 为什么单独一个按钮：修权限期间系统设置页来回跳，弹卡只会添乱。
        // 等四项都齐了、用户回到本页，再点这个按钮统一播开场动画。
        bStart = makePrimaryBtn("权限已获取，开始游戏");
        bStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startGameFlow(); }
        });
        root.addView(bStart, lpOf(dp(40), dp(4)));

        // ---------- v4.7 设置 / 个性化 ----------
        // 背景板（人物封面）、棋盘在左还是右、控制栏卡底、启动动画都在这一页里选。
        // 放成 ghost 样式：它是「可选操作」，不该跟上面两个主按钮抢注意力。
        Button bSet = makeGhostBtn("设置");
        bSet.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                } catch (Throwable t) {
                    toast("打不开设置页: " + t.getMessage());
                }
            }
        });
        root.addView(bSet, lpOf(dp(38), dp(4)));

        tvStep = new TextView(this);
        tvStep.setTextSize(10);
        tvStep.setTextColor(inkFaint());
        tvStep.setPadding(dp(2), dp(4), dp(2), dp(6));
        holoPanel(tvStep, 11, 6, 11, 6);
        root.addView(tvStep);

        // ---------- 权限清单 ----------
        TextView ph = new TextView(this);
        ph.setText("权限清单（绿=已就绪，红=缺失，点右侧按钮修复）");
        ph.setTextSize(10);
        ph.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        ph.setTextColor(inkSub());
        ph.setPadding(dp(2), dp(5), dp(2), dp(3));
        holoPanel(ph, 11, 8, 11, 8);
        root.addView(ph);

        for (int i = 0; i < N_PERM; i++) {
            root.addView(buildPermRow(i));
        }

        Button bRef = makeGhostBtn("重新检测");
        bRef.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { refresh(); toast(statusLine()); }
        });
        root.addView(bRef, lpOf(dp(31), dp(9)));

        tvState = new TextView(this);
        tvState.setTextSize(9);
        tvState.setTextColor(inkFaint());
        tvState.setLineSpacing(dp(2), 1f);
        tvState.setPadding(dp(2), dp(8), dp(2), 0);
        holoPanel(tvState, 11, 8, 11, 8);
        root.addView(tvState);
        if (holo) root.addView(buildHoloSwitcher(), lpOf(dp(30), dp(8)));


        // ★ 额头播报轨道：叠在内容之上，只做展示、不吃触摸
        FrameLayout outer = new FrameLayout(this);
        if (holo) {
            WebView wv = new WebView(this);
            WebSettings ws = wv.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            wv.setBackgroundColor(0x00000000);
            outer.addView(wv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            wv.loadUrl("http://127.0.0.1:8090/card-only.html");
        }
        outer.addView(sc);
        cardHost = new FrameLayout(this);
        FrameLayout.LayoutParams chp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        chp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        chp.topMargin = dp(52);
        outer.addView(cardHost, chp);
        UiTheme.apply(outer, UiTheme.current(this));
        // ★ v4.80：主界面换成承载 3D 全息卡的 WebView；播报卡仍叠在最上层。
        FrameLayout holoHost = new FrameLayout(this);
        holoHost.addView(buildHoloWebView(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        outer.removeView(cardHost);
        holoHost.addView(cardHost, chp);
        setContentView(holoHost);
        // ★ 开着主界面但权限还没齐 → 说明用户是来修权限的：先上锁，
        //   确保这期间（包括给完无障碍权限后服务刚连上那一刻）什么悬浮窗都不许冒出来。
        if (!coreReady()) XqService.beginSetup();
        ensureQuote();   // v4.91：过期就后台拉今日名言
        refresh();
    }

    // ============================================================
    //  小工具：dp 换算 / 统一 LayoutParams / 按钮工厂
    // ============================================================

    /** dp → px（本机 density 3.5）。 */
    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 满宽 + 指定高度 + 上边距。h 传 WRAP_CONTENT(-2) 表示自适应。 */
    private LinearLayout.LayoutParams lpOf(int h, int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h);
        lp.topMargin = topMargin;
        return lp;
    }

    /** 主按钮：品牌绿实心（唯一的强强调）。 */
    private Button makePrimaryBtn(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(13);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setTextColor(0xFF0B1014);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        b.setBackgroundResource(R.drawable.bg_btn_primary);
        return b;
    }

    /** 次要按钮：淡底 + 细边。 */
    private Button makeGhostBtn(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(11);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setTextColor(inkMain());
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        b.setBackgroundResource(R.drawable.bg_btn_ghost); holoCard(b);
        return b;
    }

    /** 一行权限：状态点 + 名称 + 状态 + 说明 + 修复按钮（卡片式）。 */
    private View buildPermRow(final int idx) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_perm_row); holoCard(row);
        row.setPadding(dp(9), dp(7), dp(9), dp(7));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(4);
        row.setLayoutParams(rlp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout nmRow = new LinearLayout(this);
        nmRow.setOrientation(LinearLayout.HORIZONTAL);
        nmRow.setGravity(Gravity.CENTER_VERTICAL);

        permDot[idx] = new View(this);
        permDot[idx].setBackgroundResource(R.drawable.dot_warn);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(6), dp(6));
        dotLp.rightMargin = dp(5);
        nmRow.addView(permDot[idx], dotLp);

        TextView nm = new TextView(this);
        nm.setText(PERM_NAME[idx]);
        nm.setTextSize(12);
        nm.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        nm.setTextColor(inkMain());
        nmRow.addView(nm);
        col.addView(nmRow);

        permState[idx] = new TextView(this);
        permState[idx].setTextSize(9);
        permState[idx].setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        col.addView(permState[idx], wrapTop(dp(1)));

        TextView ds = new TextView(this);
        ds.setText(PERM_DESC[idx]);
        ds.setTextSize(8);
        ds.setTextColor(inkFaint());
        col.addView(ds, wrapTop(dp(1)));

        row.addView(col);

        permBtn[idx] = new Button(this);
        permBtn[idx].setTextSize(10);
        permBtn[idx].setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        permBtn[idx].setMinHeight(0);
        permBtn[idx].setMinimumHeight(0);
        permBtn[idx].setPadding(dp(9), 0, dp(9), 0);
        permBtn[idx].setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { fixPerm(idx); }
        });
        row.addView(permBtn[idx], new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(28)));
        return row;
    }

    /** wrap_content + 上边距。 */
    private LinearLayout.LayoutParams wrapTop(int m) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = m;
        return lp;
    }


    private boolean isAccOn() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return flat != null && flat.contains(getPackageName() + "/");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isCapOn() { return CaptureService.isRunning(); }

    private boolean isNotifOn() {
        if (Build.VERSION.SDK_INT < 33) return true;      // 旧系统不需要
        try {
            return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean isBatteryOk() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 所有必需权限是否就绪（通知/电池属于「建议」，不阻塞运行）。 */
    private boolean coreReady() { return isAccOn() && isCapOn(); }

    private boolean allReady() { return coreReady() && isNotifOn() && isBatteryOk(); }

    // ============================================================
    //  修复动作
    // ============================================================

    // ============================================================
    //  权限播报（v4.1）—— 一次一张，排队往屏幕额头位置移动
    // ============================================================

    /**
     * 修权限期间**不弹任何卡片**（用户 v4.3 明确要求）。
     *
     * 这里的返回 true 是给调用方看的：「可以立刻跳系统页，不用留亮相时间」。
     * 真正的开场播报统一放在「权限已获取，开始游戏」按钮里（见 XqService.playIntro）。
     */
    private boolean announceStep(String title, String msg) {
        return true;
    }

    /** 同 announceStep：权限期间不弹卡。 */
    private boolean stepAnnounce(String title, String msg) {
        return true;
    }

    /** 不再需要「缺项预告队列」，恒为 false。 */
    private boolean announcePlan() {
        return false;
    }



    /** 应用内播报：塞进本页顶部那条「额头」轨道。 */
    private void enqueueLocalCard(String title, String msg) {
        if (cardHost == null) return;
        cardQ.add(new String[]{title, msg});
        if (!cardBusy) showNextLocalCard();
    }

    private void showNextLocalCard() {
        if (cardHost == null) { cardQ.clear(); cardBusy = false; return; }
        if (cardQ.isEmpty()) { cardBusy = false; return; }
        cardBusy = true;
        final String[] c = cardQ.poll();
        View v;
        try {
            v = getLayoutInflater().inflate(R.layout.announce_card, cardHost, false);
            View t = v.findViewById(R.id.ac_title);
            View m = v.findViewById(R.id.ac_msg);
            if (t instanceof TextView) ((TextView) t).setText(c[0]);
            if (m instanceof TextView) ((TextView) m).setText(c[1]);
        } catch (Throwable t) {
            cardBusy = false;
            return;
        }
        cardView = v;
        v.setAlpha(0f);
        v.setTranslationY(dp(24));
        cardHost.addView(v);
        v.animate()
                .alpha(1f).translationY(0f)
                .setDuration(240L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                .start();
        h.postDelayed(cardNext, CARD_HOLD_MS);
    }

    /** 退场：继续往上方移出，然后立刻播下一张（两张之间绝不重叠）。 */
    private void hideLocalCardAndNext() {
        h.removeCallbacks(cardNext);
        final View v = cardView;
        cardView = null;
        if (v == null) { showNextLocalCard(); return; }
        v.animate()
                .alpha(0f).translationY(-dp(28))
                .setDuration(200L)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(new Runnable() {
                    public void run() {
                        try { if (cardHost != null) cardHost.removeView(v); } catch (Throwable ignored) {}
                        showNextLocalCard();
                    }
                })
                .start();
    }

    private final Runnable cardNext = new Runnable() {
        public void run() { hideLocalCardAndNext(); }
    };

    /** 离开本页时把本页队列清干净：剩下的交给悬浮窗通道，回来会重新播。 */
    private void clearLocalCards() {
        h.removeCallbacks(cardNext);
        cardQ.clear();
        cardBusy = false;
        final View v = cardView;
        cardView = null;
        if (v != null && cardHost != null) {
            try { cardHost.removeView(v); } catch (Throwable ignored) {}
        }
    }

    /** 延迟跳转：只为让本页的播报卡先露个脸（0 = 立刻跳）。 */
    private void launchLater(final Runnable go, long delayMs) {
        if (delayMs <= 0) { go.run(); return; }
        h.postDelayed(go, delayMs);
    }

    private void fixPerm(int idx) {
        planAnnounced = false;   // 单项修复：各自播各自的
        switch (idx) {
            case 0: openAccSettings(); break;
            case 1: requestCapture(); break;
            case 2: requestNotif(); break;
            case 3: requestBattery(); break;
        }
    }

    /** 跳系统无障碍设置。优先直达本应用详情页（API 26+），不支持就退到总列表。 */
    private void openAccSettings() {
        XqService.beginSetup();
        autoFlow = true;
        setStep("第 1 步：点「已下载的应用」→ 找到「审判者」→ 打开开关，然后按返回键回到本页");
        boolean overlay = stepAnnounce("① 无障碍服务", "打开开关后按返回");
        launchLater(new Runnable() { public void run() { openAccSettingsNow(); } },
                overlay ? 0L : LEAD_IN_MS);
    }

    private void openAccSettingsNow() {
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            return;
        } catch (Throwable ignored) {}
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable t) {
            toast("请手动到 设置→无障碍 里开启本应用");
        }
    }
    private void requestCapture() {
        XqService.beginSetup();
        if (askingCapture) return;
        if (askingCapture) return;
        // 已经有一个授权请求在等用户点：再发一次会把上一个顶掉（系统只允许一个待答复请求）
        if (XqService.captureAskPending()) return;
        askingCapture = true;
        setStep("第 2 步：系统会弹出「屏幕捕获」授权，点「允许 / 开始共享」——整屏即可");
        boolean overlay = stepAnnounce("② 屏幕捕获", "弹窗里选「整个屏幕」");
        launchLater(new Runnable() {
            public void run() {
                try {
                    // 用 CaptureService.captureIntent()：Android 14+ 下锁死「整屏」，
                    // 弹窗里不再出现「单个应用」选择器（选错了只会收到占位帧，随后自动断流）。
                    startActivityForResult(CaptureService.captureIntent(MainActivity.this), REQ_CAPTURE);
                } catch (Throwable t) {
                    askingCapture = false;
                    toast("无法请求屏幕捕获: " + t.getMessage());
                }
            }
        }, overlay ? 0L : LEAD_IN_MS);
    }

    private void requestNotif() {
        if (Build.VERSION.SDK_INT < 33) { toast("本系统不需要单独申请通知权限"); return; }
        boolean overlay = stepAnnounce("③ 通知权限", "允许后才能提醒你权限掉了");
        launchLater(new Runnable() {
            public void run() {
                try {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                } catch (Throwable t) {
                    toast("请在 设置→应用→通知 里手动允许");
                }
            }
        }, overlay ? 0L : LEAD_IN_MS);
    }

    private void requestBattery() {
        boolean overlay = stepAnnounce("④ 后台运行", "加入电池白名单，防被杀");
        launchLater(new Runnable() { public void run() { requestBatteryNow(); } },
                overlay ? 0L : LEAD_IN_MS);
    }

    private void requestBatteryNow() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            return;
        } catch (Throwable ignored) {}
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Throwable t) {
            toast("请到 设置→电池 里允许本应用后台运行");
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_NOTIF) {
            boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            toast(ok ? "通知权限已允许" : "通知权限被拒绝（不影响主要功能）");
            refresh();
            continuePendingFlow();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CAPTURE) return;
        askingCapture = false;
        if (res != RESULT_OK || data == null) {
            autoFlow = false;
            toast("你取消了屏幕捕获授权");
            refresh();
            return;
        }
        try {
            CaptureService.startWith(this, res, data);
            lastCapOn = true;
            capDropNotified = false;
            toast("屏幕捕获已开启");
        } catch (Throwable t) {
            toast("启动捕获失败: " + t.getMessage());
        }
        if (autoFlow) finishFlow();
        else h.postDelayed(new Runnable() { public void run() { refresh(); } }, 1200);
    }

    // ============================================================
    //  一键流程
    // ============================================================

    private void startFlow() {
        // ★ v4.3：从这一刻起进入修权限流程，先上锁 —— 期间任何卡、任何悬浮窗都不许出现
        XqService.beginSetup();
        planAnnounced = announcePlan();
        if (allReady()) {
            announceStep("✓ 四项权限已就绪", "正在拉起悬浮窗…");
            autoFlow = false;
            pendingBringUp = true;
            bringUpAndLeave();
            return;
        }
        // 先补齐「必需」的，再顺手补「建议」的
        if (!isAccOn()) { pendingBringUp = true; openAccSettings(); return; }
        if (!isCapOn()) { pendingBringUp = true; autoFlow = true; step2Capture(); return; }
        // 核心已就绪 → 拉起悬浮窗，然后再提示可选权限
        autoFlow = false;
        pendingBringUp = true;
        if (!isNotifOn()) { requestNotif(); return; }
        if (!isBatteryOk()) { requestBattery(); return; }
        bringUpAndLeave();
    }

    private void step2Capture() {
        if (isCapOn()) { finishFlow(); return; }
        requestCapture();
    }

    private void finishFlow() {
        autoFlow = false;
        askingCapture = false;
        if (!coreReady()) { setStep("还差必需权限，请按上面清单逐项开启"); return; }
        pendingBringUp = true;
        announceStep("✓ 权限已齐", "正在拉起悬浮窗…");
        bringUpAndLeave();
    }

    /**
     * 拉起悬浮窗，确认挂上之后自动退到后台。
     *
     * ★ 为什么必须退到后台：主界面在前台时，悬浮窗会被主动隐藏
     *   （否则全宽面板正好压住顶部的权限横幅，用户看不到自己缺哪一项）。
     *   所以修完权限如果只 bringUp 不离开，用户会以为「悬浮窗没启动」——
     *   实际已经挂上了，只是被本页挡着。这里确认挂上就自动让位。
     */
    /**
     * 「权限已获取，开始游戏」—— 开场流程总入口（v4.3）。
     *
     * 顺序（用户明确要求）：
     *   ① 四张权限卡一张接一张，从屏幕中间上浮、自然移出屏幕上方
     *   ② 四张播完，悬浮窗自己从屏幕中间滑到屏幕最上方（盖住状态栏）
     *
     * 播完把本页退到后台 —— 主界面在前台时悬浮窗会被主动隐藏，不退会看不到效果。
     */
    private void startGameFlow() {
        if (!coreReady()) {
            toast("还差必需权限，请先点上面「一键获取权限」");
            return;
        }
        if (!XqService.canAnnounce()) {
            toast("无障碍服务还没就绪，等两秒再点一次");
            return;
        }
        setStep("正在播放权限开场动画…");
        // 先把本页退到后台，悬浮窗通道才不会被「主界面在前台」规则压住
        leaveToBoard();
        h.postDelayed(new Runnable() {
            public void run() { XqService.playIntro("开始游戏按钮"); }
        }, 350);
    }

    /**
     * 权限流程走完 —— **不再自动拉起悬浮窗**（v4.3 改了流程）。
     *
     * 新流程是「用户自己点开始」：
     *   修完权限回到本页 → 提示去点「权限已获取，开始游戏」→ 才播开场动画。
     *   这样动画不会跟系统设置页抢时间，也不会在用户还在操作系统时冒出来。
     */
    private void bringUpAndLeave() {
        planAnnounced = false;
        pendingBringUp = false;
        setStep("权限已就绪 —— 点下面「权限已获取，开始游戏」");
        refresh();
    }

    /** 退到后台，把悬浮窗让出来（主界面在前台时悬浮窗会自动隐藏）。 */
    private void leaveToBoard() {
        setStep("");
        try { moveTaskToBack(true); } catch (Throwable ignored) {}
    }

    /** 如果「一键全部修复」还没走完（缺建议权限那一步），回来时接着走。 */
    private void continuePendingFlow() {
        if (!pendingBringUp) return;
        if (coreReady()) {
            pendingBringUp = false;
            announceStep("✓ 权限已齐", "正在拉起悬浮窗…");
            bringUpAndLeave();
        }
    }

    // ============================================================
    //  状态刷新
    // ============================================================

    private String statusLine() {
        return "无障碍:" + (isAccOn() ? "已开" : "未开")
                + "  捕获:" + (isCapOn() ? "运行中" : "未开")
                + "  通知:" + (isNotifOn() ? "已允许" : "未允许")
                + "  电池:" + (isBatteryOk() ? "已豁免" : "未豁免");
    }


    // ============================================================
    //  v4.80：主界面 = 3D 全息卡（WebView 承载）
    //  App 的「设置 / 一键获取权限 / 开始游戏 / 重新检测」由 overlay.js
    //  画成贴在卡面上的图层，随卡片一起旋转；点击通过 JS 桥回传原生逻辑。
    // ============================================================
    private WebView webView;
    private String lastHoloState = "";
    private static final String HOLO_HOST = "appassets.androidplatform.net";
    private static final String HOLO_BASE = "https://" + HOLO_HOST + "/holo/";

    /** 建 WebView 并从 assets/holo 提供卡片资源（自带 shouldInterceptRequest 静态服务，免 CORS）。 */
    private WebView buildHoloWebView() {
        WebView wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        try { s.setAllowFileAccess(true); } catch (Throwable ignored) {}
        try { s.setAllowContentAccess(true); } catch (Throwable ignored) {}
        try { s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW); } catch (Throwable ignored) {}
        wv.setBackgroundColor(0xFF07090E);
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        wv.addJavascriptInterface(new HoloBridge(), "Android");
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                try {
                    Uri u = (req == null) ? null : req.getUrl();
                    if (u == null || !HOLO_HOST.equals(u.getHost())) return null;
                    String p = u.getPath();
                    if (p == null) return null;
                    if (p.startsWith("/holo/")) p = p.substring(6);
                    if (p.startsWith("/")) p = p.substring(1);
                    if (p.length() == 0) p = "index.html";
                    if (p.equals("holo-card")) {
                        String ip = XqService.spStr(K_HOLO_IMG, "");
                        if (ip != null && !ip.isEmpty()) {
                            java.io.InputStream in2 = new java.io.FileInputStream(ip);
                            java.util.HashMap<String, String> h2 = new java.util.HashMap<String, String>();
                            h2.put("Cache-Control", "no-cache");
                            return new WebResourceResponse("image/jpeg", null, 200, "OK", h2, in2);
                        }
                        return null;
                    }
                    InputStream in = getAssets().open("holo/" + p);
                    java.util.HashMap<String, String> h = new java.util.HashMap<String, String>();
                    h.put("Cache-Control", "no-cache");
                    return new WebResourceResponse(mimeOf(p), null, 200, "OK", h, in);
                } catch (Throwable t) {
                    return null;
                }
            }
        });
        webView = wv;
        wv.loadUrl(HOLO_BASE + "index.html");
        return wv;
    }

    private static String mimeOf(String p) {
        String q = p.toLowerCase();
        if (q.endsWith(".html") || q.endsWith(".htm")) return "text/html";
        if (q.endsWith(".js") || q.endsWith(".mjs")) return "text/javascript";
        if (q.endsWith(".css")) return "text/css";
        if (q.endsWith(".json")) return "application/json";
        if (q.endsWith(".png")) return "image/png";
        if (q.endsWith(".jpg") || q.endsWith(".jpeg")) return "image/jpeg";
        if (q.endsWith(".webp")) return "image/webp";
        if (q.endsWith(".glb")) return "model/gltf-binary";
        if (q.endsWith(".gltf")) return "model/gltf+json";
        if (q.endsWith(".svg")) return "image/svg+xml";
        if (q.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    /** JS 桥：卡片上的按钮点一下就回原生。 */
    public class HoloBridge {
        @JavascriptInterface
        public void onAction(final String id) {
            runOnUiThread(new Runnable() {
                public void run() { handleHoloAction(id); }
            });
        }
        @JavascriptInterface
        public void log(String s) {
            android.util.Log.i("HoloCard", s == null ? "null" : s);
        }
        @JavascriptInterface
        public String getConfig() { return holoConfigJson(); }
        @JavascriptInterface
        public void onReady() {
            runOnUiThread(new Runnable() {
                public void run() { pushHoloState(true); }
            });
        }
    }

    private void handleHoloAction(String id) {
        if (id == null) return;
        if ("grant".equals(id)) {
            startFlow();
        } else if ("start".equals(id)) {
            startGameFlow();
        } else if ("settings".equals(id)) {
            try { startActivity(new Intent(MainActivity.this, SettingsActivity.class)); }
            catch (Throwable t) { toast("打不开设置：" + t.getMessage()); }
        } else if ("recheck".equals(id)) {
            refresh();
            toast(statusLine());
        }
    }

    /** 把权限状态回推给卡片上的按钮层（状态没变就不重复推）。 */
    private void pushHoloState() { pushHoloState(false); }

    private void pushHoloState(boolean force) {
        final WebView wv = webView;
        if (wv == null) return;
        final String js = "window.HoloUI&&HoloUI.setState({version:\"" + versionName()
                + "\",perms:[" + (isAccOn() ? "true" : "false")
                + "," + (isCapOn() ? "true" : "false")
                + "," + (isNotifOn() ? "true" : "false")
                + "," + (isBatteryOk() ? "true" : "false")
                + "],cfg:" + holoConfigJson()
                + ",quote:\"" + holoEscape(quoteOfDay()) + "\""
                + ",poem:\"" + holoEscape(quoteFullOfDay()) + "\"});";
        if (!force && js.equals(lastHoloState)) return;
        lastHoloState = js;
        try { wv.evaluateJavascript(js, null); } catch (Throwable ignored) {}
    }

    // ============================================================
    //  v4.90：卡片正面自定义背景 + 人脸避让 + 背面联系方式
    //  设置页选图 → 拷贝进私有目录 → 系统人脸检测记录脸区比例 →
    //  WebView 里的 overlay.js 按脸区自动排版文字与按钮。
    // ============================================================
    public static final String K_HOLO_IMG = "holo_img";
    public static final String K_HOLO_FACE = "holo_face";
    public static final String K_HOLO_IMG_VER = "holo_img_ver";
    public static final String K_HOLO_CONTACT = "holo_contact";
    public static final String K_HOLO_QQ = "holo_qq";
    /** ★ v5.6：卡片背面 QQ 群的出厂默认值（设置页留空时用这个，远程配置可覆盖）。 */
    public static final String HOLO_QQ_DEF = "1128803447";
    /** 内置 mello 卡面的人物脸区（实测比例 cx/cy/r，JS 避让用）。 */
    private static final String HOLO_FACE_DEF = "{\"cx\":0.62,\"cy\":0.64,\"r\":0.15}";

    public static void bumpHoloVer() {
        XqService.spPutInt(K_HOLO_IMG_VER, XqService.uiInt(K_HOLO_IMG_VER, 0) + 1);
    }

    public static void clearHoloCardImage(android.content.Context c) {
        try {
            String p = XqService.spStr(K_HOLO_IMG, "");
            if (p != null && !p.isEmpty()) new java.io.File(p).delete();
        } catch (Throwable ignored) {}
        XqService.spPutStr(K_HOLO_IMG, "");
        XqService.spPutStr(K_HOLO_FACE, "");
        bumpHoloVer();
    }

    /** 选图入口：拷贝缩放进私有目录 + 人脸检测 + 记录版本号。 */
    public static void setHoloCardImage(android.content.Context c, Uri uri) {
        try {
            android.graphics.BitmapFactory.Options bo = new android.graphics.BitmapFactory.Options();
            bo.inJustDecodeBounds = true;
            java.io.InputStream in0 = c.getContentResolver().openInputStream(uri);
            android.graphics.BitmapFactory.decodeStream(in0, null, bo);
            in0.close();
            int sample = 1;
            while (bo.outWidth / (sample * 2) >= 1600) sample *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = sample;
            java.io.InputStream in = c.getContentResolver().openInputStream(uri);
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeStream(in, null, o2);
            in.close();
            if (b == null) return;
            java.io.File dst = new java.io.File(c.getFilesDir(), "holo_card.jpg");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(dst);
            b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, fo);
            fo.close();
            XqService.spPutStr(K_HOLO_IMG, dst.getAbsolutePath());
            XqService.spPutStr(K_HOLO_FACE, detectFaceJson(b));
            b.recycle();
            bumpHoloVer();
        } catch (Throwable t) {
            try { CaptureService.flog(c, "卡片正面图设置失败: " + t); } catch (Throwable ignored) {}
        }
    }

    /** 系统人脸检测（RGB_565）：返回脸中心/半径的归一化 JSON，失败返回空串。 */
    private static String detectFaceJson(android.graphics.Bitmap src) {
        try {
            android.graphics.Bitmap b = (src.getConfig() == android.graphics.Bitmap.Config.RGB_565)
                    ? src : src.copy(android.graphics.Bitmap.Config.RGB_565, false);
            if (b == null) return "";
            android.media.FaceDetector fd = new android.media.FaceDetector(
                    b.getWidth(), b.getHeight(), 2);
            android.media.FaceDetector.Face[] fs = new android.media.FaceDetector.Face[2];
            int n = fd.findFaces(b, fs);
            String out = "";
            if (n > 0 && fs[0] != null) {
                android.graphics.PointF m = new android.graphics.PointF();
                fs[0].getMidPoint(m);
                float e = fs[0].eyesDistance();
                if (e > 1f) {
                    float cx = m.x / b.getWidth();
                    float cy = (m.y + e * 0.35f) / b.getHeight();   // 两眼中点略下 ≈ 脸中心
                    float rx = e * 0.95f / b.getWidth();
                    float ry = e * 1.35f / b.getHeight();
                    float r = Math.max(rx, ry);
                    out = String.format(java.util.Locale.US,
                            "{\"cx\":%.4f,\"cy\":%.4f,\"r\":%.4f}", cx, cy, r);
                }
            }
            if (b != src) b.recycle();
            return out;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String holoEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "").replace("\n", " ");
    }

    /** 卡片背面显示的 QQ 群：用户填了用自己的，留空用出厂默认。 */
    public static String holoQq() {
        String v = XqService.spStr(K_HOLO_QQ, "");
        if (v == null || v.trim().isEmpty()) return HOLO_QQ_DEF;
        return v.trim();
    }

    /** 卡片配置（正面图 / 脸区 / 联系方式），给 WebView 的 overlay.js。 */
    private String holoConfigJson() {
        boolean hasImg = false;
        try {
            String p = XqService.spStr(K_HOLO_IMG, "");
            hasImg = (p != null && !p.isEmpty() && new java.io.File(p).exists());
        } catch (Throwable ignored) {}
        String face = XqService.spStr(K_HOLO_FACE, "");
        if (face == null || face.isEmpty()) {
            face = hasImg ? "null" : HOLO_FACE_DEF;
        }
        return "{\"img\":" + hasImg
                + ",\"ver\":" + XqService.uiInt(K_HOLO_IMG_VER, 0)
                + ",\"face\":" + face
                + ",\"contact\":\"" + holoEscape(XqService.spStr(K_HOLO_CONTACT, ""))
                + "\",\"qq\":\"" + holoEscape(holoQq()) + "\"}";
    }

    // ============================================================
    //  v4.92：每日名言（正面一句 + 背面完整原文）—— 今日诗词 API。
    //  正面显示名句 + 出处；背面显示整首诗（标题/朝代作者/全文）。
    //  当天拉到即缓存不再请求；失败用内置兜底诗按天轮换。
    // ============================================================
    private static final String K_QUOTE_TEXT = "holo_quote_text";
    private static final String K_QUOTE_FULL = "holo_quote_full";
    private static final String K_QUOTE_DAY = "holo_quote_day_v2";
    private static volatile boolean quoteFetching = false;

    /** 兜底（断网）：[0]=正面一句 [1]=完整原文，按天轮换。 */
    private static final String[][] QUOTE_FALLBACK = {
        {"青山绿水，白草红叶黄花。",
         "天净沙·秋\n元·白朴\n孤村落日残霞，轻烟老树寒鸦，一点飞鸿影下。\n青山绿水，白草红叶黄花。"},
        {"长风破浪会有时，直挂云帆济沧海。",
         "行路难·其一\n唐·李白\n长风破浪会有时，直挂云帆济沧海。"},
        {"路漫漫其修远兮，吾将上下而求索。",
         "离骚（节选）\n战国·屈原\n路漫漫其修远兮，吾将上下而求索。"},
        {"海内存知己，天涯若比邻。",
         "送杜少府之任蜀州\n唐·王勃\n城阙辅三秦，风烟望五津。与君离别意，同是宦游人。\n海内存知己，天涯若比邻。无为在歧路，儿女共沾巾。"},
        {"会当凌绝顶，一览众山小。",
         "望岳\n唐·杜甫\n岱宗夫如何？齐鲁青未了。造化钟神秀，阴阳割昏晓。\n荡胸生曾云，决眦入归鸟。会当凌绝顶，一览众山小。"},
        {"落霞与孤鹜齐飞，秋水共长天一色。",
         "滕王阁序（节选）\n唐·王勃\n落霞与孤鹜齐飞，秋水共长天一色。"},
    };

    private static String todayKey() {
        return new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(new java.util.Date());
    }

    private static int dayIndex() {
        return Math.abs(todayKey().hashCode()) % QUOTE_FALLBACK.length;
    }

    /** 当天该显示的名言（同步读缓存；过期先给兜底句，拉到后自动刷新）。 */
    private String quoteOfDay() {
        String t = XqService.spStr(K_QUOTE_TEXT, "");
        if (todayKey().equals(XqService.spStr(K_QUOTE_DAY, "")) && !t.isEmpty()) return t;
        return QUOTE_FALLBACK[dayIndex()][0];
    }

    /** 当天该显示的完整原文（背面用）。 */
    private String quoteFullOfDay() {
        String t = XqService.spStr(K_QUOTE_FULL, "");
        if (todayKey().equals(XqService.spStr(K_QUOTE_DAY, "")) && !t.isEmpty()) return t;
        return QUOTE_FALLBACK[dayIndex()][1];
    }

    /** 过期就后台拉新的（今日诗词，带全文）；失败不缓存，下次打开再试。 */
    private void ensureQuote() {
        if (quoteFetching) return;
        if (todayKey().equals(XqService.spStr(K_QUOTE_DAY, ""))
                && !XqService.spStr(K_QUOTE_TEXT, "").isEmpty()) return;
        quoteFetching = true;
        final android.content.Context c = getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                java.net.HttpURLConnection conn = null;
                try {
                    java.net.URL u = new java.net.URL("https://v1.jinrishici.com/all");
                    conn = (java.net.HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);
                    conn.setRequestProperty("User-Agent", "JudgeChess/4.92");
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    org.json.JSONObject o = new org.json.JSONObject(sb.toString());
                    String content = o.optString("content", "");
                    String title = o.optString("origin", "");
                    String author = o.optString("author", "");
                    if (content.isEmpty() || title.isEmpty()) throw new Exception("返回不完整");
                    String front = content + " —— " + author + "《" + title + "》";
                    String full = PoemBook.find(title);
                    if (full == null) full = title + "\n" + author;
                    XqService.spPutStr(K_QUOTE_TEXT, front);
                    XqService.spPutStr(K_QUOTE_FULL, full);
                    XqService.spPutStr(K_QUOTE_DAY, todayKey());
                    runOnUiThread(new Runnable() {
                        public void run() { pushHoloState(true); }
                    });
                    try { CaptureService.flog(c, "今日名言已更新（含全文）"); } catch (Throwable ignored) {}
                } catch (Throwable t) {
                    try { CaptureService.flog(c, "名言拉取失败（今日先用兜底诗）: " + t.getMessage()); }
                    catch (Throwable ignored) {}
                } finally {
                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
                    quoteFetching = false;
                }
            }
        }, "holo-quote").start();
    }

    private void setStep(String s) { if (tvStep != null) tvStep.setText(s == null ? "" : s); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    /** 刷新权限清单、横幅、看盘信息。 */

    // ============ v4.81 MELLO 文字可读性三方案（临时预览，选好再固化） ============
    private int inkMain() { return holoTextMode == 1 ? 0xFF2A1E12 : 0xFFF2F6FA; }
    private int inkSub()  { return holoTextMode == 1 ? 0xFF5A4632 : 0xFFA8B4C0; }
    private int inkFaint(){ return holoTextMode == 1 ? 0xFF8A7355 : 0xFF7E8B98; }

    private void holoPanel(View v, int l, int t, int r, int b) {
        if (holoTextMode == 1) return;
        v.setBackgroundResource(holoTextMode == 2 ? R.drawable.bg_holo_panel_c : R.drawable.bg_holo_panel);
        v.setPadding(dp(l), dp(t), dp(r), dp(b));
    }
    private void holoCard(View v) {
        if (holoTextMode == 1) return;
        v.setBackgroundResource(holoTextMode == 2 ? R.drawable.bg_holo_panel_c : R.drawable.bg_holo_panel);
    }
    private View buildHoloSwitcher() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        String[] names = {"A 玻璃衬底", "B 深色文字", "C 毛玻璃"};
        for (int i = 0; i < 3; i++) {
            final int m = i;
            Button b = new Button(this);
            b.setText((holoTextMode == m ? "▶ " : "") + names[i]);
            b.setTextSize(10);
            b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            b.setTextColor(0xFF0B1014);
            b.setBackgroundResource(R.drawable.bg_btn_primary);
            b.setMinHeight(0); b.setMinimumHeight(0);
            b.setPadding(dp(8), 0, dp(8), 0);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    XqService.spPutInt("holo_text_mode", m);
                    recreate();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(30), 1f);
            lp.leftMargin = dp(3); lp.rightMargin = dp(3);
            bar.addView(b, lp);
        }
        return bar;
    }

    private void refresh() {
        boolean acc = isAccOn(), cap = isCapOn(), nf = isNotifOn(), bat = isBatteryOk();
        boolean[] st = {acc, cap, nf, bat};
        pushHoloState();
        for (int i = 0; i < N_PERM; i++) {
            if (permDot[i] != null) {
                boolean req = (i < 2);
                permDot[i].setBackgroundResource(st[i] ? R.drawable.dot_ok
                        : (req ? R.drawable.dot_no : R.drawable.dot_warn));
            }
            if (permState[i] == null) continue;
            boolean required = (i < 2);
            permState[i].setText(st[i] ? "已就绪" : (required ? "缺失（必需）" : "未开启（建议）"));
            permState[i].setTextColor(st[i] ? 0xFF2BE06B : (required ? 0xFFFF5A5A : 0xFFFFB020));
            if (permBtn[i] != null) {
                permBtn[i].setText(st[i] ? "重新授权" : "去开启");
                permBtn[i].setEnabled(true);
                if (st[i]) {
                    permBtn[i].setBackgroundResource(R.drawable.bg_btn_ghost);
                    permBtn[i].setTextColor(0xFF5C6874);
                } else {
                    permBtn[i].setBackgroundResource(R.drawable.bg_btn_primary);
                    permBtn[i].setTextColor(0xFF0B1014);
                }
            }
        }

        // 横幅
        if (allReady()) {
            tvBanner.setText("全部就绪，助手可正常运行");
            tvBanner.setBackgroundResource(R.drawable.bg_banner_ok);
            tvBanner.setTextColor(0xFF2BE06B);
        } else if (coreReady()) {
            tvBanner.setText("核心已就绪，建议补上通知 / 电池权限（防掉线）");
            tvBanner.setBackgroundResource(R.drawable.bg_banner_warn);
            tvBanner.setTextColor(0xFFFFB020);
        } else if (acc && !cap) {
            tvBanner.setText("无障碍已开，但屏幕捕获未开 —— 点下面按钮或等自动弹出授权");
            tvBanner.setBackgroundResource(R.drawable.bg_banner_warn);
            tvBanner.setTextColor(0xFFFFB020);
        } else {
            tvBanner.setText("缺少必需权限，助手无法运行");
            tvBanner.setBackgroundResource(R.drawable.bg_banner_bad);
            tvBanner.setTextColor(0xFFFF5A5A);
        }


        // 「权限已获取，开始游戏」：核心权限齐了才亮起来（唯一可点的强按钮）
        if (bStart != null) {
            boolean ok = coreReady();
            bStart.setEnabled(ok);
            if (ok) {
                bStart.setBackgroundResource(R.drawable.bg_btn_primary);
                bStart.setTextColor(0xFF0B1014);
                bStart.setText("权限已获取，开始游戏");
            } else {
                bStart.setBackgroundResource(R.drawable.bg_btn_ghost);
                bStart.setTextColor(0xFF5C6874);
                bStart.setText("权限已获取，开始游戏（先把上面四项修好）");
            }
        }
        long age = CaptureService.frameAge();
        tvState.setText("无障碍: " + (acc ? "已开启" : "未开启")
                + "    屏幕捕获: " + (cap ? "运行中" : "未开启")
                + (age >= 0 ? "（最新帧 " + age + "ms 前）" : "")
                + (XqService.panelAlive() ? "    悬浮窗: 已启动" : "    悬浮窗: 未启动"));

        // 发现「捕获掉了」这个事件 → 自动弹授权（只在用户已在使用时）
        if (lastCapOn && !cap) {
            capDropNotified = false;
            setStep("屏幕捕获掉了（通常是重启手机导致）—— 正在自动重新申请…");
        }
        lastCapOn = cap;
        autoHealCapture();
        updateEngineInfo();
    }

    /**
     * ★ v4.28：刷新主界面的「引擎 / 算力」两行。
     *
     * 引擎名来自 UCI 握手（Pikafish 自报），算力描述来自设备探测 + 性能自检。
     * 两者都要等 XqService 把服务跑起来才有值，所以这里对「还没就绪」有兜底文案 ——
     * 不能显示空白，否则用户会以为是坏了。
     */
    private void updateEngineInfo() {
        try {
            if (tvEngine != null) {
                String eng = XqService.engineNameCache();
                tvEngine.setText((eng == null || eng.length() == 0)
                        ? "尚未启动（打开悬浮窗后自动加载）" : eng);
            }
            if (tvCompute != null) {
                String desc = XqService.spStrOf(this, XqService.K_DEV_DESC, "");
                if (desc == null || desc.length() == 0) {
                    tvCompute.setText("尚未自检（首次打开悬浮窗时自动完成）");
                } else {
                    tvCompute.setText(desc);
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 自动补救：无障碍已开、只缺屏幕捕获时，自动把系统授权弹出来。
     * 带冷却，避免反复弹窗骚扰；用户取消过就不再纠缠，等他手动点。
     */
    private void autoHealCapture() {
        if (!isAccOn() || isCapOn()) return;
        long now = System.currentTimeMillis();
        if (XqService.captureAskPending()) return;  // 已有授权页在等用户点，别顶掉
        if (now - lastAutoAsk < 120000L) return;    // 120 秒冷却，避免反复弹窗骚扰
        lastAutoAsk = now;
        h.postDelayed(new Runnable() {
            public void run() { if (!isCapOn() && isAccOn()) requestCapture(); }
        }, 300);
    }

    // ================= 版本信息 =================

    /** 读当前 APK 的版本名（构建时由 release.sh 从 rel/version 写进 manifest）。 */
    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 版本号行：点一下弹出「本版更新说明」。 */
    private void showUpdateNotes(String vn) {
        try {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("审判者  v" + vn)
                    .setMessage(UPDATE_NOTES)
                    .setPositiveButton("知道了", null)
                    .show();
        } catch (Throwable ignored) {}
    }

    /** 本版更新说明。每次发版跟着改。 */
    private static final String UPDATE_NOTES =
            "【v5.0】主界面换代\n"
          + "\n"
          + "· ★ 主界面换成 3D 全息卡\n"
          + "  —— 整张收藏卡铺满屏幕，双击卡片翻面。\n"
          + "  · 正面：文字与按钮直接印在卡面上（描金标题、金线名言、\n"
          + "    细金线菜单），人物立绘完整不被遮挡；\n"
          + "  · 背面：整首诗居中 + 朱红印章 + 联系方式与 QQ 群卡片。\n"
          + "\n"
          + "· 每日名言（正面一句，背面完整原文）\n"
          + "  —— 联网时每天自动拉一句诗词名句，一天一换；\n"
          + "     背面显示整首原文（内置 76 首经典诗词库），断网也有兜底。\n"
          + "\n"
          + "· 自定义卡片正面图\n"
          + "  —— 设置 → 背景 → 卡片正面与背面，可从相册选图铺满卡面；\n"
          + "     自动检测图中人脸位置，文字与按钮自动避开人脸排布。\n"
          + "\n"
          + "· App 界面背景主题 ×5（设置 → 背景 → 界面背景）\n"
          + "  —— 午夜鎏金 / 画廊纸感 / 全息扫光 / 线稿档案 / 面板融合。\n"
          + "     只作用于 App 自身界面，完全不影响悬浮窗。\n"
          + "\n"
          + "· 修复\n"
          + "  · 分析条分数方向：黑方优势不再显示负分（方向由文字表达）；\n"
          + "  · 局终自动点「再来一局」：修复开关残留导致永不点击的问题，\n"
          + "    并给绝杀后的结算页判定加 12 秒宽限期。\n"
          + "\n"
          + "【v4.32】\n"
          + "· ★ 悬浮窗大小固定了\n"
          + "  —— 以前面板高度是「按内容撑开」的，折叠分析区、隐藏某个构件，\n"
          + "     悬浮窗就会跟着变矮变高（实测同一次会话里 374px 与 509px 来回跳）。\n"
          + "     现在面板高度写死，改任何设置都不再改变悬浮窗大小。\n"
          + "· 新增「悬浮窗上下位置」（设置 → 棋盘）\n"
          + "  —— 0 是贴屏幕最顶端；往右拉，整块悬浮窗往下挪。只挪位置，不改大小。\n"
          + "· 分类改成选项卡：棋盘 / 背景 / 动画 / 对局，一行四个、选中的填绿，\n"
          + "  未选中的只有描边，一眼能看出是一排选项卡。\n"
          + "· 清掉界面上多余的装饰符号（对勾、警告、播放三角等），只留必要提示文字。\n";

    @Override
    protected void onResume() {
        super.onResume();
        XqService.mainUiForeground = true;
        // ★ v4.42：用户按「音量减 + 电源」截屏时别把悬浮窗收掉（API 34+）
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34 && shotCb == null) {
                shotCb = new android.app.Activity.ScreenCaptureCallback() {
                    public void onScreenCaptured() { XqService.onUserScreenshot(); }
                };
                registerScreenCaptureCallback(getMainExecutor(), shotCb);
            }
        } catch (Throwable ignored) {}
        polling = true;
        h.post(poll);
        refresh();

        if (autoFlow) {
            // 从系统页返回：先播「上一步的结果」，再播下一步，一张接一张
            if (!isAccOn()) {
                setStep("还差第 1 步：无障碍服务没打开 —— 点「已下载的应用」→「审判者」→ 打开开关");
                announceStep("① 无障碍服务", "还没打开开关");
            } else if (!isCapOn()) {
                announceStep("✓ 无障碍已开启", "接下来授权屏幕捕获");
                h.postDelayed(new Runnable() { public void run() { if (autoFlow) step2Capture(); } }, 500);
            } else {
                finishFlow();
            }
            return;
        }
        // 「一键全部修复」走到「补建议权限」那一步时，用户从系统页返回就接着拉起悬浮窗
        continuePendingFlow();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // ★ 用 notifySelfUiLeft 而不是直接写静态字段：它会顺手让服务立刻把悬浮窗
        //   放出来。否则要等巡查看门狗那一秒，用户刚点完修复会觉得「没启动」。
        XqService.notifySelfUiLeft();
        clearLocalCards();
        polling = false;
        h.removeCallbacks(poll);
    }

    /** 每 400ms 拉一次：先刷权限，再刷识别结果。 */
    private final Runnable poll = new Runnable() {
        public void run() {
            if (!polling) return;
            refresh();
            if (BoardBus.has()) {
                if (boardView != null) boardView.setBoard(BoardBus.board(), BoardBus.conf(), BoardBus.side());
                if (tvFen != null) tvFen.setText(BoardBus.fen());
            }
            String mv = BoardBus.move();
            if (tvMove != null) tvMove.setText(mv == null || mv.isEmpty() ? "" : "着法: " + mv + "\n" + BoardBus.info());
            h.postDelayed(this, 400);
        }
    };
}
