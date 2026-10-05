package com.yypm.assistant;

import android.content.Intent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * YYPM 核心服务：无障碍截图 + ONNX 识别 + 皮卡鱼算招 + 悬浮窗提示 + 手势落子。
 * 全部免 root。
 */
public class XqService extends AccessibilityService {

    private static final String TAG = "YYPM";
    public static final String ACTION_DO_ONE = "com.yypm.assistant.DO_ONE";
    public static final String ACTION_AUTO_ON = "com.yypm.assistant.AUTO_ON";
    public static final String ACTION_AUTO_OFF = "com.yypm.assistant.AUTO_OFF";

    // 进程级共享：Android 会在同一进程里创建多个 XqService 实例，
    // 引擎/模型必须是静态的，否则每个实例各起一个 pikafish（实测起过 3 个）。
    private static volatile BoardVision vision;
    private static volatile PikafishEngine engine;
    private volatile boolean engineRestarting = false;
    private static final String CH_PERM = "yypm_perm";
    private volatile long capAlertAt = 0L;      // 上次「捕获失效」提醒时间（60 秒冷却）
    private static final AtomicInteger sInstances = new AtomicInteger(0);
    private static final AtomicBoolean sHeavyInited = new AtomicBoolean(false);
    private boolean counted = false;
    private WindowManager wm;
    private View panel;
    private TextView tvStatus;
    private Button btnLink, btnOne, btnLight, btnLianKai;
    private TextView tvTimer;
    private EvalBar evalBar;
    private volatile long gameStartMs = 0L;   // 本局开始时刻（0=本局还没开始）
    private volatile long gameStopMs = 0L;    // 本局结束时刻（>0=已停表冻结）
    private volatile boolean lastWasOpening = false;
    private volatile int lastPieceCount = 0;
    private volatile boolean timerTickOn = false;
    private BoardView miniBoard;
    private View colLeft;        // 左栏（按钮+文字），棋盘按它的高度撑满
    private int leftMinW = 0;    // 左栏按钮排的自然宽度：棋盘再大也不许把按钮挤没

    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    /** 专门用来定时发第二次点击。
     *
     *  为什么不能用 ui.postDelayed：主线程在截图 / 拷贝大图时会被占住，第二次点击
     *  就会被推迟。实测 JJ 象棋那局第二次点击晚了 6 秒，帅的选中状态早已过期，
     *  于是「点了一下没反应」——整步棋白走。第二次点击必须独立于主线程。 */
    private final ScheduledExecutorService tapSched = Executors.newSingleThreadScheduledExecutor();
    /** 一着棋里两次点击之间的间隔。JJ 象棋对快速双击会当成「取消选中」，
     *  620ms 实测能被正常接受（这一局 11 步全部生效）。 */
    private static final long TAP_GAP_MS = 620L;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean auto = new AtomicBoolean(false);
    private final AtomicBoolean autoPlay = new AtomicBoolean(true);  // ⚡ 自动走子
    private final AtomicBoolean linkBusy = new AtomicBoolean(false);
    private static volatile long sLastTapAt = 0;      // 进程级落子节流（跨实例）
    private static final AtomicBoolean sLinkLoop = new AtomicBoolean(false);  // 连线循环实例锁
    private boolean heavyInited = false;
    private boolean connectedOnce = false;
    private volatile long lastDumpAt = 0;
    private volatile int dumpSeq = 0;
    private volatile String dumpNote = "";

    // ---- v4.27 设备自适应 ----
    //   同一份 APK 要在强机 / 弱机上都跑得动，所以这些值不再写死。
    //   探测在 initHeavy() 里做一次；识别耗时每帧更新。
    private volatile long lastDetectMs = 0L;   // 最近一次单帧识别耗时
    private volatile long detectMsAvg = 0L;    // 指数平滑，抗单帧抖动
    private volatile int devThreads = 2;       // 引擎线程数（探测后确定）
    private volatile int devHash = 64;         // 引擎置换表 MB

    private char mySide = 'w';
    private volatile boolean sideAuto = true;   // 自动识别执红/执黑
    private volatile boolean sideFromVision = false;
    private volatile float[] lastMark;     // x1,y1,x2,y2（屏幕坐标）
    private volatile String lastFen = "";

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        if (!counted) { counted = true; sInstances.incrementAndGet(); }

        sInstance = this;
        // ★ v6.2：服务自己把 appCtx 置上。
        //   原来只有 MainActivity / SettingsActivity 设置，而无障碍服务可能先于两者启动
        //   （重启手机 / 切换无障碍开关），那时 appCtx 为 null，
        //   读配置会失败（个别设置项读不到）。
        appCtx = getApplicationContext();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        // 主界面可能已经在前台了：先把悬浮窗状态定下来，避免开机闪一下
        if (mainUiForeground && !settingsOpen) overlaySelfHidden = true;
        // ★ v4.3：服务刚连上**绝不弹悬浮窗**。
        //   waitingStart=true 表示「用户还没点『权限已获取，开始游戏』」——
        //   这段时间（正是用户在系统设置里逐个开权限的时候）什么都不能冒出来。
        //   点过开始之后会把这个标记存进 SharedPreferences，服务被系统回收重建也不用再点一次。
        waitingStart = sp(K_STARTED, 0) == 0;
        removeKeepAlive();
        pool.execute(new Runnable() {
            public void run() { initHeavy(); }
        });
        startWatchdog();
    }

    /** v4.27：本实例视角的低内存标志（探测后落盘，这里读回来）。 */
    private boolean devLowRamNow() {
        return sp(K_DEV_LOWRAM, 0) == 1;
    }

    /**
     * ★ v4.27 设备探测：核数 / 低内存 / 线程 / 置换表 / 取帧间隔。
     *
     * 结果落盘，因为设置页（可能在无障碍都没开的时候被打开）也要读得到。
     */
    private void probeDevice() {
        try {
            int cores = Runtime.getRuntime().availableProcessors();
            boolean lowRam = false;
            try {
                android.app.ActivityManager am =
                        (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                lowRam = (am != null) && am.isLowRamDevice();
            } catch (Throwable ignored) {}
            devThreads = DeviceProfile.engineThreads(cores, lowRam);
            devHash = DeviceProfile.engineHashMb(lowRam);
            int tier = sp(K_DEV_TIER, 1);
            spPut(K_DEV_LOWRAM, lowRam ? 1 : 0);
            spPutStr(K_DEV_DESC, DeviceProfile.describeCn(cores, lowRam, devThreads, devHash, tier));
            // 低内存设备把屏幕捕获的取帧间隔也放宽（位图转换是常驻负载的大头）
            try { CaptureService.setFrameGap(DeviceProfile.captureFrameGap(lowRam)); } catch (Throwable ignored) {}
            flog("设备探测 " + DeviceProfile.describe(cores, lowRam, devThreads, devHash, tier)
                    + " 取帧间隔=" + DeviceProfile.captureFrameGap(lowRam) + "ms");
        } catch (Throwable t) {
            flog("设备探测失败，按默认值走: " + t);
        }
    }

    /**
     * ★ v4.27 性能自检：跑一次限时搜索，按实测 nps 分档。全过程只做一次。
     *
     * 为什么用「实测」而不是只看核数：核数多不代表快 —— 以小核为主的机器
     * 跑多线程搜索，可能还不如核心少但全是大核的机器。实测一次最可靠，
     * 代价只有 0.7 秒，而且只在首次运行时发生。
     */
    private void runBenchmarkOnce() {
        try {
            if (sp(K_DEV_TIER, -1) >= 0) return;      // 已经测过，不重复
            setStatus("正在检测设备性能…");
            java.util.List<PikafishEngine.Move> bm = engine.analyse(
                    "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1",
                    700, 1);
            long nps = (bm == null || bm.isEmpty()) ? 0L : bm.get(0).nps;
            int tier = DeviceProfile.tierOf(nps);
            spPut(K_DEV_TIER, tier);
            int cores = Runtime.getRuntime().availableProcessors();
            boolean lowRam = sp(K_DEV_LOWRAM, 0) == 1;
            spPutStr(K_DEV_DESC, DeviceProfile.describeCn(cores, lowRam, devThreads, devHash, tier));
            flog("性能自检 nps=" + nps + " -> 档位 " + DeviceProfile.tierName(tier)
                    + "（" + DeviceProfile.describe(cores, lowRam, devThreads, devHash, tier) + "）");
        } catch (Throwable t) {
            spPut(K_DEV_TIER, 1);                     // 失败按标准档，不留悬空值
            flog("性能自检失败，按标准档处理: " + t);
        }
    }

    /** 展开资源并加载模型 / 引擎。 */
    private void initHeavy() {
        // 进程级幂等：已有实例把引擎起好了就直接复用，绝不再起第二个
        if (!sHeavyInited.compareAndSet(false, true)) {
            Log.i(TAG, "引擎已由其它实例初始化，直接复用");
            for (int i = 0; i < 60 && (vision == null || engine == null); i++) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
            return;
        }
        try {
            setStatus("正在释放资源…");
            File dir = new File(getFilesDir(), "xq");
            if (!dir.exists()) dir.mkdirs();

            File pose = new File(dir, "board_pose.onnx");
            File cls = new File(dir, "board_classifier.onnx");
            File nnue = new File(dir, "pikafish.nnue");
            if (!pose.exists()) copyAsset("board_pose.onnx", pose);
            if (!cls.exists()) copyAsset("board_classifier.onnx", cls);
            if (!nnue.exists()) copyAsset("pikafish.nnue", nnue);

            setStatus("正在加载识别模型…");
            vision = new BoardVision();
            vision.load(this, pose.getAbsolutePath(), cls.getAbsolutePath());

            // ★ v4.27：先探测设备能力，再按能力起引擎。
            //   原来线程数写死 4 —— 在 4 核机上会把核心占满，每步 0.7 秒里整台手机是僵的。
            probeDevice();

            String exe = new File(getApplicationInfo().nativeLibraryDir, "libpikafish.so").getAbsolutePath();
            setStatus("正在启动皮卡鱼…");
            engine = new PikafishEngine();
            // ★ v4.54：引擎的生死事件也写进应用自己的文件日志 ——
            //   logcat 在这台机器上跑一会儿就被刷掉，出事只能靠 app.log 复盘。
            PikafishEngine.setLogSink(new PikafishEngine.LogSink() {
                public void log(String s) { flog(s); }
            });
            engine.start(exe, nnue.getAbsolutePath(), devThreads, devHash);

            // ★ v4.27 性能自检：首次运行跑一次（约 0.7 秒），据此定「性能档」。
            //   档位只改我们自己的默认值（背景板、轮询间隔），不动用户的显式设置。
            runBenchmarkOnce();

            // ★ v4.28：把引擎自报名字缓存下来，主界面直接读（它拿不到服务实例）
            setEngineNameCache(engine.engineName());
            flog("引擎名：" + engineNameCache());
            // ---- 自检：若 files/probe.png 存在，直接跑一次完整识别并打日志 ----
            try {
                // 诊断用：优先读外部目录（shell 可直接写入，不用 chcon）
                File probe = new File(getExternalFilesDir(null), "probe.png");
                if (!probe.exists()) probe = new File(getFilesDir(), "probe.png");
                Log.i(TAG, "PROBE 路径=" + probe.getAbsolutePath()
                        + " exists=" + probe.exists()
                        + " len=" + (probe.exists() ? probe.length() : -1)
                        + " canRead=" + probe.canRead());
                if (probe.exists()) {
                    android.graphics.Bitmap b = android.graphics.BitmapFactory
                            .decodeFile(probe.getAbsolutePath());
                    Log.i(TAG, "PROBE decode=" + (b == null ? "null" :
                            (b.getWidth() + "x" + b.getHeight())));
                    if (b != null) {
                        long t0 = System.currentTimeMillis();
                        BoardVision.Result pr = vision.detect(b);
                        Log.i(TAG, "PROBE 耗时=" + (System.currentTimeMillis() - t0) + "ms");
                        Log.i(TAG, "PROBE ok=" + pr.ok + " msg=" + pr.message
                                + " minConf=" + pr.minConf + " dir=" + pr.direction);
                        Log.i(TAG, "PROBE FEN=" + pr.fen);
                        Log.i(TAG, "PROBE rows=" + (pr.fen == null ? -1 : pr.fen.split("/").length));
                        Log.i(TAG, "PROBE validate=" + (pr.fen == null ? "?" : Board.validate(pr.fen)));
                        Log.i(TAG, "PROBE plausible=" + (pr.fen == null ? "?" : Board.plausibleProblem(pr.fen)));
                        if (pr.chars != null) {
                            Log.i(TAG, "PROBE LAYOUT=" + BoardVision.layoutOf(pr.chars).replace("\n", " | "));
                            Log.i(TAG, "PROBE side=" + BoardVision.sideOf(pr.chars, pr.conf, 0.5f)
                                    + " sideKing=" + BoardVision.sideOfByKing(pr.chars));
                        }
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "PROBE failed", t);
            }


            Log.i(TAG, "screen = " + scrW() + "x" + scrH() + " density = " + dens());
            heavyInited = true;              // 初始化完成：允许看门狗做悬浮窗/引擎自愈
            renderSelfTest();
        } catch (Throwable t) {
            Log.e(TAG, "init failed", t);
            setStatus("初始化失败: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    /**
     * 渲染自检：把 BoardView 真画一遍（含走向箭头）并存成 PNG。
     * 只在外部目录存在 selftest 文件时执行，用于验证渲染效果，不依赖真机对局。
     */
    private void renderSelfTest() {
        try {
            File flag = new File(getExternalFilesDir(null), "selftest");
            if (!flag.exists()) return;
            String fen = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR";
            char[][] ch = Board.fromFen(fen);
            float[][] cf = new float[10][9];
            for (int i = 0; i < 10; i++) for (int j = 0; j < 9; j++) cf[i][j] = 0.95f;
            int[] myRc = BoardView.rcOfUci("h2e2", false);   // 我方 炮二平五
            int[] oppRc = BoardView.rcOfUci("b9c7", false);  // 敌方 马8进7

            // (1) 棋盘单独一张
            BoardView bv = new BoardView(this);
            bv.setBoard(ch, cf, "");
            bv.setMoves(myRc, oppRc);
            shotView(bv, 360, 400, "selftest_board.png");
            // (1b) V1.1 新样式：我方 1 条绿箭头 + 敌方 3 条带 ①②③ 序号的紫箭头
            BoardView bv3 = new BoardView(this);
            bv3.setBoard(ch, cf, "");
            bv3.setMovesMulti(myRc, new int[][]{
                    BoardView.rcOfUci("b9c7", false),
                    BoardView.rcOfUci("h9g7", false),
                    BoardView.rcOfUci("a6a5", false)});
            shotView(bv3, 540, 600, "selftest_board3.png");

            // (2) 整个悬浮面板（棋盘 + 我方/敌方文字行）
            View p = LayoutInflater.from(this).inflate(R.layout.panel, null);
            BoardView mb = p.findViewById(R.id.board_mini);
            if (mb != null) { mb.setBoard(ch, cf, ""); mb.setMovesMulti(myRc, new int[][]{ BoardView.rcOfUci("b9c7", false), BoardView.rcOfUci("h9g7", false), BoardView.rcOfUci("a6a5", false) }); }
            TextView ts = p.findViewById(R.id.tv_status);
            if (ts != null) ts.setText("就绪");
            int pw = 1272;
            p.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY),
                      View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST));
            p.layout(0, 0, pw, p.getMeasuredHeight());
            Bitmap bmp = Bitmap.createBitmap(pw, p.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
            p.draw(cv);
            FileOutputStream fo = new FileOutputStream(new File(getExternalFilesDir(null), "selftest.png"));
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();

            // (3) 黑方视角的形势条（检验符号翻转：引擎分数按走子方，条按红方）
            int eh = (int) (26 * getResources().getDisplayMetrics().density + 0.5f);
            EvalBar eb2 = new EvalBar(this);
            eb2.setEval(220, false, 0, 'b');   // 黑方视角 +2.20 = 黑优
            shotView(eb2, 400, eh, "selftest_eval_black.png");
            EvalBar eb3 = new EvalBar(this);
            eb3.setEval(0, true, 3, 'w');      // 红方 3 步杀
            shotView(eb3, 400, eh, "selftest_eval_mate.png");
            Log.i(TAG, "自检图已输出 selftest.png " + pw + "x" + p.getMeasuredHeight());
        } catch (Throwable t) {
            Log.e(TAG, "自检渲染失败", t);
        }
    }

    /** 把单个 View 画进一张 PNG（自检用）。 */
    private void shotView(View v, int w, int h, String name) throws Exception {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                  View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
        v.draw(cv);
        FileOutputStream fo = new FileOutputStream(new File(getExternalFilesDir(null), name));
        bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
        fo.close();
        Log.i(TAG, "自检图已输出 " + name + " " + w + "x" + h);
    }

    private void copyAsset(String name, File dst) throws Exception {
        InputStream is = getAssets().open(name);
        FileOutputStream os = new FileOutputStream(dst);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        os.close();
        is.close();
    }

    // ============================================================
    //  v4.7 个性化（设置页可改，改完立刻重建面板生效）
    // ============================================================
    /** 棋盘位置：0 = 左（默认，压头发不压脸）1 = 右（恢复 v4.6 以前的样子）。 */
    public static final String K_UI_BOARD_SIDE = "ui_board_side";
    /** 控制栏是否加半透明卡底：0 = 不加（人像最透）1 = 加（文字对比度最好）。 */
    public static final String K_UI_CTRL_CARD = "ui_ctrl_card";
    /** 背景板开关：1 = 开（默认）。 */
    public static final String K_UI_BG_ON = "ui_bg_on";
    /** 背景板不透明度，0~100（内部按百分比用），默认 30。 */
    public static final String K_UI_BG_ALPHA = "ui_bg_alpha";
    /**
     * 背景板默认不透明度。
     * 实测 30% 时人像被玻璃底 + 压暗层吃掉，基本看不见；40% 才「看得见人、文字也不累眼」。
     */
    public static final int K_UI_BG_ALPHA_DEF = 40;
    /** ★ v4.52：人物与文字方案（0~5）—— 解耦「人物亮度」和「遮罩强度」。
     *  0 现状 / 1 投影提亮 / 2 局部遮罩 / 3 文字软底 / 4 轮廓光暗角 / 5 综合。 */
    public static final String K_UI_BG_STYLE = "ui_bg_style";
    public static final int K_UI_BG_STYLE_DEF = 0;
    /** ★ v4.52：文字投影（默认开）。它是「人物能提亮」的前提。 */
    public static final String K_UI_TXT_SHADOW = "ui_txt_shadow";
    /** ★ v4.52：文字主次分明（着法提白加大、状态行降到最次灰）。 */
    public static final String K_UI_TXT_HIER = "ui_txt_hier";
    /** ★ v4.52：形势条三段渐变（红 → 中性 → 黑，强弱靠颜色深浅读出来）。 */
    public static final String K_UI_EVAL3 = "ui_eval3";
    /** ★ v4.53：数字等宽（计时 / 评分用等宽字体，跳数时不左右抖）。 */
    public static final String K_UI_TXT_MONO = "ui_txt_mono";
    /** ★ v4.53：计时并入着法行（关掉则计时单独占一行）。 */
    public static final String K_UI_TXT_COMPACT = "ui_txt_compact";

    // ---- v4.9：悬浮窗按键显隐（各 1=显示，0=隐藏）----
    public static final String K_UI_SHOW_LINK = "ui_show_link";         // 链（连线/开捕获）
    public static final String K_UI_SHOW_ONE = "ui_show_one";           // 识（识别一步）
    public static final String K_UI_SHOW_LIGHT = "ui_show_light";       // ⚡（自动落子）
    public static final String K_UI_SHOW_CLOSE = "ui_show_close";       // ✕（关闭面板）
    public static final String K_UI_SHOW_GRIP = "ui_show_grip";         // ⠿（拖动把手）
    public static final String K_UI_SHOW_COLLAPSE = "ui_show_collapse"; // —（折叠）

    // ---- v4.11：悬浮窗构件顺序可自定义（「占位自己移动」）----
    /** 左栏构件的上下顺序。 */
    public static final String K_UI_ORDER_COL = "ui_order_col";
    /** 按钮排（链/识/⚡）内部的左右顺序。 */
    public static final String K_UI_ORDER_BTN = "ui_order_btn";
    /** 右栏（✕/⠿/—）内部的上下顺序。 */
    public static final String K_UI_ORDER_RIGHT = "ui_order_right";
    public static final String ORDER_COL_DEF = "btn,eval,move,status";
    public static final String ORDER_BTN_DEF = "link,one,light";
    /**
     * v4.17：链 / 识 / ⚡ 各自放在哪一列：0 = 按钮排（左栏），1 = 右侧竖列。
     * 用户点名要「把链挪到右边那一列竖着放」，所以链的默认值是 1（右栏）。
     */
    public static final String K_UI_COL_LINK = "ui_col_link";
    public static final String K_UI_COL_ONE = "ui_col_one";
    public static final String K_UI_COL_LIGHT = "ui_col_light";
    /** v4.17：设置页按钮风格：0 = 方角（默认），1 = 圆润（小米那种胶囊感）。 */
    public static final String K_UI_BTN_STYLE = "ui_btn_style";
    /**
     * v4.18：设置页当前停在哪个分类（0~3）。
     *
     * 为什么要存盘：切「按钮排 / 右栏」之后要 recreate() 整页重建
     * （列变了，↑↓ 得换另一张顺序表），而重建后默认会回到第一个分类 ——
     * 用户反映「点按钮排，页面闪一下就跳回棋盘」，就是这里。
     */
    public static final String K_UI_TAB = "ui_tab";
    /** v4.17：右栏顺序。默认「链」排在最上面；换列后靠这个表把新成员接进来。 */
    public static final String ORDER_RIGHT_DEF = "link,one,light,close,grip,lk";

    /** 左栏 / 按钮排 / 右栏的「键 ↔ 资源 id」对照表（顺序即默认顺序）。 */
    public static final String[] ORDER_COL_KEYS = {"btn", "eval", "move", "status"};
    public static final int[] ORDER_COL_IDS = {
            R.id.row_btn, R.id.row_eval, R.id.row_move,
            R.id.tv_status};
    public static final String[] ORDER_BTN_KEYS = {"link", "one", "light"};
    public static final int[] ORDER_BTN_IDS = {R.id.btn_link, R.id.btn_one, R.id.btn_light};
    /** 右栏可容纳的构件（含从按钮排挪过来的三个）—— 多出来的键会自动跳过，不会出错。 */
        /** ★ v4.50：右栏固定住 链 / 识 / 自 / ✕ / ⠿ 五个。
         *  「—」（折叠小球）已删除 —— 它的功能由「音量减」键代替。
         *  五颗 26dp 圆钮 + 4dp 间距 = 525px，小于面板内容高 560px，放得下。 */
        public static final String[] ORDER_RIGHT_KEYS =
                {"link", "one", "light", "close", "grip", "lk"};
        public static final int[] ORDER_RIGHT_IDS = {
                R.id.btn_link, R.id.btn_one, R.id.btn_light, R.id.btn_close, R.id.tv_title,
                R.id.btn_liankai};

    /**
     * 按自定义顺序重排一个容器里的子视图。
     *
     * 找不到的键（用户从没设置过、或以后增删了构件）会自动补到末尾，
     * 所以顺序串是「软」的 —— 不用怕它和实际构件对不上而丢东西。
     */
    /**
     * v4.17：把某个构件挪到目标容器里（已经在里面就不动）。
     * 用途：「链 / 识 / ⚡」可以自由选择待在**按钮排**还是**右侧竖列**。
     */
    public static void reparent(View root, int childId, int targetId) {
        try {
            View child = root.findViewById(childId);
            View target = root.findViewById(targetId);
            if (child == null || target == null) return;
            if (child.getParent() == target) return;
            android.view.ViewGroup old = (android.view.ViewGroup) child.getParent();
            if (old != null) old.removeView(child);
            ((android.view.ViewGroup) target).addView(child);
        } catch (Throwable ignored) {}
    }

    public static void applyOrder(View parent, String[] keys, int[] ids, String order) {
        if (!(parent instanceof android.view.ViewGroup)) return;
        try {
            final android.view.ViewGroup g = (android.view.ViewGroup) parent;
            java.util.LinkedHashMap<String, View> map = new java.util.LinkedHashMap<>();
            for (int i = 0; i < ids.length; i++) {
                View v = g.findViewById(ids[i]);
                if (v != null) map.put(keys[i], v);
            }
            if (map.isEmpty()) return;

            java.util.List<View> want = new java.util.ArrayList<>();
            java.util.HashSet<View> seen = new java.util.HashSet<>();
            if (order != null) {
                for (String s : order.split(",")) {
                    View v = map.get(s.trim());
                    if (v != null && seen.add(v)) want.add(v);
                }
            }
            for (String k : keys) {
                View v = map.get(k);
                if (v != null && seen.add(v)) want.add(v);
            }

            // ★ v4.53：先把「不在排序表里的孩子」记下来，重排后原样接回尾部 ——
            //   否则拖动棋盘大小时 liveApplyBoard 会连同 removeAllViews 一起把它丢掉
            //   （例如被挪出「着法行」、单独占一行的计时）。
            java.util.List<View> extra = new java.util.ArrayList<>();
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c != null && !seen.contains(c)) extra.add(c);
            }

            g.removeAllViews();
            for (int i = 0; i < want.size(); i++) {
                View v = want.get(i);
                g.addView(v);
                // 首项不留边距，其余补一点 —— 否则换了顺序会出现「顶上多一条缝」
                // 或「两项粘在一起」。
                android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
                    android.view.ViewGroup.MarginLayoutParams mp =
                            (android.view.ViewGroup.MarginLayoutParams) lp;
                    // 横排改 leftMargin、竖排改 topMargin —— ViewGroup 没有 getOrientation，
                    // 要判断成 LinearLayout 才拿得到方向。
                    int orient = (g instanceof android.widget.LinearLayout)
                            ? ((android.widget.LinearLayout) g).getOrientation()
                            : android.widget.LinearLayout.VERTICAL;
                    if (orient == android.widget.LinearLayout.HORIZONTAL) {
                        mp.leftMargin = (i == 0) ? 0 : Math.max(mp.leftMargin, dpOf(g.getContext(), 4));
                    } else {
                        mp.topMargin = (i == 0) ? 0 : Math.max(mp.topMargin, dpOf(g.getContext(), 4));
                    }
                    v.setLayoutParams(lp);
                }
            }
            // 排序表里没有的孩子原样接回尾部（保持它们相对彼此的先后）
            for (int i = 0; i < extra.size(); i++) g.addView(extra.get(i));
        } catch (Throwable ignored) {}
    }
    /**
     * ★ v4.50：把右侧竖列的间距与左边距统一。
     *
     * 为什么需要：链 / 识 / 自 是从按钮排（横排）搬过来的，它们在 panel.xml 里
     * 带着 layout_marginLeft=3dp（横排相邻间距）。搬到竖列后这个边距还在，
     * 于是这三颗的圆心整体偏 3dp，和 ✕ / ⠿ 不在一条竖线上（用户反馈过）。
     *
     * 做法：清掉所有子钮的左边距，重设上边距 —— 第一个 0、其余 4dp（= space_4），
     * 与 ✕ / ⠿ 原来的间距一致。只在右栏调用。
     */
    /** ★ v4.73：右栏按钮自适应等大 —— 数可见按钮 N，按面板固定内容高等分，
     *  统一 width=height，谁都不挤谁；排序/隐藏后 N 变化会自动重算。 */
    private static void normalizeRightCol(View root) {
        try {
            View col = root.findViewById(R.id.col_right);
            if (!(col instanceof android.view.ViewGroup)) return;
            android.view.ViewGroup g = (android.view.ViewGroup) col;
            float dens = root.getResources().getDisplayMetrics().density;
            int gap = Math.round(2f * dens);         // 右栏按钮间距
            // ① 数可见按钮
            int n = 0;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (g.getChildAt(i).getVisibility() != View.GONE) n++;
            }
            if (n <= 0) return;
            // ② 等大直径 = 可用高度去掉间距后均分；上限 30dp 下限 12dp
            int avail = panelContentH(root.getContext());
            int maxPx = Math.round(30f * dens);
            int minPx = Math.round(12f * dens);
            int d = (avail - (n - 1) * gap) / n;
            if (d > maxPx) d = maxPx;
            if (d < minPx) d = minPx;
            // ③ 统一大小 + 间距
            int visible = 0;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c.getVisibility() == View.GONE) continue;
                android.view.ViewGroup.LayoutParams lp = c.getLayoutParams();
                if (!(lp instanceof android.view.ViewGroup.MarginLayoutParams)) continue;
                android.view.ViewGroup.MarginLayoutParams mlp =
                        (android.view.ViewGroup.MarginLayoutParams) lp;
                lp.width = d;
                lp.height = d;
                mlp.leftMargin = 0;
                mlp.rightMargin = 0;
                mlp.topMargin = (visible == 0) ? 0 : gap;
                c.setLayoutParams(mlp);
                visible++;
            }
        } catch (Throwable ignored) {}
    }


    private static int dpOf(android.content.Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** 把某个键在顺序串里上移 / 下移一位（dir = -1 / +1），返回新的顺序串。 */
    public static String moveInOrder(String order, String def, String key, int dir) {
        try {
            java.util.List<String> list = new java.util.ArrayList<>();
            if (order != null && !order.isEmpty())
                for (String s : order.split(",")) if (!s.trim().isEmpty()) list.add(s.trim());
            // 补齐：默认顺序里有、但 order 里没有的键也加进来，保证不丢构件
            for (String s : def.split(",")) if (!list.contains(s)) list.add(s);
            int i = list.indexOf(key);
            if (i < 0) return order;
            int j = i + dir;
            if (j < 0 || j >= list.size()) return order;      // 到顶 / 到底就不动
            list.set(i, list.get(j));
            list.set(j, key);
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < list.size(); k++) {
                if (k > 0) sb.append(',');
                sb.append(list.get(k));
            }
            return sb.toString();
        } catch (Throwable t) {
            return order;
        }
    }
    // ---- v4.9：面板「信息类」构件的显隐（各 1=显示，0=隐藏）----
    public static final String K_UI_SHOW_BOARD = "ui_show_board";   // 小棋盘
    public static final String K_UI_SHOW_EVAL = "ui_show_eval";     // 势（形势评分条）
    public static final String K_UI_SHOW_MOVE = "ui_show_move";     // 本局计时
    public static final String K_UI_SHOW_LK = "ui_show_lk";         // ★ v4.72：连开按钮
    /** ★ v4.72：连开开关（绝杀挂起期间低频盯结算页、自动点「再来一局」）。 */
    public static final String K_LIANKAI = "liankai";
    public static final String K_UI_SHOW_STATUS = "ui_show_status"; // 状态行
    /** 自定义背景图路径（files 目录下）；空 = 用内置那张。 */
    public static final String K_UI_BG_IMG = "ui_bg_img";
    /** 启动动画：0=呼吸对焦(默认) 1=化形 2=拉幕 3=推入 4=扫光 5=关闭。 */
    public static final String K_UI_INTRO = "ui_intro_anim";

    // ---- v4.21：棋盘大小 / 位置微调（设置页「棋盘」分类）----
    /** 小棋盘的缩放百分比：50~150，100 = 原来的「撑满左栏」。 */
    public static final String K_UI_BOARD_SCALE = "ui_board_scale";
    public static final int K_UI_BOARD_SCALE_DEF = 100;
    /** 棋盘左右微调（dp，-60~+60）。正数向右。 */
    public static final String K_UI_BOARD_DX = "ui_board_dx";
    /** 棋盘上下微调（dp，-60~+60）。正数向下。 */
    public static final String K_UI_BOARD_DY = "ui_board_dy";
    /** ★ v4.65：左栏各构件独立 X/Y 偏移（dp，-60~+60），随意排版、避开背景人物眼睛。 */
    public static final String K_UI_BTN_DX = "ui_btn_dx";
    public static final String K_UI_BTN_DY = "ui_btn_dy";
    public static final String K_UI_EVAL_DX = "ui_eval_dx";
    public static final String K_UI_EVAL_DY = "ui_eval_dy";
    public static final String K_UI_MOVE_DX = "ui_move_dx";
    public static final String K_UI_MOVE_DY = "ui_move_dy";
    public static final String K_UI_REPLY_DX = "ui_reply_dx";
    public static final String K_UI_REPLY_DY = "ui_reply_dy";
    public static final String K_UI_STATUS_DX = "ui_status_dx";
    public static final String K_UI_STATUS_DY = "ui_status_dy";
    /** ★ v4.32：悬浮窗上下位置（dp，0~240）。0 = 贴屏幕最顶端，正数往下挪。 */
    public static final String K_UI_PANEL_DY = "ui_panel_dy";

    // ---- v4.27 设备自适应（探测结果落盘，设置页与静态渲染都要读）----
    /** 性能档：0 轻量 / 1 标准 / 2 强劲。首次启动自检后写入。 */
    public static final String K_DEV_TIER = "dev_tier";
    /** 是否为低内存设备（ActivityManager.isLowRamDevice）。 */
    public static final String K_DEV_LOWRAM = "dev_lowram";
    /** 设备探测的完整描述串（设置页展示用）。 */
    public static final String K_DEV_DESC = "dev_desc";

    /** 背景图最大解码宽度：面板就是 1272px 宽，再大纯浪费内存。 */
    private static final int BG_MAX_W = 1280;

    /** 读一个个性化项（int）。服务没起来时也读得到设置页存过的值（appCtx 兜底）。 */
    public static int uiInt(String key, int def) {
        XqService s = sInstance;
        android.content.Context c = (s != null) ? s : appCtx;
        if (c == null) return def;
        return c.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE).getInt(key, def);
    }

    /** 读/写个性化字符串项（背景图路径用）。静态版，设置页直接用。 */
    public static String spStr(String key, String def) {
        XqService s = sInstance;
        android.content.Context c = (s != null) ? s : appCtx;
        if (c == null) return def;
        return c.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
                .getString(key, def);
    }

    public static void spPutStr(String key, String val) {
        XqService s = sInstance;
        android.content.Context c = (s != null) ? s : appCtx;
        if (c == null) return;
        c.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
                .edit().putString(key, val).apply();
    }

    /** 读/写个性化整型项（静态版，设置页直接用）。 */
    public static void spPutInt(String key, int val) {
        XqService s = sInstance;
        android.content.Context c = (s != null) ? s : appCtx;
        if (c == null) return;
        c.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
                .edit().putInt(key, val).apply();
    }

    /** 应用级 Context：服务没起来时（无障碍还没开）设置页也能读写配置。 */
    public static volatile android.content.Context appCtx = null;

    /**
     * 把设置页的选择应用到面板上 —— **不用重启 App**。
     *
     * 背景板、卡底、棋盘位置都是「布局级」的改动，最省事也最不容易出错的做法是：
     * 先按新配置重建一次面板（removePanel + showPanel）。
     * 这样不用去逐个 patch 视图状态，也不会留下上一次配置的残留。
     */
    public static void applyPersonalization() {
        final XqService s = sInstance;
        if (s == null) return;
        s.ui.post(new Runnable() {
            public void run() {
                try {
                    s.flog("个性化已保存：棋盘=" + (s.sp(K_UI_BOARD_SIDE, 0) == 1 ? "右" : "左")
                            + " 卡底=" + (s.sp(K_UI_CTRL_CARD, 0) == 1 ? "开" : "关")
                            + " 背景板=" + (s.sp(K_UI_BG_ON, 1) == 1 ? "开" : "关")
                            + "(" + s.sp(K_UI_BG_ALPHA, K_UI_BG_ALPHA_DEF) + "%)"
                            + " 动画=" + s.sp(K_UI_INTRO, 0)
                            + (s.panel == null ? "（面板未开，下次显示生效）" : ""));
                    // ★ v4.48：设置页里必须能实时看到 —— 面板被收回过就现场建一个，
                    //   否则「改一项立刻生效」在面板没开时完全不生效（用户看到的只是日志）。
                    if (s.panel == null && settingsOpen) s.showPanel();
                    if (s.panel == null) return;
                    if (!previewAnimOnce) {
                        // ★ v4.63：原地应用，不再拆窗重建 —— remove+add 之间必有一帧
                        //   空档，这就是设置页每改一项闪一下的根因。
                        //   applyPersonalizationTo 幂等（v4.7 起注释明确），拖滑杆的
                        //   liveApplyBoard 一直走这条路，实测跟手不闪。
                        applyPersonalizationTo(s, s.panel);
                        s.fitMiniBoard(s.panel);
                        s.updateUi();
                    } else {
                        // 选入场方式：保留重建，让动画真播一遍给用户看
                        s.removePanel();
                        s.showPanel();
                    }
                    s.flog("个性化已应用：棋盘=" + (s.sp(K_UI_BOARD_SIDE, 0) == 1 ? "右" : "左")
                            + " 卡底=" + (s.sp(K_UI_CTRL_CARD, 0) == 1 ? "开" : "关")
                            + " 背景板=" + (s.sp(K_UI_BG_ON, 1) == 1 ? "开" : "关")
                            + "(" + s.sp(K_UI_BG_ALPHA, K_UI_BG_ALPHA_DEF) + "%)"
                            + " 动画=" + s.sp(K_UI_INTRO, 0));
                } catch (Throwable ignored) {}
            }
        });
    }

    /** 背景板当前是否生效（开关开 + 有图）。 */
    private boolean bgEnabled() {
        return sp(K_UI_BG_ON, DeviceProfile.defaultBgOff(sp(K_DEV_TIER, 1), devLowRamNow()) ? 0 : 1) == 1;
    }

    /**
     * 把背景板 / 卡底 / 棋盘位置应用到任意一份 panel.xml 视图上。
     *
     * v4.7 起改成**静态**：设置页要拿它渲染一份「预览面板」，让用户改一项就当场看到
     * 悬浮窗会变成什么样 —— 预览和真机悬浮窗走的是同一份代码、同一份配置，不会对不上。
     *
     * 必须在 addView 之前调用（它会重排子视图顺序和 margin）。
     */
    public static void applyPersonalizationTo(android.content.Context ctx, View p) {
        if (p == null || ctx == null) return;
        try {
            float dens = ctx.getResources().getDisplayMetrics().density;
            int gap = Math.round(4f * dens);
            boolean boardLeft = spIntOf(ctx, K_UI_BOARD_SIDE, 0) == 0;

            // ---- 0. 构件顺序（v4.11：占位可以自己移动）----
            //   必须在「卡底」「显隐」之前：那两步都遍历 col_left 的子视图，
            //   顺序先定下来，它们才作用在正确的一组视图上。
            // ★ 先归列：链 / 识 / ⚡ 按设置挪到「按钮排」或「右侧竖列」。
            //   必须排在顺序之前 —— 顺序是按「容器里现在有谁」来排的。
            // ★ v4.50：链 / 识 / 自 全搬到右侧竖列（用户要求）。
            //   它们原来在按钮排里带 3dp 的 layout_marginLeft（相邻间距），
            //   搬过来后这个边距会让圆心偏 3dp、和 ✕ / ⠿ 对不齐 ——
            //   所以下面 applyOrder 之后统一调一次 normalizeRightCol()。
            reparent(p, R.id.btn_link, R.id.col_right);
            reparent(p, R.id.btn_one, R.id.col_right);
            reparent(p, R.id.btn_light, R.id.col_right);

            applyOrder(p.findViewById(R.id.col_left), ORDER_COL_KEYS, ORDER_COL_IDS,
                    spStrOf(ctx, K_UI_ORDER_COL, ORDER_COL_DEF));
            applyOrder(p.findViewById(R.id.row_btn), ORDER_BTN_KEYS, ORDER_BTN_IDS,
                    spStrOf(ctx, K_UI_ORDER_BTN, ORDER_BTN_DEF));
            applyOrder(p.findViewById(R.id.col_right), ORDER_RIGHT_KEYS, ORDER_RIGHT_IDS,
                    spStrOf(ctx, K_UI_ORDER_RIGHT, ORDER_RIGHT_DEF));
            // ★ v4.50：右栏改成竖排 5 颗圆钮，统一间距与左边距 ——
            //   否则从按钮排搬来的 识 / 自 会自带 3dp 左偏，圆心对不齐。
            normalizeRightCol(p);

            // ---- 1. 棋盘位置（左 / 右） ----
            View rowV = p.findViewById(R.id.row_main);
            View colL = p.findViewById(R.id.col_left);
            View bd = p.findViewById(R.id.board_mini);
            View colR = p.findViewById(R.id.col_right);
            if (rowV instanceof android.view.ViewGroup && colL != null && bd != null && colR != null) {
                android.view.ViewGroup row = (android.view.ViewGroup) rowV;
                android.view.ViewGroup.LayoutParams lpB = bd.getLayoutParams();
                android.view.ViewGroup.LayoutParams lpL = colL.getLayoutParams();
                if (lpB instanceof android.view.ViewGroup.MarginLayoutParams)
                    ((android.view.ViewGroup.MarginLayoutParams) lpB).leftMargin = boardLeft ? 0 : gap;
                if (lpL instanceof android.view.ViewGroup.MarginLayoutParams)
                    ((android.view.ViewGroup.MarginLayoutParams) lpL).leftMargin = boardLeft ? gap : 0;
                row.removeAllViews();
                if (boardLeft) { row.addView(bd); row.addView(colL); row.addView(colR); }
                else { row.addView(colL); row.addView(bd); row.addView(colR); }
                if (lpB != null) bd.setLayoutParams(lpB);
                if (lpL != null) colL.setLayoutParams(lpL);
            }

            // ---- 2. 控制栏卡底 ----
            // 不加卡底（默认）：控制栏本来就是透明的，只有按钮不透明 → 人像最透
            // 加卡底：整块盖一层半透明卡，文字对比度更好，但人像被遮更多
            boolean card = spIntOf(ctx, K_UI_CTRL_CARD, 0) == 1;
            if (colL instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) colL;
                int n = g.getChildCount();
                for (int i = 0; i < n; i++) {
                    View c = g.getChildAt(i);
                    if (c == null) continue;
                    if (card) {
                        c.setBackgroundResource(R.drawable.bg_ctrl_card);
                        int pv = Math.round(5f * dens), ph = Math.round(6f * dens);
                        c.setPadding(ph, pv, ph, pv);
                    } else {
                        c.setBackground(null);
                        c.setPadding(0, 0, 0, 0);
                    }
                }
            }

            // ---- 3. 背景板 ----
            //
            // ★★ 这里有个必须记住的坑（v4.7 实测踩到）：
            //   bg_cover / bg_scrim 是 match_parent，而面板本身是 wrap_content。
            //   测量时它们拿到的是 UNSPECIFIED 高度规格 → **ImageView 会按图片原始尺寸
            //   把自己撑开**，于是整个面板被撑到图片那么高（截图里人物图占了面板下方一大块）。
            //   GONE 的视图不参与测量，所以正确顺序是：
            //     ① 先 GONE（面板高度只由 row_main 决定）
            //     ② 等布局完成，再按 row_main 的**实际高度**把背景层挂上去（syncCoverSize）
            View cover = p.findViewById(R.id.bg_cover);
            View scrim = p.findViewById(R.id.bg_scrim);
            // ★ v4.27：默认值不再固定为 1 —— 弱机 / 低内存机默认关（见 defaultBgOnOf）。
            //   spIntOf 的 def 只在「用户从未设置过」时生效，所以设置过的人不受影响。
            boolean on = spIntOf(ctx, K_UI_BG_ON, defaultBgOnOf(ctx)) == 1;
            if (cover instanceof android.widget.ImageView) {
                android.widget.ImageView iv = (android.widget.ImageView) cover;
                Bitmap b = on ? loadCoverBitmapOf(ctx) : null;
                if (b != null) iv.setImageBitmap(b);
                else iv.setImageDrawable(null);
            }
            if (cover != null) cover.setVisibility(View.GONE);
            if (scrim != null) scrim.setVisibility(View.GONE);
            if (rowV != null) rowV.setBackgroundResource(
                    on ? R.drawable.panel_bg_glass : R.drawable.panel_bg);
            if (on) {
                final View fp = p;
                p.post(new Runnable() { public void run() { syncCoverSize(fp); } });
            }

            // ★ v4.52：人物与文字方案 + 文字优化（遮罩形状 / 人物轮廓光 / 文字投影）
            applyBgStyleTo(ctx, p);
            applyTextStyleTo(ctx, p);

            // ---- 4. 悬浮窗各构件的显隐（v4.9：每一项都能在设置里单独关掉）----
            //    ★ 用 INVISIBLE 而非 GONE：GONE 会让它从布局消失、其余构件跟着移位，
            //      INVISIBLE 保留占位，位置感和用户熟悉的样子都不变。
            hideIfOff(p, R.id.btn_link, K_UI_SHOW_LINK);
            hideIfOff(p, R.id.btn_one, K_UI_SHOW_ONE);
            hideIfOff(p, R.id.btn_light, K_UI_SHOW_LIGHT);
            hideIfOff(p, R.id.btn_close, K_UI_SHOW_CLOSE);
            hideIfOff(p, R.id.tv_title, K_UI_SHOW_GRIP);
            hideIfOff(p, R.id.btn_liankai, K_UI_SHOW_LK);
            // v4.50：btn_collapse 已从 panel.xml 删除，不再需要显隐控制
            hideIfOff(p, R.id.board_mini, K_UI_SHOW_BOARD);
            hideIfOff(p, R.id.row_eval, K_UI_SHOW_EVAL);
            hideIfOff(p, R.id.row_move, K_UI_SHOW_MOVE);
            // ★ v4.53：计时可能被挪出着法行、单独占一行，所以跟着「着法」一起显隐
            hideIfOff(p, R.id.tv_timer, K_UI_SHOW_MOVE);
            hideIfOff(p, R.id.tv_status, K_UI_SHOW_STATUS);

            // ---- 5. 棋盘大小 / 位置微调（v4.21）----
            applyBoardOffsetTo(ctx, p);
            // ---- 6. 左栏构件自由排版（v4.65）----
            applyWidgetOffsets(ctx, p);


            Log.i(TAG, "个性化：棋盘=" + (boardLeft ? "左" : "右")
                    + " 卡底=" + (card ? "开" : "关") + " 背景板=" + (on ? "开" : "关"));
        } catch (Throwable t) {
            Log.w(TAG, "applyPersonalizationTo failed", t);
        }
    }

    /**
     * ★ v4.21：把「棋盘位置微调」应用到任意一份面板视图上（设置页预览与真机共用）。
     *
     * 为什么用 translation 而不是改 margin：
     *   margin 会改变布局 → 触发 col_left 的 OnLayoutChange → fitMiniBoard 再算一次
     *   → 两个尺寸互相影响可能来回震荡；translation 只影响绘制、完全不碰布局，
     *   绝不会引起重排，也就绝不会打架。
     * 代价是棋盘原来的位置会留出空白 —— 但「微调」本来就不该改变面板尺寸，
     * 用户要的是「这块棋盘在面板里挪一挪」，而不是「把面板撑大」。
     */
    public static void applyBoardOffsetTo(android.content.Context ctx, View p) {
        if (p == null || ctx == null) return;
        try {
            View bd = p.findViewById(R.id.board_mini);
            if (bd == null) return;
            float dens = ctx.getResources().getDisplayMetrics().density;
            int dx = spIntOf(ctx, K_UI_BOARD_DX, 0);
            int dy = spIntOf(ctx, K_UI_BOARD_DY, 0);
            if (dx < -60) dx = -60; else if (dx > 60) dx = 60;
            if (dy < -60) dy = -60; else if (dy > 60) dy = 60;
            bd.setTranslationX(dx * dens);
            bd.setTranslationY(dy * dens);
        } catch (Throwable ignored) {}
    }

    /** ★ v4.65：左栏各构件独立偏移（随意排版，避开背景人物眼睛）。translation 实现。 */
    public static void applyWidgetOffsets(android.content.Context ctx, View p) {
        if (p == null || ctx == null) return;
        try {
            float dens = ctx.getResources().getDisplayMetrics().density;
            int[][] spec = {
                    {R.id.row_btn, spIntOf(ctx, K_UI_BTN_DX, 0), spIntOf(ctx, K_UI_BTN_DY, 0)},
                    {R.id.row_eval, spIntOf(ctx, K_UI_EVAL_DX, 0), spIntOf(ctx, K_UI_EVAL_DY, 0)},
                    {R.id.row_move, spIntOf(ctx, K_UI_MOVE_DX, 0), spIntOf(ctx, K_UI_MOVE_DY, 0)},
                    {R.id.tv_status, spIntOf(ctx, K_UI_STATUS_DX, 0), spIntOf(ctx, K_UI_STATUS_DY, 0)},
            };
            for (int i = 0; i < spec.length; i++) {
                View v = p.findViewById(spec[i][0]);
                if (v == null) continue;
                int dx = spec[i][1]; if (dx < -60) dx = -60; else if (dx > 60) dx = 60;
                int dy = spec[i][2]; if (dy < -60) dy = -60; else if (dy > 60) dy = 60;
                v.setTranslationX(dx * dens);
                v.setTranslationY(dy * dens);
            }
        } catch (Throwable ignored) {}
    }

    /** 免费版：不显示分析区。 */
    private static String analysisText(PikafishEngine.Move best, String cn, char sideToMove) { return ""; }

    /**
     * v4.9：按设置把某个面板控件显示 / 隐藏。
     *
     * 用 INVISIBLE 而不是 GONE：GONE 会让它从布局里消失，按钮排／右栏会跟着移位、
     * 按钮间距变样；INVISIBLE 保留占位，布局和用户熟悉的按钮位置都不变。
     */
    private static void hideIfOff(View p, int id, String key) {
        hideIfOffDef(p, id, key, 1);
    }

    /**
     * 同 hideIfOff，但可指定「没设置过时的默认值」。
     * ★ 分析区必须走这个重载：它的默认是**折叠**（K_ANA=0），
     *   而其它构件默认是显示。默认值传错会导致「没设置过时预览显示得跟真面板不一样」。
     */
    private static void hideIfOffDef(View p, int id, String key, int def) {
        try {
            View v = p.findViewById(id);
            if (v == null) return;
            boolean show = spIntOf(p.getContext(), key, def) == 1;
            // ★ v4.18：用 GONE 而不是 INVISIBLE。
            //   用户要求「隐藏了就让出占位，再次显示时排到空着的位置」——
            //   INVISIBLE 会保留占位，那个位置永远空不出来，再显示出还是回到原位。
            v.setVisibility(show ? View.VISIBLE : View.GONE);
        } catch (Throwable ignored) {}
    }

    /** v4.18：把某个键移到顺序串末尾（=「排到队尾 / 空出来的位置」）。 */
    public static String orderAppend(String order, String def, String key) {
        String s = orderRemove(order, key);
        if (s == null || s.isEmpty()) return key;
        return s + "," + key;
    }

    /** v4.18：把某个键从顺序串里删掉（隐藏时调用 —— 它不该再占着顺序）。 */
    public static String orderRemove(String order, String key) {
        try {
            StringBuilder sb = new StringBuilder();
            if (order != null) {
                for (String s : order.split(",")) {
                    String t = s.trim();
                    if (t.isEmpty() || t.equals(key)) continue;
                    if (sb.length() > 0) sb.append(',');
                    sb.append(t);
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return order;
        }
    }

    /** 背景图不透明度（0~100）。设置页预览和真机悬浮窗共用。 */
    public static int coverAlphaPct(android.content.Context c) {
        int v = spIntOf(c, K_UI_BG_ALPHA, K_UI_BG_ALPHA_DEF);
        return Math.max(0, Math.min(100, v));
    }

    /** 把背景图 / 压暗层设到指定透明度（预览拖滑杆时实时调用，不重建视图）。 */
    public static void applyCoverAlpha(View p, float alpha) {
        if (p == null) return;
        try {
            View cover = p.findViewById(R.id.bg_cover);
            View scrim = p.findViewById(R.id.bg_scrim);
            int style = spIntOf(p.getContext(), K_UI_BG_STYLE, K_UI_BG_STYLE_DEF);
            if (cover != null) cover.setAlpha(alpha);
            // ★ v4.52 解耦（关键）：方案 0 = 老行为，遮罩跟着浓度一起缩放；
            //   方案 1~5 遮罩强度固定由样式本身决定，**不再**被浓度拉淡。
            //   原来自始至终两者同 alpha —— 那就是「人物一亮文字就糊」的死结。
            if (scrim != null) scrim.setAlpha(style == 0 ? alpha : 1f);
        } catch (Throwable ignored) {}
    }

    /**
     * ★ v4.52：把「人物与文字方案」应用到面板上。
     *
     * 要解决的问题：原来「人物有多亮」和「压暗层有多浓」是同一个滑杆
     * （applyCoverAlpha 同时改 cover 和 scrim 的 alpha），
     * 于是人物一亮文字就糊、文字一清人物就灰，只能二选一。
     * 这一版把两者拆开：**遮罩形状由方案决定、浓度滑杆只管人物**。
     *
     *   0 现状        —— 遮罩横跨整块面板（老样子）
     *   1 投影提亮    —— 遮罩恢复满强度；人物可拉到 90%+（配文字投影）
     *   2 局部遮罩    —— 遮罩只在左半（文字栏），右半完全不加
     *   3 文字软底    —— 遮罩更实但衰减更靠右（60%），边界更柔
     *   4 轮廓光暗角  —— 2 的遮罩 + 人物加饱和对比 + 四周暗角
     *   5 综合        —— 3 的遮罩 + 人物加饱和对比 + 四周暗角
     */
    public static void applyBgStyleTo(android.content.Context ctx, View p) {
        if (ctx == null || p == null) return;
        try {
            int style = spIntOf(ctx, K_UI_BG_STYLE, K_UI_BG_STYLE_DEF);
            View scrim = p.findViewById(R.id.bg_scrim);
            View vig = p.findViewById(R.id.bg_vignette);
            View cover = p.findViewById(R.id.bg_cover);

            if (scrim != null) {
                int res = R.drawable.bg_scrim;
                if (style == 2 || style == 4) res = R.drawable.bg_scrim_left;
                else if (style == 3 || style == 5) res = R.drawable.bg_scrim_soft;
                scrim.setBackgroundResource(res);
            }

            // 轮廓光：加饱和 + 轻微提对比，让人物从「灰蒙蒙一层」里立起来。
            // 用 ColorMatrix 一次性搞定，不额外分配位图。
            if (cover instanceof android.widget.ImageView) {
                android.widget.ImageView iv = (android.widget.ImageView) cover;
                if (style == 4 || style == 5) {
                    android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
                    cm.setSaturation(1.18f);
                    float c = 1.08f;
                    float t = 128f * (1f - c);        // 对比度的平移量
                    float[] m = cm.getArray();
                    for (int i = 0; i < 3; i++) m[i * 5 + 4] += t;
                    iv.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
                } else {
                    iv.clearColorFilter();
                }
            }

            if (vig != null)
                vig.setVisibility((style == 4 || style == 5) ? View.VISIBLE : View.GONE);
        } catch (Throwable ignored) {}
    }

    /**
     * ★ v4.52：文字优化 —— 投影 + 主次分明。
     *
     * 投影不只是好看：它是「人物可以提亮」的前提 ——
     * 文字自己带对比度之后，就不必再靠压暗背景来保证可读，两者才解耦。
     *
     * 主次分明：着法提到 15sp 纯白（一眼能看的主信息），
     * 状态行降到最次灰（不出问题时不该抢注意力）。
     */
    /**
     * ★ v4.52：文字优化 —— 投影 + 主次分明。
     *
     * 投影不只是好看：它是「人物可以提亮」的前提 ——
     * 文字自己带对比度之后，就不必再靠压暗背景来保证可读，两者才解耦。
     *
     * 主次分明：着法提到 15sp 纯白（一眼能看的主信息），
     * 状态行降到最次灰（不出问题时不该抢注意力）。
     *
     * ★ v4.53：数字等宽、计时并入着法行，也都能单独开关。
     */
    public static void applyTextStyleTo(android.content.Context ctx, View p) {
        if (ctx == null || p == null) return;
        try {
            boolean shadow = spIntOf(ctx, K_UI_TXT_SHADOW, 1) == 1;
            boolean hier = spIntOf(ctx, K_UI_TXT_HIER, 1) == 1;
            boolean mono = spIntOf(ctx, K_UI_TXT_MONO, 1) == 1;
            boolean compact = spIntOf(ctx, K_UI_TXT_COMPACT, 1) == 1;
            float dens = ctx.getResources().getDisplayMetrics().density;
            float r = 3.4f * dens, dy = 1.1f * dens;

            int[] ids = {R.id.tv_timer, R.id.tv_status};
            for (int i = 0; i < ids.length; i++) {
                View v = p.findViewById(ids[i]);
                if (!(v instanceof android.widget.TextView)) continue;
                android.widget.TextView t = (android.widget.TextView) v;
                if (shadow) t.setShadowLayer(r, 0f, dy, 0xE6000000);
                else t.setShadowLayer(0f, 0f, 0f, 0);
            }

            android.widget.TextView st = p.findViewById(R.id.tv_status);
            if (hier) {
                if (st != null) st.setTextColor(0xFF737F8C);
            } else {
                if (st != null) st.setTextColor(0xFFA8B4C0);
            }

            // ★ v4.53：数字等宽（计时 / 评分跳数时不左右抖）
            android.widget.TextView tm = p.findViewById(R.id.tv_timer);
            android.graphics.Typeface tf = mono
                    ? android.graphics.Typeface.MONOSPACE
                    : android.graphics.Typeface.DEFAULT_BOLD;
            if (tm != null) tm.setTypeface(tf);

            // ★ v4.53：计时并入着法行 / 让它单独占一行
            applyTimerRow(p, compact, dens);
        } catch (Throwable ignored) {}
    }

    /**
     * ★ v4.53：把「计时」挂进「着法行」，或让它单独占一行。
     *
     * 只改父子关系、不动计时状态，所以幂等；面板每次重建都会重新套用一次。
     */
    static void applyTimerRow(View p, boolean compact, float dens) {
        if (p == null) return;
        try {
            android.widget.TextView t = p.findViewById(R.id.tv_timer);
            android.widget.LinearLayout row = p.findViewById(R.id.row_move);
            android.view.ViewGroup col = p.findViewById(R.id.col_left);
            if (t == null || row == null || col == null) return;
            android.view.ViewGroup cur = (android.view.ViewGroup) t.getParent();
            if (compact) {
                if (cur == row) return;
                if (cur != null) cur.removeView(t);
                android.widget.LinearLayout.LayoutParams lp =
                        new android.widget.LinearLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.leftMargin = (int) (6 * dens);
                row.addView(t, lp);
            } else {
                if (cur != null) cur.removeView(t);
                android.widget.LinearLayout.LayoutParams lp =
                        new android.widget.LinearLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = (int) (2 * dens);
                int idx = col.indexOfChild(row) + 1;
                col.addView(t, idx, lp);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 按 row_main 的实际高度把背景层挂上去（v4.7）。
     *
     * 为什么要单独一步：背景层是 match_parent，直接在测量阶段就可见的话，
     * ImageView 会因为拿到 UNSPECIFIED 高度规格而按图片原始尺寸撑开整个面板。
     * 所以先 GONE，布局完成后由这里量出 row_main 的真实高度，再把两个背景层
     * 设成同样的固定高度并显示 —— 幂等，重复调用没问题。
     */
    public static void syncCoverSize(View p) {
        if (p == null) return;
        try {
            View row = p.findViewById(R.id.row_main);
            if (row == null) return;
            int h = row.getHeight();
            if (h <= 0) h = row.getMeasuredHeight();
            if (h <= 0) return;
            View cover = p.findViewById(R.id.bg_cover);
            View scrim = p.findViewById(R.id.bg_scrim);
            View vig = p.findViewById(R.id.bg_vignette);
            View[] vs = {cover, scrim, vig};
            for (int i = 0; i < vs.length; i++) {
                View v = vs[i];
                if (v == null) continue;
                android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (lp != null && lp.height != h) {
                    lp.height = h;
                    v.setLayoutParams(lp);
                }
                // 暗角层的显隐由「人物与文字方案」决定，这里不碰
                if (i < 2) v.setVisibility(View.VISIBLE);
            }
        } catch (Throwable ignored) {}
    }

    /** 静态版读 int 配置：任意 Context 都能用（设置页在无障碍没开时也要读得到）。 */
    public static int spIntOf(android.content.Context c, String k, int def) {
        if (c == null) return def;
        return c.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE).getInt(k, def);
    }

    /** v4.27：设备性能档（0 轻量 / 1 标准 / 2 强劲）。没探测过时按 1 处理，最保险。 */
    public static int devTierOf(android.content.Context c) {
        return spIntOf(c, K_DEV_TIER, 1);
    }

    /** v4.27：是否低内存设备。 */
    public static boolean devLowRamOf(android.content.Context c) {
        return spIntOf(c, K_DEV_LOWRAM, 0) == 1;
    }

    /** v4.27：背景板的默认开关值 —— 弱机 / 低内存机默认关（省一次解码 + 少一层合成）。 */
    public static int defaultBgOnOf(android.content.Context c) {
        return DeviceProfile.defaultBgOff(devTierOf(c), devLowRamOf(c)) ? 0 : 1;
    }

    // ============================================================
    //  v4.28：配置快照（保存 / 恢复）
    //
    //  为什么要有：设置是「改一项立刻存一项」的，本身就能持久化。但用户实际要的是
    //  「这整套我调好了，存下来，以后随时能回到这套」—— 这跟逐项持久化是两件事。
    //  存的是 ConfigProfile 里那份键清单（29 个整型 + 4 个字符串），
    //  dev_*（硬件探测结果）和界面位置不进去，原因见 ConfigProfile.isKnown 的注释。
    // ============================================================

    /** 配置快照正文（ConfigProfile 格式的文本）。 */
    public static final String K_PROFILE = "cfg_profile";
    /** 快照保存时间（毫秒时间戳）。 */
    public static final String K_PROFILE_AT = "cfg_profile_at";

    /** ★ v4.28：引擎自报名字的进程内缓存（主界面拿不到服务实例时读它）。 */
    private static volatile String sEngineNameCache = "";

    public static void setEngineNameCache(String n) {
        if (n != null && n.length() > 0) sEngineNameCache = n;
    }

    /** 引擎名；没起来过就返回空串（调用方自己兜底显示）。 */
    public static String engineNameCache() {
        return sEngineNameCache == null ? "" : sEngineNameCache;
    }

    /**
     * ★ 保存当前配置为快照。
     *
     * @return 实际保存的项数（0 表示什么都没存，调用方应提示失败）
     */
    public static int saveProfile() {
        XqService s = sInstance;
        android.content.Context c = (s != null) ? s : appCtx;
        if (c == null) return 0;
        try {
            android.content.SharedPreferences sp = c.getSharedPreferences(
                    PREF, android.content.Context.MODE_PRIVATE);
            java.util.Map<String, String> kv = new java.util.LinkedHashMap<String, String>();
            for (String k : ConfigProfile.KEYS_INT) {
                kv.put(k, String.valueOf(sp.getInt(k, defaultIntOf(k))));
            }
            for (String k : ConfigProfile.KEYS_STR) {
                String v = sp.getString(k, defaultStrOf(k));
                kv.put(k, v == null ? "" : v);
            }
            String text = ConfigProfile.encode(kv);
            sp.edit().putString(K_PROFILE, text)
                     .putLong(K_PROFILE_AT, System.currentTimeMillis())
                     .apply();
            if (s != null) s.flog("配置已保存，共 " + ConfigProfile.count(text) + " 项");
            return ConfigProfile.count(text);
        } catch (Throwable t) {
            if (s != null) s.flog("配置保存失败: " + t);
            return 0;
        }
    }

    /**
     * ★ 恢复到上次保存的快照。
     *
     * <p>先写盘再重建界面 —— 顺序不能反，否则新界面读到的还是旧值。
     *
     * @return 实际恢复的项数（0 表示没有可用的快照）
     */
    public static int restoreProfile() {
        XqService s = sInstance;
        android.content.Context c = (s != null) ? s : appCtx;
        if (c == null) return 0;
        try {
            android.content.SharedPreferences sp = c.getSharedPreferences(
                    PREF, android.content.Context.MODE_PRIVATE);
            String text = sp.getString(K_PROFILE, "");
            if (text == null || !ConfigProfile.isValid(text)) return 0;
            android.content.SharedPreferences.Editor e = sp.edit();
            int n = 0;
            for (java.util.Map.Entry<String, Integer> en : ConfigProfile.decodeInts(text).entrySet()) {
                e.putInt(en.getKey(), en.getValue());
                n++;
            }
            for (java.util.Map.Entry<String, String> en : ConfigProfile.decodeStrs(text).entrySet()) {
                e.putString(en.getKey(), en.getValue());
                n++;
            }
            e.apply();
            // 引擎相关项要立刻生效（速度 / 分析区 / 前台校验 / 执子）
            applyEngineOptions();
            if (s != null) s.flog("配置已恢复，共 " + n + " 项");
            return n;
        } catch (Throwable t) {
            if (s != null) s.flog("配置恢复失败: " + t);
            return 0;
        }
    }

    /** 快照保存时间；没存过返回 0。 */
    public static long profileSavedAt() {
        XqService s = sInstance;
        android.content.Context c = (s != null) ? s : appCtx;
        if (c == null) return 0L;
        try {
            return c.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
                    .getLong(K_PROFILE_AT, 0L);
        } catch (Throwable t) { return 0L; }
    }

    /** 快照项数；没有快照返回 0。 */
    public static int profileCount() {
        XqService s = sInstance;
        android.content.Context c = (s != null) ? s : appCtx;
        if (c == null) return 0;
        try {
            String t = c.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
                    .getString(K_PROFILE, "");
            return ConfigProfile.count(t);
        } catch (Throwable e) { return 0; }
    }

    /**
     * 各项的默认值 —— 保存快照时若某项从未设置过，存默认值而不是 0。
     * 存 0 会在恢复时把「默认开启」的项变成「关闭」，属于静默改用户设置。
     */
    private static int defaultIntOf(String key) {
        if (key.startsWith("ui_show_")) return 1;            // 构件默认都显示
        if (K_UI_BOARD_SCALE.equals(key)) return K_UI_BOARD_SCALE_DEF;
        if (K_UI_BG_ON.equals(key)) return 1;
        if (K_UI_BG_ALPHA.equals(key)) return K_UI_BG_ALPHA_DEF;
        if (K_UI_COL_LINK.equals(key)) return 1;             // 链默认在右栏
        if (K_SPEED.equals(key)) return Speed.DEFAULT;
        if (K_FGMODE.equals(key)) return FG_LOOSE;
        if (K_UI_SIDE.equals(key)) return 0;
        if (K_ANA.equals(key)) return 0;
        return 0;
    }

    private static String defaultStrOf(String key) {
        if (K_UI_ORDER_COL.equals(key)) return ORDER_COL_DEF;
        if (K_UI_ORDER_BTN.equals(key)) return ORDER_BTN_DEF;
        if (K_UI_ORDER_RIGHT.equals(key)) return ORDER_RIGHT_DEF;
        if (K_UI_BG_IMG.equals(key)) return "";
        return "";
    }

    /** 静态版读 String 配置。 */
    public static String spStrOf(android.content.Context c, String k, String def) {
        if (c == null) return def;
        return c.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE).getString(k, def);
    }

    // ============================================================
    //  v4.8 引擎类选项（从面板按钮挪进设置页）
    //
    //  挪走的四个：执子(红) / 分析区(析) / 走棋速度 / 前台校验。
    //  判断依据：它们都是「一次设定、长期不变」的偏好，不占对局中的操作流；
    //  留在面板上白白占掉一整行（4 个速度 pill）和两个圆按钮，挡住棋盘。
    // ============================================================

    /** 执子偏好：0=自动识别（默认）1=执红 2=执黑。 */
    public static final String K_UI_SIDE = "ui_side";

    public static int getSidePref(android.content.Context c) { return spIntOf(c, K_UI_SIDE, 0); }

    public static boolean getAnaPref(android.content.Context c) { return spIntOf(c, K_ANA, 0) == 1; }

    public static int getSpeedPref(android.content.Context c) {
        return Speed.clamp(spIntOf(c, K_SPEED, Speed.DEFAULT));
    }

    public static int getFgModePref(android.content.Context c) {
        int v = spIntOf(c, K_FGMODE, FG_LOOSE);
        return (v < FG_STRICT || v > FG_OFF) ? FG_LOOSE : v;
    }

    /** 设置页写一个整型偏好，并立刻应用到正在跑的悬浮窗。 */
    public static void putEnginePref(android.content.Context c, String k, int v) {
        if (c == null) return;
        c.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE).edit().putInt(k, v).apply();
        applyEngineOptions();
    }

    /**
     * 把设置页改的「执子 / 分析区 / 走棋速度 / 前台校验」应用到正在运行的悬浮窗。
     *
     * 无障碍服务没起来时是空操作 —— 配置已经写进 prefs，下次 showPanel() 会读到新值。
     * 和 applyPersonalization() 的区别：那一个是「布局级」要重建面板，这一组只要改状态。
     */
    public static void applyEngineOptions() {
        final XqService s = sInstance;
        if (s == null) return;
        s.ui.post(new Runnable() {
            public void run() {
                try {
                    int sd = s.sp(K_UI_SIDE, 0);
                    s.sideAuto = (sd == 0);
                    if (sd == 1) s.mySide = 'w';
                    else if (sd == 2) s.mySide = 'b';
                    s.speedLevel = Speed.clamp(s.sp(K_SPEED, Speed.DEFAULT));
                    int fg = s.sp(K_FGMODE, FG_LOOSE);
                    s.fgMode = (fg < FG_STRICT || fg > FG_OFF) ? FG_LOOSE : fg;
                    s.updateUi();
                    s.setStatus("设置已应用：执子=" + (s.sideAuto ? "自动" : (s.mySide == 'w' ? "执红" : "执黑"))
                            + "　速度=" + Speed.NAME[s.speedLevel]);
                    s.flog("引擎选项已应用 side=" + sd + " speed=" + s.speedLevel
                            + " fg=" + s.fgMode);
                } catch (Throwable ignored) {}
            }
        });
    }

    /** 背景图缓存：设置页拖不透明度滑杆时会反复取图，不能每次都 decode。 */
    private static Bitmap sCoverCache;
    private static String sCoverCacheKey;

    /**
     * 静态版背景图（用户选的优先，没有就用内置那张）。
     *
     * ★ 缓存键 = 图片路径，换图后路径变了自然重新解码，不用手动清缓存。
     *   缓存出来的 Bitmap 交给 ImageView，**不要 recycle**（两份视图共用同一张）。
     */
    public static Bitmap loadCoverBitmapOf(android.content.Context c) {
        if (c == null) return null;
        try {
            String path = spStrOf(c, K_UI_BG_IMG, "");
            String key = (path == null || path.isEmpty()) ? "@default" : path;
            if (sCoverCache != null && !sCoverCache.isRecycled() && key.equals(sCoverCacheKey))
                return sCoverCache;
            Bitmap b = null;
            if (!"@default".equals(key)) {
                File f = new File(key);
                if (f.exists() && f.length() > 0) b = decodeSampled(f.getAbsolutePath(), BG_MAX_W);
            }
            if (b == null)
                b = android.graphics.BitmapFactory.decodeResource(c.getResources(), R.drawable.cover_default);
            if (b != null) {
                sCoverCache = b;
                sCoverCacheKey = key;
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 载入背景图（用户选的优先，没有就用内置那张）。
     *
     * 按 BG_MAX_W 降采样解码：用户选的照片动辄 4000px 宽，
     * 直接 decodeFile 会吃掉几十 MB 内存 —— 面板上的悬浮窗，不值得。
     */
    private Bitmap loadCoverBitmap() {
        try {
            String path = spStr(K_UI_BG_IMG, "");
            if (path != null && !path.isEmpty()) {
                File f = new File(path);
                if (f.exists() && f.length() > 0) {
                    Bitmap b = decodeSampled(f.getAbsolutePath(), BG_MAX_W);
                    if (b != null) return b;
                }
            }
        } catch (Throwable ignored) {}
        try {
            return android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.cover_default);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按目标宽度降采样解码（2 的幂次 inSampleSize）。 */
    private static Bitmap decodeSampled(String path, int maxW) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= maxW) sample *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.RGB_565;   // 背景图不需要 alpha，省一半
            return android.graphics.BitmapFactory.decodeFile(path, o2);
        } catch (Throwable t) {
            return null;
        }
    }
    /**
     * v4.7 个性化入场动画（设置页可选）。
     *
     * 共同思路：**先只出现人物封面**（面板内容全透明），停一下，再让封面淡到设定的
     * 背景不透明度、同时把棋盘/控制栏淡进来。观感是「人物沉到玻璃后面」，
     * 而不是「换了一张图」。这一段是用户明确要的效果。
     *
     * 0=呼吸对焦(默认) 1=化形 2=拉幕 3=推入 5=关闭（=沿用原来的 animateIn）
     */
    private void animatePersonalIn() {
        if (panel == null) { return; }
        // ★ v4.47：设置页开着时不播入场动画。
        //   那一页每改一项都要重建面板一次，动画跟着重播 → 屏幕一直闪，
        //   而且用户调的是静态外观（大小/位置/显隐），不是想看动画。
        //   直接落到动画终态：内容全显、封面按设定浓度、无位移无缩放。
        // ★ v4.51：设置页里默认不播（避免一直闪）；但用户点「入场方式」时
        //   previewAnimOnce 置位 —— 那一次要真播，否则没法挑。
        if (settingsOpen && !previewAnimOnce) {
            snapPersonalIn();
            return;
        }
        previewAnimOnce = false;
        final View cover = panel.findViewById(R.id.bg_cover);
        final View scrim = panel.findViewById(R.id.bg_scrim);
        final View row = panel.findViewById(R.id.row_main);
        int anim = sp(K_UI_INTRO, 0);
        if (!bgEnabled() || cover == null || row == null || anim == 5) {
            animateIn(panel);      // 关掉背景板 / 没上背景 → 用原来的入场
            return;
        }
        final float target = Math.max(0, Math.min(100, sp(K_UI_BG_ALPHA, K_UI_BG_ALPHA_DEF))) / 100f;
        try {
            row.setAlpha(0f);
            if (scrim != null) scrim.setAlpha(0f);
            cover.setAlpha(0f);
            panel.setAlpha(1f);
            panel.setTranslationY(0f);
            cover.setTranslationX(0f);
            cover.setScaleX(1f);
            cover.setScaleY(1f);

            // ---- 第一段：人物入场（只有封面可见） ----
            final long riseMs = 440L;
            switch (anim) {
                case 2:   // 拉幕：封面横向从中间拉开
                    cover.setScaleX(0.12f);
                    cover.animate().alpha(1f).scaleX(1f).setDuration(420L)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.3f))
                            .start();
                    break;
                case 3:   // 推入：封面从右侧滑入
                    cover.setTranslationX(dp(90));
                    cover.animate().alpha(1f).translationX(0f).setDuration(380L)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.3f))
                            .start();
                    break;
                case 1:   // 化形：封面从上方滑下
                    cover.setTranslationY(-dp(22));
                    cover.animate().alpha(1f).translationY(0f).setDuration(320L)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                            .start();
                    break;
                default:  // 0 呼吸对焦：1.06 → 1.0 轻推近
                    cover.setScaleX(1.06f);
                    cover.setScaleY(1.06f);
                    cover.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(riseMs)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                            .start();
                    break;
            }

            // ---- 第二段：人物「沉到玻璃后面」，内容淡入 ----
            panel.postDelayed(new Runnable() {
                public void run() {
                    if (panel == null) return;
                    try {
                        cover.animate().alpha(target).setDuration(680L)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.2f))
                                .start();
                        if (scrim != null)
                            scrim.animate().alpha(target).setDuration(680L).start();
                        row.animate().alpha(1f).setDuration(560L).start();
                        flog("个性化入场动画已播放（不透明度 " + (int) (target * 100) + "%）");
                    } catch (Throwable ignored) {}
                }
            }, 900L);
        } catch (Throwable t) {
            try { row.setAlpha(1f); if (scrim != null) scrim.setAlpha(1f); } catch (Throwable ignored) {}
        }
    }
    /** ★ v4.47：把面板直接摆到「入场动画播完之后」的样子（不播动画）。
     *  设置页里每次改设置都重建面板，用它收尾才不闪。 */
    private void snapPersonalIn() {
        if (panel == null) return;
        try {
            final View cover = panel.findViewById(R.id.bg_cover);
            final View scrim = panel.findViewById(R.id.bg_scrim);
            final View row = panel.findViewById(R.id.row_main);
            float target = Math.max(0, Math.min(100,
                    sp(K_UI_BG_ALPHA, K_UI_BG_ALPHA_DEF))) / 100f;
            panel.setAlpha(1f);
            panel.setTranslationY(0f);
            if (row != null) { row.setAlpha(1f); row.setTranslationY(0f); }
            if (cover != null) {
                cover.setAlpha(bgEnabled() ? target : 0f);
                cover.setTranslationX(0f);
                cover.setTranslationY(0f);
                cover.setScaleX(1f);
                cover.setScaleY(1f);
            }
            if (scrim != null) scrim.setAlpha(bgEnabled() ? target : 0f);
        } catch (Throwable ignored) {}
        // 万一上一次的动画还在跑，把它们的动画也停掉
        try { panel.animate().cancel(); } catch (Throwable ignored) {}
    }
    /** ★ v4.51：下一次重建面板时**真播一次**入场动画。
     *  只给「设置页里选入场方式」用 —— 平时设置页不播，但那一次必须播给用户看。 */
    public static void previewIntro() {
        previewAnimOnce = true;
        applyPersonalization();
    }

    // ------------------------------------------------ 悬浮窗（可拖动 / 折叠 / 位置记忆）
    /** v4.0 迁移标记：把老版本存下的「分析区展开」偏好一次性迁回折叠。 */
    private static final String K_ANA_MIG4 = "ana_mig4";
    /** ★ v4.50：迁移标记 —— 右栏顺序换成新默认（链/识/自/✕/⠿，去掉 collapse）。 */
    private static final String K_COL_MIG6 = "col_mig6";
    /** ★ v4.51：迁移标记 —— 按钮风格固定圆润。 */
    private static final String K_MIG7 = "mig7";
    /** v4.3：用户点过「权限已获取，开始游戏」没有（1=点过）。存盘，服务重启后不用再点。 */
    private static final String K_STARTED = "started";
    /** v4.5：结算页自动点「再来一局」的开关（1=开，默认开）。 */
    private static final String K_REMATCH = "rematch";
    /** v4.5：两次「再来一局」点击之间的最小间隔。 */
    private static final long REMATCH_CD_MS = 7000L;
    /** v4.5：上一次点「再来一局」的时间（0 = 从没点过）。 */
    private volatile long lastRematchTapAt = 0;
    /** v4.5：连续命中「结算页绿按钮」的帧数（连中 2 帧才动手，防单帧误判）。 */
    private int rematchHit = 0;
    /**
     * v4.3 开场闸门：true = 用户还没点「权限已获取，开始游戏」。
     *
     * 这段时间（正好是用户在系统设置里逐个开权限的时候）**任何悬浮窗/卡片都不许出现**，
     * 这是用户明确要求的流程。showPanel() 开头、看门狗自愈条件都要看它。
     */
    private volatile boolean waitingStart = true;
    private static final String PREF = "yypm";
    private static final String K_PX = "px", K_PY = "py", K_BX = "bx", K_BY = "by";
    public static final String K_ANA = "ana";           // v4.8：设置页也要读写
    /** 卡片在屏幕中间亮相的停留时长（用户要求「不需要很快」，所以要看得清）。 */
    private static final long CARD_HOLD_MS = 420L;
    /** 卡片第一段：从屏幕中间「升到屏幕最上方」的时长（v4.3 两段式动画）。 */
    private static final long CARD_RISE_MS = 950L;
    /** 卡片第二段：从最上方「滑出屏幕」的时长。 */
    private static final long CARD_EXIT_MS = 620L;
    /** 悬浮窗从中间滑到最上方所用的时长。 */
    private static final long PANEL_FLY_MS = 800L;
    /** 面板窗口的「家」= 屏幕最顶端 y=0（盖住状态栏）。 */
    private static final int HOME_Y = 0;
    /** 面板「视觉 y」与 lp.y 的实测偏移（frame = lp.y + 偏移）；标定前是 MIN_VALUE。 */
    private int panelOff = Integer.MIN_VALUE;
    private View ball;

    private int sp(String k, int def) {
        return getSharedPreferences(PREF, MODE_PRIVATE).getInt(k, def);
    }

    private void spPut(String k, int v) {
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putInt(k, v).apply();
    }

    /**
     * 真实屏幕尺寸。
     *
     * 两条老坑 + 一条新坑：
     *   ① 在 AccessibilityService 里 getResources().getDisplayMetrics() 拿到的是服务自身配置
     *      （实测 811x1161），用它算拖动边界会把窗口钳在左上角。
     *   ② 而 Display.getRealMetrics() 在 Android 16 上**也不可靠**：
     *      实测 heightPixels 返回的是「屏宽」（1272），把 2800 高的屏幕当成正方形。
     *      后果：卡片「屏幕中间」算成 y=585（其实只有 21% 高），
     *            小球拖动边界也只到 y=1118 —— 下半屏根本拖不过去。
     *   ③ 所以这里取「多源取最大值」：只要有一个来源给出了真实值就能纠正回来，
     *      拿不到就退回旧路径，绝不比原来更差。
     */
    private DisplayMetrics realDm() {
        DisplayMetrics best = new DisplayMetrics();
        boolean has = false;

        // 来源 A（Android 11+，最可靠）：WindowManager.getMaximumWindowMetrics()
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.view.WindowManager wmgr =
                        (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
                if (wmgr != null) {
                    android.graphics.Rect b = wmgr.getMaximumWindowMetrics().getBounds();
                    if (b.width() > 0 && b.height() > 0) {
                        best.widthPixels = b.width();
                        best.heightPixels = b.height();
                        has = true;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getMaximumWindowMetrics failed", t);
        }

        // 来源 B：Display.getRealMetrics()
        try {
            Display d = ((android.hardware.display.DisplayManager)
                    getSystemService(DISPLAY_SERVICE)).getDisplay(Display.DEFAULT_DISPLAY);
            if (d != null) {
                DisplayMetrics dm = new DisplayMetrics();
                d.getRealMetrics(dm);
                if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                    if (!has) { best.widthPixels = dm.widthPixels; best.heightPixels = dm.heightPixels; has = true; }
                    else {
                        if (dm.widthPixels > best.widthPixels) best.widthPixels = dm.widthPixels;
                        if (dm.heightPixels > best.heightPixels) best.heightPixels = dm.heightPixels;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getRealMetrics failed", t);
        }

        // 来源 C：兜底
        DisplayMetrics res = getResources().getDisplayMetrics();
        if (!has) {
            best.widthPixels = res.widthPixels;
            best.heightPixels = res.heightPixels;
        } else {
            if (res.widthPixels > best.widthPixels) best.widthPixels = res.widthPixels;
            if (res.heightPixels > best.heightPixels) best.heightPixels = res.heightPixels;
        }
        best.density = res.density;
        best.densityDpi = res.densityDpi;
        return best;
    }

    /**
     * 真实屏幕尺寸。
     * 坑：在 AccessibilityService 里 getResources().getDisplayMetrics() 拿到的是服务自身的
     * 配置（实测是 811x1161），用它算拖动边界会把窗口钳在左上角；必须走 Display.getRealMetrics。
     */
    private int scrW() { return realDm().widthPixels; }
    private int scrH() { return realDm().heightPixels; }
    private float dens() { return realDm().density; }

    private int dp(float v) { return Math.round(v * dens()); }

    /**
     * 给窗口加拖动。
     * handle = 手指按住的视图；target = 该窗口的「根视图」，必须是真正 addView 进去的那个，
     *          因为 WindowManager.updateViewLayout 只接受根视图（传子视图会污染它的
     *          LayoutParams 并抛异常，随后 measure 时 ClassCastException 崩溃）。
     */
    private void attachDrag(final View handle, final View target,
                            final WindowManager.LayoutParams lp,
                            final String kx, final String ky, final Runnable onTap) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;
            private boolean moved;
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        Log.i(TAG, "touch DOWN " + kx + " raw=" + (int) e.getRawX() + "," + (int) e.getRawY());
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = lp.x; startY = lp.y; moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float mx = e.getRawX() - downX, my = e.getRawY() - downY;
                        if (Math.abs(mx) > 6 || Math.abs(my) > 6) moved = true;
                        int maxX = Math.max(0, scrW() - target.getWidth());
                        if (lp.width == WindowManager.LayoutParams.MATCH_PARENT) maxX = 0;
                        int maxY = Math.max(0, scrH() - target.getHeight());
                        int nx = Math.max(0, Math.min(startX + (int) mx, maxX));
                        int ny = Math.max(0, Math.min(startY + (int) my, maxY));
                        if (lp.x != nx || lp.y != ny) {
                            lp.x = nx;
                            lp.y = ny;
                            try {
                                wm.updateViewLayout(target, lp);
                            } catch (Throwable t) {
                                Log.w(TAG, "updateViewLayout failed", t);
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) {
                            spPut(kx, lp.x);
                            spPut(ky, lp.y);
                            // ★ v4.54：面板被拖动过 → 立刻把结果换算成「悬浮窗上下位置」(dp) 存盘。
                            //   不存的话，音量减隐藏、再按音量加恢复时，showPanel() 会按
                            //   panelTopY() 重新落位，用户刚拖好的位置就白拖了
                            //   （实测反馈的原话：「又回到刚进游戏时的位置」）。
                            //   面板窗口宽度是 MATCH_PARENT，横向本来就拖不动，所以只记纵向。
                            if (K_PX.equals(kx) && target == panel) {
                                float d = dens();
                                int vis = lp.y + (panelOff == Integer.MIN_VALUE ? 0 : panelOff);
                                int dy = Math.round((vis - HOME_Y) / d);
                                int maxDy = Math.round((scrH() - target.getHeight()) / d);
                                if (maxDy < 0) maxDy = 0;
                                if (dy < 0) dy = 0;
                                if (dy > maxDy) dy = maxDy;
                                spPut(K_UI_PANEL_DY, dy);
                                flog("面板拖动：视觉 y=" + vis + " → 上下位置 " + dy + "dp（已存盘）");
                            }
                        } else if (onTap != null) {
                            onTap.run();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void showPanel() {
        // ★ v4.3：还没点「权限已获取，开始游戏」之前，悬浮窗一律不许自己冒出来。
        //   用户明确要求「在 app 主界面点一键全部修复、开四个权限的时候，什么也不要弹出来」。
        //   以前这里是裸的：给完无障碍权限，服务一 onServiceConnected 就把面板弹到桌面上，
        //   用户还在系统设置页里，流程就乱了。
        // ★ v4.36：设置页开着时例外 —— 那一页调的就是真悬浮窗本身，必须让它出来。
        //   （v4.35 的坑：这里无条件拦截，而用户还没点「开始游戏」时 waitingStart=true，
        //    于是设置页里 showPanel() 直接返回，屏幕上什么都没有。）
        if (waitingStart && !settingsOpen) return;
        removeKeepAlive();
        if (panel != null) return;
        removeBall();

        panel = LayoutInflater.from(this).inflate(R.layout.panel, null);
        tvStatus = panel.findViewById(R.id.tv_status);
        btnLink = panel.findViewById(R.id.btn_link);
        btnOne = panel.findViewById(R.id.btn_one);
        btnLight = panel.findViewById(R.id.btn_light);
        // ★ v4.8：执子 / 分析区 / 走棋速度 / 前台校验 四个控件已从面板挪进设置页。
        //   这里不再 findViewById，也不再有按钮绑定 —— 只把已存的偏好转成运行时状态。
        miniBoard = panel.findViewById(R.id.board_mini);
        setupMiniBoardFit(panel);
        evalBar = panel.findViewById(R.id.eval_bar);   // ★ v4.69：恢复文字压条（旧版样式）
        tvTimer = panel.findViewById(R.id.tv_timer);
        // 状态行可点：中途接入时轮次自动判定可能不准，点一下就能手动纠正
        if (tvStatus != null) {
            tvStatus.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (linkState == ST_MY_TURN) {
                        linkState = ST_WAIT;
                        setStatus("连线: 已改为等对手走");
                    } else {
                        linkState = ST_MY_TURN;
                        linkActAt = 0;          // 让下一帧立刻算招
                        linkExpect = null;
                        setStatus("连线: 已改为轮到我走");
                    }
                    Log.i(TAG, "连线: 手动改轮次 → " + linkState);
                }
            });
        }

        // 长按计时 → 手动重开本局（自动判定不准时兜底）
        if (tvTimer != null) {
            tvTimer.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    gameTimerRestart("手动重置");
                    setStatus("计时: 本局已重新计时");
                    return true;
                }
            });
        }
        // 链 = 连线总开关：持续识别 + 实时同步棋盘（⚡ 开时同时自动落子）
        btnLink.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean on = !auto.get();
                auto.set(on);
                if (on) startLink(); else stopLink();
                updateUi();
            }
        });
        // 长按「链」= 强制重新同步（识别跑偏后一键复位，不用关掉再开）
        btnLink.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                linkBoard = ""; repairBase = "";
                linkExpect = null;
                linkMover = 0;
                badFrames = 0;
                linkState = ST_INIT;
                if (vision != null) vision.resetLock();
                setStatus("连线: 已重新同步");
                return true;
            }
        });
        // 识 = 识别一步（只提示，不落子）
        btnOne.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doOne(); }
        });
        // 长按「识」：前台校验三档循环（严格 → 宽松 → 关闭）
        btnOne.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                fgMode = (fgMode + 1) % 3;
                spPut(K_FGMODE, fgMode);
                setStatus("前台校验: " + fgModeName());
                return true;
            }
        });
        // ⚡ = 是否代我落子
        btnLight.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                autoPlay.set(!autoPlay.get());
                updateUi();
                setStatus(autoPlay.get() ? "自动走子: 开" : "自动走子: 关（只给建议）");
            }
        });
        // 长按 ⚡ = 循环切换走棋速度（极速/快/正常/慢）
        btnLight.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { cycleSpeed(); return true; }
        });
        // 执子 / 分析区 / 走棋速度 / 前台校验都挪到设置页了（v4.8），这里没有按钮可绑
        // ★ v4.72：连开 = 绝杀停机后自动点「再来一局」开下一局
        btnLianKai = panel.findViewById(R.id.btn_liankai);
        if (btnLianKai != null) {
            btnLianKai.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    liankaiOn = !liankaiOn;
                    spPut(K_LIANKAI, liankaiOn ? 1 : 0);
                    updateUi();
                    setStatus(liankaiOn ? "连开: 开（绝杀后自动再来一局）" : "连开: 关");
                    flog("连开 → " + (liankaiOn ? "开" : "关"));
                }
            });
        }
        panel.findViewById(R.id.btn_close).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { userClosed = true; volHidden = false; hidePanel(); }
        });
        // ★ v4.50：「—」（折叠成小球）按钮已删除 —— 功能由「音量减」键代替。

        // ★ v4.50：链 / 识 / 自 全搬进右侧竖列；「—」已删除。
        //   一次性把右栏顺序重置成新的默认（原来可能残留 collapse 项）。
        // ★ v4.51：「按钮风格」已删除，样式固定圆润 —— 一次性把偏好置 1。
        if (sp(K_MIG7, 0) == 0) { spPut(K_UI_BTN_STYLE, 1); spPut(K_MIG7, 1); }
        if (sp(K_COL_MIG6, 0) == 0) {
            spPut(K_UI_COL_LINK, 1);
            spPut(K_UI_COL_ONE, 1);
            spPut(K_UI_COL_LIGHT, 1);
            spPutStr(K_UI_ORDER_RIGHT, ORDER_RIGHT_DEF);
            spPutStr(K_UI_ORDER_BTN, ORDER_BTN_DEF);
            spPut(K_COL_MIG6, 1);
        }
        liankaiOn = sp(K_LIANKAI, 1) == 1;   // ★ v4.75：默认开（绝杀后自动点「再来一局」）
        speedLevel = Speed.clamp(sp(K_SPEED, Speed.DEFAULT));
        fgMode = sp(K_FGMODE, FG_LOOSE);
        if (fgMode < FG_STRICT || fgMode > FG_OFF) fgMode = FG_LOOSE;
        // ★ v4.8：执子偏好也从设置页读（面板上的「红」按钮已挪走）
        int sdPref = sp(K_UI_SIDE, 0);
        sideAuto = (sdPref == 0);
        if (sdPref == 1) mySide = 'w';
        else if (sdPref == 2) mySide = 'b';
        updateUi();
        startTimerTick();

        // ★ v4.7：把设置页的选择应用到这块面板上（棋盘位置 / 卡底 / 背景板）。
        //   必须赶在 addView 之前 —— 它会重排子视图顺序和 margin，addView 之后再动会闪一下。
        applyPersonalizationTo(this, panel);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                panelFixedH(this),      // ★ v4.32：固定高度，不再随内容变化
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        // ★ 必须允许「画到挖孔/状态栏区域里去」：本机挖孔 inset 正好是 141px，
        //   不加这一行，窗口会被系统钳在 y>=141，永远贴不到屏幕最顶端。
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        // ★ 面板的「家」= 屏幕最顶端（y=0，盖住状态栏）。
        //   两个必须：
        //     · gravity 写死 TOP|START —— 本机（ColorOS/Android 16）对
        //       ACCESSIBILITY_OVERLAY 窗口，gravity 不写时 y 被当成「相对垂直居中的偏移」，
        //       dumpsys 实测 frame=[0,1132][1272,1667]（1132=(2800-535)/2，正好居中），
        //       lp.y=7 看着就在屏幕正中间 —— 这就是「悬浮窗不在最上方」的根因。
        //     · layoutInDisplayCutoutMode=ALWAYS（见上）—— 否则窗口被钳在 y>=141（状态栏下沿）。
        //   仍然保留运行期标定兜底（calibratePanel），换机型/换窗口高度都不怕。
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.y = 0;
        // ★ v4.47：设置页里重建面板不算「新出场」——不先透明（那会黑一下再亮，就是闪的来源）
        if (!settingsOpen) panel.setAlpha(0f);
        try {
            wm.addView(panel, lp);
        int prevOff = panelOff;
        panelOff = Integer.MIN_VALUE;   // 新窗口 → 偏移要重新标定
        if (settingsOpen && prevOff != Integer.MIN_VALUE) {
            // ★ v4.47：设置页里偏移早就标定过 —— 直接落位 + 摆到动画终态。
            //   不重新标定（要连等好几帧、期间面板是空白的），也不播入场动画。
            panelOff = prevOff;
            panel.post(new Runnable() { public void run() {
                placePanel(panelTopY());
                // ★ v4.51：改走 animatePersonalIn() —— 它自己决定「摆终态」还是
                //   「真播一遍」（previewAnimOnce）。原来直接 snap，选动画时看不到效果。
                animatePersonalIn();
            }});
        } else if (panelFlyIn) {
            panelFlyIn = false;
            panel.post(new Runnable() { public void run() { flyPanelToTop(); } });
        } else {
            panel.post(new Runnable() { public void run() {
                calibratePanel(new Runnable() { public void run() {
                    placePanel(panelTopY());
                    // ★ v4.7：背景板开着时走「人物入场 → 沉成背景板」的动画
                    animatePersonalIn();
                }});
            }});
        }
        // 启动提示卡不再自动弹：v4.3 起由「权限已获取，开始游戏」统一走开场播报
        if (overlaySelfHidden) panel.setVisibility(View.INVISIBLE);
        if (settingsOpen) { try { panel.setVisibility(View.VISIBLE); } catch (Throwable ignored) {} }
        } catch (Exception e) {
            Log.e(TAG, "addPanel failed", e);
            panel = null;
            return;
        }

        attachDrag(panel.findViewById(R.id.tv_title), panel, lp, K_PX, K_PY, null);

        panel.post(new Runnable() {
            public void run() {
                int[] p = new int[2];
                int[] ids = {R.id.tv_title, R.id.btn_link, R.id.btn_one, R.id.btn_light,
                        R.id.btn_close, R.id.board_mini};
                String[] nm = {"tv_title", "btn_link", "btn_one", "btn_light",
                        "btn_close", "board_mini"};
                for (int i = 0; i < ids.length; i++) {
                    View v = panel.findViewById(ids[i]);
                    if (v == null) continue;
                    v.getLocationOnScreen(p);
                    Log.i(TAG, "hit " + nm[i] + " = [" + p[0] + "," + p[1] + "]["
                            + (p[0] + v.getWidth()) + "," + (p[1] + v.getHeight()) + "]");
                }
                panel.getLocationOnScreen(p);
                Log.i(TAG, "PANEL 高=" + panel.getHeight() + " 覆盖 y[" + p[1] + ","
                        + (p[1] + panel.getHeight()) + "] 屏高=" + scrH());
                try {
                    if (colLeft != null) {
                        StringBuilder sb = new StringBuilder("左栏高=" + colLeft.getHeight());
                        android.view.ViewGroup cg = (android.view.ViewGroup) colLeft;
                        for (int ci = 0; ci < cg.getChildCount(); ci++) {
                            View cv = cg.getChildAt(ci);
                            sb.append(" | ").append(ci).append("=").append(cv.getWidth())
                              .append("x").append(cv.getHeight())
                              .append(cv.getVisibility() == View.VISIBLE ? "V" : "G");
                            if (ci == 3 && cv instanceof android.view.ViewGroup) {
                                android.view.ViewGroup mg = (android.view.ViewGroup) cv;
                                for (int mi = 0; mi < mg.getChildCount(); mi++) {
                                    View mv = mg.getChildAt(mi);
                                    sb.append(" [").append(mi).append("=").append(mv.getWidth())
                                      .append("x").append(mv.getHeight()).append("]");
                                }
                            }
                        }
                        flog("面板尺寸 " + sb);
                    }
                    if (miniBoard != null) flog("棋盘实测 " + miniBoard.getWidth() + "x" + miniBoard.getHeight());
                } catch (Throwable ignored) {}
            }
        });
    }

    /** 刷新按钮外观：连线中亮绿圈，自动走子开亮黄圈。 */
    // ============================================================
    //  启动动画 + 启动提示卡（v4.0，照 ColorOS「游戏助手」卡片观感）
    // ============================================================

    /**
     * 入场动画：alpha 0→1 + scale 0.88→1.0 + 顶部滑下 22dp，260ms。
     *
     * 为什么不用 Overshoot/Bounce —— ColorOS 启动游戏后弹出的那张「游戏助手」卡片
     * 不是弹跳，是「滑下来 + 定住」。所以用 DecelerateInterpolator 收尾，稳、不晃。
     */
    private void animateIn(final View v) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setScaleX(0.88f);
        v.setScaleY(0.88f);
        v.setTranslationY(-dp(22));
        v.animate()
                .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(260L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                .start();
    }

    /** 退场动画：180ms 淡出 + 轻微上移（收回比展开快一点，手感才跟手）。 */
    private void animateOut(final View v, final Runnable end) {
        if (v == null) { if (end != null) end.run(); return; }
        v.animate()
                .alpha(0f).scaleX(0.97f).scaleY(0.97f).translationY(-dp(9))
                .setDuration(180L)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(new Runnable() {
                    public void run() { if (end != null) end.run(); }
                })
                .start();
    }

    /**
     * 启动提示卡：本次进程首次弹出面板时出现一次，约 2.6 秒后自动淡出。
     *
     * FLAG_NOT_TOUCHABLE —— 纯展示，绝不拦截任何触摸，玩家该点哪点哪。
     * 位置在屏幕上方居中，避开棋盘中路与落子区。
     */
    /**
     * 启动提示卡：和播报卡同一套观感 ——
     * 屏幕中间出发 → 升到屏幕最上方（停一下让人看清）→ 再移出屏幕。
     */
    private void showHintCard() {
        if (hintShown || hintCard != null) return;
        hintShown = true;
        try {
            final View card = LayoutInflater.from(this).inflate(R.layout.hintcard, null);
            TextView t = card.findViewById(R.id.hc_title);
            if (t != null) {
                String vn = appVersionName();
                t.setText(vn == null || vn.isEmpty() ? "审判者" : "审判者  v" + vn);
            }

            FrameLayout host = new FrameLayout(this);
            host.setClipChildren(false);
            final int startY = Math.round(scrH() * 0.46f);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            clp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            clp.topMargin = startY;
            host.addView(card, clp);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 0;
            lp.y = 0;
            wm.addView(host, lp);
            hintCard = host;

            card.post(new Runnable() {
                public void run() {
                    final int h = card.getHeight();
                    hintOutY = -(startY + h + dp(24));              // 移出屏幕的目标位移
                    final float toTop = panelTopY() - startY;       // 升到最上方的位移
                    card.postDelayed(new Runnable() {
                        public void run() {
                            card.animate()
                                    .translationY(toTop)
                                    .setDuration(620L)
                                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                                    .withEndAction(new Runnable() {
                                        public void run() {
                                            ui.postDelayed(hintDismiss, 1500L);
                                        }
                                    })
                                    .start();
                        }
                    }, 300L);
                }
            });
            flog("启动提示卡已弹出（中间 → 最上方 → 移出屏幕）");
            flog("提示卡几何 startY=" + startY + " 面板顶=" + panelTopY());
        } catch (Throwable t) {
            Log.w(TAG, "showHintCard failed", t);
            hintCard = null;
        }
    }

    private final Runnable hintDismiss = new Runnable() {
        public void run() { dismissHintCard(); }
    };

    private void dismissHintCard() {
        ui.removeCallbacks(hintDismiss);
        final View host = hintCard;
        if (host == null) return;
        hintCard = null;
        View card = null;
        try { card = ((FrameLayout) host).getChildAt(0); } catch (Throwable ignored) {}
        if (card == null) {
            try { if (wm != null) wm.removeView(host); } catch (Throwable ignored) {}
            return;
        }
        card.animate()
                .translationY(hintOutY)
                .setDuration(520L)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.15f))
                .withEndAction(new Runnable() {
                    public void run() {
                        try { if (wm != null) wm.removeView(host); } catch (Throwable ignored) {}
                    }
                })
                .start();
    }

    // ============================================================
    //  悬浮窗位置与「从中间滑到最上方」（v4.2）
    // ============================================================

    /**
     * 面板的「家」= 窗口最顶端 y=0。
     *
     * 用户要求「能不能覆盖到状态栏」—— 能。我们的窗口是
     * TYPE_ACCESSIBILITY_OVERLAY + FLAG_LAYOUT_IN_SCREEN，
     * y=0 就是物理屏幕最顶端，会盖住状态栏的时间/信号/电量。
     * v4.2 及以前用的是状态栏高度（141px），看着永远「差一截没贴顶」。
     */
    private int panelTopY() {
        // ★ v4.32：悬浮窗上下位置（设置页滑杆）。HOME_Y = 0 是贴屏幕最顶端（盖住状态栏），
        //   正数往下挪 —— 用户点名只要「上下移动」。
        float d = getResources().getDisplayMetrics().density;
        return HOME_Y + Math.round(sp(K_UI_PANEL_DY, 0) * d);
    }

    /** ★ v4.32：面板固定高度（px）。面板尺寸从此不再随任何设置变化。 */
    public static int panelFixedH(android.content.Context c) {
        return (int) c.getResources().getDimension(R.dimen.panel_fixed_h);
    }

    /**
     * ★ v4.54：「悬浮窗上下位置」的上限（dp）—— 内容就是面板底边贴到屏幕底为止。
     *
     * 原来设置页滑杆写死 0~240dp，而面板能一直拖到屏幕底（本机 624dp）。
     * 用户在游戏里把面板拖到 240dp 以下之后，一旦用音量键隐藏再恢复，
     * 位置会被钳回 240dp —— 看着就像「自己往上跳了一截」。所以改成同一个上限。
     */
    public static int panelDyMax(android.content.Context c) {
        if (c == null) return 240;
        try {
            android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
            android.view.WindowManager wm = (android.view.WindowManager)
                    c.getSystemService(android.content.Context.WINDOW_SERVICE);
            if (wm == null) return 240;
            android.graphics.Point pt = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(pt);
            int m = Math.round((pt.y - panelFixedH(c)) / dm.density);
            return m > 0 ? m : 240;
        } catch (Throwable t) {
            return 240;
        }
    }

    /** ★ v4.32：面板内容区固定高（去掉上下 padding）。 */
    public static int panelContentH(android.content.Context c) {
        int pad = (int) c.getResources().getDimension(R.dimen.panel_pad);
        return panelFixedH(c) - 2 * pad;
    }

    /** ★ v4.32：设置页拖动「悬浮窗上下位置」时即时生效。 */
    public static void applyPanelDy() {
        final XqService s = sInstance;
        if (s == null) return;
        s.ui.post(new Runnable() { public void run() {
            try { s.placePanel(s.panelTopY()); } catch (Throwable ignored) {}
        }});
    }

    // ============================================================
    //  v4.33：设置页不再放「预览副本」，改成直接调真悬浮窗
    // ============================================================

    /** 设置页是否正在前台。开着的时候，真悬浮窗强制可见（本页调的就是它）。 */
    public static volatile boolean settingsOpen = false;
    /** 进设置页之前悬浮窗本来就没开着（离开时要收回临时那份）。 */
    private boolean panelWasNull = false;

    /** 设置页 onResume / onPause 调用。打开时把真面板亮出来并落位。 */
    public static void notifySettingsOpen(final boolean open) {
        settingsOpen = open;
        final XqService s = sInstance;
        if (s == null) return;
        s.ui.post(new Runnable() {
            public void run() {
                try {
                    if (open) {
                        // ★ v4.33：设置页要调的就是真悬浮窗 —— 没开着就先开一个，
                        //   并记住「原来是不是没开」，离开本页时还原，不破坏原有流程。
                        s.panelWasNull = (s.panel == null);
                        if (s.panel == null) {
                            s.showPanel();
                            s.flog("设置页：临时显示真悬浮窗（原来没开）");
                        } else {
                            s.applyOverlaySelfHidden(false);
                            try { s.panel.setVisibility(View.VISIBLE); } catch (Throwable ignored) {}
                            s.placePanel(s.panelTopY());
                            s.flog("设置页：真悬浮窗直接可见");
                        }
                    } else {
                        // 离开设置页 → 等下一个界面稳定再交回原来的前台判定
                        if (s.panelWasNull) {
                            // 原来就没开（比如还没点「开始游戏」）→ 收回临时那份
                            s.removePanel();
                            s.panelWasNull = false;
                            s.flog("设置页：离开 → 收回临时悬浮窗");
                            return;
                        }
                        s.ui.postDelayed(new Runnable() {
                            public void run() {
                                try { s.applyOverlaySelfHidden(s.isSelfForeground()); }
                                catch (Throwable ignored) {}
                            }
                        }, 600L);
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    /**
     * ★ v4.42：用户主动截屏了（Activity.registerScreenCaptureCallback，API 34+）。
     *
     * 用途：音量减和电源是截屏组合键，而音量减又是「隐藏悬浮窗」键 ——
     * 两者撞车，截出来的图里没有悬浮窗。收到这个通知就把在途的隐藏取消。
     */
    public static void onUserScreenshot() {
        final XqService s = sInstance;
        if (s == null) return;
        s.userShotAt = System.currentTimeMillis();
        s.volHideGen++;                    // 取消还没执行的隐藏
        s.flog("用户截屏：保留悬浮窗（音量减的隐藏已取消）");
    }

    /** ★ v4.33：拖动「棋盘大小 / 左右 / 上下」时即时作用到真面板上。
     *  只改 margin 和高度，不重建窗口 —— 所以拖起来跟手、不闪。 */
    public static void liveApplyBoard() {
        final XqService s = sInstance;
        if (s == null) return;
        s.ui.post(new Runnable() {
            public void run() {
                try {
                    if (s.panel == null) return;
                    applyPersonalizationTo(s, s.panel);
                    s.fitMiniBoard(s.panel);
                } catch (Throwable ignored) {}
            }
        });
    }

    /** ★ v4.33：拖动「背景板浓度」时直接改真面板的人像透明度。 */
    public static void liveCoverAlpha(final float pct01) {
        final XqService s = sInstance;
        if (s == null) return;
        s.ui.post(new Runnable() {
            public void run() {
                try { if (s.panel != null) applyCoverAlpha(s.panel, pct01); }
                catch (Throwable ignored) {}
            }
        });
    }

    /** ★ v4.33：真悬浮窗下沿的屏幕 y 坐标 —— 设置页用它留出顶部空间。
     *  v4.36：面板压根没起来（无障碍没开等）时返回 0 —— 不留白，
     *  否则设置页顶上会空一大块却什么都没有。 */
    public static int panelBottomPx(android.content.Context c) {
        if (c == null) return 0;
        XqService s = sInstance;
        if (s == null || s.panel == null) return 0;
        // ★ v4.42：面板建着、但被音量键收起（或临时隐藏）时也不能留白 ——
        //   否则设置页顶上是一大块纯黑的空洞（用户截图里那个洞就是这么来的）。
        try { if (s.panel.getVisibility() != View.VISIBLE) return 0; } catch (Throwable ignored) {}
        float d = c.getResources().getDisplayMetrics().density;
        return Math.round(spIntOf(c, K_UI_PANEL_DY, 0) * d) + panelFixedH(c);
    }
    /**
     * 让面板从屏幕中间滑到屏幕最上方。
     *
     * 为什么必须改 lp.y + updateViewLayout，而不是平移视图：
     *   面板是 WRAP_CONTENT 的独立窗口，平移视图会被窗口边界裁掉；
     *   改窗口 y 才是移动窗口的正路（拖动本身就是这么做的，已验证稳）。
     */
    /**
     * 让面板从屏幕中间滑到屏幕最上方。
     *
     * ★ 关键坑（实测出来的，不是猜的）：
     *   本机 ColorOS/Android 16 对 TYPE_ACCESSIBILITY_OVERLAY 窗口，**frame = lp.y + 偏移**，
     *   偏移量正好等于「垂直居中」的位置（(2800-535)/2 = 1132）。
     *   也就是说 lp.y=0 并不是贴顶，而是居中；无论怎么调 lp.y，窗口都整体偏下 1132px。
     *   所以这里先测一次真实偏移（off = 实际屏幕 y − lp.y），之后**全部按视觉坐标来算**：
     *   想让面板看起来在 y，就写 lp.y = y − off。
     *
     * 为什么用人工帧循环而不是 ValueAnimator：
     *   每帧都要读/写窗口位置，人工循环每一步都确定执行，还能打日志，出问题查得动。
     */
    /**
     * 让面板从屏幕中间滑到屏幕最上方。
     *
     * ★ 关键坑（实测出来的，不是猜的）：
     *   本机 ColorOS/Android 16 对 TYPE_ACCESSIBILITY_OVERLAY 窗口，真实屏幕位置
     *   **不等于** lp.y —— 实测 frame = lp.y + 1132（1132 正好是窗口垂直居中的位置），
     *   所以 lp.y=0 时面板并不贴顶，而是停在屏幕中间；负的 lp.y 才往上走。
     *   而且这个偏移在窗口刚 addView、还没完成布局时读出来是错的（实测读到 791）。
     *
     *   结论：不要去「算」偏移，直接**闭环**——
     *   每帧读一次真实位置，用误差反推 lp.y，一步就能落到位，与偏移量无关。
     */
    private void flyPanelToTop() {
        if (panel == null) return;
        if (!(panel.getLayoutParams() instanceof WindowManager.LayoutParams)) return;
        final WindowManager.LayoutParams lp = (WindowManager.LayoutParams) panel.getLayoutParams();
        panel.setAlpha(1f);
        final int fromV = Math.round(scrH() * 0.40f);   // 视觉起点：屏幕中间偏上
        final int toV = panelTopY();                     // 视觉终点：设置页指定的悬浮窗上下位置
        panel.setAlpha(0f);                              // 标定完成前先不露脸
        flog("悬浮窗上滑：视觉 " + fromV + " → " + toV);
        calibratePanel(new Runnable() {
            public void run() {
                if (panel == null) return;
                placePanel(fromV);
                panel.setAlpha(1f);
                final long t0 = System.currentTimeMillis();
                panel.post(new Runnable() {
                    public void run() {
                        if (panel == null) return;
                        long el = System.currentTimeMillis() - t0;
                        float pr = Math.min(1f, el / (float) PANEL_FLY_MS);
                        float e = (float) (1.0 - Math.pow(1.0 - pr, 1.5));   // Decelerate(1.5)
                        boolean done = (pr >= 1f);
                        placePanel(done ? toV : Math.round(fromV + (toV - fromV) * e));
                        if (!done) {
                            panel.postDelayed(this, 16L);
                        } else {
                            panel.postDelayed(new Runnable() {
                                public void run() {
                                    if (panel == null) return;
                                    WindowManager.LayoutParams lp2 =
                                            (WindowManager.LayoutParams) panel.getLayoutParams();
                                    spPut(K_PY, lp2.y);
                                    flog("面板落位：视觉 y=" + panelFrameY() + "（目标 " + toV
                                            + "，lp.y=" + lp2.y + "，标定偏移 " + panelOff + "）");
                                }
                            }, 150L);
                        }
                    }
                });
            }
        });
    }

    /** 面板窗口在屏幕上的真实 y；窗口还没 attach 或读不到时返回 -1。 */
    private int panelFrameY() {
        if (panel == null) return -1;
        try {
            if (!panel.isAttachedToWindow()) return -1;
            int[] p = new int[2];
            panel.getLocationOnScreen(p);
            return p[1];
        } catch (Throwable t) { return -1; }
    }

    /**
     * 标定面板窗口的「y 偏移」，之后所有定位都按视觉坐标走。
     *
     * ★ 实测（dumpsys window，2026-10-02）：本机 ColorOS/Android 16 对
     *   TYPE_ACCESSIBILITY_OVERLAY 窗口，frame_y **不等于** lp.y，而是
     *   frame_y = lp.y + 偏移，偏移量正好是「垂直居中」的位置 ——
     *   面板高 535 时实测 frame=[0,1132][1272,1667]，1132 = (2800-535)/2。
     *   所以只写 lp.y=7 时面板看着就在屏幕正中间（这就是用户说的「不在最上方」）。
     *
     * 做法：等布局稳定（**连续两次读数一致**才算稳定 —— 实测太早读会得到
     * 791 这种中途值），记下 off = frame − lp.y；此后「想让面板视觉停在 y」
     * 就写 lp.y = y − off。窗口高度变了（展开/折叠分析区）要重新标定。
     */
    private void calibratePanel(final Runnable done) {
        final int[] tries = {0};
        final int[] last = {-1};
        final Runnable[] step = new Runnable[1];
        step[0] = new Runnable() {
            public void run() {
                if (panel == null) { if (done != null) done.run(); return; }
                int y = panelFrameY();
                if (y >= 0 && y == last[0]) {
                    try {
                        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) panel.getLayoutParams();
                        panelOff = y - lp.y;
                        flog("面板标定：frame=" + y + " lp.y=" + lp.y + " 偏移=" + panelOff
                                + "（窗口高 " + panel.getHeight() + "）");
                    } catch (Throwable ignored) {}
                    if (done != null) done.run();
                    return;
                }
                last[0] = y;
                if (++tries[0] > 14) {
                    flog("面板标定超时：frame=" + y);
                    if (done != null) done.run();
                    return;
                }
                ui.postDelayed(step[0], 70L);
            }
        };
        ui.postDelayed(step[0], 70L);
    }

    /** 把面板放到「视觉 y = wantY」（px，屏幕坐标）。没标定出来就不动。 */
    private void placePanel(int wantY) {
        if (panel == null || panelOff == Integer.MIN_VALUE) return;
        try {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) panel.getLayoutParams();
            int ny = wantY - panelOff;
            if (lp.y == ny) return;
            lp.y = ny;
            if (wm != null) wm.updateViewLayout(panel, lp);
        } catch (Throwable ignored) {}
    }


    /**
     * 开场流程（「权限已获取，开始游戏」按钮调用）：
     *   ① 收起悬浮窗与小球的，专心播卡
     *   ② 四张权限卡：屏幕中间 → 上浮 → 移出屏幕上方
     *   ③ 播完，悬浮窗自己从屏幕中间滑到最上方（盖住状态栏）
     */
    public static void playIntro(final String src) {
        final XqService s = sInstance;
        if (s == null) return;
        s.ui.post(new Runnable() {
            public void run() {
                try {
                    s.userClosed = false;
                    s.volHidden = false;
                    // ★ 点了「权限已获取，开始游戏」= 允许悬浮窗出场了（并记住，服务重启后不用再点）
                    s.waitingStart = false;
                    s.spPut(K_STARTED, 1);
                    // 播报期间不要有面板/小球抢镜（看门狗也被 introMode 挡住，不会自愈弹出来）
                    try { if (s.panel != null && s.wm != null) s.wm.removeView(s.panel); } catch (Throwable ignored) {}
                    s.panel = null;
                    s.removeBall();
                    s.clearCards();
                    s.introMode = true;
                    s.cardQ.add(new String[]{"① 无障碍服务", "已开启"});
                    s.cardQ.add(new String[]{"② 屏幕捕获", "已开启"});
                    s.cardQ.add(new String[]{"③ 通知权限", "已允许"});
                    s.cardQ.add(new String[]{"④ 后台运行", "已加入白名单"});
                    s.showNextCard();
                    s.flog("开场播报开始：4 张权限卡（来源 " + src + "）");
                } catch (Throwable ignored) {}
            }
        });
    }

    public static void bringUpToTop() {
        final XqService s = sInstance;
        if (s == null) return;
        s.ui.post(new Runnable() {
            public void run() {
                try {
                    s.userClosed = false;
                    s.volHidden = false;
                    if (s.panel == null) {
                        s.panelFlyIn = true;
                        s.showPanel();
                    } else {
                        s.flyPanelToTop();
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    // ============================================================
    //  播报队列（v4.1）—— 一次一张，排队往屏幕额头（顶部）上移
    // ============================================================

    /**
     * 排队播报一张卡。
     *
     * 设计约束（用户明确要求）：
     *   · 一次只显示一张，**不叠在一起**；上一张退场后才出下一张
     *   · 每张都往屏幕上方的「额头」位置移动 —— 入场从下方 24dp 上移到原位，
     *     退场继续往上方 28dp 移出屏幕，观感就是「一张接一张往上走」
     *   · 只做展示，FLAG_NOT_TOUCHABLE，绝不拦截任何触摸
     *
     * 用静态方法给 MainActivity 调用；服务没起来（例如无障碍还没开）返回 false，
     * 由调用方退回「应用内播报」。
     */
    public static boolean announce(String title, String msg) {
        final XqService s = sInstance;
        if (s == null) return false;
        s.ui.post(new Runnable() {
            public void run() { s.enqueueCard(title, msg); }
        });
        return true;
    }


    /** 悬浮窗通道是否可用（无障碍已开、服务已起来）。 */
    public static boolean canAnnounce() { return sInstance != null; }
    /** 把一张卡放进队列；若当前空闲就立刻播。 */
    private void enqueueCard(String title, String msg) {
        cardQ.add(new String[]{title == null ? "" : title, msg == null ? "" : msg});
        if (!cardBusy) showNextCard();
    }

    /**
     * 让面板给播报卡让位。
     *
     * 用户要的是「卡片一张接一张往额头走」，额头就是屏幕最上方那条。
     * 但悬浮面板默认也占着那一带（面板顶 y ≈ 45dp）。两者硬碰会互相盖住，
     * 所以队列活跃期间把面板整体下移到卡片下方，队列播完立刻复位。
     *
     * 只动位置、不隐藏 —— 面板始终看得见，用户不会以为助手挂了。
     *
     * @param needBelow 卡片下沿（px）；面板顶低于它就要让位
     */
    private void yieldPanelForCards(int needBelow) {
        try {
            if (panel == null || panel.getVisibility() != View.VISIBLE) return;
            if (!(panel.getLayoutParams() instanceof WindowManager.LayoutParams)) return;
            WindowManager.LayoutParams plp = (WindowManager.LayoutParams) panel.getLayoutParams();
            if (plp.y >= needBelow) return;                 // 本来就在下方，不用动
            int target = needBelow + dp(6);
            if (target + dp(120) > scrH()) return;          // 下移会跑出屏幕，那就不动
            if (cardPanelSavedY == Integer.MIN_VALUE) cardPanelSavedY = plp.y;
            plp.y = target;
            if (wm != null) wm.updateViewLayout(panel, plp);
        } catch (Throwable ignored) {}
    }

    /** 队列播完 → 面板回原位。 */
    private void restorePanelY() {
        if (cardPanelSavedY == Integer.MIN_VALUE) return;
        final int y = cardPanelSavedY;
        cardPanelSavedY = Integer.MIN_VALUE;
        try {
            if (panel != null && panel.getLayoutParams() instanceof WindowManager.LayoutParams) {
                WindowManager.LayoutParams plp = (WindowManager.LayoutParams) panel.getLayoutParams();
                plp.y = y;
                if (wm != null) wm.updateViewLayout(panel, plp);
            }
        } catch (Throwable ignored) {}
    }

    /** 取队首播出。播完由 cardNext 触发下一张。 */
    /**
     * 播报一张卡：从屏幕中间出发 → 一路上浮 → 直接移出屏幕上方。
     *
     * 为什么用「全屏宿主窗口 + 平移子视图」，而不是每帧改窗口 y：
     *   改窗口 y 要 60 次/秒调 updateViewLayout 重建窗口布局，既重又容易抖，
     *   而且窗口边界会把自己裁掉 —— 根本飞不出屏幕。
     *   宿主窗口占满整屏、背景全透明，卡片只是它里面的一个子视图，
     *   平移它就是一次纯渲染动画，交给 RenderThread 跑，稳、顺、飞得出去。
     */
    /**
     * 播报一张权限卡：屏幕中间亮相 → 一路上浮 → 自然移出屏幕上方。
     *
     * 卡片就是「启动提示卡」那一张（hintcard 布局）：满屏宽、圆角、近黑底 + 品牌绿图标，
     * 和用户截图里那张「YYPM 象棋助手 v4.x / 音量－收起助手」大小完全一致 ——
     * 实测 1272x310（屏宽 1272）。这是用户明确要求的尺寸。
     *
     * 为什么用「全屏宿主窗口 + 平移子视图」，而不是每帧改窗口 y：
     *   改窗口 y 要 60 次/秒调 updateViewLayout 重建窗口布局，既重又容易抖，
     *   而且窗口边界会把自己裁掉 —— 根本飞不出屏幕。
     *   宿主窗口占满整屏、背景全透明，卡片只是它里面的一个子视图，
     *   平移它就是一次纯渲染动画，交给 RenderThread 跑，稳、顺、飞得出去。
     */
    private void showNextCard() {
        if (cardQ.isEmpty()) { cardBusy = false; onCardsDrained(); return; }
        if (wm == null) { cardQ.clear(); cardBusy = false; onCardsDrained(); return; }
        cardBusy = true;
        final String[] c = cardQ.poll();

        View cardTmp;
        try {
            // ★ 和启动卡同一个布局 → 大小完全一致
            cardTmp = LayoutInflater.from(this).inflate(R.layout.hintcard, null);
            TextView t = cardTmp.findViewById(R.id.hc_title);
            TextView m = cardTmp.findViewById(R.id.hc_msg);
            TextView m2 = cardTmp.findViewById(R.id.hc_msg2);
            if (t != null) t.setText(c[0]);
            if (m != null) m.setText(c[1]);
            if (m2 != null) m2.setText(c.length > 2 ? c[2] : "");
        } catch (Throwable e) {
            Log.w(TAG, "inflate hintcard failed", e);
            cardBusy = false;
            onCardsDrained();
            return;
        }
        final View card = cardTmp;

        FrameLayout host = new FrameLayout(this);
        host.setClipChildren(false);
        final int startY = Math.round(scrH() * 0.46f);      // 屏幕中间
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        clp.topMargin = startY;
        host.addView(card, clp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        // ★★ 千万不要给卡片宿主窗口加 layoutInDisplayCutoutMode=ALWAYS！
        //   实测（2026-10-02，vc55）：加了之后宿主窗口变成「铺满整个屏幕含挖孔区」的
        //   全屏悬浮窗，ColorOS 安全中心 2 秒内就判定为风险行为，直接：
        //     removeEnableService（把无障碍服务关掉）+ 弹 RiskyBehaviorInterceptActivity
        //   —— 四张卡只播了第一张，服务就死了（日志见 app.log + logcat 的
        //   StartupManagerMaliciousPrevent / AccessibilityPkgNameRunningTime）。
        //   不加这一行时宿主窗口被系统钳在 y>=141，ColorOS 不拦。
        //   代价：卡片最上方只能到 y=141（状态栏下沿），视觉上就是「升到状态栏正下方」。
        try {
            wm.addView(host, lp);
        } catch (Throwable e) {
            Log.w(TAG, "addView card host failed", e);
            cardBusy = false;
            onCardsDrained();
            return;
        }
        cardHostWin = host;
        cardView = card;
        flog("播报 " + c[0] + " ｜ " + c[1] + "（屏高 " + scrH() + "，起点 " + startY + "）");

        card.post(new Runnable() {
            public void run() {
                final int h = card.getHeight();
                // 两段式「自然移出屏幕」（用户明确要求：一张一张、速度不要太快）：
                //   ① 从屏幕中间升到屏幕最上方 —— 减速，像被稳稳送上去，到顶正好停一下
                //   ② 再从最上方滑出屏幕 —— 加速，越走越快，像自然被推出屏幕
                final float riseTo = -(startY - HOME_Y);
                final float outTo = riseTo - (h + dp(170));
                flog("播报卡尺寸 " + card.getWidth() + "x" + h + "（起点 " + startY
                        + "，升到 " + riseTo + "，出屏 " + outTo + "）");
                card.postDelayed(new Runnable() {
                    public void run() {
                        card.animate()
                                .translationY(riseTo)
                                .setDuration(CARD_RISE_MS)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.25f))
                                .withEndAction(new Runnable() {
                                    public void run() {
                                        card.animate()
                                                .translationY(outTo)
                                                .setDuration(CARD_EXIT_MS)
                                                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.1f))
                                                .withEndAction(new Runnable() {
                                                    public void run() {
                                                        removeCardHost();
                                                        ui.postDelayed(new Runnable() {
                                                            public void run() { showNextCard(); }
                                                        }, 140L);
                                                    }
                                                })
                                                .start();
                                    }
                                })
                                .start();
                    }
                }, CARD_HOLD_MS);
            }
        });
    }

    /**
     * 队列播完 —— 如果是「开场流程」，这时候才让悬浮窗自己从屏幕中间滑到最上方。
     *
     * 顺序必须是：四张权限卡 → 悬浮窗，用户明确要求「最后才是悬浮窗」。
     */
    private void onCardsDrained() {
        if (!introMode) return;
        introMode = false;
        flog("播报队列播完 → 悬浮窗开始从中间上滑");
        bringUpToTop();
    }

    private void removeCardHost() {
        final View h = cardHostWin;
        cardHostWin = null;
        cardView = null;
        if (h != null) {
            try { if (wm != null) wm.removeView(h); } catch (Throwable ignored) {}
        }
    }

    /** 服务销毁时把队列和正在显示的卡一起收掉。 */
    private void clearCards() {
        restorePanelY();
        cardQ.clear();
        cardBusy = false;
        removeCardHost();
    }

    /** 版本名（提示卡标题用；读不到就返回空串，不抛）。 */
    private String appVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    private void updateUi() {
        ui.post(new Runnable() {
            public void run() {
                try {
                    // ★ v4.76：右栏开关统一 启用=绿 / 未启用=红（叉号除外）
                    if (btnLink != null) {
                        boolean on = auto.get();
                        btnLink.setBackgroundResource(on ? R.drawable.btn_circle_on : R.drawable.btn_circle_red);
                        btnLink.setTextColor(on ? 0xFF10301A : 0xFFFFFFFF);
                    }
                    if (btnOne != null) {
                        boolean on = fgMode != FG_OFF;   // 前台校验启用=绿，关闭=红（长按循环）
                        btnOne.setBackgroundResource(on ? R.drawable.btn_circle_on : R.drawable.btn_circle_red);
                        btnOne.setTextColor(on ? 0xFF10301A : 0xFFFFFFFF);
                    }
                    if (btnLight != null) {
                        boolean on = autoPlay.get();
                        btnLight.setBackgroundResource(on ? R.drawable.btn_circle_on : R.drawable.btn_circle_red);
                        btnLight.setTextColor(on ? 0xFF10301A : 0xFFFFFFFF);
                    }
                    if (btnLianKai != null) {
                        boolean on = liankaiOn;
                        btnLianKai.setBackgroundResource(on ? R.drawable.btn_circle_on : R.drawable.btn_circle_red);
                        btnLianKai.setTextColor(on ? 0xFF10301A : 0xFFFFFFFF);
                    }
                    // 速度档按钮已挪进设置页（v4.8），面板上没有可高亮的控件了
                } catch (Throwable ignored) {}
                updateSideBtn();
            }
        });
    }

    /** 自动 → 红 → 黑 → 自动。 */
    private void cycleSide() {
        if (sideAuto) {                 // 自动 -> 执红
            sideAuto = false;
            mySide = 'w';
        } else if (mySide == 'w') {     // 执红 -> 执黑
            mySide = 'b';
        } else {                        // 执黑 -> 自动
            sideAuto = true;
        }
        updateSideBtn();
    }

    /**
     * v4.8：执子按钮已挪进设置页 → 面板上不再有 btnSide，这里保留空实现。
     * （调用点分布在识别流程多处，删函数要动一大片，留空更稳。）
     */
    private void updateSideBtn() {
    }

    private void removeBall() {
        try { if (ball != null && wm != null) wm.removeView(ball); } catch (Exception ignored) {}
        ball = null;
    }

    private void removePanel() {
        try { if (panel != null && wm != null) wm.removeView(panel); } catch (Exception ignored) {}
        panel = null;
        tvTimer = null;   // 面板没了 → 每秒刷新自然停止
    }

    /**
     * 折叠成一个小球（可拖），点球展开。
     * 坑：inflate(R.layout.ball, null) 时根视图的 layout_width/height 会被丢弃，
     *     图标会缩成文字大小；必须自己套一层容器并显式给定尺寸。
     */

    // ------------------------------------------------ 对局计时（一局一次）

    /** 秒 → mm:ss（超 1 小时为 h:mm:ss）。 */
    private static String fmtDur(long sec) {
        if (sec < 0) sec = 0;
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%02d:%02d", m, s);
    }

    /** 每秒刷新计时显示；面板不在时自动停止。 */
    private final Runnable timerTick = new Runnable() {
        public void run() {
            if (tvTimer == null) { timerTickOn = false; return; }
            long end = gameStopMs > 0 ? gameStopMs : System.currentTimeMillis();
            long sec = gameStartMs > 0 ? (end - gameStartMs) / 1000 : 0;
            tvTimer.setText(fmtDur(sec));
            timerTickOn = true;
            ui.postDelayed(this, 1000);
        }
    };

    private void startTimerTick() {
        if (timerTickOn) return;
        timerTickOn = true;
        ui.post(timerTick);
    }

    /** 本局开始（重新计时）。 */
    private void gameTimerRestart(String why) {
        gameStartMs = System.currentTimeMillis();
        gameStopMs = 0;
        startTimerTick();
        Log.i(TAG, "计时: 本局开始(" + why + ")");
    }

    /** 本局结束（冻结用时，不再走字）。 */
    private void gameTimerStop(String why) {
        if (gameStartMs == 0 || gameStopMs > 0) return;
        gameStopMs = System.currentTimeMillis();
        Log.i(TAG, "计时: 本局结束(" + why + ") 用时 " + fmtDur((gameStopMs - gameStartMs) / 1000));
    }

    /**
     * 每帧识别结果都过一遍，一局只计一次：
     *   将帅缺一                  → 终局，冻结用时
     *   从没起过表                → 首帧即起表（中途接入也从接入时刻算起）
     *   非开局态 → 开局态（子力≥28）→ 换局了，重置计时
     *
     * 为什么用「非开局→开局」翻转而不是「上一局结束」：
     *   象棋终局是绝杀，将帅仍在盘上，靠"将帅消失"几乎永远不会触发；
     *   而子力一旦打掉就回不来（≥28 子只存在于开局头几回合），
     *   所以"子力从少回到多"就是最可靠的换局信号。
     */
    private void updateGameTimer(String board) {
        int[] ck;
        try { ck = Board.countAndKings(board); } catch (Throwable t) { return; }
        if (ck[1] < 2) {
            gameTimerStop("将帅不在盘上");
            lastWasOpening = false;
            return;
        }
        boolean opening = ck[0] >= 28;
        if (gameStartMs == 0) {
            gameTimerRestart("首帧接入");
        } else if (opening && !lastWasOpening && lastPieceCount > 0 && ck[0] - lastPieceCount >= 4) {
            gameTimerRestart("换局（子力回到 " + ck[0] + "）");
        }
        lastWasOpening = opening;
        lastPieceCount = ck[0];
    }

    // ------------------------------------------------ 走棋速度（真人化）

    public static final String K_SPEED = "speed";       // v4.8：设置页也要读写

    private volatile int speedLevel = Speed.DEFAULT;
    private final java.util.Random rnd = new java.util.Random();

    private int speedMultiPv() { return ProModule.multiPv(Speed.multiPv(speedLevel)); }


    /** 取候选着法：付费版云库优先，未命中回退本地引擎；免费版直接走引擎。 */
    private List<PikafishEngine.Move> candsOf(String fen) {
        return engine.analyse(fen, thinkTime(), speedMultiPv());
    }
    private int speedThrottle() { return Speed.throttle(speedLevel); }
    private int speedRetry() { return Speed.retry(speedLevel); }
    private int thinkTime() { return Speed.thinkTime(speedLevel, rnd); }

    /** 直接设定速度档（点选按钮调用）。 */
    private void setSpeed(int lv) {
        speedLevel = Speed.clamp(lv);
        spPut(K_SPEED, speedLevel);
        int[] rng = Speed.stepRange(speedLevel);
        setStatus("走棋速度: " + Speed.describe(speedLevel)
                + "　一步约 " + (rng[0] / 1000.0) + "~" + (rng[1] / 1000.0) + " 秒");
        Log.i(TAG, "走棋速度 → " + Speed.NAME[speedLevel] + " level=" + speedLevel
                + " 步耗时 " + rng[0] + "~" + rng[1] + "ms");
        updateUi();
    }

    /** 切换速度档：极速 → 快 → 正常 → 慢 → 极速。 */
    private void cycleSpeed() { setSpeed(Speed.next(speedLevel)); }


    private void collapse() {
        removePanel();
        if (ball != null) return;

        final int sz = dp(44);
        FrameLayout box = new FrameLayout(this);
        View dot = LayoutInflater.from(this).inflate(R.layout.ball, null);
        box.addView(dot, new FrameLayout.LayoutParams(sz, sz));
        ball = box;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                sz, sz,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Math.max(0, Math.min(sp(K_BX, scrW() - sz - dp(6)), scrW() - sz));
        lp.y = Math.max(0, Math.min(sp(K_BY, dp(200)), scrH() - sz));
        try {
            wm.addView(ball, lp);
        animateIn(ball);
        if (overlaySelfHidden) ball.setVisibility(View.INVISIBLE);
        } catch (Exception e) {
            Log.e(TAG, "addBall failed", e);
            ball = null;
            return;
        }
        attachDrag(ball, ball, lp, K_BX, K_BY, new Runnable() {
            public void run() { showPanel(); }
        });
        Log.i(TAG, "collapsed to ball " + sz + "px at " + lp.x + "," + lp.y);
        ball.post(new Runnable() {
            public void run() {
                int[] p = new int[2];
                ball.getLocationOnScreen(p);
                Log.i(TAG, "ball onScreen = [" + p[0] + "," + p[1] + "][" + (p[0] + ball.getWidth()) + "," + (p[1] + ball.getHeight()) + "]");
            }
        });
    }

    /**
     * ★ 隐藏悬浮窗期间，挂一个 1x1 全透明、不可触摸的窗口「保活」。
     *
     * 为什么需要它 —— 这是实测出来的，不是猜的：
     *   ColorOS 的电池管理（com.oplus.battery）会把「没有可见界面的应用」
     *   当成后台应用直接强制停止。日志取证：
     *       13:25:03  用音量减隐藏悬浮窗
     *       13:29:49  Force stopping com.yypm.assistant.b
     *                 Killing ... due to from pid 3736 (com.oplus.battery)
     *                 reason=10 (USER REQUESTED) subreason=21 (FORCE STOP)
     *                 importance=125  ← 只剩前台服务，没有可见窗口
     *   而此前十几次「隐藏后几十秒内就召回来」都没事 —— 差别就是有没有可见窗口。
     *   本机还有 ColorOS 的省电检查：power_check_interval=300000（每 5 分钟一次），
     *   被杀时刻距隐藏 4.7 分钟，正好落在一个检查周期里。
     *
     *   所以隐藏时不能把窗口全删掉，得留一个看不见但「存在」的窗口：
     *   系统认为应用始终有界面 → importance 提高 → 不会被当后台应用清理。
     *   1x1 像素 + 全透明 + FLAG_NOT_TOUCHABLE，用户既看不见也碰不到。
     */
    private void addKeepAlive() {
        if (keepAlive != null) return;
        try {
            keepAlive = new View(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 0;
            lp.y = 0;
            lp.alpha = 0f;
            wm.addView(keepAlive, lp);
            flog("已挂 1x1 透明保活窗口（防 ColorOS 当后台应用清理）");
        } catch (Throwable t) {
            keepAlive = null;
            Log.w(TAG, "addKeepAlive failed", t);
        }
    }

    private void removeKeepAlive() {
        try { if (keepAlive != null && wm != null) wm.removeView(keepAlive); } catch (Throwable ignored) {}
        keepAlive = null;
    }

    private void hidePanel() {
        removeKeepAlive();
        removePanel();
        removeBall();
    }

    /**
     * 从引擎 PV 里取出「对手最可能的应手」，翻成中文着法。
     *
     * 原理：PV 第 1 步 = 我们该走的；第 2 步 = 走完之后对手的最佳应手。
     * 引擎本来就算了（PikafishEngine.Move.ponder），只是以前没人用，算完就丢。
     */
    /**
     * 组装「我方：X　敌方：Y」两段文字。
     *
     * 我方 = 引擎给出的最佳着法（标准中文记谱，红用中文数字、黑用阿拉伯数字）
     * 敌方 = 引擎 PV 的第 2 步，即我走完之后对手最可能的应手（对齐鲨鱼象棋的预判显示）
     */
    /** 把「我方：X　敌方：Y」上色：我方绿、敌方紫（和箭头颜色一一对应）。 */
    /**
     * 让小棋盘把悬浮窗里的空余区域全部吃掉。
     *
     * 面板高度本来就是由左栏（按钮 + 文字行 + 分析区）决定的，而小棋盘以前固定 80dp 宽、
     * 高约 89dp —— 分析区一展开，棋盘右侧和下方就空出一大块，白白浪费。
     * 这里在左栏每次布局完成后，把棋盘高度对齐到左栏高度，宽度由 BoardView 按 9:10 反推。
     * 结果是「面板尺寸不变、棋盘尽量大」；分析区折叠时左栏变矮，棋盘也自动跟着变小。
     * 左栏最小宽度用按钮排的自然宽度兜底，保证棋盘再大也不会把按钮挤出去。
     */
    private void setupMiniBoardFit(final View panel) {
        try {
            colLeft = panel.findViewById(R.id.col_left);
            View rowBtn = panel.findViewById(R.id.row_btn);
            if (rowBtn != null) {
                rowBtn.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                               View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                leftMinW = rowBtn.getMeasuredWidth();
            }
            if (colLeft == null) return;
            colLeft.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                public void onLayoutChange(View v, int l, int t, int r, int b,
                                           int ol, int ot, int or, int ob) {
                    fitMiniBoard(panel);
                }
            });
            colLeft.post(new Runnable() { public void run() { fitMiniBoard(panel); } });
        } catch (Throwable ignored) {}
    }

    /**
     * ★ v4.36：内容超出固定面板高度时，压缩左栏各行之间的上边距。
     *
     * 目的：面板高度写死后，如果「所有构件都显示 + 分析区展开」顶出边界，
     * 不能靠长高（那就违背「大小不变」），也不能裁掉（内容会丢）——
     * 只能挤一挤行距。每行至少留 4px，压缩总量受这份余量上限约束，
     * 所以不会把内容挤成一团；压完重排一次，够不够由 fitMiniBoard 再判一次。
     *
     * @return 是否真的压缩了（压了才需要重排）
     */
    private boolean shrinkColLeft(View col, int overPx) {
        if (!(col instanceof android.widget.LinearLayout)) return false;
        android.widget.LinearLayout ll = (android.widget.LinearLayout) col;
        int n = ll.getChildCount();
        int budget = 0;
        for (int i = 0; i < n; i++) {
            android.view.ViewGroup.LayoutParams lp0 = ll.getChildAt(i).getLayoutParams();
            if (lp0 instanceof android.view.ViewGroup.MarginLayoutParams) {
                int tm = ((android.view.ViewGroup.MarginLayoutParams) lp0).topMargin;
                if (tm > 4) budget += tm - 4;
            }
        }
        if (budget <= 0) return false;
        int cut = Math.min(overPx, budget);
        if (cut <= 0) return false;
        int acc = 0;
        for (int i = 0; i < n && acc < cut; i++) {
            View c = ll.getChildAt(i);
            android.view.ViewGroup.LayoutParams lp1 = c.getLayoutParams();
            if (!(lp1 instanceof android.view.ViewGroup.MarginLayoutParams)) continue;
            android.view.ViewGroup.MarginLayoutParams mlp =
                    (android.view.ViewGroup.MarginLayoutParams) lp1;
            if (mlp.topMargin <= 4) continue;
            int take = Math.min(mlp.topMargin - 4, cut - acc);
            mlp.topMargin -= take;
            acc += take;
            c.setLayoutParams(mlp);
        }
        flog("面板内容超高，压缩左栏行距 " + acc + "px（超出 " + overPx + "px）");
        return acc > 0;
    }

    /** 按左栏高度给棋盘定尺寸（9:10 比例），并按可用宽度封顶。 */
    private void fitMiniBoard(View panel) {
        try {
            if (miniBoard == null || colLeft == null) return;
            View row = (View) colLeft.getParent();
            if (row == null || row.getWidth() <= 0) return;
            float dens = getResources().getDisplayMetrics().density;
            // ★ v4.32：面板高度固定后，内容高也必须固定 —— 不再读 colLeft.getHeight()。
            //   原来折叠分析区 / 隐藏构件都会让左栏变矮 → 棋盘跟着缩 → 面板跟着矮，
            //   用户反馈的「动一个设置悬浮窗就变一次大小」根因就在这里。
            int colH = panelContentH(this);
            if (colH <= 0) return;
            int leftAct = colLeft.getHeight();
            if (leftAct > colH + 2) {
                flog("★面板内容溢出 固定=" + colH + " 实需=" + leftAct);
                // ★ v4.36：所有构件都显示时会顶出固定高度 —— 自动压缩左栏行距，
                //   保证「面板大小不变」的前提下一定放得下（用户点名要求）。
                if (shrinkColLeft(colLeft, leftAct - colH))
                    colLeft.post(new Runnable() { public void run() { fitMiniBoard(panel); } });
            }

            int overhead = row.getPaddingLeft() + row.getPaddingRight();
            android.view.ViewGroup.LayoutParams blp = miniBoard.getLayoutParams();
            if (blp instanceof android.view.ViewGroup.MarginLayoutParams)
                overhead += ((android.view.ViewGroup.MarginLayoutParams) blp).leftMargin;
            // ★ v4.7：棋盘移到左边之后，「棋盘」和「控制栏」之间的 4dp 间隙挂到了
            //   控制栏的 leftMargin 上（原来挂在棋盘的 leftMargin 上）。
            //   不补这一笔，可用宽会多算 4dp(14px) → 控制栏被压到自然宽以下 →
            //   文字行异常变高（正是 v4.0 踩过的那个事故）。
            //   两种排布都加、总和不变，所以不分情况。
            if (colLeft.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams)
                overhead += ((android.view.ViewGroup.MarginLayoutParams) colLeft.getLayoutParams()).leftMargin;

            // ★ v4.0 修：右栏改成了「容器」(col_right)，它自己的 leftMargin 也必须算进来。
            //   旧代码只算了 ✕ 按钮的 margin —— 而 ✕ 现在在容器内部、自身 margin 为 0，
            //   于是漏掉容器那 14px，棋盘多占 14px，左栏被挤到 555px，
            //   而按钮排自然宽 569px 放不下 → 文字行被压窄后异常变高（实测 mvrow 涨到 237px）。
            View colR = panel.findViewById(R.id.col_right);
            int cw = (colR == null) ? 0 : colR.getWidth();
            if (cw <= 0) cw = (int) (30 * dens);
            overhead += cw;
            if (colR != null && colR.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams)
                overhead += ((android.view.ViewGroup.MarginLayoutParams) colR.getLayoutParams()).leftMargin;

            // ★ v4.24：上限取「除棋盘外最高的那一栏」的高度 —— 也就是「棋盘还很小时的
            //   面板高度」。用它当上限，棋盘再大也撑不高面板（面板大小不变）。
            int availH = Math.max(colH, (colR == null) ? 0 : colR.getHeight());
            int bscale = sp(K_UI_BOARD_SCALE, K_UI_BOARD_SCALE_DEF);
            int h = calcBoardH(row.getWidth(), colH, availH, leftMinW, cw, overhead, bscale, dens);

            android.view.ViewGroup.LayoutParams lp = miniBoard.getLayoutParams();
            if (lp.height == h) return;
            lp.height = h;
            lp.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            miniBoard.setLayoutParams(lp);
            flog("棋盘适配 行宽=" + row.getWidth() + " 面板内容高=" + availH + " 左栏高=" + colH
                    + " 按钮排宽=" + leftMinW + " 右栏=" + cw + " overhead=" + overhead
                    + " 缩放=" + bscale + "% -> 棋盘 " + (int) Math.round(h * 0.9f) + "x" + h);
        } catch (Throwable ignored) {}
    }

    /**
     * ★ v4.21：棋盘高度的**唯一**算法（真机面板与设置页预览共用同一个，避免两边漂移）。
     *
     * 设计目标（用户点名要的）：
     *   悬浮窗整体大小不变，而棋盘的「高度」可以调到和悬浮窗差不多高。
     *
     * 怎么做到：
     *   · 面板高度 = max(左栏高, 棋盘高)。所以只要「棋盘高 ≤ 左栏高」，面板高度就一点不变
     *     —— 上限取 colH（左栏高），这就是「悬浮窗大小不变」。
     *   · 棋盘宽 = 高 × 0.9，要宽度就得从左栏身上拿。100% 时左栏不许低于「按钮排自然宽」
     *     （否则文字换行、面板反被撑高 —— v4.0 踩过这个坑）。
     *   · 所以只有放大（scale > 100）时才放开这条线：允许左栏一直退到 150dp 的硬下限，
     *     把腾出来的宽度全给棋盘。这样拉到 200% 时，棋盘才真能长到面板那么高。
     *
     * @param rowContentW 面板内容区总宽（row_main 的宽）
     * @param colH        左栏实测高（= 面板内容高）
     * @param leftMinW    按钮排自然宽（左栏的「舒服下限」）
     * @param colRW       右栏实测宽
     * @param overhead    内边距 + 右栏 + 各 leftMargin 之和
     * @param scalePct    用户缩放百分比，50~200
     */
    public static int calcBoardH(int rowContentW, int colH, int availH, int leftMinW, int colRW,
                                 int overhead, int scalePct, float dens) {
        if (scalePct < 50) scalePct = 50;
        if (scalePct > 200) scalePct = 200;
        int reserve = Math.max(leftMinW, (int) (150 * dens));   // 留给左栏的宽（不许比这更窄）
        int minH = (int) (60 * dens);

        int byW = (int) Math.floor((rowContentW - overhead - reserve) / 0.9f);
        if (byW < minH) byW = minH;

        int h100 = Math.min(colH, byW);     // 100% = 原来那样：棋盘高度对齐「左栏」
        // ★ v4.24 的关键修正：上限要用「面板内容高 availH」，而不是「左栏高 colH」。
        //   面板高度通常是被右栏那摞竖排按钮撑起来的（实测 595 vs 左栏 216），
        //   以前拿左栏高当上限，棋盘就永远只有面板的 1/3 —— 用户反馈的正是这个。
        int hCap = Math.min(availH, byW);

        int h;
        if (scalePct <= 100) {
            h = Math.round(h100 * scalePct / 100f);
        } else {
            // 100%~200%：从「对齐左栏」平滑长到「顶满面板」，而面板本身大小不变
            float t = (scalePct - 100) / 100f;
            h = Math.round(h100 + (hCap - h100) * t);
        }
        if (h > hCap) h = hCap;
        if (h < minH) h = minH;
        return h;
    }

    private CharSequence colorizeMoves(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        try {
            android.text.SpannableString sp = new android.text.SpannableString(s);
            int i = s.indexOf("我方：");
            int j = s.indexOf("敌方：");
            int segEnd = (j > 0) ? j : s.length();
            if (i >= 0) sp.setSpan(new android.text.style.ForegroundColorSpan(0xFF2BE06B), i, segEnd,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (j >= 0) sp.setSpan(new android.text.style.ForegroundColorSpan(0xFFB16CFF), j, s.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return sp;
        } catch (Throwable t) { return s; }
    }




    // ================= V1.1：敌方候选预判 =================

    /** 预判专用线程。绝不能占用 pool（它是单线程），否则会把识别/决策链路堵住。 */
    private final ExecutorService predPool = Executors.newSingleThreadExecutor();
    private final AtomicBoolean predBusy = new AtomicBoolean(false);

    /** 敌方候选预判结果。 */
    private static class Pred {
        int[][] rc;      // 最多 3 个，board row/col
        String text;     // 完整显示文字（我方 + 敌方候选）
    }

    /**
     * 显示走向箭头，并异步补上「敌方最多 3 个候选」。
     *
     * 先用引擎 PV 第 2 步（ponder）立刻画一个紫箭头，保证马上有东西看；
     * 再在后台对「我走完之后」的局面重新问引擎一次 MultiPV，按起点格去重
     * —— 也就是「三个不同棋子」—— 拿最多 3 条回来补画，带 ①②③ 序号。
     *
     * 敌方真正能走的棋子不足 3 个时（被将军只有一两种应法、子力太少、
     * 或引擎只给出 2 条可用主变），有多少画多少，不硬凑。
     */
    private void showMovesAndPredict(final String placeBefore, final PikafishEngine.Move best,
                                     final boolean flipped, final char mySide, final String rpText) {
        if (best == null || best.uci == null || best.uci.length() < 4) return;
        final int[] myRc = rc(best.uci, flipped);
        final int[] oppRc0 = rc(best.ponder, flipped);
        ui.post(new Runnable() {
            public void run() {
                if (miniBoard != null) miniBoard.setMoves(myRc, null);
            }
        });
    }

    /**
     * 对「我走完之后」的局面问一次 MultiPV，取最多 3 个**不同棋子**的应手。
     *
     * 去重键用起点格（uci 前两位）：同一个子走到不同落点只保留引擎评分最高的那条，
     * 这样得到的才是「3 个棋子的动向」，而不是「同一个子的 3 种走法」。
     */
    /** 免费版：不做敌方预判。 */
    private Pred predictReplies(String placeBefore, PikafishEngine.Move best,
                                boolean flipped, char mySide) { return null; }
    /** 把着法转成 board row/col，用于在 mini 棋盘上画走向箭头。 */
    private int[] rc(String uci, boolean flipped) {
        return BoardView.rcOfUci(uci, flipped);
    }


    /**
     * 轮次状态文案。
     *
     * 为什么带上「点此改」：中途接入对局时，「轮到谁」从单帧画面无法可靠推断
     * （不知道双方各走了几手）。自动判定猜错时，用户点一下状态行就能立刻纠正，
     * 不用重开连线，也不会卡死在「等对手走棋」。
     */
    private void setTurnStatus() {
        if (linkState == ST_MY_TURN) setStatus("连线: 该我走…(点此改等对手)");
        else setStatus("连线: 等对手…(点此改我走)");
    }

    /** 前台校验档位的可读名。 */
    private String fgModeName() {
        if (fgMode == FG_STRICT) return "严格（只认象棋类App）";
        if (fgMode == FG_OFF) return "关闭（任何界面都识别）";
        return "宽松（除本应用和系统界面外都识别）";
    }

    /** 关键诊断写盘。logcat 在这台机器上长时间运行后会取不到内容，写文件最可靠。 */
    private void flog(String s) {
        try {
            File d = getExternalFilesDir("logs");
            if (d == null) d = getFilesDir();
            if (d != null && !d.exists()) d.mkdirs();
            File f = new File(d, "app.log");
            // ★ v4.58：以前是直接 delete —— 一超过 1MB 就把**整份日志**删掉，
            //   出事之后什么都没有（12:11 那份日志就是这样消失的，前因全没了）。
            //   改成滚动保留上一份。
            if (f.length() > 1024 * 1024) {
                File bak = new File(d, "app.log.1");
                try { if (bak.exists()) bak.delete(); } catch (Throwable ignored) {}
                try { f.renameTo(bak); } catch (Throwable ignored) {}
                if (f.exists()) { try { f.delete(); } catch (Throwable ignored) {} }
            }
            FileOutputStream fo = new FileOutputStream(f, true);
            fo.write((new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS",
                    java.util.Locale.US).format(new java.util.Date()) + "  " + s + "\n").getBytes("UTF-8"));
            fo.close();
        } catch (Throwable ignored) {}
    }

    /** 取某格的识别置信度；拿不到就返回 -1（日志里显示 -1 表示这一格没参与投票）。 */
    /** 以 (x,y) 为中心、边长 2r 的方块里，亮像素（棋子木色）的占比。
     *  棋盘底是青灰（亮度 ~130~160），棋子木色（~190~255），阈值 175 能分开。
     *  返回 -1 表示取不到图。 */
    /** 整帧是否是无内容的空白帧。
     *
     *  屏幕捕获中途失效时（虚拟显示器被系统回收、受保护内容等），
     *  MediaProjection 会持续吐白屏或黑屏。这种帧上跑识别，ONNX 分类结果是乱的，
     *  但「多帧投票」会把 chars 救回上一帧的正确局面 —— 于是就出现
     *  「棋盘看着是好的、点击却全点飞」。先在这里把无效帧挡掉。
     */
    private static boolean isBlankFrame(Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) return true;
        int W = bmp.getWidth(), H = bmp.getHeight();
        int step = Math.max(1, Math.min(W, H) / 120);
        int n = 0, bright = 0, dark = 0;
        int[] row = new int[W];
        for (int y = 0; y < H; y += step) {
            try { bmp.getPixels(row, 0, W, 0, y, W, 1); } catch (Throwable t) { return false; }
            for (int x = 0; x < W; x += step) {
                int c = row[x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                int lum = (r * 77 + g * 151 + b * 28) >> 8;
                n++;
                if (lum >= 228) bright++;
                if (lum <= 26) dark++;
            }
        }
        if (n == 0) return true;
        return bright * 100L / n >= 80L || dark * 100L / n >= 80L;
    }
    private static float pieceFillAt(Bitmap bmp, float x, float y, float r) {
        if (bmp == null || bmp.isRecycled()) return -1f;
        int W = bmp.getWidth(), H = bmp.getHeight();
        int n = Math.max(3, Math.round(r * 2));
        int x0 = Math.round(x) - n / 2, y0 = Math.round(y) - n / 2;
        if (x0 < 0) x0 = 0;
        if (y0 < 0) y0 = 0;
        if (x0 + n > W) n = W - x0;
        if (y0 + n > H) n = H - y0;
        if (n <= 2) return -1f;
        int[] buf = new int[n * n];
        try { bmp.getPixels(buf, 0, n, x0, y0, n, n); } catch (Throwable t) { return -1f; }
        int hit = 0;
        for (int i = 0; i < buf.length; i++) {
            int c = buf[i];
            int rr = (c >> 16) & 0xFF, gg = (c >> 8) & 0xFF, bb = c & 0xFF;
            if (((rr * 77 + gg * 151 + bb * 28) >> 8) >= 175) hit++;
        }
        return hit / (float) buf.length;
    }

    /**
     * 坐标自检：识别说有棋子的格子，其屏幕坐标处必须真的有亮块。
     *
     * 为什么必须有这一关：ONNX 是按「拉正图」固定 90 格分类的，而落子坐标是
     * 由 classify 里 snapAxis 在拉正图上另找的网格反算出来的。这两条链路一旦
     * 不一致（snapAxis 被棋子边缘带偏），就会出现「识别正确、点击点飞」——
     * 表现就是反复「等落子生效」却永远不动。用亮块把这两条链路对一遍，
     * 错位当场就能抓到，而不是等用户发现走不动棋。
     *
     * 返回匹配率；-1 表示无法判定（取不到图）。
     */
    private static float gridPictureMatch(Bitmap bmp, BoardVision.Result r, float radius) {
        if (bmp == null || r == null || r.points == null || r.chars == null) return -1f;
        int checked = 0, ok = 0;
        for (int rr = 0; rr < r.chars.length; rr++) {
            for (int cc = 0; cc < r.chars[rr].length; cc++) {
                char pc = r.chars[rr][cc];
                if (pc == 0 || pc == '.' || pc == 'x') continue;
                if (rr < 0 || rr >= r.points.length) continue;
                if (cc * 2 + 1 >= r.points[rr].length) continue;
                float f = pieceFillAt(bmp, r.points[rr][cc * 2], r.points[rr][cc * 2 + 1], radius);
                if (f < 0) continue;
                checked++;
                if (f >= 0.45f) ok++;
            }
        }
        if (checked < 6) return -1f;
        return ok / (float) checked;
    }
    private static float confAt(BoardVision.Result r, int rr, int cc) {
        try {
            if (r == null || r.conf == null) return -1f;
            if (rr < 0 || cc < 0 || rr >= r.conf.length || cc >= r.conf[rr].length) return -1f;
            return Math.round(r.conf[rr][cc] * 100) / 100f;
        } catch (Throwable t) { return -1f; }
    }

    private String lastStatusLogged = "";
    private long lastStatusAt = 0L;

    private void setStatus(final String s) {
        long t = System.currentTimeMillis();
        if (s != null && (!s.equals(lastStatusLogged) || t - lastStatusAt > 3000L)) {
            lastStatusLogged = s;
            lastStatusAt = t;
            flog("状态  " + s);
        }
        ui.post(new Runnable() { public void run() { if (tvStatus != null) tvStatus.setText(s); } });
    }

    /** 关闭连线循环。 */
    /** 连续落子无效 → 停手，像鲨鱼象棋那样提示「未识别到正确局面」。 */
    private void pauseLink(String why) {
        linkPaused = true;
        linkFailTry = 0;
        linkExpect = null;
        linkState = ST_INIT;
        linkBoard = "";
        auto.set(false);
        updateUi();
        setStatus("未识别到正确局面（" + why + "）");
        Log.w(TAG, "停止连线: " + why);

}
    private void stopLink() {
        sLinkLoop.set(false);
        linkGen++;
        linkBoard = ""; repairBase = "";
        linkExpect = null;
        linkMover = 0;
        badFrames = 0;
        linkState = ST_INIT;
        setStatus("连线: 已关闭");
    }

    /** 把识别到的棋盘同步到悬浮窗（同时推给主界面总线）。 */
    /** 把识别到的棋盘同步到悬浮窗小棋盘，同时推给主界面总线。 */
    private void syncMini(final char[][] ch, final float[][] cf, final String side) {
        BoardBus.push(ch, cf, null, side);
        ui.post(new Runnable() {
            public void run() {
                try { if (miniBoard != null) miniBoard.setBoard(ch, cf, ""); } catch (Throwable ignored) {}
            }
        });
    }

    // ------------------------------------------------ 主流程
    private void doOne() {
        if (!busy.compareAndSet(false, true)) return;
        busyAt = System.currentTimeMillis();
        // ★ v4.6：音量条正在屏幕上（会盖住棋盘右上角）→ 本帧不识别，等它消失
        if (volPanelBlocking()) {
            busy.set(false);
            setStatus("正在调音量…（音量条会挡棋盘，稍等）");
            if (!volBlockLogged) {
                volBlockLogged = true;
                flog("音量条遮挡：暂停识别 —— 音量条实测盖住棋盘右上角 x[1063,1178] y[798,940]");
            }
            return;
        }
        if (fgMode != FG_OFF && !isChessForeground()) {
            Log.i(TAG, "前台校验拦下(识别): " + lastFgPkg()); setStatus("未在象棋对局（最近前台 " + fgTop + "）");
            busy.set(false);
            return;
        }
        if (!CaptureService.isRunning()) {
            sLinkLoop.set(false);
            busy.set(false);
            // 用户手点「识」= 明确的手动意图：清掉重试计数、不受冷却限制
            if (!requestCapture(false, true))
                setStatus("屏幕捕获未开启 —— 点悬浮窗「链」重新授权");
            return;
        }
        setStatus("截图中…");
        captureNow(new ScreenCallback() {
            public void onShot(final Bitmap bmp) {
                if (bmp == null) { setStatus("截图失败"); busy.set(false); return; }
                pool.execute(new Runnable() {
                    public void run() {
                        try { analyzeAndHint(bmp); }
                        finally { recycleQuiet(bmp); }
                    }
                });
            }
        });
    }

    /**
     * 取一帧，并把悬浮窗占的那块像素抹掉。
     *
     * 为什么不隐藏悬浮窗：
     *   以前是「setOverlayVisible(false) → 等 200ms → 截图 → 再显示」，
     *   每 700ms 就闪一下，肉眼很明显。鲨鱼象棋的悬浮窗不闪，因为它压根不隐藏，
     *   而是在识别前把面板那一块直接从画面里抠掉。
     *   只有悬浮窗真的压住棋盘时，才退化成短暂隐藏。
     */
    private void captureNow(final ScreenCallback cb) {
        // ★ 无论走哪条链路，回调最多触发一次、且一定会触发（见 onceShot）。
        final ScreenCallback once = onceShot(cb, 4000L);
        if (panelOverBoard()) {
            setOverlayVisible(false);
            ui.postDelayed(new Runnable() {
                public void run() {
                    captureScreen(new ScreenCallback() {
                        public void onShot(Bitmap bmp) {
                            setOverlayVisible(true);
                            once.onShot(bmp);
                        }
                    });
                }
            }, 60);
            return;
        }
        captureScreen(new ScreenCallback() {
            public void onShot(Bitmap bmp) {
                try { maskOverlay(bmp); } catch (Throwable ignored) {}
                once.onShot(bmp);
            }
        });
    }

    /** ★ 保证回调最多一次、并且一定有回调。
     *  截图链路任何一环不回调（MediaProjection 不出帧、takeScreenshot 不回话），
     *  busy 就会永远为 true，连线循环从此静默停摆 —— 界面正是停在「连线: 算招中…」。 */
    private ScreenCallback onceShot(final ScreenCallback cb, final long timeoutMs) {
        final AtomicBoolean fired = new AtomicBoolean(false);
        ui.postDelayed(new Runnable() {
            public void run() {
                if (fired.compareAndSet(false, true)) {
                    Log.w(TAG, "截图回调超时(" + timeoutMs + "ms)，按失败处理");
                    cb.onShot(null);
                }
            }
        }, timeoutMs);
        return new ScreenCallback() {
            public void onShot(Bitmap bmp) {
                if (fired.compareAndSet(false, true)) cb.onShot(bmp);
            }
        };
    }

    /** 悬浮窗当前屏幕矩形 [x0,y0,x1,y1]；没有则 null。 */
    private int[] panelRect() {
        View v = panel != null ? panel : ball;
        if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) return null;
        int[] p = new int[2];
        try { v.getLocationOnScreen(p); } catch (Throwable t) { return null; }
        return new int[]{p[0], p[1], p[0] + v.getWidth(), p[1] + v.getHeight()};
    }

    /** 用面板正下方一行的像素覆盖掉面板区域 —— 抹掉悬浮窗，又不影响棋盘。 */
    private void maskOverlay(Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) return;
        int[] pr = panelRect();
        if (pr == null) return;
        int W = bmp.getWidth(), H = bmp.getHeight();
        int x0 = Math.max(0, pr[0]), x1 = Math.min(W, pr[2]);
        int y0 = Math.max(0, pr[1]), y1 = Math.min(H, pr[3]);
        if (x1 - x0 < 2 || y1 - y0 < 2) return;
        int sy = Math.min(H - 1, y1 + 8);
        int n = x1 - x0;
        int[] row = new int[n];
        try { bmp.getPixels(row, 0, n, x0, sy, n, 1); } catch (Throwable t) { return; }
        for (int y = y0; y < y1; y++) {
            try { bmp.setPixels(row, 0, n, x0, y, n, 1); } catch (Throwable t) { break; }
        }
    }

    /** 悬浮窗是否压住了上一次识别到的棋盘（压住才需要隐藏）。 */
    private boolean panelOverBoard() {
        int[] pr = panelRect(), bb = lastBoardBox;
        if (pr == null || bb == null) return false;
        int ix = Math.min(pr[2], bb[2]) - Math.max(pr[0], bb[0]);
        int iy = Math.min(pr[3], bb[3]) - Math.max(pr[1], bb[1]);
        if (ix <= 0 || iy <= 0) return false;
        float area = (float) (bb[2] - bb[0]) * (bb[3] - bb[1]);
        if (area <= 0) return false;
        return (ix * (float) iy) / area > 0.05f;
    }

    /** 记下棋盘外接框，供 panelOverBoard 判断是否遮挡。 */
    private void rememberBoardBox(float[][] pts) {
        if (pts == null || pts.length == 0) return;
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -1, y1 = -1;
        for (int r = 0; r < 10 && r < pts.length; r++) {
            for (int c = 0; c < 9 && c * 2 + 1 < pts[r].length; c++) {
                float x = pts[r][c * 2], y = pts[r][c * 2 + 1];
                if (x < x0) x0 = x;
                if (x > x1) x1 = x;
                if (y < y0) y0 = y;
                if (y > y1) y1 = y;
            }
        }
        if (x1 > x0 && y1 > y0)
            lastBoardBox = new int[]{(int) x0, (int) y0, (int) x1, (int) y1};
    }

    /** 隐藏 / 恢复悬浮窗（截图期间必须隐藏，否则面板会被拍进画面）。
     *  另外叠加 overlaySelfHidden：主界面在前台时即使要求显示也保持隐藏。 */
    private void setOverlayVisible(final boolean v) {
        ui.post(new Runnable() {
            public void run() {
                int vis = (v && !overlaySelfHidden) ? View.VISIBLE : View.INVISIBLE;
                try { if (panel != null) panel.setVisibility(vis); } catch (Exception ignored) {}
                try { if (ball != null) ball.setVisibility(vis); } catch (Exception ignored) {}
            }
        });
    }
    /** 本应用自己的界面是否正在前台。
     *
     *  ① 首选 Activity 生命周期标志（onResume/onPause 直接维护，最可靠）；
     *  ② 退化到 getWindows() 枚举，用于服务与 Activity 不同进程的极端情况。 */
    private boolean isSelfForeground() {
        // ① Activity 生命周期标志优先，最可靠
        if (mainUiForeground) return true;
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return false;
            String me = getPackageName();
            for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
                try {
                    if (w.getType()
                            != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                    if (!w.isActive() && !w.isFocused()) continue;
                    android.view.accessibility.AccessibilityNodeInfo root = w.getRoot();
                    if (root == null) continue;
                    CharSequence p = root.getPackageName();
                    if (p != null && me.contentEquals(p)) return true;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 按 overlaySelfHidden 决定悬浮窗实际可见性。 */
    private void applyOverlaySelfHidden(boolean hidden) {
        // ★ v4.33：设置页开着的时候，真悬浮窗必须留在屏幕上 —— 那一页调的就是它本人。
        if (settingsOpen) hidden = false;
        if (hidden == overlaySelfHidden) return;
        overlaySelfHidden = hidden;
        ui.post(new Runnable() {
            public void run() {
                int iv = overlaySelfHidden ? View.INVISIBLE : View.VISIBLE;
                try { if (panel != null) panel.setVisibility(iv); } catch (Exception ignored) {}
                try { if (ball != null) ball.setVisibility(iv); } catch (Exception ignored) {}
                try { if (hintCard != null) hintCard.setVisibility(iv); } catch (Exception ignored) {}
            }
        });
        flog(hidden ? "主界面在前台，临时隐藏悬浮窗" : "已离开主界面，恢复悬浮窗");
    }

    private void analyzeAndHint(Bitmap bmp) {
            if (new File(getFilesDir(), "debug").exists()) {
                try {
                    FileOutputStream fo = new FileOutputStream(new File(getFilesDir(), "last.png"));
                    bmp.compress(Bitmap.CompressFormat.PNG, 90, fo);
                    fo.close();
                    Log.i(TAG, "saved last.png " + bmp.getWidth() + "x" + bmp.getHeight());
                } catch (Throwable t) {
                    Log.w(TAG, "save png failed", t);
                }
            }
        try {
            if (vision == null || !vision.isLoaded()) { setStatus("模型未就绪"); return; }
            setStatus("识别中…");
            long t0 = System.currentTimeMillis();
            BoardVision.Result r = vision.detect(bmp);
            // ★ v4.27：记下这一段识别耗时，用于自适应轮询间隔（间隔不足会追尾）
            long dtDetect = System.currentTimeMillis() - t0;
            lastDetectMs = dtDetect;
            detectMsAvg = (detectMsAvg <= 0) ? dtDetect : (detectMsAvg * 3 + dtDetect) / 4;
            if (!r.ok) { setStatus("识别失败: " + r.message); return; }
            if (!r.gridOk) { setStatus("识别不可信（格点异常），重拍"); return; }

            // —— 自动识别执红 / 执黑 ——
            String sideNote = "";
            if (sideAuto) {
                // 将帅位置是铁证，绝对优先；子力加权只在认不出将帅时兜底
                char s = BoardVision.sideOfByKing(r.chars);
                boolean strongByKing = (s != 0);
                if (s == 0) s = BoardVision.sideOf(r.chars, r.conf, 0.5f);
                boolean strong = (s != 0);
                if (!strong) s = BoardVision.sideOfByKing(r.chars);   // 退化方案
                if (s != 0) {
                    mySide = s;
                    sideFromVision = strong;
                    sideNote = strong ? "" : "(估)";
                }
                updateSideBtn();
            }

            // 每帧识别成功就同步给主界面（主界面棋盘跟着实时变）
            BoardBus.push(r.chars, r.conf, r.fen, (mySide == 'w' ? "执红" : "执黑"));
            // 同步悬浮窗小棋盘 + 记住棋盘位置（判断悬浮窗是否遮挡）
            updateGameTimer(r.fen);
            syncMini(r.chars, r.conf, (mySide == 'w' ? "执红" : "执黑"));
            rememberBoardBox(r.points);
            String place = r.fen.trim().split("\\s+")[0];
            StringBuilder cp = new StringBuilder();
            for (int i = 0; i < Math.min(14, r.fen.length()); i++) {
                cp.append(Integer.toHexString(r.fen.charAt(i))).append(' ');
            }
            Log.i(TAG, "FEN len=" + r.fen.length() + " placeLen=" + place.length()
                    + " rows1=" + r.fen.split("/").length
                    + " rows2=" + place.split("/").length
                    + " dir=" + r.direction + " minConf=" + r.minConf);
            Log.i(TAG, "CP=" + cp);
            Log.i(TAG, "LAYOUT=" + BoardVision.layoutOf(r.chars).replace("\n", " | "));
            boolean flipped = "flipped".equals(r.direction);
            String fen = flipped ? Board.rotate180(r.fen, mySide) : (r.fen.split("\\s+")[0] + " " + mySide);
            // 校验必须在「翻转成标准朝向」之后：执黑时原始 FEN 上下颠倒，
            // 直接校验会把合法局面判成「红仕不在九宫」，每帧都丢、永远认不出来。
            String bad = Board.validate(fen);
            Log.i(TAG, "validate=" + bad);
            if (bad != null) { setStatus("识别不可靠: " + bad); return; }
            lastFen = fen;

            setStatus(engine.isReady() ? "算招中…" : "引擎恢复中…");
            List<PikafishEngine.Move> cands = candsOf(fen);
            if (cands.isEmpty()) {
                String why = engine.deathReason();
                setStatus(why.length() > 0
                        ? ("引擎异常(" + why + ")，已自动重启，稍后再点")
                        : "引擎无着法（可能已终局）");
                return;
            }
            PikafishEngine.Move best = cands.get(0);

            String cn = Board.uciToCnStd(best.uci, Board.placeOf(fen) + " " + mySide);
            final String msg = cn;
            final String info = "我执" + (mySide == 'w' ? "红" : "黑") + sideNote
                    + " | 形势 " + best.scoreText() + " | 深度 " + best.depth
                    + " | " + best.nps + " nps | " + (System.currentTimeMillis() - t0) + "ms";
            if (evalBar != null) {
                evalBar.setEval(best.scoreCp, best.hasMate, best.scoreMate, mySide);
                evalBar.setAnalysis(analysisText(best, cn, mySide));   // ★ v4.70：条上显示形势+推荐
            }
                Log.i(TAG, "side=" + mySide + sideNote + " fen=" + fen + " best=" + best.uci);
            BoardBus.push(r.chars, r.conf, fen, (mySide == 'w' ? "执红" : "执黑"));
            BoardBus.pushMove(msg, info);

            float[] mark = uciToScreen(best.uci, r, flipped);
        showMovesAndPredict(Board.placeOf(fen), best, flipped, mySide, "");
            if (mark != null) {
                lastMark = mark;
                setStatus("就绪");
                // 落子由连线状态机负责（识别一步只提示）
            } else {
                setStatus("坐标映射失败");
            }
        } catch (Throwable t) {
            Log.e(TAG, "analyze", t);
            setStatus("异常: " + t.getMessage());
        } finally {
            busy.set(false);
        }
    }

    private float[] uciToScreen(String uci, BoardVision.Result r, boolean flipped) {
        if (uci == null || uci.length() < 4 || r.points == null) return null;
        try {
            int c1 = Board.COLS.indexOf(uci.charAt(0)), r1 = Board.rowOfRank(uci.charAt(1) - '0');
            int c2 = Board.COLS.indexOf(uci.charAt(2)), r2 = Board.rowOfRank(uci.charAt(3) - '0');
            if (flipped) {
                r1 = 9 - r1; r2 = 9 - r2; c1 = 8 - c1; c2 = 8 - c2;
            }
            return new float[]{
                    r.points[r1][c1 * 2], r.points[r1][c1 * 2 + 1],
                    r.points[r2][c2 * 2], r.points[r2][c2 * 2 + 1]
            };
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------ 截图
    private interface ScreenCallback { void onShot(Bitmap b); }

    private long grabFailAt = 0L;
    private int grabFailStreak = 0;
    /** 取不到帧时写一条文件日志（最多每 3 秒一条）：区分「没有帧」和「有帧但内容无效」。 */
    private long noteGrabFail() {
        grabFailStreak++;
        long now = System.currentTimeMillis();
        if (now - grabFailAt > 3000L) {
            grabFailAt = now;
            flog("取帧失败 x" + grabFailStreak + "（捕获=" + CaptureService.isRunning()
                    + " 帧龄=" + CaptureService.frameAge() + "ms 累计帧=" + CaptureService.frameCount()
                    + " 停止原因=\"" + CaptureService.lastStopReason() + "\"）");
        }
        return now;
    }

    private void captureScreen(final ScreenCallback cb) {
        // 优先用 MediaProjection 取帧。本机无障碍 takeScreenshot 持续 error=1，不可用；
        // 鲨鱼象棋走的也是 MediaProjection + VirtualDisplay + ImageReader 这条路。
        //
        // ★ 拷贝那一大张 1272x2800 位图（约 14MB）绝不能放在主线程做：
        //   主线程一卡，第二次点击就被推迟，整步棋作废（JJ 象棋实测踩到过）。
        //   这里改成在 pool 里拷，拷完再回主线程回调。
        if (CaptureService.isRunning()) {
            pool.execute(new Runnable() {
                public void run() {
                    final Bitmap b = CaptureService.grab();
                    ui.post(new Runnable() {
                        public void run() {
                            if (b != null) cb.onShot(b);
                            else {
                                grabFailAt = noteGrabFail();
                                Log.w(TAG, "CaptureService 无帧，回退 takeScreenshot");
                                captureScreenFallback(cb);
                            }
                        }
                    });
                }
            });
            return;
        }
        captureScreenFallback(cb);
    }

    /** MediaProjection 不可用时的退路：无障碍 takeScreenshot。 */
    private void captureScreenFallback(final ScreenCallback cb) {
        if (Build.VERSION.SDK_INT < 30) {
            cb.onShot(null);
            return;
        }
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY,
                    getMainExecutor(), new TakeScreenshotCallback() {
                        public void onSuccess(ScreenshotResult result) {
                            Bitmap soft = null;
                            try {
                                Bitmap b = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(),
                                        result.getColorSpace());
                                soft = b != null ? b.copy(Bitmap.Config.ARGB_8888, false) : null;
                            } catch (Throwable t) {
                                Log.w(TAG, "截图结果解码失败", t);
                            } finally {
                                try { result.getHardwareBuffer().close(); } catch (Exception ignored) {}
                            }
                            cb.onShot(soft);
                        }
                        public void onFailure(int errorCode) {
                            Log.e(TAG, "screenshot fail " + errorCode);
                            cb.onShot(null);
                        }
                    });
        } catch (Throwable t) {
            // takeScreenshot 自身也可能直接抛（无权限/被限流）。以前这里没人接，
            // 异常把 UI 线程那一轮直接吞掉，busy 永远不释放 → 循环静默卡死。
            Log.e(TAG, "takeScreenshot 调用失败，按截图失败处理", t);
            cb.onShot(null);
        }
    }

    // ------------------------------------------------ 结算页「再来一局」（v4.5）
    /** v4.5：本帧检测到的最长连续绿段（诊断用：阈值不灵时靠它按实测校准）。 */
    private int rematchGreenW = 0;
    /** v4.5：结算页探测日志节流计数。 */
    private int rematchProbe = 0;
    /**
     * v4.5：本局是否曾经成功识别过棋盘（= 真的打过这一局）。
     *
     * 这是「本局打完了」的前提：没打过棋的界面（大厅/桌面/战绩）一律不碰，
     * 从源头掐掉误点。点完「再来一局」或重新开始连线都会清零。
     */
    private volatile boolean rematchGoodBoard = false;
    /** v4.5：最近一次成功认出棋盘的时刻（用来给「打完了」加时效，超过 2 分钟不再认）。 */
    private volatile long lastGoodBoardAt = 0;

    /**
     * 找「再来一局」那个大绿按钮，返回 {cx, cy, w, h, top, bot}，没找到返回 null。
     *
     * 为什么用画面认，而不是无障碍节点：
     *   JJ 象棋（cn.jj.chess.nearme.gamecenter）整个游戏内容是 Unity 的 unitySurfaceView，
     *   实测 `uiautomator dump` 出来的树里**只有 SurfaceView，没有任何文字/按钮节点**。
     *
     * ★ 下面是拿 8 张真实帧（5 张结算页 + 桌面 + 2 张其它）逐项标定出来的，改之前先看数据：
     *
     *   1) 判据必须是「**连续**绿段」，不能是「整行绿像素累加」——
     *      桌面底部几个绿图标（电话/短信/微信）累加就能凑出 241px，
     *      用累加统计时桌面帧会误命中；改成连续段后桌面只剩 108px（16.9%），被挡掉。
     *   2) 颜色按按钮实测色定：按钮主体 #C7DD54 / #B6D259 / #D3E054（黄绿，r、g 都高、b 低），
     *      按钮上的白字区域 r≈g，所以判据不能用「g 远高于 r」——
     *      否则白字把按钮切断，绿色带只剩 4~20px 高（这正是第一版失败的原因）。
     *   3) 允许 40px 空洞（把按钮上的白字跨过去），整条按钮才连得起来：
     *      实测按钮 286x92、占屏宽 44.4%，完整命中。
     *   4) 「高 ≥ 70px」是关键过滤器：结算页里其它绿装饰条（保护卡那一行、卡片边）
     *      实测只有 4~20px 高，全被挡下。
     */
    private int[] findRematchButton(Bitmap bmp) {
        if (bmp == null) return null;
        try {
            int W = bmp.getWidth(), H = bmp.getHeight();
            if (W < 200 || H < 200) return null;
            final int yStart = (int) (H * 0.50f);
            final int need = (int) (W * 0.25f);       // 连续绿段至少要有这么宽
            // 允许的空洞宽度：按屏宽取比例。
            // 半分辨率诊断帧上按钮白字约 20~30px，用 40 够；全屏翻倍到 60px 左右，
            // 写死 40 会把按钮切断（第一版就是被白字切断才只剩 4~20px 高）。
            final int gapMax = Math.max(36, (int) (W * 0.07f));
            final int[] row = new int[W];
            int top = -1, bot = -1, bestL = 0, bestR = 0, bestW = -1, widestAny = 0;
            int runTop = -1, runL = 0, runR = 0;
            for (int y = yStart; y < H; y += 4) {
                bmp.getPixels(row, 0, W, 0, y, W, 1);
                // 该行最长连续绿段（允许 gapMax 空洞 → 跨过按钮上的白字）
                int cur = 0, gap = 0, best = 0, bs = -1, be = -1, cs = -1;
                for (int x = 0; x < W; x += 2) {
                    int p = row[x];
                    int rr = (p >> 16) & 0xff, gg = (p >> 8) & 0xff, bb = p & 0xff;
                    boolean green = (gg >= 130 && bb <= 145 && gg - bb >= 50 && gg - rr >= 5)
                            || (gg >= 180 && bb <= 170 && gg - bb >= 45 && rr <= gg + 30);
                    if (green) {
                        if (cur == 0) cs = x;
                        cur += 2 + gap; gap = 0;
                        if (cur > best) { best = cur; bs = cs; be = x; }
                    } else {
                        gap += 2;
                        if (gap > gapMax) { cur = 0; gap = 0; }
                    }
                }
                if (best > widestAny) widestAny = best;
                if (best >= need && bs >= 0) {
                    if (runTop < 0) { runTop = y; runL = bs; runR = be; }
                    else { if (bs < runL) runL = bs; if (be > runR) runR = be; }
                } else if (runTop >= 0) {
                    int ww = runR - runL;
                    if (y - runTop >= 70 && ww > bestW) {
                        bestW = ww; top = runTop; bot = y; bestL = runL; bestR = runR;
                    }
                    runTop = -1;
                }
            }
            if (runTop >= 0) {                       // 一直绿到屏幕底
                int ww = runR - runL;
                if (H - runTop >= 70 && ww > bestW) {
                    bestW = ww; top = runTop; bot = H; bestL = runL; bestR = runR;
                }
            }
            rematchGreenW = widestAny;
            if (top < 0) return null;
            int w = bestR - bestL, h = bot - top;
            if (w < W * 0.25f) return null;          // 太窄：不是按钮
            if (w > W * 0.70f) return null;          // 太宽：是整片绿背景
            if (w < h * 2) return null;              // 必须是扁长条（实测按钮 286x92 ≈ 3.1 倍）
            int cx = (bestL + bestR) / 2, cy = (top + bot) / 2;
            if (Math.abs(cx - W / 2) > W * 0.22f) return null;
            if (cy < H * 0.60f || cy > H * 0.99f) return null;
            return new int[]{cx, cy, w, h, top, bot};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 结算页拦截（v4.5）：一局结束后游戏停在结算界面，下面有个大绿「再来一局」。
     * 认出来就用「落子那只手」把它点掉，自动开下一局。
     *
     * @return true = 本帧按结算页处理，不再走棋局识别（避免拿结算画面去认棋盘）
     *
     * 三层保险（离线按真实帧标定过）：
     *   ① 前提 `rematchGoodBoard` + 2 分钟时效 —— 这一局**真的打过**（认出过棋盘）才允许点。
     *      桌面、大厅、战绩页从没认出过棋盘，永远不会被点。
     *   ② 几何判据（见 findRematchButton）—— 实测结算页 5/5 命中、桌面与其它界面 0 命中。
     *   ③ 连中 2 帧才动手 —— 单帧闪一下（过场动画、亮块）不该真点下去。
     * 点完给 7 秒冷却：点完游戏要过场/匹配，这期间结算画面可能还没消失，不冷却会连点。
     */
    private boolean checkResultPage(Bitmap bmp) {
        try {
            if (sp(K_REMATCH, 1) == 0) return false;                 // 开关，默认开
            long now = System.currentTimeMillis();
            if (now - lastRematchTapAt < REMATCH_CD_MS) return false; // 冷却中
            if (!rematchGoodBoard || lastGoodBoardAt == 0) return false;
            if (now - lastGoodBoardAt > 120000L) return false;        // 打完超过 2 分钟，不再认
            int[] box = findRematchButton(bmp);
            if (box == null) {
                // 探测日志：约每 8~14 秒一条，把几何记下来，阈值万一不灵靠它校准（别删）。
                rematchHit = 0;
                if (++rematchProbe >= 20) {
                    rematchProbe = 0;
                    flog("结算页探测：最长连续绿段 " + rematchGreenW + "px（需 ≥ "
                            + (int) (bmp.getWidth() * 0.25f) + "px）");
                }
                return false;
            }
            if (++rematchHit < 2) {
                flog("结算页候选：绿按钮 中心(" + box[0] + "," + box[1] + ") " + box[2] + "x" + box[3]
                        + " y[" + box[4] + "," + box[5] + "]");
                return true;
            }
            lastRematchTapAt = now;
            rematchHit = 0;
            synchronized (XqService.class) { sLastTapAt = now; }       // 别和落子抢节流
            flog("本局结束 → 自动点「再来一局」(" + box[0] + "," + box[1] + ") 按钮 "
                    + box[2] + "x" + box[3] + " y[" + box[4] + "," + box[5] + "]");
            gestureTap(box[0], box[1]);
            // 换局了：连线状态清干净，下一局从头建立基线
            rematchGoodBoard = false;
            linkBoard = ""; linkExpect = null; linkPrev = null; linkUci = null;
            linkMover = 0; linkState = ST_INIT; linkActAt = 0;
            badFrames = 0; rejSame = 0; rejBoard = "";
            if (vision != null) vision.resetLock();
            setStatus("本局结束 → 已点「再来一局」，等新对局");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
    // ------------------------------------------------ 落子
    /** 免费版：不代替用户落子。 */
    private void tapMove(float[] m) { }
    private void gestureTap(float x, float y) { }


    // ------------------------------------------------ 连线（自动对局）
    //  状态机：INIT → 判断轮次 → MY_TURN（算招落子）→ MOVING（等生效）→ WAIT（等对手）→ MY_TURN …
    //  轮次靠「两帧画面里哪一方的子动了」自动推断，不依赖 FEN 的走子方字段。
    private static final int ST_INIT = Board.T_INIT, ST_MY_TURN = Board.T_MY_TURN,
            ST_MOVING = Board.T_MOVING, ST_WAIT = Board.T_WAIT;
    private volatile int linkState = ST_INIT;
    private volatile String linkBoard = "";      // 上次识别的棋盘（FEN 前半）
    private volatile String repairBase = "";   /* v4.60 多子纠错专用基线：只由「被采纳的盘面」更新，丢帧重置/暂停都不清空 */
    private volatile long linkActAt = 0;         // 上次落子时间
    private volatile int linkGen = 0;          // 循环代际，防止重复启动
    private volatile long busyAt = 0;          // busy 被置位的时刻（卡死看门狗用）
    private volatile long lastHealthAt = 0;    // 上次健康日志时刻
    private volatile int[] lastBoardBox;       // 上次识别到的棋盘外接框
    private volatile String linkExpect = null;  // 我落子后应有的局面，用来核对是否真的落下去了
    private volatile char linkMover = 0;        // 上一手是谁走的（决定现在轮到谁）
    private volatile int badFrames = 0;         // 连续几帧差分不是「一步」
    private volatile int gridBadFrames = 0;     // 连续几帧坐标与画面对不上
    private volatile int blankFrames = 0;       // 连续几帧画面是空白的（捕获失效）
    private volatile long lastCapRebuildAt = 0L; // 上次自动重建捕获管线的时刻
    private volatile boolean blankHold = false;  // ★ v4.56：画面被遮挡期间是否已进入「暂停」
    private volatile long lastBoardTryAt = 0L;   // ★ v4.56：上次做「找不到棋盘」全屏搜索的时刻
    private volatile String linkPrev = null;    // 落子前的局面（核对用）
    private volatile String linkUci = null;     // 我方刚落的那一步
    private volatile String rejBoard = "";      // 最近被丢弃的盘面
    private volatile int rejSame = 0;           // 同一被丢弃盘面连续出现的次数
    private volatile int noBoardFrames = 0;     // 连续「找不到棋盘」的帧数
    private volatile Bitmap curFrame;           // 本帧真正被分析的那张图（诊断帧存它）
    private volatile int noiseStreak = 0;       // 连续多少帧判定为「识别抖动」
    private String noiseBoard = "";              // 上一次被判定为「抖动」的盘面
    private int noiseSame = 0;                   // 同一个抖动盘面连续出现了几次
    private volatile int linkFailTry = 0;      // 连续落子无效次数
    private volatile boolean linkPaused = false; // 已停手（未识别到正确局面）
    private volatile boolean mateArmed = false;  // ★ v4.62：已落绝杀步，待确认
    private volatile boolean mateHold = false;   // ★ v4.62：绝杀确认后挂起，停止截图/识别
  private volatile long mateHoldAt = 0L;   // ★ v4.101：绝杀挂起起始时刻（解除宽限用）
    private volatile boolean liankaiOn = false;  // ★ v4.72：连开开关
    private volatile boolean userClosed = false;   // 用户主动点了 ✕，不要自愈
    /** ★ 启动提示卡（v4.0 照 ColorOS 游戏助手卡片做的弹出卡片）。 */
    private View hintCard = null;
    private boolean hintShown = false;
    /** ★ 播报队列（v4.1）：一次只显示一张，排队往额头位置移动。 */
    private final java.util.ArrayDeque<String[]> cardQ = new java.util.ArrayDeque<>();
    private View cardView = null;
    /** 播报卡的全屏透明宿主窗口。 */
    private View cardHostWin = null;
    /** 启动提示卡移出屏幕的目标位移。 */
    private float hintOutY = 0f;
    /** 下一次 showPanel 是否走「从中间滑到最上方」。 */
    private boolean panelFlyIn = false;
    /** 开场流程进行中：播报队列播完后要把悬浮窗拉上来。 */
    private boolean introMode = false;
    private boolean cardBusy = false;
    /** 队列活跃期间面板被下移，这里记住原位（MIN_VALUE = 没动过）。 */
    private int cardPanelSavedY = Integer.MIN_VALUE;
    /** ★ V3.2 用音量减隐藏了悬浮窗（连小球都不留）。
     *
     *  为什么要单独一个标记：
     *    原来的守卫是「面板和球都没有 → 不抢音量键」。可现在音量减会把两者都清掉，
     *    那条守卫会把「音量加召回来」也一起挡死 —— 用户就再也回不来了。
     *    所以需要这个标记：它表示「是用户主动收起来的，按键仍然归我们管」。
     *    同时看门狗的自愈也必须看它，否则一秒后悬浮窗自己又蹦出来。 */
    private volatile boolean volHidden = false;
    /** ★ 隐藏悬浮窗期间挂着的 1x1 全透明保活窗口（见 addKeepAlive 注释）。 */
    private View keepAlive;
    /** 本应用自己的界面在前台时，临时藏起悬浮窗。
     *
     *  悬浮面板是全宽贴顶的，正好压住主界面顶部的权限横幅（✅全部就绪 / ⚠缺建议权限），
     *  用户一进主界面就看不到自己缺哪项权限。切回棋局时立刻恢复。 */
    private volatile boolean overlaySelfHidden = false;
    private volatile boolean serviceDestroyed = false;   // 服务已销毁：停掉巡查看门狗
    private volatile String pendBoard = "";   // 待确认的首帧局面
    private volatile int pendCount = 0;        // 连续认到同一局面的次数
    // 最近见过的前台包（值=时间戳）。用「最近 2.5 秒内见过象棋 App」判定，
    // 而不是只看最后一个包 —— 否则系统悬浮层/输入法一冒头就会把对局误拦。
    private final java.util.LinkedHashMap<String, Long> fgSeen =
            new java.util.LinkedHashMap<String, Long>(16, 0.75f, true) {
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> e) {
                    return size() > 12;
                }
            };
    private final java.util.HashMap<String, Boolean> chessPkgCache = new java.util.HashMap<String, Boolean>();
    private volatile String fgTop = "";            // 最近看到的顶层应用包（仅用于提示）
    private static int fgLog = 0;
    private volatile long fgDumpAt = 0L;
    private volatile long selfFgAt = 0L;   // 本应用最近一次处于前台的时间戳

    // 前台校验三档：严格(只认象棋类App) / 宽松(除本应用和系统壳外全认，默认) / 关闭
    private static final int FG_STRICT = 0, FG_LOOSE = 1, FG_OFF = 2;
    public static final String K_FGMODE = "fgmode";     // v4.8：设置页也要读写
    private volatile int fgMode = FG_LOOSE;

    /** 授权通过后是否自动接上连线（由 PermissionActivity 设置）。 */
    public static volatile boolean wantLinkAfterCapture = false;

    /** 主界面（MainActivity）是否可见。由 onResume / onPause 直接维护。
     *
     *  这比 getWindows() 可靠：Activity 生命周期是系统直接回调，
     *  不受无障碍窗口枚举时序或缓存影响。 */
    public static volatile boolean mainUiForeground = false;

    /** 进程内唯一的服务实例，供主界面「启动悬浮窗」直接唤起面板。 */
    public static volatile XqService sInstance = null;

    /**
     * 从主界面唤起悬浮窗。
     *
     * 之前只能靠无障碍服务自己 onServiceConnected 时弹一次；
     * 用户点 ✕ 关掉后（userClosed=true）就不会再自愈了。
     * 现在主界面点「启动悬浮窗」可以直接把它拉回来。
     */
    /** 主界面退到后台 → 立刻把悬浮窗放出来，不等巡查看门狗的 1 秒延迟。 */
    public static void notifySelfUiLeft() {
        mainUiForeground = false;
        final XqService s = sInstance;
        if (s == null) return;
        s.ui.post(new Runnable() {
            public void run() {
                try { s.applyOverlaySelfHidden(false); } catch (Throwable ignored) {}
            }
        });
    }

    public static void bringUp() {
        final XqService s = sInstance;
        if (s == null) return;
        s.ui.post(new Runnable() {
            public void run() {
                try {
                    s.userClosed = false;
                    s.volHidden = false;   // 用户从主界面主动拉起，恢复音量键接管
                    s.waitingStart = false;
                    s.spPut(K_STARTED, 1);
                    if (s.panel == null) s.showPanel();
                    else s.updateUi();
                } catch (Throwable ignored) {}
            }
        });
    }

    /**
     * 进入「修权限」流程 —— 重新上锁：**在用户点「权限已获取，开始游戏」之前，什么都不许弹**。
     *
     * 为什么要有这个方法（v4.3 用户报的 bug）：
     *   给完无障碍权限后，服务 onServiceConnected 会把面板弹到桌面上（用户还在系统设置页里），
     *   流程就乱了。waitingStart 这个闸门能挡住「本次进程」，但服务被系统回收重建、
     *   或者用户上次点过开始（K_STARTED=1）时又会漏。所以每次用户走进修权限流程
     *   （打开主界面/点一键全部修复/点单个权限按钮）都重新上一遍锁。
     */
    public static void beginSetup() {
        final XqService s = sInstance;
        if (s == null) return;
        s.ui.post(new Runnable() {
            public void run() {
                try {
                    s.waitingStart = true;
                    s.spPut(K_STARTED, 0);
                    s.clearCards();
                    s.removeBall();
                    s.removePanel();
                    s.removeKeepAlive();
                } catch (Throwable ignored) {}
            }
        });
    }

    /** 悬浮窗当前是否已经挂在屏幕上。 */
    public static boolean panelAlive() {
        XqService s = sInstance;
        return s != null && (s.panel != null || s.ball != null);
    }
    private volatile long askedAt = 0;
    private boolean askedCapture = false;

    /**
     * 自动把「屏幕捕获」授权弹窗顶出来（MediaProjection 必须由 Activity 发起）。
     * 传 autoLink=true 表示授权成功后自动开始连线。
     */
    /** 每秒看一眼：捕获起来了没有、要不要自动接上线。 */
    /**
     * 屏幕捕获掉了 → 发一条通知，点一下直达主界面自动恢复。
     *
     * 说明：MediaProjection 是系统安全项，**重启手机后必须重新授权**，
     * 任何 App 都无法绕过。所以这里能做的不是「永久保住」，
     * 而是「第一时间发现 + 一键/自动补救」，把用户的操作从「自己找按钮」
     * 降成「点一下通知」。
     */
    private void notifyCaptureLost() {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    CH_PERM, "权限提醒", android.app.NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("屏幕捕获失效时提醒，点一下即可恢复");
            nm.createNotificationChannel(ch);

            android.content.Intent it = new android.content.Intent(this, MainActivity.class);
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 0, it,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);

            android.app.Notification n = new android.app.Notification.Builder(this, CH_PERM)
                    .setSmallIcon(R.drawable.ic_notify)
                    .setContentTitle("YYPM: 屏幕捕获已失效")
                    .setContentText("点这里一键恢复（重启手机后需要重新授权）")
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();
            nm.notify(9001, n);
            Log.i(TAG, "已发出「屏幕捕获失效」通知");
        } catch (Throwable t) {
            Log.w(TAG, "发通知失败", t);
        }
    }

    /**
     * 权限看护（每秒跑一次）：
     *   · 连线中但屏幕捕获掉了 → 发通知请用户一键恢复（60 秒冷却，不骚扰）
     *   · 无障碍本身被系统关掉时，服务会随之销毁，回到主界面自会提示
     */
    private void watchdogPermissions() {
        try {
            if (!auto.get()) return;
            if (CaptureService.isRunning()) { capAlertAt = 0L; return; }
            long now = System.currentTimeMillis();
            if (capAlertAt != 0L && now - capAlertAt < 60000L) return;
            capAlertAt = now;
            setStatus("屏幕捕获已失效 —— 下拉通知栏点一下即可恢复");
            notifyCaptureLost();
        } catch (Throwable ignored) {}
    }

    private void startWatchdog() {
        ui.postDelayed(new Runnable() {
            public void run() {
                if (serviceDestroyed) return;
                try {
                    // 注意：不能要求 !auto.get()。用户点「链」时 auto 已经是 true，
                    // 若还要求它为 false，授完权就永远接不上（实测卡在「正在弹出授权…」）。
                    if (wantLinkAfterCapture && CaptureService.isRunning()) {
                        wantLinkAfterCapture = false;
                        capAutoTries = 0; capAskInFlight = false;
                        auto.set(true);
                        updateUi();
                        startLink();
                        Log.i(TAG, "授权完成，自动开始连线");
                    }
                } catch (Throwable ignored) {}
                // 悬浮窗不要盖住自己的主界面（顶部权限横幅会被全宽面板压住）
                try { applyOverlaySelfHidden(isSelfForeground()); } catch (Throwable ignored) {}
                // ★ 自测钩子（v4.3）：外部私有目录里出现 files/demo_intro 就自动走一遍开场
                //   （4 张权限卡 → 悬浮窗从中间滑到最上方）。正式使用不会触发。
                try {
                    java.io.File demo = new java.io.File(getExternalFilesDir(null), "demo_intro");
                    if (demo.exists()) {
                        demo.delete();
                        playIntro("自测钩子 demo_intro");
                    }
                } catch (Throwable ignored) {}
                // 自测钩子：开发期用来在没有人工点击的情况下直接显示悬浮窗，便于量尺寸 / 截图核对。
                // 触发条件是 App 私有目录下存在某个标记文件 —— 该目录受分区存储保护，
                // 其它应用无法写入，因此发行版里这个开关不会被外部触发。
                try {
                    java.io.File ds = new java.io.File(getExternalFilesDir(null), "demo_start");
                    if (ds.exists() && waitingStart) {
                        ds.delete();
                        waitingStart = false;
                        spPut(K_STARTED, 1);
                        flog("自测钩子 demo_start：解锁开场闸门，显示悬浮窗");
                        removePanel();
                        showPanel();
                    }
                } catch (Throwable ignored) {}
                // 悬浮窗自愈：万一被系统回收或意外移除，自动重建
                try {
                    if (panel == null && ball == null && !heavyInited) { /* 未连接完成，不重建 */ }
                    else if (panel == null && ball == null && !userClosed && !volHidden && !introMode && !waitingStart) showPanel();
                } catch (Throwable ignored2) {}
                // 权限看护：捕获掉了就提醒用户恢复
                watchdogPermissions();
                // 没有这一步，引擎一死就永久「引擎无着法」，只能重启应用。
                // ★ 必须在后台线程里做：start() 要等 uciok/readyok 数秒，
                //   看门狗跑在主线程，直接调用会卡住 UI。
                try {
                    if (heavyInited && engine != null && !engine.isReady() && !engineRestarting) {
                        engineRestarting = true;
                        Thread th = new Thread(new Runnable() {
                            public void run() {
                                try {
                                    if (engine != null && engine.ensureStarted())
                                        Log.i(TAG, "看门狗: 引擎已自动恢复");
                                } catch (Throwable ignored) {
                                } finally {
                                    engineRestarting = false;
                                }
                            }
                        });
                        th.setDaemon(true);
                        th.setName("engine-revive");
                        th.start();
                    }
                } catch (Throwable ignored3) {}
                // ★ 循环回去。以前这里漏了这一句：整个巡查看门狗只跑了一次就没了，
                //   于是「引擎自愈」「捕获失效提醒」「悬浮窗自隐」全部形同虚设。
                try { ui.postDelayed(this, 1000); } catch (Throwable ignored) {}
            }
        }, 1000);
    }

    // ---- 屏幕捕获授权请求的全局闸门 ----
    //  为什么必须要有：系统同一时刻只允许存在一个「待答复」的屏幕捕获请求。
    //  新的 createScreenCaptureIntent() 会作废上一个请求，并给上一个页面回
    //  RESULT_CANCELED。而连线循环每 1.5 秒就会因为「捕获还没起来」再要一次，
    //  于是弹窗被自己反复顶掉、用户永远点不中 —— 日志里成片的「用户取消了授权」
    //  和「一直弹这个」都是这么来的。
    private static volatile boolean capAskInFlight = false;
    private static volatile long capAskAt = 0L;
    private static volatile int capAutoTries = 0;
    private static volatile long capAutoAt = 0L;
    private static final long CAP_ASK_COOLDOWN = 10000L;
    private static final int  CAP_AUTO_MAX = 2;

    /** PermissionActivity 答复完（或被销毁）后调用，放开飞行闸门。 */
    public static void captureAskFinished(boolean ok) {
        capAskInFlight = false;
        if (ok) capAutoTries = 0;
        else capAutoAt = System.currentTimeMillis();
    }

    /** 是否正有一个授权请求在等用户答复。 */
    public static boolean captureAskPending() { return capAskInFlight; }

    /** 自动把「屏幕捕获」授权弹窗顶出来（MediaProjection 必须由 Activity 发起）。
     *  返回 true = 这次真的发起了；false = 被闸门挡下（已有请求在等 / 冷却未到 / 重试用尽）。 */
    private boolean requestCaptureAuto(boolean autoLink) {
        return requestCapture(autoLink, false);
    }

    /** @param autoLink 授权成功后自动开始连线
     *  @param manual   true=用户自己按的（悬浮窗「链」/主界面按钮），清掉重试计数、不受冷却限制 */
    private boolean requestCapture(boolean autoLink, boolean manual) {
        if (autoLink) wantLinkAfterCapture = true;
        long now = System.currentTimeMillis();

        // ① 已有请求在等用户点：再发一次只会把上一个顶掉，绝不重复发
        //    （超过 20 秒还没结果 → 判定僵死，放行重来一次）
        if (capAskInFlight) {
            if (now - capAskAt < 20000L) return false;
            Log.w(TAG, "授权请求超过 20 秒无结果，判定僵死，重新发起");
            capAskInFlight = false;
        }
        // ② 冷却：刚被取消过就等一等，别贴着用户的脸连弹
        if (!manual && now - capAutoAt < CAP_ASK_COOLDOWN) return false;
        // ③ 自动重试有上限，用尽后不再自动弹，改由状态栏喊用户点「链」
        if (manual) capAutoTries = 0;
        else if (capAutoTries >= CAP_AUTO_MAX) return false;

        askedCapture = true;
        askedAt = now;
        if (!manual) capAutoTries++;
        capAutoAt = now;
        capAskAt = now;
        capAskInFlight = true;

        try {
            Intent it = new Intent(this, PermissionActivity.class);
            // 不要 CLEAR_TOP：它会把「正在等用户点允许」的同一个页面 finish 掉，
            // 授权结果就永远回不来（实测每次都回一句「用户取消了授权」）。
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(it);
            setStatus("正在弹出屏幕捕获授权…");
            return true;
        } catch (Throwable t) {
            capAskInFlight = false;
            Log.e(TAG, "拉起授权页失败", t);
            setStatus("请到主界面点「② 开启屏幕捕获」");
            return false;
        }
    }

    private void startLink() {
        // 进程级锁：同一时刻只允许一个连线循环（防止两个实例各跑一个，并发落子）
        if (!sLinkLoop.compareAndSet(false, true)) {
            Log.w(TAG, "已有连线循环在跑，忽略本次启动");
            setStatus("连线: 已在运行");
            return;
        }
        final int gen = ++linkGen;
        linkPaused = false;
        mateArmed = false; mateHold = false;   // ★ v4.62：重开连线，清绝杀挂起
        linkFailTry = 0;
        linkState = ST_INIT;
        linkBoard = ""; repairBase = "";
        rematchGoodBoard = false;   // v4.5：重新开始连线 —— 之前那局「打过」的标记作废
        linkExpect = null;
        linkMover = 0;
        badFrames = 0;
        linkActAt = 0;
        if (!CaptureService.isRunning()) {
            requestCapture(true, true);   // 用户按的「链」= 手动请求，清掉重试计数
            sLinkLoop.set(false);      // 没授权就释放，等授权完成后看门狗会重新启动
            return;
        }
        setStatus("连线: 启动中…");
        ui.postDelayed(new Runnable() {
            public void run() {
                if (gen != linkGen) { sLinkLoop.set(false); return; }   // 旧循环退出并释放锁
                if (!auto.get()) { sLinkLoop.set(false); return; }
                // ★ 卡死看门狗：busy 超过 12 秒不释放，说明截图/识别某一环没回调。
                //   旧实现在这里是永久静默 —— 界面停在最后一条状态（典型就是「算招中…」），
                //   日志里也什么都没有，只能靠肉眼猜。
                long nowMs = System.currentTimeMillis();
                if (busy.get() && busyAt > 0 && nowMs - busyAt > 12000L) {
                    Log.w(TAG, "连线: 上一帧 " + (nowMs - busyAt) + "ms 未完成，判定卡死，强制解锁");
                    busy.set(false);
                    busyAt = 0;
                    setStatus("连线: 上一帧卡死，已强制恢复");
                }
                if (nowMs - lastHealthAt > 5000L) {
                    lastHealthAt = nowMs;
                    Log.i(TAG, "连线健康 busy=" + busy.get() + " 阶段=" + linkState
                            + " 引擎=" + (engine != null && engine.isReady())
                            + " 捕获=" + CaptureService.isRunning()
                            + " 帧龄=" + CaptureService.frameAge() + "ms"
                            + " 自动走子=" + autoPlay.get() + " 暂停=" + linkPaused);
                }
                if (!busy.get()) linkStep();
                if (linkPaused) { sLinkLoop.set(false); return; }   // 已停手，退出循环
                // ★ 等对手时应答要快：对手落子后我们得尽快发现。
                //   识别链路本身还要走「多帧投票」，一次变化要 2 帧才翻过来，
                //   所以每一帧的间隔直接乘 2 变成显示延迟。等对手时用 400ms
                //   （约 0.8 秒跟上），算招/落子阶段不必那么密，回到 700ms 省电。
                // ★ v4.27：再按实测识别耗时自适应。弱机识别慢时必须放宽 ——
                //   否则「还没识别完就又去截一帧」，CPU 一直满载，这正是发热与卡顿的来源。
                int baseIv = (linkState == ST_WAIT) ? 400 : 700;
                if (mateHold) baseIv = 2000;   // ★ v4.72：连开监听期低频省电
                ui.postDelayed(this, DeviceProfile.linkInterval(baseIv, detectMsAvg, devLowRamNow()));
            }
        }, 300);
    }

    /** 连线的一步：截图 → 识别 → 状态机决策。 */
    private void linkStep() {
        if (!busy.compareAndSet(false, true)) return;
        busyAt = System.currentTimeMillis();
        // ★ v4.6：音量条正在屏幕上（会盖住棋盘右上角）→ 本帧不截图不识别
        if (volPanelBlocking()) {
            busy.set(false);
            setStatus("正在调音量…（音量条会挡棋盘，稍等）");
            if (!volBlockLogged) {
                volBlockLogged = true;
                flog("音量条遮挡：暂停识别 —— 音量条实测盖住棋盘右上角 x[1063,1178] y[798,940]");
            }
            return;
        }
        if (fgMode != FG_OFF && !isChessForeground()) {
            busy.set(false);
            Log.i(TAG, "前台校验拦下(连线): " + lastFgPkg()); setStatus("连线: 未在象棋对局（最近前台 " + fgTop + "）");
            return;
        }
        if (!CaptureService.isRunning()) {
            busy.set(false);
            // 自动重试有上限：以前这里是「每 1.5 秒要一次」，结果把系统弹窗反复顶掉、
            // 用户永远点不中，日志里成片的「用户取消了授权」。挡不住就停下来说清楚。
            if (!requestCapture(true, false)) {
                linkPaused = true;
                linkState = ST_INIT;
                auto.set(false);
                updateUi();
                setStatus("屏幕捕获未开启 —— 点悬浮窗「链」重新授权");
            }
            return;
        }
        if (mateHold && !liankaiOn) { busy.set(false); return; }   // ★ v4.72：连开开着则低频监听结算页
        captureNow(new ScreenCallback() {
            public void onShot(final Bitmap bmp) {
                if (bmp == null) { busy.set(false); setStatus("连线: 截图失败"); return; }
                pool.execute(new Runnable() {
                    public void run() {
                        try { linkDecide(bmp); }
                        catch (Throwable t) {
                            Log.e(TAG, "连线异常: " + t.getClass().getSimpleName() + " " + t.getMessage(), t);
                            setStatus("连线异常: " + t.getMessage());
                        } finally { busy.set(false); }
                    }
                });
            }
        });
    }

    /**
     * 记一次「这一帧不可信」。连续 8 帧不可信 → 清空基线并解锁棋盘位置，
     * 下一帧从头重新检测。返回 true 表示调用方应当直接 return。
     */
    /**
     * 把当前帧存到 /sdcard/Android/data/com.yypm.assistant/files/frames/，
     * 供离线分析（识别为什么失败）。最多每 2 秒存一张，循环覆盖 8 张。
     */
    /** 存诊断帧的专用后台线程。
     *
     *  ★ 绝不能放在主线程：全屏 1272x2800 PNG 编码一张要几百毫秒到 2 秒，
     *    而悬浮窗重绘（postInvalidate）和下一轮识别（postDelayed）都排在同一条
     *    主线程队列里 —— 于是「对手已经走完子，悬浮窗还停在旧局面」。
     *    实测主循环被拖到 8 秒一轮。改到独立线程 + 缩小 + JPEG 后恢复正常。 */
    private final ExecutorService dumpPool = Executors.newSingleThreadExecutor();

    /**
     * 把当前帧存到 files/frames/，供离线分析。
     *
     * 节流规则统一成「不管什么原因，2 秒内最多存一次」——
     * 旧写法是「note 变了就直接存」，而 note 里带 FEN / 失败原因，几乎每次都不同，
     * 于是等于没节流，每帧都在写盘。
     */
    private void dumpFrame(String note) {
        long now = System.currentTimeMillis();
        if (now - lastDumpAt < 2000L) return;
        lastDumpAt = now;
        // ★ 在当前线程就把图缩成 640 宽的小副本（约 1.5MB）再交给写盘线程。
        //   以前写盘线程直接读 curFrame，而主链路用完就会回收它 ——
        //   跨线程 recycle 一张正被读的 Bitmap 会 native abort（libhwui），
        //   catch (Throwable) 拦不住，整个无障碍进程直接死。
        Bitmap small = null;
        try {
            Bitmap b = curFrame;
            if (b == null || b.isRecycled()) b = CaptureService.grab();
            if (b != null && !b.isRecycled()) {
                int tw = 640;
                int th = Math.max(1, Math.round(b.getHeight() * tw / (float) b.getWidth()));
                small = Bitmap.createScaledBitmap(b, tw, th, true);
            }
        } catch (Throwable ignored) {}
        if (small == null) return;
        final Bitmap fsmall = small;
        final Bitmap warp = BoardVision.warpOf();
        final int seq = dumpSeq;
        dumpSeq = (dumpSeq + 1) % 8;
        final String nt = (note == null ? "" : note);
        try {
            dumpPool.execute(new Runnable() {
                public void run() { writeFrame(fsmall, warp, seq, nt); }
            });
        } catch (Throwable ignored) {
            recycleQuiet(small);
        }
    }

    /** 真正落盘（在 dumpPool 线程上跑）；只碰传进来的小副本，不再读共享位图。 */
    private void writeFrame(Bitmap small, Bitmap warp, int seq, String note) {
        try {
            if (small == null || small.isRecycled()) return;
            File dir = getExternalFilesDir("frames");
            if (dir == null) dir = new File(getFilesDir(), "frames");
            if (!dir.exists()) dir.mkdirs();

            File f = new File(dir, "f" + seq + ".jpg");
            FileOutputStream fo = new FileOutputStream(f);
            small.compress(Bitmap.CompressFormat.JPEG, 80, fo);
            fo.close();

            if (warp != null && !warp.isRecycled()) {
                File wf = new File(dir, "w" + seq + ".jpg");
                FileOutputStream wo = new FileOutputStream(wf);
                warp.compress(Bitmap.CompressFormat.JPEG, 85, wo);
                wo.close();
            }
            Log.i(TAG, "诊断帧已存 f" + seq + ".jpg (" + note + ")");
        } catch (Throwable t) {
            Log.w(TAG, "存诊断帧失败", t);
        } finally {
            recycleQuiet(small);
        }
    }

    private boolean rejectFrame(String why) { return rejectFrame(why, null); }

    /**
     * ★ v4.59：「多子」自动纠错（核心逻辑在 Board.repairExtra，这里只负责日志与状态）。
     *
     * 治的是「某一格被恒定读成同一种子」这个硬锁 —— 实测整局瘫痪 2.5 分钟、
     * 107 次「黑方 p 多子(6)」，多帧投票压不掉、重启「链」也没用。
     */
    private String repairExtraPiece(String cur, String bad) {
        if (bad == null || bad.indexOf("多子") < 0) return null;
        Board.Fix f = Board.repairExtra(repairBase.length() > 0 ? repairBase : linkBoard, cur, linkExpect);
        if (f == null) {
            flog("多子纠正失败：" + bad + "（基线=" + (repairBase.length() > 0 ? repairBase : linkBoard) + "）");
            return null;
        }
        flog("多子纠正：" + bad + " → " + f.note + " 结果=" + f.board);
        setStatus("连线: 识别多子已自动纠正，继续");
        return f.board;
    }

    /**
     * ★ v4.60：把「多子」的来源格连同模型置信度写进日志。
     *
     * 为什么需要：日志里只看到「黑方 r 多子(3)」，不知道是哪一格、也不知道
     * 模型是不是真的「自信地」把它读成了车。这一条就是用来分辨的：
     *   · 置信很高（≥0.9）→ 是分类器在那格上确实认错（字体/渲染问题）
     *   · 置信中等（0.5~0.8）→ 是灰度/网格采样轻微偏了
     * （注：识别结果可能被多帧投票改过，这里的置信度取自单帧分类。）
     */
    private void logPhantomCells(BoardVision.Result r, boolean flipped, String board) {
        try {
            char pc = Board.overPiece(board);
            if (pc == 0 || r == null) return;
            char[][] b = Board.fromFen(board);
            StringBuilder sb = new StringBuilder();
            for (int rr = 0; rr < Board.N_ROW; rr++) {
                for (int cc = 0; cc < Board.N_COL; cc++) {
                    if (b[rr][cc] != pc) continue;
                    char inBase = (linkBoard == null || linkBoard.isEmpty()) ? 0
                            : Board.at(linkBoard, rr, cc);
                    if (inBase == pc) continue;          // 基线里本来就有 → 不是它
                    int vr = flipped ? Board.N_ROW - 1 - rr : rr;
                    int vc = flipped ? Board.N_COL - 1 - cc : cc;
                    float cf = -1f;
                    try { cf = r.conf[vr][vc]; } catch (Throwable ignored) {}
                    sb.append(" 格(").append(rr).append(",").append(cc).append(")=").append(pc)
                      .append("/置信").append(String.format(java.util.Locale.US, "%.2f", cf))
                      .append("/基线").append(inBase == 0 ? "." : String.valueOf(inBase));
                }
            }
            if (sb.length() > 0) flog("多子来源:" + sb);
        } catch (Throwable ignored) {}
    }

    /**
     * 丢帧。badFrames 衡量「连续多少帧读不出可信盘面」。
     *
     * 关键改动：非单步差分不再靠帧数硬重置，而是要求「同一个新盘面连续出现 3 次」
     * 才承认它 —— 识别抖动一次不会再污染基线，真正换了局面也照样能跟上。
     */
    private boolean rejectFrame(String why, String cur) {
        badFrames++;
        if (cur != null) {
            if (cur.equals(rejBoard)) rejSame++;
            else { rejBoard = cur; rejSame = 1; }
        }
        final String w = why;
        dumpFrame(w);
        Log.w(TAG, "连线: 丢弃本帧(" + badFrames + ") " + why
                + (cur == null ? "" : " 同值=" + rejSame));
        // ★ v4.58：丢帧原因**必须写进文件日志**。
        //   以前只写 logcat，而本机 logcat 只留几分钟 ——
        //   12:16~12:24 那 8 分钟「识别不稳」就是这么查不下去的：
        //   想知道为什么丢帧，一个字的证据都没有。
        //   前 3 帧全记（症状刚出现时最关键），之后每 5 帧记一条，避免刷屏。
        try {
            if (badFrames <= 3 || badFrames % 5 == 0) {
                String detail = (vision == null) ? "" : vision.lastWhy();
                // ★ v4.59：校验不过时把**被判非法的整张盘面**也写下来 ——
                //   不然只能看到「黑方 p 多子(6)」，查不出到底是哪一格读错了。
                boolean wantBoard = (cur != null) && (why != null)
                        && (why.indexOf("不可靠") >= 0 || why.indexOf("不可信") >= 0);
                flog("丢帧(" + badFrames + ") " + why
                        + (cur == null ? "" : " 同值=" + rejSame)
                        + (wantBoard ? " 盘面=" + cur : "")
                        + (detail == null || detail.length() == 0 ? "" : " | " + detail));
            }
        } catch (Throwable ignored) {}
        boolean stable = (cur == null) || (rejSame >= 3);
        if (badFrames < 8 || !stable) {
            setStatus("连线: 识别不稳，暂不采信…");
            return true;
        }
        Log.w(TAG, "连线: 连续 " + badFrames + " 帧不可信且盘面稳定 " + rejSame + " 次，重新对齐");
        badFrames = 0; rejSame = 0; rejBoard = "";
        linkBoard = "";      /* repairBase 故意不跟着清：多子纠错就靠它 */
        linkPrev = null; linkUci = null;
        linkMover = 0;
        linkState = ST_INIT;
        if (vision != null) vision.resetLock();
        return true;       // 重置完，本帧仍然跳过；下一帧以全新基线重新开始
    }

    private void linkDecide(Bitmap bmp) {
        // 防重入：同一时刻只允许一次「算招+落子」
        if (!linkBusy.compareAndSet(false, true)) {
            recycleQuiet(bmp);
            return;
        }
        try {
            linkDecideInner(bmp);
        } catch (Throwable t) {
            Log.e(TAG, "连线异常: " + t.getClass().getSimpleName() + " " + t.getMessage(), t);
            setStatus("连线异常: " + t.getMessage());
        } finally {
            linkBusy.set(false);
            // ★ 用完立刻回收。这张 14MB 位图以前谁也不管，全靠 GC 慢慢收，
            //   是「分配风暴」的一半来源（另一半在 CaptureService 的每帧新建）。
            recycleQuiet(bmp);
        }
    }

    /** 安静地回收一张位图（可能仍被诊断帧写盘线程引用，那边会自己判 isRecycled）。 */
    private static void recycleQuiet(Bitmap b) {
        if (b == null) return;
        try { if (!b.isRecycled()) b.recycle(); } catch (Throwable ignored) {}
    }

    private void linkDecideInner(Bitmap bmp) throws Exception {
        curFrame = bmp;
        if (mateHold) {
            // ★ v4.72：连开监听 —— 眼只认结算页，手只点「再来一局」
            // ★ v4.75：不走 checkResultPage（它有 2 分钟时效，挂起久了会「盯而不点」）。
            //   mateHold 本身就证明刚打完这局，直接认按钮 + 点击。
            if (!liankaiOn) return;
            int[] lkBox = findRematchButton(bmp);
            long lkNow = System.currentTimeMillis();
            if (lkBox != null) {
                if (lkNow - lastRematchTapAt < REMATCH_CD_MS) { rematchHit = 0; return; }  // 冷却中
                if (++rematchHit < 2) return;                                              // 连中2帧才点
                lastRematchTapAt = lkNow;
                rematchHit = 0;
                synchronized (XqService.class) { sLastTapAt = lkNow; }
                flog("连开：点「再来一局」(" + lkBox[0] + "," + lkBox[1] + ") 按钮 "
                        + lkBox[2] + "x" + lkBox[3]);
                gestureTap(lkBox[0], lkBox[1]);
                setStatus("连开: 已点「再来一局」，等新对局…");
                return;                                      // 仍在结算页（过场中），继续盯
            }
            rematchHit = 0;
            // ★ v4.101：解除宽限 —— 绝杀后对方有数秒认输动画，棋局画面还在，
            //   一帧没看到按钮就解除会错过整个结算页（v4.99 实测 1 秒即解除）。
            if (System.currentTimeMillis() - mateHoldAt < 12000L) return;   // 宽限期内继续盯
            // 新画面（过场/新对局）→ 解除挂起，全量重同步，下一帧恢复正常识别
            mateHold = false; mateArmed = false;
            linkBoard = ""; repairBase = ""; linkState = ST_INIT;
            linkMover = 0; badFrames = 0;
            linkExpect = null; linkPrev = null; linkUci = null;
            if (vision != null) vision.resetLock();
            flog("连开：新画面出现，解除绝杀挂起，恢复识别");
            setStatus("连开: 新局已开，恢复连线");
            return;
        }
        if (vision == null || !vision.isLoaded()) { setStatus("连线: 模型未就绪"); return; }

        // ★ v4.5：结算页拦截 —— 一局结束后游戏会停在结算界面（下面一个大绿「再来一局」）。
        //   先看是不是这个界面；是的话直接用「落子那只手」点掉它，自动开下一局，
        //   顺带避免拿结算画面去认棋盘（那种帧只会污染识别基线）。
        if (checkResultPage(bmp)) return;
        // ★ 无效帧闸门：屏幕捕获中途失效时，MediaProjection 会持续吐空白帧
        //   （实测 #F7F7F7、占 87%）。这种帧上跑识别，ONNX 分类是乱的，但
        //   「多帧投票」会把 chars 救回上一帧的正确局面 —— 于是表现成
        //   「棋盘看着是对的、点击却全点飞」，反复「等落子生效」永远不动。
        //   必须先把无效帧挡掉，绝不能拿它去算落子坐标。
        if (isBlankFrame(bmp)) {
            if (++blankFrames == 3) {
                flog("连续 " + blankFrames + " 帧画面空白（投屏存活=" + CaptureService.isAlive() + "）");
            }
            // ★ v4.56：空白帧必须分成两种**完全不同**的情况，以前混为一谈就是「不动了」的根因。
            //
            //   ① 投屏**已经停了**（onStop / 用户点了停止 / 系统回收）→ 只能重新授权。
            //      这里立刻发起（约 1 秒内），不再像旧代码那样死等到第 25 帧 ——
            //      帧率一慢 25 帧就是一分多钟，用户看到的就是「画面没了、它也不动」。
            //
            //   ② 投屏**还活着**、只是画面被别的窗口挡住（聊天界面 / 系统界面 / 黑屏）→ 只暂停。
            //      旧代码在这里调 CaptureService.rebuild() 重建 VirtualDisplay，
            //      而 Android 禁止对同一个 MediaProjection 调第二次 createVirtualDisplay
            //      （实测抛 SecurityException），结果把一个**本来还能用**的投屏弄死，
            //      逼用户重新授权。这里改成：什么都不动，等画面回来。
            //
            //   两种情况都不跑识别 —— 空白帧上跑 ONNX 既没意义，又会把帧率拖到 3~7 秒一帧。
            if (!CaptureService.isAlive()) {
                if (blankFrames == 3
                        && System.currentTimeMillis() - lastCapRebuildAt > 15000L) {
                    lastCapRebuildAt = System.currentTimeMillis();
                    flog("投屏已停，立即请求重新授权");
                    requestCaptureAuto(true);
                }
                if (rejectFrame("画面空白:投屏已停", null)) return;
            } else {
                if (!blankHold) {
                    blankHold = true;
                    flog("画面被遮挡，暂停识别（投屏仍存活，不做任何重建）");
                }
                setStatus("连线: 画面被遮挡，已暂停（回到棋局自动继续）");
                return;
            }
        } else {
            blankFrames = 0;
            if (blankHold) {
                // ★ v4.56：画面回来了 —— 清掉抖动计数，让识别在 1~2 帧内重新锁定，
                //   而不是带着几十帧的「不可信」包袱从头慢慢爬。
                blankHold = false;
                badFrames = 0; rejSame = 0; rejBoard = "";
                noiseStreak = 0; noiseSame = 0; noiseBoard = "";
                pendBoard = ""; pendCount = 0;
                setStatus("连线: 画面恢复，重新锁定…");
                flog("画面恢复，抖动计数已清零");
            }
        }

        // ★ v4.56：找不到棋盘时不要每帧都做完整搜索。
        //   实测（聊天界面在前台时，11:49~11:51）：一次「找不到棋盘」的全屏搜索要 3~7 秒，
        //   于是帧率被拖到 3.5 秒/帧、连着两分钟刷「识别不稳」；
        //   等回到棋局，锁定早被这些垃圾帧冲掉了，还得几十秒才重新确立 ——
        //   用户看到的就是「它不动了，重启连线也不马上恢复」。
        //   这里把连续失败时的完整搜索限制到每 1.2 秒最多一次，其余帧直接跳过
        //   （开销几乎为 0）—— 画面一回到棋盘，最迟 1.2 秒内就会重新锁定。
        // ★ v4.58：连续失败的越久，重试间隔越长（1.2s → 6s 封顶）。
        //   前台明显不是棋类画面时（聊天界面 / 短视频），这一条把全屏搜索的
        //   占空比压得很低 —— 不会再出现「CPU 烧满 8 分钟却一帧都认不出」。
        //   起点就从 1.5 秒起（一次失败的搜索本身约 1.3 秒），保证占空比早早降到 50% 以下。
        long gate = 1500L + 700L * Math.min(8, (long) noBoardFrames / 2);
        if (noBoardFrames > 0
                && System.currentTimeMillis() - lastBoardTryAt < gate) {
            return;
        }
        lastBoardTryAt = System.currentTimeMillis();
        BoardVision.Result r = vision.detect(bmp);
        if (!r.ok) {
            if (++noBoardFrames == 3) {
                flog("连续找不到棋盘，降到每 1.2 秒才做一次全屏搜索");
                setStatus("连线: 画面里没有棋盘，已降频重试…");
            }
            if (noBoardFrames >= 40) {
                noBoardFrames = 0;
                pauseLink("连续 20 秒没找到棋盘");
                return;
            }
            if (rejectFrame("未找到棋盘")) return;
        }
        noBoardFrames = 0;
        if (!r.gridOk) { if (rejectFrame("格点异常")) return; }
        // 多帧投票票数不足 → 这一帧还不够稳，不采信
        if (r.minVote < 2) { if (rejectFrame("投票不足:" + r.minVote)) return; }

        // ★ 坐标自检：识别说有棋子的格子，其屏幕坐标处必须真的坐着亮块（棋子木色）。
        //   ONNX 是按「拉正图」固定 90 格分类的，而落子坐标是 classify 里另外用
        //   snapAxis 网格反算出来的。这两条链路一旦不一致，就会「识别正确、点击点飞」——
        //   表现正是反复「等落子生效」却永远不动。这里当场对一遍，对不上绝不落子。
        float gridDy = Math.abs(r.points[1][1] - r.points[0][1]);
        float gm = gridPictureMatch(bmp, r, Math.max(12f, gridDy * 0.30f));
        if (gm >= 0f) {
            flog("坐标自检 匹配=" + String.format(java.util.Locale.US, "%.2f", gm)
                    + " 角 tl=(" + Math.round(r.points[0][0]) + "," + Math.round(r.points[0][1]) + ")"
                    + " tr=(" + Math.round(r.points[0][16]) + "," + Math.round(r.points[0][17]) + ")"
                    + " bl=(" + Math.round(r.points[9][0]) + "," + Math.round(r.points[9][1]) + ")"
                    + " dy=" + Math.round(gridDy)
                    + " 帧龄=" + CaptureService.frameAge() + "ms"
                    + " 有票=" + r.minVote);
            flog("识别角点 corners=" + (r.corners == null ? "null"
                    : (Math.round(r.corners[0]) + "," + Math.round(r.corners[1])
                       + " " + Math.round(r.corners[4]) + "," + Math.round(r.corners[5]))));
            if (gm < 0.70f) {
                if (++gridBadFrames >= 2) {
                    gridBadFrames = 0;
                    if (vision != null) vision.resetLock();
                    linkBoard = "";
                    linkExpect = null; linkPrev = null; linkUci = null;
                    linkState = ST_INIT;
                    linkActAt = 0;
                    setStatus("连线: 坐标与画面不符，重新定位棋盘…");
                }
                return;
            }
        }
        gridBadFrames = 0;

        // 屏幕下半是谁，我执谁（row 大 = 屏幕下方 = 我方）
        // 将帅位置是铁证，绝对优先；子力加权只在认不出将帅时兜底
        char layout = BoardVision.sideOfByKing(r.chars);
        if (layout == 0) layout = BoardVision.sideOf(r.chars, r.conf, 0.5f);
        if (sideAuto && layout != 0) { mySide = layout; updateSideBtn(); }

        boolean flipped = (layout == 'b');
        if (layout == 0) flipped = "flipped".equals(r.direction);

        String board = flipped ? Board.placeOf(Board.rotate180(r.fen, 'w'))
                               : Board.placeOf(r.fen);
        // 校验必须在「翻转成标准朝向」之后再做。
        // 执黑时屏幕是红在上（上下颠倒），原始 FEN 直接喂 validate 会把
        // 完全合法的局面判成「红仕不在九宫」，于是每帧都被丢、永远认不出来。
        String bad2 = Board.validate(board);
        if (bad2 != null) {
            // ★ v4.60：多子时先把「是哪一格、模型有多确信」记下来。
            //   这一版就是为了查「卒被读成车」到底出在哪一格、模型是否真的自信。
            if (bad2.indexOf("多子") >= 0) logPhantomCells(r, flipped, board);
            // ★ v4.59：再试「多子自动纠正」—— 恒定误读投票压不掉，
            //   不救的话整局都下不了（实测瘫痪 90 秒、重启「链」也没用）。
            String fixed = repairExtraPiece(board, bad2);
            if (fixed != null) board = fixed;
            else if (rejectFrame("不可靠:" + bad2, board)) return;
        }

        // 首帧基线必须「连续两帧认到同一局面」才建立。
        // 这是比任何棋型判据都靠谱的闸门：真实对局每帧都一致，
        // 而把聊天界面/游戏大厅认成棋盘的随机垃圾帧几乎不会连续两次一样。
        if (linkBoard.isEmpty()) {
            String imp = Board.plausibleProblem(board);
            if (imp != null) {
                Log.w(TAG, "连线: 局面不可信(" + imp + "): " + board);
                if (rejectFrame("不可信:" + imp)) return;
            } else if (board.equals(pendBoard)) {
                if (++pendCount >= 2) {
                    pendBoard = "";
                    pendCount = 0;
                    // 落到下面正常建立基线
                } else {
                    setStatus("连线: 确认局面…");
                    return;
                }
            } else {
                pendBoard = board;
                pendCount = 1;
                setStatus("连线: 确认局面…");
                return;
            }
        }


        String noiseMsg = null;
        // ★ 单步闸门（改造：能判成"抖动"就忽略，不再一拒了之，也不再因此换基线）
        //   真实对局一步最多动 2 格。差异若是「1 格变化」「3 格变化」
        //   或「2 格但兵卒横移 / 象士出界 / 子种颜色不符」——一律判为识别抖动：
        //   保持基线、忽略这一帧。绝不把错盘面当新基线（这正是连输的根因）。
        //   只有连续 10 帧都是同一个"抖动"盘面，才承认是基线自己错了，换基线。
        if (!linkBoard.isEmpty() && !board.equals(linkBoard)) {
            int kind = Board.explainChange(linkBoard, board);
            if (kind == Board.EXPL_ONE || kind == Board.EXPL_TWO) {
                noiseStreak = 0;
                badFrames = 0;
                if (kind == Board.EXPL_TWO) Log.i(TAG, "连线: 漏拍一次轮转，按两步采纳");
            } else if (kind == Board.EXPL_NOISE) {
                noiseStreak++;
                // 同一个「抖动」盘面反复出现，就不是瞬时误读了，是真的换了局面。
                // 旧代码要连续 5 帧才承认，帧率低时要卡好几秒，表现成「对方走了却不动」。
                if (board.equals(noiseBoard)) noiseSame++; else { noiseBoard = board; noiseSame = 1; }
                Log.w(TAG, "连线: 识别抖动已忽略(" + noiseStreak + ") 同值=" + noiseSame + " new=" + board);
                if (noiseSame < 3 && noiseStreak < 5) {
                    board = linkBoard;
                    if (noiseStreak >= 3) noiseMsg = "连线: 轻微抖动已自动过滤（" + noiseStreak + "）";
                } else {
                    Log.w(TAG, "连线: 抖动盘面稳定 " + noiseSame + " 次，判定为真实变化，采纳");
                    noiseStreak = 0; noiseSame = 0; noiseBoard = "";
                    badFrames = 0;
                }
            } else {
                noiseStreak = 0;
                Log.w(TAG, "连线: 非单步差分 new=" + board);
                if (rejectFrame("非单步差分", board)) return;
            }
        } else {
            noiseStreak = 0;
            badFrames = 0;
        }

        updateGameTimer(board);
        syncMini(r.chars, r.conf, (mySide == 'w' ? "执红" : "执黑"));
        rememberBoardBox(r.points);

        long now = System.currentTimeMillis();
        boolean changed = !board.equals(linkBoard);

        if (!changed) {
            boolean retry = false;
            if (linkPaused) { setStatus("未识别到正确局面"); return; }
            if (linkState == ST_MOVING && now - linkActAt > speedRetry()) {
                linkFailTry++;
                if (linkFailTry >= 5) {
                    Log.w(TAG, "连线: 落子连续 " + linkFailTry + " 次未生效，重新对齐后继续");
                    linkFailTry = 0;
                    linkBoard = board; repairBase = board;
                    linkMover = 0;
                    linkState = ST_MY_TURN;
                    linkActAt = now; linkExpect = null; linkPrev = null; linkUci = null;
                    setStatus("连线: 落子被忽略，重新对齐");
                    retry = true;
                } else {
                    // ★ 重放同一步，绝不重新算招。
                    //   游戏动画 + 多帧投票让盘面更新比点击晚 2~3 秒。以前这里一律把状态
                    //   打回 ST_MY_TURN 并清掉 linkExpect，下一轮就拿「已经变了的新盘面」
                    //   重新问引擎 → 拿到另一个着法再点一次，等于把刚走出去的那步悄悄换掉，
                    //   甚至在同一回合连走两步。开局崩盘就是这么来的。
                    Log.w(TAG, "连线: 落子未生效 重放(" + linkFailTry + ") 走=" + linkUci + " 期望=" + linkExpect + " 实际=" + board);
                    if (linkFailTry >= 3 && vision != null) {
                        vision.resetLock();
                        flog("连续 " + linkFailTry + " 次落子对不上，已解锁棋盘重新定位");
                    }
                    linkActAt = now;
                    final float[] rm = lastMark;
                    if (rm != null) {
                        sLastTapAt = 0;          // 重放不受落子节流限制
                        tapMove(rm);
                    }
                    setStatus("连线: 落子重放中…");
                    retry = false;
                }
            } else if (linkState == ST_MY_TURN && now - linkActAt > speedRetry()) {
                retry = true;
            }
            if (!retry) {
                if (noiseMsg != null) setStatus(noiseMsg);
                else if (linkState == ST_MOVING) setStatus("连线: 等落子生效…");
                else if (linkState == ST_WAIT) setTurnStatus();
                else if (linkState == ST_MY_TURN) setTurnStatus();
                else setStatus("连线: 就绪");
                return;
            }
        } else {
            // ---- 局面真的变了 ----
            char[][] cur = Board.fromFen(board);
            char[][] prev = linkBoard.isEmpty() ? null : Board.fromFen(linkBoard);
            char mover = prev == null ? 0 : Board.whoMoved(prev, cur);
            linkBoard = board; repairBase = board;
            if (mover != 0) linkMover = mover;
            Log.i(TAG, "连线: 局面变 上一手=" + (mover == 'w' ? "红" : mover == 'b' ? "黑" : "?")
                    + " 我方=" + mySide + " 状态=" + linkState + " fen=" + board);
            final String bd = board;
            dumpFrame("变:" + bd);

            if (linkState == ST_MOVING) {
                boolean exact = (linkExpect != null && board.equals(linkExpect));
                if (exact) {
                    linkState = ST_WAIT;              // 恰好就是我那一步
                    linkFailTry = 0;
                    linkExpect = null;
                    linkMover = mySide;
                    // ★ v4.77：落子生效 = 本局打过棋的铁证。将死一子落下后画面立刻
                    //   切结算页，不会再有有效棋盘帧来置位/刷新时效，导致结算页
                    //   点击的前提（rematchGoodBoard）还是 false → 永远不点。
                    rematchGoodBoard = true;
                    lastGoodBoardAt = System.currentTimeMillis();
                    if (mateArmed) {                  // ★ v4.62：绝杀步落成 → 挂起省电
                        mateArmed = false;
                        mateHold = true;
                        mateHoldAt = System.currentTimeMillis();   // ★ v4.101 宽限计时
                        if (liankaiOn) {
                            flog("绝杀确认：进入连开监听（低频盯结算页，自动再来一局）");
                            setStatus("连线: 绝杀！连开监听中…");
                        } else {
                            flog("绝杀确认：停止截图/识别，等待下一局");
                            setStatus("连线: 绝杀！已停（点链开下一局）");
                        }
                        return;
                    }
                    setStatus("连线: 已落子，等对手…");
                    return;
                }
                // 我的子确实动了，但盘面不止我这一步 —— 说明对手在同一帧间隙里已经应招。
                // 旧代码这里一律进 WAIT，于是「对方已走子却一直显示等对手」，只能重新连线才恢复。
                if (linkExpect != null && Board.moveApplied(linkPrev, linkUci, board)) {
                    linkState = ST_MY_TURN;
                    linkFailTry = 0;
                    linkExpect = null;
                    linkMover = (mySide == 'w') ? 'b' : 'w';
                    Log.i(TAG, "连线: 我落子且对手已应，直接续招");
                } else {
                    linkFailTry++;
                    Log.w(TAG, "连线: 落子未按预期生效(" + linkFailTry + ")，重新对齐后继续");
                    // 不再因为几次不符就停手：用最新识别到的局面重建基线继续下
                    linkBoard = board; repairBase = board;
                    linkMover = 0;
                    linkState = ST_MY_TURN;
                    linkActAt = now; linkExpect = null; linkPrev = null; linkUci = null;
                    setStatus("连线: 局面已重新对齐，继续");
                }
            } else {
                // 轮次推导：优先看「上一手是谁走的」；判不出来时由 Board.nextTurn
                // 做前向推断（绝不复用残留 linkMover，那会导致 ST_WAIT 永久卡死）
                int prevState = linkState;
                linkState = Board.nextTurn(linkState, mover, mySide, Board.START.equals(board));
                Log.i(TAG, "连线: 轮次 " + prevState + " --mover=" + (mover == 0 ? '?' : mover)
                        + "--> " + linkState);
            }
        }

        // ★ v4.5：这一帧真的认出了棋盘 → 记下「本局打过」。
        //   结算页判定拿它当前提：没打过棋的界面（大厅/选人/战绩）一律不碰，从源头防误点。
        rematchGoodBoard = true;
        lastGoodBoardAt = System.currentTimeMillis();   // 时效起点（超 2 分钟不再认结算页）
        if (linkState != ST_MY_TURN) { setTurnStatus(); return; }

        // ---- 轮到我：算招 + 落子 ----
        String fen = board + " " + mySide;
            flog("识别 fen=" + fen);
        lastFen = fen;
        setStatus(engine.isReady() ? "连线: 算招中…" : "连线: 引擎恢复中…");
        long t0 = System.currentTimeMillis();
        long tA = System.currentTimeMillis();
        List<PikafishEngine.Move> cands = candsOf(fen);
        long dtA = System.currentTimeMillis() - tA;
        if (dtA > 1200) Log.i(TAG, "算招耗时 " + dtA + "ms ready=" + engine.isReady()
                + " level=" + speedLevel + " 多路=" + speedMultiPv());
        if (cands.isEmpty()) {
            // 引擎没给出着法：可能引擎进程刚被系统回收（正在自动重启），
            // 也可能这条局面真的无着法（已终局）。
            // ★ 绝不能强制回 ST_WAIT —— 那会把用户手动「改我走」立刻打回去
            //   （表现为点一下被打回一次），而且引擎恢复后也没人再叫它算招。
            //   保持「轮到我」，约 1 秒后重试；引擎由 ensureStarted 自行拉起。
            String why = engine.deathReason();
            if (why.length() > 0) {
                Log.w(TAG, "连线: 引擎无着法，" + why);
                setStatus("连线: 引擎异常(" + why + ")，自动重启中…");
            } else {
                setStatus("连线: 引擎暂未出招，稍后重试");
            }
            linkState = ST_MY_TURN;
            linkActAt = System.currentTimeMillis() - 2500;
            return;
        }
        PikafishEngine.Move best = cands.get(0);

        float[] mark = uciToScreen(best.uci, r, flipped);
        if (mark == null) { setStatus("连线: 坐标映射失败"); return; }
        lastMark = mark;

        String cn = Board.uciToCnStd(best.uci, Board.placeOf(fen) + " " + mySide);
        final String msg = cn;
        final String info = "连线 | 我执" + (mySide == 'w' ? "红" : "黑")
                + " | 形势 " + best.scoreText() + " | 深度 " + best.depth
                + " | " + best.nps + " nps | " + (System.currentTimeMillis() - t0) + "ms";
        if (evalBar != null) {
            evalBar.setEval(best.scoreCp, best.hasMate, best.scoreMate, mySide);
            evalBar.setAnalysis(analysisText(best, cn, mySide));   // ★ v4.70：条上显示形势+推荐
        }
        BoardBus.pushMove(msg, info);

        // ★ v5.1：授权闸门 —— 付费版未激活 / 免费版都只给建议，不落子。
        if (!ProModule.autoMove()) {
            linkState = ST_WAIT;
            setStatus("连线: 建议 " + cn + " " + ProModule.lockedHint());
            return;
        }
        if (!autoPlay.get()) {
            linkState = ST_WAIT;
            setStatus("连线: 建议 " + cn + "（自动走子关）");
            return;
        }

    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            CharSequence p = event.getPackageName();
            if (p == null) return;
            String s = p.toString();
            if (s == null || s.isEmpty()) return;
            if (s.equals(getPackageName())) { selfFgAt = System.currentTimeMillis(); return; }
            synchronized (fgSeen) { fgSeen.put(s, System.currentTimeMillis()); }
        } catch (Throwable ignored) {}
    }

    /**
     * ★ V3.1 音量键控制悬浮窗。
     *
     *   音量减 = 缩小成小圆球
     *   音量加 = 展开回面板
     *
     * 为什么这么做：面板是全宽贴顶的，原先想缩小必须去点面板上那个小小的「—」按钮；
     * 对局中手指在棋盘上，去够它很别扭。音量键是物理键，盲按就行。
     *
     * 三条自我约束（很重要，否则会变成「抢音量键」）：
     *   ① 只在「悬浮窗真的存在」时才拦截。没有悬浮窗就放行 ——
     *      用户点了 ✕ 关掉悬浮窗后，音量键立刻恢复正常。
     *   ② 长按（repeatCount > 0）不拦截，交给系统 —— 想连着调音量就长按。
     *   ③ 按键抬起事件也一并吃掉，避免「按下被拦、抬起漏过去」导致音量乱跳。
     */
    // ============================================================
    //  音量中性 + 音量条遮挡屏蔽（v4.6）
    // ============================================================
    /**
     * v4.6：按音量键后，系统会弹出音量条，**实测它会盖住棋盘右上角**：
     *    音量条窗口 OplusVolumeDialogImpl，位置 x[1063,1207] y[477,940]，
     *    棋盘实测 tl=(88,798) tr=(1178,798) → 重叠 x[1063,1178] y[798,940]，
     *    正好是棋盘最上面一行最右一列那个棋子。
     *  截图若拍进音量条，那一格就会被误认，进而把整盘局面喂错。
     *  实测音量条持续 3.5~4.0 秒（每 0.5 秒采样一次，第 7 次还在、第 8 次消失），
     *  所以这段时间**直接不截图不识别**，等它消失再继续。连按会不断刷新。
     */
    private static final long VOL_PANEL_MS = 4200L;

    /** ★ v4.42：音量减「隐藏悬浮窗」的延迟。
     *  为什么不能立刻收：ColorOS 的系统截屏 = 音量减 + 电源，两个键几乎同时按。
     *  音量减一被消费就 removePanel()，等系统把画面拍下来时悬浮窗已经不在了 ——
     *  用户要的是「截图里带上悬浮窗」。延迟这么久，截图早就拍完了；
     *  期间若收到用户的截屏通知（Activity.registerScreenCaptureCallback），整单取消。 */
    private static final long VOL_HIDE_DELAY_MS = 1000L;
    /** v4.6：音量条屏蔽的截止时刻。 */
    private volatile long volPanelUntil = 0;
    /** ★ v4.42：最近一次「用户主动截屏」的时间戳；见 VOL_HIDE_DELAY_MS。 */
    private volatile long userShotAt = 0L;
    /** ★ v4.51：下一次重建面板时真播一次入场动画（设置页选动画时用）。 */
    private static volatile boolean previewAnimOnce = false;
    /** ★ v4.42：延迟隐藏的世代号 —— 自增即作废所有在途的隐藏任务。 */
    private volatile int volHideGen = 0;
    /** v4.6：本次屏蔽是否已经记过日志（避免每帧刷屏）。 */
    private volatile boolean volBlockLogged = false;
    /** v4.6：切换悬浮窗时，按键前的各流音量快照（-1 = 没取到）。 */
    private final int[] volSnap = new int[]{-1, -1, -1, -1, -1};
    /** v4.6：快照覆盖的音频流。 */
    private static final int[] VOL_STREAMS = {
            android.media.AudioManager.STREAM_MUSIC,
            android.media.AudioManager.STREAM_RING,
            android.media.AudioManager.STREAM_ALARM,
            android.media.AudioManager.STREAM_NOTIFICATION,
            android.media.AudioManager.STREAM_SYSTEM,
    };
    /** v4.6：按键后多久还原音量（要等系统先把音量改完，太早会被它覆盖）。 */
    private static final long VOL_RESTORE_MS = 300L;

    /**
     * 记下按键前所有用户流的音量。
     *
     * 为什么要「所有流」而不是只记媒体流：按音量键时系统调哪个流，
     * 取决于当时有没有音频在播/有没有来电，本机实测默认是 MUSIC，
     * 但通话中可能是 VOICE_CALL、闹钟响的时候可能是 ALARM。
     * 全记下来、只回滚**确实变了**的那些，才不会搞错。
     */
    private void snapshotVolumes() {
        try {
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return;
            for (int i = 0; i < VOL_STREAMS.length; i++) {
                try { volSnap[i] = am.getStreamVolume(VOL_STREAMS[i]); }
                catch (Throwable t) { volSnap[i] = -1; }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 把音量还原成按键前的样子 —— 这就是「切换悬浮窗不影响音量」的实现。
     *
     * 为什么必须延后 300ms：按键是 `return false` 交给系统的，系统改音量是在
     * 我们返回之后才发生；立刻回滚会被系统随后的写入覆盖掉，等于没做。
     *
     * 为什么 flags 传 0：`setStreamVolume(..., 0)` 不会再弹一次音量条。
     * 只回滚「值与快照不同」的流 —— 用户在这一瞬间自己调的音量不会被误吞。
     */
    private void restoreVolumesSoon() {
        ui.postDelayed(new Runnable() {
            public void run() {
                try {
                    android.media.AudioManager am =
                            (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                    if (am == null) return;
                    int fixed = 0;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < VOL_STREAMS.length; i++) {
                        int want = volSnap[i];
                        if (want < 0) continue;
                        if (am.getStreamVolume(VOL_STREAMS[i]) == want) continue;
                        try {
                            am.setStreamVolume(VOL_STREAMS[i], want, 0);
                            fixed++;
                            sb.append(VOL_STREAMS[i]).append("→").append(want).append(' ');
                        } catch (Throwable ignored) {}
                    }
                    if (fixed > 0) flog("音量已还原（" + fixed + " 个流："
                            + sb.toString().trim() + "）—— 切换悬浮窗不再影响音量");
                } catch (Throwable ignored) {}
            }
        }, VOL_RESTORE_MS);
    }

    /**
     * 音量条遮挡期间不许识别（音量条会盖住棋盘右上角，见 VOL_PANEL_MS 的说明）。
     * @return true = 现在正在「音量条时段」，本帧应跳过
     */
    private boolean volPanelBlocking() {
        return System.currentTimeMillis() < volPanelUntil;
    }
    /**
     * 音量键：**切换悬浮窗的那一次直接消费掉，音量完全不参与**。
     *
     * ★ v4.6 的核心修复（用户反馈「打开和关闭悬浮窗会导致音量突然变大或变小」）
     *
     * 以前是「一律 return false，让系统照常调音量，我们只是顺便收展面板」——
     * 结果是开关一次面板音量就必然变一档。想给音量调回来，又得再按一次，
     * 而那一次往往又把面板切回去，音量和面板彻底绑死。
     *
     * v4.6 先试过「return false + 300ms 后把音量还原」，实测**不可靠**：
     *   日志显示还原确实执行了（`音量已还原（1 个流：3→30）`），
     *   但 2.5 秒后读回来是 20 —— 系统在处理完按键后又把音量写回去了，我们的还原被覆盖。
     *
     * 所以改成「切面板的按键直接消费」（`return true`）：
     *   · 面板在 + 短按音量减 → 收起面板，**系统不动音量、也不弹音量条**，按键被消费
     *   · 面板收起 + 短按音量加 → 召回面板，同上
     *   · 其余情况一律 `return false` → 音量键 100% 是音量键
     *     （面板在时按音量加 / 面板收起时按音量减 / 长按）
     *
     * 「长按必调音量」很重要：面板收起时想调大音量，长按音量加就行
     * （`repeatCount > 0` 一律放行），不必先召回面板。
     */
    @Override
    protected boolean onKeyEvent(android.view.KeyEvent event) {
        try {
            if (event == null) return false;
            int k = event.getKeyCode();
            if (k != android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                    && k != android.view.KeyEvent.KEYCODE_VOLUME_UP) return false;

            // 抬起事件不管；长按（repeatCount>0）一律交给系统正常调音量
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            if (event.getRepeatCount() > 0) return false;

            final boolean down = (k == android.view.KeyEvent.KEYCODE_VOLUME_DOWN);
            // 这次按键是不是「要切换悬浮窗」：面板在就收、已收起就召
            final boolean toggling = down
                    ? (panel != null || ball != null)
                    : volHidden;

            if (!toggling) {
                // ★ 这次是「真的要调音量」：放行给系统，同时记下屏蔽窗口 ——
                //   系统会弹出音量条，而实测音量条会盖住棋盘右上角（见 VOL_PANEL_MS）。
                volPanelUntil = System.currentTimeMillis() + VOL_PANEL_MS;
                volBlockLogged = false;   // 新的一次调音量，允许再记一条日志
                return false;
            }

            // ★ 这次是「切换悬浮窗」：消费按键 —— 音量不变、系统不会弹音量条。
            //   面板的收起/展开本身就是用户看到的反馈。
            if (down) {
                // ★ v4.42：设置页开着时干脆不收起 —— 那一页调的就是悬浮窗本人，
                //   而且用户多半正是为了「连着悬浮窗一起截图」才打开的它。
                if (settingsOpen) {
                    flog("音量减：设置页开着，保留悬浮窗（本次按键交回系统调音量）");
                    volPanelUntil = System.currentTimeMillis() + VOL_PANEL_MS;
                    volBlockLogged = false;
                    return false;
                }
                // ★ v4.42：延迟再收 —— 见 VOL_HIDE_DELAY_MS 的说明（避开截屏按键）。
                final int gen = ++volHideGen;
                flog("音量减：延迟 " + VOL_HIDE_DELAY_MS + "ms 收起悬浮窗（避开截屏按键）");
                ui.postDelayed(new Runnable() {
                    public void run() {
                        if (gen != volHideGen) return;      // 期间被取消（截屏 / 音量加）
                        if (System.currentTimeMillis() - userShotAt
                                < VOL_HIDE_DELAY_MS + 800L) {
                            flog("检测到用户截屏，取消隐藏悬浮窗");
                            return;
                        }
                        volHidden = true;
                        addKeepAlive();      // ★ 留住窗口，别让系统当后台应用清理
                        removePanel();
                        removeBall();
                        toastMsg("悬浮窗已隐藏 —— 按音量加恢复");
                    }
                }, VOL_HIDE_DELAY_MS);
            } else {
                // volHidden 不能少：它区分「用音量键收起来的」和「用户点 ✕ 关掉的」，
                // 后者不该被音量加复活。
                flog("音量加：召唤悬浮窗（消费本次按键，音量不变）");
                ui.post(new Runnable() {
                    public void run() {
                        volHideGen++;        // ★ v4.42：作废还没执行的隐藏
                        volHidden = false;
                        removeKeepAlive();
                        showPanel();
                        toastMsg("悬浮窗已恢复 —— 按音量减隐藏");
                    }
                });
            }
            return true;   // ← 只消费「切面板」这一次
        } catch (Throwable t) {
            return false;   // 出任何问题都不许把按键吞掉
        }
    }

    /** 轻提示。缩小后面板已被移除、状态行写不进去，所以这里用 Toast：
     *  用户按了音量键，得让他知道「怎么恢复」。 */
    private void toastMsg(String s) {
        try {
            android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }

    /** 最近 2.5 秒内是否出现过象棋类应用（即：正在对局）。 */
    /**
     * 当前前台是不是象棋类对局。
     *
     * ★ 必须用 getWindows()：象棋 App 是自绘引擎（Unity/native），不发无障碍事件，
     *   靠「最近事件包名」永远看不到它，会把真实对局拦在外面。
     *   拿不到窗口信息时一律放行，宁可多试也不能误拦。
     */
    /**
     * 当前前台是不是象棋类对局。
     *
     * ★ 用 getWindows() 取真实顶层窗口。
     *   注意：以前写的是「找不到象棋窗口就放行」，等于永远放行 ——
     *   在聊天界面里也会照跑识别，把垃圾画面当棋盘。
     *   正确做法：只要枚举到应用窗口，就由那个窗口说了算；只有连窗口都枚举不到时才放行。
     */
    /**
     * 判断当前前台是不是象棋对局。
     *
     * ★ 这里修了一个会让识别锁被污染的洞：
     *   旧版遇到「本应用自己的窗口」时直接 continue 跳过，如果之后枚举不到别的应用窗口，
     *   就会走到最后那句「实在判断不出来，才放行」→ 把我们自己的界面当成棋盘去识别。
     *   实测证据：诊断帧 w1/w2 拉正出来的就是 YYPM 自己的主界面和悬浮面板。
     *   一旦拿自己的界面认字，锁定的棋盘位置就被带偏，回到对局后持续「识别不稳，暂不采信」。
     *   现在：只要看到自己的应用窗口在前台，直接拦下；兜底路径也不再无条件放行。
     */
    /**
     * 系统壳 / 悬浮层 / 输入法：不参与「前台是不是象棋」的判定。
     *
     * 它们在窗口列表里常年存在，而旧代码遇到第一个非本应用的窗口就立刻 return，
     * 于是状态栏、悬浮球、输入法排在象棋窗口前面时就直接误拦 —— 实测
     * com.android.systemui 被误拦 1600+ 次。这里先按包名把它们剔出去。
     */
    private static boolean isShellPkg(String s) {
        if (s == null || s.isEmpty()) return true;
        if (s.equals("com.android.systemui")) return true;
        if (s.contains("launcher")) return true;
        if (s.startsWith("com.oplus.")) return true;
        if (s.startsWith("com.coloros.")) return true;
        if (s.startsWith("com.heytap.")) return true;
        if (s.contains("input")) return true;                 // 输入法
        if (s.equals("com.android.photopicker")) return true;
        if (s.equals("com.android.settings")) return true;
        return false;
    }

    private boolean isChessForeground() {
        // 宽松模式：不按 App 身份挡人。除了「本应用自己」和「系统壳界面」，
        // 其余一律放行 —— 判断是不是棋盘交给识别本身（几何定位 + 双帧一致 +
        // 局面合理性三道闸门）。这样任何象棋 App、任何下棋界面都能直接用，
        // 不必事先把包名加进白名单。
        if (fgMode == FG_LOOSE) return isAnyAppForeground();
        if (fgMode == FG_OFF) return true;

        boolean sawSelf = false;
        String chessSeen = null;
        String otherSeen = null;
        StringBuilder dump = new StringBuilder();
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
                    try {
                        boolean app = (w.getType()
                                == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION);
                        boolean act = w.isActive(), foc = w.isFocused();
                        android.view.accessibility.AccessibilityNodeInfo root = w.getRoot();
                        CharSequence p = (root == null) ? null : root.getPackageName();
                        String s = (p == null) ? "?" : p.toString();
                        dump.append(s).append(app ? "/APP" : "/t" + w.getType())
                            .append(act ? "/act" : "").append(foc ? "/foc" : "")
                            .append(root == null ? "/noRoot" : "").append("  ");
                        if (!app) continue;
                        if (root == null) continue;
                        if (s.isEmpty() || "?".equals(s)) continue;
                        if (s.equals(getPackageName())) { sawSelf = true; continue; }
                        if (isShellPkg(s)) continue;
                        if (isChessPkg(s)) {
                            if (chessSeen == null) chessSeen = s;
                            if (act || foc) { fgTop = s; dumpDecision("严格:象棋窗口持有焦点", dump); return true; }
                        } else {
                            if (otherSeen == null) otherSeen = s;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        if (chessSeen != null) {
            fgTop = chessSeen;
            dumpDecision("严格:象棋窗口在列", dump);
            return true;
        }
        if (otherSeen != null) {
            fgTop = otherSeen;
            if (++fgLog % 10 == 1) {
                Log.i(TAG, "前台校验(严格): 顶层应用=" + otherSeen + " → 非象棋，拦下");
                dumpDecision("严格:非象棋", dump);
            }
            return false;
        }
        return fgFallback(sawSelf, dump);
    }

    /**
     * 宽松模式的前台判断：只要前面有一个「真实应用」窗口就放行。
     * 只挡两种：本应用自己（否则会拿自己的界面去识别，污染锁定网格）、
     * 以及系统壳（状态栏 / 桌面 / 悬浮球 / 输入法）。
     */
    private boolean isAnyAppForeground() {
        StringBuilder dump = new StringBuilder();
        boolean sawSelf = false;
        String real = null;
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
                    try {
                        boolean app = (w.getType()
                                == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION);
                        android.view.accessibility.AccessibilityNodeInfo root = w.getRoot();
                        CharSequence p = (root == null) ? null : root.getPackageName();
                        String s = (p == null) ? "?" : p.toString();
                        dump.append(s).append(app ? "/APP" : "/t" + w.getType())
                            .append(w.isActive() ? "/act" : "").append(root == null ? "/noRoot" : "")
                            .append("  ");
                        if (!app || root == null) continue;
                        if (s.isEmpty() || "?".equals(s)) continue;
                        if (s.equals(getPackageName())) { sawSelf = true; continue; }
                        if (isShellPkg(s)) continue;
                        if (w.isActive() || w.isFocused()) {           // 有焦点的优先
                            fgTop = s;
                            dumpDecision("宽松:焦点应用", dump);
                            return true;
                        }
                        if (real == null) real = s;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        if (real != null) {
            fgTop = real;
            dumpDecision("宽松:应用窗口在列", dump);
            return true;
        }
        return fgFallback(sawSelf, dump);
    }

    /** 枚举不到可用窗口时的兜底：看最近 2.5 秒内出现过什么应用事件。 */
    private boolean fgFallback(boolean sawSelf, StringBuilder dump) {
        long now = System.currentTimeMillis();
        if (sawSelf && now - selfFgAt < 2500L) {
            if (++fgLog % 10 == 1) {
                Log.i(TAG, "前台校验: 本应用刚在前台 → 拦下");
                dumpDecision("本应用在前台", dump);
            }
            return false;
        }
        java.util.ArrayList<String> keys;
        synchronized (fgSeen) { keys = new java.util.ArrayList<String>(fgSeen.keySet()); }
        boolean anyReal = false;
        for (String s : keys) {
            Long t;
            synchronized (fgSeen) { t = fgSeen.get(s); }
            if (t == null || now - t > 2500L) continue;
            if (s.equals(getPackageName()) || isShellPkg(s)) continue;
            if (fgMode == FG_STRICT) {
                if (isChessPkg(s)) return true;
            } else {
                anyReal = true;
            }
        }
        if (anyReal) {
            dumpDecision("回退:最近有应用在前台", dump);
            return true;
        }
        if (++fgLog % 10 == 1) {
            Log.i(TAG, "前台校验: 无法判定前台 → " + (fgMode == FG_STRICT ? "拦下" : "放行"));
            dumpDecision("无法判定", dump);
        }
        // 宽松模式下「判不出来」= 放行：用户既然开了助手，就按信任处理
        return fgMode != FG_STRICT;
    }

    /** 每 30 秒最多打一次完整窗口列表，用于事后定位误拦。 */
    private void dumpDecision(String why, StringBuilder dump) {
        long now = System.currentTimeMillis();
        if (now - fgDumpAt < 30000L) return;
        fgDumpAt = now;
        Log.i(TAG, "前台判定[" + why + "] 窗口: " + dump);
    }

    private boolean isChessPkg(String p) {
        if (p == null) return false;
        if (p.equals("com.tencent.qqgame.xq") || p.equals("com.qqgame.xq")
                || p.equals("com.sharkchess.newshark")) return true;
        synchronized (chessPkgCache) {
            Boolean c = chessPkgCache.get(p);
            if (c != null) return c;
        }
        boolean r = false;
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            String label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0)));
            r = label.contains("象棋") || label.toLowerCase().contains("chess");
        } catch (Throwable ignored) {}
        synchronized (chessPkgCache) { chessPkgCache.put(p, r); }
        return r;
    }

    /** 最近见过的前台包，用于状态提示。 */
    private String lastFgPkg() {
        try {
            synchronized (fgSeen) {
                String last = "";
                for (String s : fgSeen.keySet()) last = s;
                return last;
            }
        } catch (Throwable t) { return ""; }
    }


    @Override
    public void onInterrupt() { }


    /** 最后一个实例退出时才真正关掉引擎（引擎是进程级共享的）。 */
    private void maybeQuitEngine() {
        if (!counted) return;
        counted = false;
        if (sInstances.decrementAndGet() <= 0) {
            try { if (engine != null) engine.quit(); } catch (Exception ignored) {}
            engine = null;
            sHeavyInited.set(false);
            Log.i(TAG, "最后一个实例退出，已关闭引擎");
        } else {
            Log.i(TAG, "仍有其它实例存活，保留引擎");
        }
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        maybeQuitEngine();
        hidePanel();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        serviceDestroyed = true;
        maybeQuitEngine();
        if (sInstance == this) sInstance = null;
        try { clearCards(); } catch (Throwable ignored) {}
        try { dismissHintCard(); } catch (Throwable ignored) {}
        hidePanel();
        super.onDestroy();
    }
}
